import XCTest
@testable import CineVaultCore

final class PhotoFileTests: XCTestCase {
    func testSupportedPhotosRemainPhotosEvenWithMovieOrEpisodeNames() {
        for ext in ["jpg", "jpeg", "png", "webp", "heic", "heif"] {
            let path = "Movies/Show.S01E02.{tmdb-123}.\(ext.uppercased())"
            XCTAssertTrue(MediaFile.isPhoto(path: path), ext)
            XCTAssertTrue(MediaFile.shouldIndex(path: path, size: 100), ext)
            XCTAssertEqual(MediaFile.kind(path: path, genres: ["Documentary"], matched: true, isEpisode: true), .photo, ext)
            XCTAssertFalse(MediaFile.isPlayable(path: path), ext)
        }
    }

    func testPhotosRespectEmptyFilesSystemFoldersAndUnsupportedExtensions() {
        for path in ["Photos/Empty.jpg", "Photos/.cache/Thumb.png", "Photos/@eaDir/Thumb.jpg", "Photos/node_modules/image.webp"] {
            XCTAssertFalse(MediaFile.shouldIndex(path: path, size: path.contains("Empty") ? 0 : 100), path)
        }
        for ext in ["svg", "pdf", "gif", "raw", "dng", "ts"] { XCTAssertFalse(MediaFile.isPhoto(path: "Photo.\(ext)"), ext) }
        XCTAssertTrue(MediaFile.shouldIndex(path: "Photos/Samples/Family-trailer.jpg", size: 100))
    }
}
