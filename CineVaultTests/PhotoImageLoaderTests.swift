import Foundation
import ImageIO
import UIKit
import XCTest
@testable import CineVault

final class PhotoImageLoaderTests: XCTestCase {
    static func fixture(_ name: String) throws -> Data {
        let path = name as NSString
        let bundleURL = Bundle(for: PhotoImageLoaderTests.self).url(forResource: path.deletingPathExtension, withExtension: path.pathExtension)
        let url = bundleURL ?? URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("Fixtures/Photos/\(name)")
        return try Data(contentsOf: url)
    }

    private func configuration(root: String = "Photos", ignored: [String] = []) -> SourceConfig {
        SourceConfig(id: UUID(), host: "fixture.invalid", share: "Photos", rootPath: root, username: "", password: "", ignoredFolders: ignored)
    }

    func testNativeFormatsDownsampleAndRotateExifOrientation() throws {
        for name in ["01.jpg", "02.png", "03.webp"] {
            let image = try PhotoImageLoader.decode(Self.fixture(name), maxPixelSize: 32)
            XCTAssertLessThanOrEqual(image.cgImage!.width, 32, name)
            XCTAssertLessThanOrEqual(image.cgImage!.height, 32, name)
        }
        let portrait = try PhotoImageLoader.decode(Self.fixture("portrait.jpg"), maxPixelSize: 512)
        XCTAssertEqual(portrait.cgImage?.width, 64)
        XCTAssertEqual(portrait.cgImage?.height, 96)
        XCTAssertEqual(portrait.imageOrientation, .up)
        XCTAssertThrowsError(try PhotoImageLoader.decode(Data("not an image".utf8), maxPixelSize: 512))
    }

    @MainActor
    func testLargeSquareImageRespectsDecodedPixelAndByteBudgets() throws {
        let format = UIGraphicsImageRendererFormat(); format.scale = 1
        let data = UIGraphicsImageRenderer(size: CGSize(width: 3000, height: 3000), format: format).jpegData(withCompressionQuality: 0.8) { drawing in
            UIColor.blue.setFill(); drawing.fill(CGRect(x: 0, y: 0, width: 3000, height: 3000))
        }
        let image = try XCTUnwrap(PhotoImageLoader.decode(data, maxPixelSize: 4096).cgImage)
        XCTAssertLessThanOrEqual(image.width * image.height, PhotoImageLoader.maximumDecodedPixels)
        XCTAssertLessThanOrEqual(image.bytesPerRow * image.height, PhotoImageLoader.maximumDecodedBytes)
        XCTAssertLessThanOrEqual(image.width, 2048)
    }

    func testSixteenBitPNGStaysWithinDecodedBudget() throws {
        let data = Data(repeating: 0xff, count: 32 * 16 * 8)
        let image = try XCTUnwrap(CGImage(width: 32, height: 16, bitsPerComponent: 16, bitsPerPixel: 64, bytesPerRow: 32 * 8,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.last.rawValue).union(.byteOrder16Big),
            provider: CGDataProvider(data: data as CFData)!, decode: nil, shouldInterpolate: false, intent: .defaultIntent))
        let encoded = NSMutableData()
        let destination = try XCTUnwrap(CGImageDestinationCreateWithData(encoded, "public.png" as CFString, 1, nil))
        CGImageDestinationAddImage(destination, image, nil)
        XCTAssertTrue(CGImageDestinationFinalize(destination))
        let decoded = try XCTUnwrap(PhotoImageLoader.decode(encoded as Data, maxPixelSize: 512).cgImage)
        XCTAssertEqual(decoded.width, 32); XCTAssertEqual(decoded.height, 16)
        XCTAssertLessThanOrEqual(decoded.bytesPerRow * decoded.height, PhotoImageLoader.maximumDecodedBytes)
    }

    func testTooLargeOrExcludedFilesDoNotReadPhotoBytes() async throws {
        let loader = PhotoImageLoader(fileSize: { _, _ in PhotoImageLoader.maximumEncodedBytes + 1 }, read: { _, _, _, _ in
            XCTFail("Oversize or excluded files must not be read"); return Data()
        })
        do {
            _ = try await loader.image(config: configuration(), path: "Photos/large.jpg", knownSize: 1, maxPixelSize: 512)
            XCTFail("Expected size failure")
        } catch { XCTAssertEqual(error as? PhotoLoadingError, .tooLarge) }
        for path in ["Other/1.jpg", "Photos/../Private/1.jpg", "Photos/Private/1.jpg"] {
            do {
                _ = try await loader.image(config: configuration(ignored: ["Private"]), path: path, knownSize: 1, maxPixelSize: 512)
                XCTFail("Expected unavailable: \(path)")
            } catch { XCTAssertEqual(error as? PhotoLoadingError, .unavailable) }
        }
    }

    private actor Reads {
        var count = 0
        let bytes: Data
        init(_ bytes: Data) { self.bytes = bytes }
        func read(offset: Int64, length: Int) -> Data {
            count += 1
            return bytes.subdata(in: Int(offset)..<min(bytes.count, Int(offset) + length))
        }
    }

    func testCacheInvalidatesOnRescanAndSourceConfigurationChange() async throws {
        let bytes = try Self.fixture("01.jpg"), reads = Reads(try Self.fixture("01.jpg"))
        let loader = PhotoImageLoader(fileSize: { _, _ in Int64(bytes.count) }, read: { _, _, offset, length in await reads.read(offset: offset, length: length) })
        let config = configuration(), firstScan = Date(timeIntervalSince1970: 1)
        _ = try await loader.image(config: config, path: "Photos/1.jpg", knownSize: Int64(bytes.count), maxPixelSize: 512, revision: firstScan)
        _ = try await loader.image(config: config, path: "Photos/1.jpg", knownSize: Int64(bytes.count), maxPixelSize: 512, revision: firstScan)
        let cachedReads = await reads.count
        XCTAssertEqual(cachedReads, 1)
        _ = try await loader.image(config: config, path: "Photos/1.jpg", knownSize: Int64(bytes.count), maxPixelSize: 512, revision: Date(timeIntervalSince1970: 2))
        let edited = SourceConfig(id: config.id, host: "other.invalid", share: config.share, rootPath: config.rootPath, username: "", password: "")
        _ = try await loader.image(config: edited, path: "Photos/1.jpg", knownSize: Int64(bytes.count), maxPixelSize: 512, revision: firstScan)
        let refreshedReads = await reads.count
        XCTAssertEqual(refreshedReads, 3)
    }

    func testCancelledReadDoesNotReturnAnImage() async throws {
        let bytes = try Self.fixture("01.jpg")
        let entered = expectation(description: "Photo read started")
        let loader = PhotoImageLoader(fileSize: { _, _ in Int64(bytes.count) }, read: { _, _, _, _ in
            entered.fulfill()
            try await Task.sleep(nanoseconds: 20_000_000_000)
            return bytes
        })
        let task = Task { try await loader.image(config: configuration(), path: "Photos/1.jpg", knownSize: Int64(bytes.count), maxPixelSize: 512) }
        await fulfillment(of: [entered], timeout: 5)
        task.cancel()
        do { _ = try await task.value; XCTFail("A cancelled photo must not be returned") }
        catch { XCTAssertTrue(error is CancellationError) }
    }

    private actor BlockedReads {
        let bytes: Data
        let entered: XCTestExpectation
        var count = 0
        private var released = false
        private var waiters: [CheckedContinuation<Void, Never>] = []
        init(_ bytes: Data, entered: XCTestExpectation) { self.bytes = bytes; self.entered = entered }
        func read() async -> Data {
            count += 1
            if !released {
                entered.fulfill()
                await withCheckedContinuation { waiters.append($0) }
            }
            return bytes
        }
        func release() {
            released = true
            let pending = waiters; waiters = []
            pending.forEach { $0.resume() }
        }
    }

    func testOnlyTwoReadsRunAndQueuedCancellationDoesNotBlockLaterPhotos() async throws {
        let bytes = try Self.fixture("01.jpg")
        let entered = expectation(description: "Two read slots are occupied")
        entered.expectedFulfillmentCount = 2; entered.assertForOverFulfill = true
        let reads = BlockedReads(bytes, entered: entered)
        let loader = PhotoImageLoader(fileSize: { _, _ in Int64(bytes.count) }, read: { _, _, _, _ in await reads.read() })
        let config = configuration()
        let first = Task { try await loader.image(config: config, path: "Photos/1.jpg", knownSize: Int64(bytes.count), maxPixelSize: 512) }
        let second = Task { try await loader.image(config: config, path: "Photos/2.jpg", knownSize: Int64(bytes.count), maxPixelSize: 512) }
        await fulfillment(of: [entered], timeout: 5)
        let third = Task { try await loader.image(config: config, path: "Photos/3.jpg", knownSize: Int64(bytes.count), maxPixelSize: 512) }
        third.cancel()
        do { _ = try await third.value; XCTFail("Queued cancellation must finish without an image") }
        catch { XCTAssertTrue(error is CancellationError) }
        let activeReads = await reads.count
        XCTAssertEqual(activeReads, 2)
        await reads.release()
        _ = try await first.value; _ = try await second.value
        _ = try await loader.image(config: config, path: "Photos/4.jpg", knownSize: Int64(bytes.count), maxPixelSize: 512)
        let finalReads = await reads.count
        XCTAssertEqual(finalReads, 3)
    }
}
