import XCTest
import Foundation
@testable import CineVault

final class PhotoSMBIntegrationTests: XCTestCase {
    private func configuration(root: String = "Photos", ignored: [String] = []) -> SourceConfig {
        SourceConfig(id: UUID(), host: "127.0.0.1", port: 1445, share: "COVEPHOTOS", rootPath: root,
            username: "cinevault", password: "fixture-only", ignoredFolders: ignored)
    }

    @MainActor
    func testPhotoShareScanAndNativeDecodingKeepVideoFixtureSeparate() async throws {
        let config = configuration()
        let service = SMBService(timeout: 5)
        let files = try await service.scanMedia(config) { _ in }
        XCTAssertEqual(Set(files.map { ($0.path as NSString).lastPathComponent }), ["01.jpg", "02.png", "03.webp", "portrait.jpg", "broken.jpg"])
        let loader = PhotoImageLoader(fileSize: { try await service.fileSize($0, path: $1) },
            read: { try await service.read($0, path: $1, offset: $2, length: $3) })
        for name in ["01.jpg", "02.png", "03.webp"] {
            let file = try XCTUnwrap(files.first { $0.path.hasSuffix(name) })
            let image = try await loader.image(config: config, path: file.path, knownSize: file.size, maxPixelSize: 512)
            XCTAssertEqual(image.cgImage?.width, 96, name)
            XCTAssertEqual(image.cgImage?.height, 64, name)
        }
        let portrait = try XCTUnwrap(files.first { $0.path.hasSuffix("portrait.jpg") })
        let rotated = try await loader.image(config: config, path: portrait.path, knownSize: portrait.size, maxPixelSize: 4096)
        XCTAssertEqual(rotated.cgImage?.width, 64)
        XCTAssertEqual(rotated.cgImage?.height, 96)
        let source = MediaSource(name: "Photo fixture", host: config.host, port: config.port, share: config.share,
            rootPath: config.rootPath, username: config.username)
        let portraitRow = Movie(filePath: portrait.path, fileSize: portrait.size, source: source)
        XCTAssertTrue(PhotoLibrary.isAvailable(portraitRow))
        source.ignoredFolders = ["Family"]
        XCTAssertFalse(PhotoLibrary.isAvailable(portraitRow), "Ignoring a folder hides its photos before rescan.")
        source.ignoredFolders = nil; source.rootPath = "Photos/Trip"
        XCTAssertFalse(PhotoLibrary.isAvailable(portraitRow), "Changing the scan root must immediately exclude the old album.")
        let trip = try XCTUnwrap(files.first { $0.path.hasSuffix("01.jpg") })
        XCTAssertTrue(PhotoLibrary.isAvailable(Movie(filePath: trip.path, fileSize: trip.size, source: source)))
        let ignored = configuration(ignored: ["Family"])
        let remaining = try await service.scanMedia(ignored) { _ in }
        XCTAssertFalse(remaining.contains { $0.path.hasSuffix("portrait.jpg") })
        await service.reset(config); await service.reset(ignored)
    }

    func testCorruptAndOversizePhotosFailWithoutAPlayerOrFullDownload() async throws {
        let config = configuration(root: "")
        let loader = PhotoImageLoader()
        do {
            _ = try await loader.image(config: config, path: "Photos/Trip/broken.jpg", knownSize: 1, maxPixelSize: 512)
            XCTFail("Corrupt image must fail")
        } catch { XCTAssertEqual(error as? PhotoLoadingError, .invalidImage) }
        do {
            _ = try await loader.image(config: config, path: "Validation/TooLarge.jpg", knownSize: 1, maxPixelSize: 512)
            XCTFail("Oversize image must fail before reading the payload")
        } catch { XCTAssertEqual(error as? PhotoLoadingError, .tooLarge) }
    }
}
