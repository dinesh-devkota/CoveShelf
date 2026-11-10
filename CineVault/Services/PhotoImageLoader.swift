import CineVaultCore
import Foundation
import ImageIO
import UIKit

enum PhotoLoadingError: LocalizedError, Equatable {
    case unavailable, tooLarge, invalidImage, invalidDimensions
    var errorDescription: String? {
        switch self {
        case .unavailable: return "This photo is unavailable. Check its NAS source or scan again."
        case .tooLarge: return "This photo is larger than the 64 MB viewing limit."
        case .invalidImage: return "This file could not be opened as a photo. It may be damaged or use an unsupported image format."
        case .invalidDimensions: return "This photo's dimensions are too large to display safely."
        }
    }
}

/// Limits simultaneous encoded buffers without polling; cancelled thumbnails leave the queue immediately.
private actor PhotoLoadSlots {
    private let onQueued: () -> Void
    private var active = 0
    private var order: [UUID] = []
    private var waiters: [UUID: CheckedContinuation<Void, Error>] = [:]

    init(onQueued: @escaping () -> Void) { self.onQueued = onQueued }

    func acquire() async throws {
        try Task.checkCancellation()
        if active < 2 { active += 1; return }
        let id = UUID()
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                if Task.isCancelled { continuation.resume(throwing: CancellationError()) }
                else { order.append(id); waiters[id] = continuation; onQueued() }
            }
        } onCancel: { Task { await self.cancel(id) } }
    }

    func release() {
        while !order.isEmpty {
            let id = order.removeFirst()
            if let waiter = waiters.removeValue(forKey: id) { waiter.resume(); return }
        }
        active -= 1
    }

    private func cancel(_ id: UUID) {
        order.removeAll { $0 == id }
        waiters.removeValue(forKey: id)?.resume(throwing: CancellationError())
    }
}

/// NAS-only image reads. ImageIO creates a bounded thumbnail directly, without decoding a full-size UIImage.
actor PhotoImageLoader {
    static let shared = PhotoImageLoader()
    static let maximumEncodedBytes: Int64 = 64 * 1024 * 1024
    static let thumbnailPixels = 512
    static let viewingPixels = 4096
    static let maximumDecodedPixels = 4_194_304
    static let maximumDecodedBytes = 32 * 1024 * 1024
    private let slots: PhotoLoadSlots
    private let fileSize: (SourceConfig, String) async throws -> Int64
    private let read: (SourceConfig, String, Int64, Int) async throws -> Data
    private var cache: [CacheKey: CachedImage] = [:]
    private var cacheOrder: [CacheKey] = []
    private var cacheCost = 0
    private let cacheLimit = 64 * 1024 * 1024

    private struct CacheKey: Hashable {
        let config: SourceConfig
        let path: String
        let size: Int64
        let pixels: Int
        let revision: Date?
        init(_ config: SourceConfig, _ path: String, _ size: Int64, _ pixels: Int, _ revision: Date?) {
            self.config = config; self.path = path; self.size = size; self.pixels = pixels
            self.revision = revision
        }
    }
    private final class CachedImage {
        let image: UIImage
        let expires = Date().addingTimeInterval(120)
        let cost: Int
        init(_ image: UIImage, cost: Int) { self.image = image; self.cost = cost }
    }

    init(fileSize: ((SourceConfig, String) async throws -> Int64)? = nil,
         read: ((SourceConfig, String, Int64, Int) async throws -> Data)? = nil,
         onQueued: @escaping () -> Void = {}) {
        slots = PhotoLoadSlots(onQueued: onQueued)
        let service = SMBService(timeout: 15)
        self.fileSize = fileSize ?? { try await service.fileSize($0, path: $1) }
        self.read = read ?? { try await service.read($0, path: $1, offset: $2, length: $3) }
    }

    private func cachedImage(for key: CacheKey) -> UIImage? {
        guard let value = cache[key] else { return nil }
        cacheOrder.removeAll { $0 == key }
        if value.expires <= Date() { cache.removeValue(forKey: key); cacheCost -= value.cost; return nil }
        cacheOrder.append(key)
        return value.image
    }

    private func store(_ image: UIImage, for key: CacheKey, cost: Int) {
        guard cost <= cacheLimit else { return }
        if let old = cache.removeValue(forKey: key) { cacheCost -= old.cost }
        cacheOrder.removeAll { $0 == key }
        while !cacheOrder.isEmpty && (cacheCost + cost > cacheLimit || cacheOrder.count >= 96) {
            if let removed = cache.removeValue(forKey: cacheOrder.removeFirst()) { cacheCost -= removed.cost }
        }
        cache[key] = CachedImage(image, cost: cost); cacheOrder.append(key); cacheCost += cost
    }

    func image(config: SourceConfig, path: String, knownSize: Int64, maxPixelSize: Int, revision: Date? = nil) async throws -> UIImage {
        try Task.checkCancellation()
        guard MediaFile.isPhoto(path: path), MediaFile.shouldIndex(path: path, size: 1),
              PhotoLocation.contains(path: path, root: config.rootPath), !config.excludesFile(path) else { throw PhotoLoadingError.unavailable }
        guard knownSize <= Self.maximumEncodedBytes else { throw PhotoLoadingError.tooLarge }
        let pixels = min(Self.viewingPixels, max(1, maxPixelSize))
        let key = CacheKey(config, path, knownSize, pixels, revision)
        if let cached = cachedImage(for: key) { return cached }
        try await slots.acquire()
        do {
            try Task.checkCancellation()
            // Another waiting thumbnail may already have completed this request.
            if let cached = cachedImage(for: key) {
                await slots.release(); return cached
            }
            let size = try await fileSize(config, path)
            try Task.checkCancellation()
            guard size > 0 else { throw PhotoLoadingError.invalidImage }
            guard size <= Self.maximumEncodedBytes else { throw PhotoLoadingError.tooLarge }
            var bytes = Data()
            bytes.reserveCapacity(Int(size))
            var offset: Int64 = 0
            while offset < size {
                try Task.checkCancellation()
                let length = Int(min(1024 * 1024, size - offset))
                let chunk = try await read(config, path, offset, length)
                try Task.checkCancellation()
                guard !chunk.isEmpty, chunk.count <= length else { throw SMBError.unexpectedEndOfFile }
                bytes.append(chunk); offset += Int64(chunk.count)
            }
            let image = try Self.decode(bytes, maxPixelSize: pixels)
            try Task.checkCancellation()
            let cost = image.cgImage.map { $0.bytesPerRow * $0.height } ?? pixels * pixels * 4
            store(image, for: key, cost: cost)
            await slots.release()
            return image
        } catch {
            await slots.release()
            throw error
        }
    }

    nonisolated static func decode(_ data: Data, maxPixelSize: Int) throws -> UIImage {
        guard !data.isEmpty else { throw PhotoLoadingError.invalidImage }
        guard Int64(data.count) <= maximumEncodedBytes else { throw PhotoLoadingError.tooLarge }
        return try autoreleasepool {
            guard let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
                  let type = CGImageSourceGetType(source) as String?,
                  ["public.jpeg", "public.png", "org.webmproject.webp", "public.heic", "public.heif", "public.heics", "public.heifs"].contains(type) else { throw PhotoLoadingError.invalidImage }
            let index = type.hasPrefix("public.hei") ? CGImageSourceGetPrimaryImageIndex(source) : 0
            guard index < CGImageSourceGetCount(source),
                  let properties = CGImageSourceCopyPropertiesAtIndex(source, index, nil) as? [CFString: Any],
                  let width = properties[kCGImagePropertyPixelWidth] as? NSNumber,
                  let height = properties[kCGImagePropertyPixelHeight] as? NSNumber else { throw PhotoLoadingError.invalidImage }
            let w = width.int64Value, h = height.int64Value
            guard w > 0, h > 0, w <= 32768, h <= 32768, w * h <= 200_000_000 else { throw PhotoLoadingError.invalidDimensions }
            let budgetScale = min(1, sqrt(Double(maximumDecodedPixels) / Double(w * h)))
            let budgetEdge = max(1, Int(floor(Double(max(w, h)) * budgetScale)))
            let options: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true, kCGImageSourceShouldCacheImmediately: true,
                kCGImageSourceShouldAllowFloat: false,
                kCGImageSourceThumbnailMaxPixelSize: min(budgetEdge, viewingPixels, max(1, maxPixelSize))]
            guard let image = CGImageSourceCreateThumbnailAtIndex(source, index, options as CFDictionary) else { throw PhotoLoadingError.invalidImage }
            guard image.width * image.height <= maximumDecodedPixels,
                  image.bytesPerRow * image.height <= maximumDecodedBytes else { throw PhotoLoadingError.invalidDimensions }
            return UIImage(cgImage: image)
        }
    }
}
