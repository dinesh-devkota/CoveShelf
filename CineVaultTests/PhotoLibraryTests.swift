import CineVaultCore
import Foundation
import SwiftData
import XCTest
@testable import CineVault

@MainActor
final class PhotoLibraryTests: XCTestCase {
    private func source(_ name: String = "Family NAS", root: String = "Photos") -> MediaSource {
        MediaSource(name: name, host: "fixture.invalid", share: "Media", rootPath: root, username: "")
    }
    private func photo(_ path: String, source: MediaSource) -> Movie {
        let value = Movie(filePath: path, fileSize: 100, source: source)
        value.resetToFileName()
        return value
    }

    func testAlbumsUseSourceAndFullParentPathWithNaturalPhotoOrder() {
        let first = source(), second = source("Second NAS")
        let ten = photo("Photos/Trip/10.jpg", source: first)
        let two = photo("Photos/Trip/2.jpg", source: first)
        let sameName = photo("Photos/Trip/2.jpg", source: second)
        let nested = photo("Photos/Archive/Trip/1.jpg", source: first)
        let albums = PhotoLibrary.albums([ten, sameName, nested, two])
        XCTAssertEqual(albums.count, 3)
        let trip = albums.first { $0.id.sourceID == first.id && $0.folder == "Photos/Trip" }!
        XCTAssertEqual(trip.photos.map(\.id), [two.id, ten.id])
        XCTAssertEqual(PhotoLibrary.album(containing: ten, in: [ten, sameName, nested, two]).map(\.id), [two.id, ten.id])
    }

    func testIgnoredHiddenMissingAndOutsideRootPhotosAreUnavailableImmediately() throws {
        let source = source()
        let kept = photo("Photos/Trip/1.jpg", source: source)
        let hidden = photo("Photos/Hidden.jpg", source: source); hidden.isHidden = true
        let missing = photo("Photos/Missing.jpg", source: source); missing.isMissing = true
        let outside = photo("Photos-old/1.jpg", source: source)
        let invalid = photo("Photos/../Private/1.jpg", source: source)
        let ignored = photo("Photos/Private/1.jpg", source: source)
        source.ignoredFolders = ["Private"]
        XCTAssertEqual(PhotoLibrary.albums([kept, hidden, missing, outside, invalid, ignored]).flatMap(\.photos).map(\.id), [kept.id])
        source.rootPath = "Photos/Other"
        XCTAssertFalse(PhotoLibrary.isAvailable(kept))
        XCTAssertThrowsError(try PhotoLibrary.configuration(for: kept))
        XCTAssertFalse(PhotoLocation.contains(path: "Photos/Trip/1.jpg", root: "Photos/Tri"))
        XCTAssertTrue(PhotoLocation.contains(path: "Photos\\Trip\\1.jpg", root: "Photos/Trip"))
    }

    func testPhotoScanUsesFilenameAndNeverRequestsMetadataOrEpisodeMatching() async throws {
        let container = try ModelContainer(for: MediaSource.self, Movie.self, configurations: ModelConfiguration(isStoredInMemoryOnly: true))
        let context = container.mainContext
        let source = source(); context.insert(source)
        var metadataRequests = 0
        let metadata = MetadataService(requestData: { _ in metadataRequests += 1; throw URLError(.unsupportedURL) }, readAPIKey: { "fixture" })
        let scanner = LibraryScanner(fetchMetadata: { try await metadata.fetchMissing(in: $0) }, scanVideos: { _ in
            [RemoteFile(path: "Photos/Trip/S01E02 (2024) {tmdb-42}.jpg", size: 100)]
        })
        await scanner.scan(source, context: context)
        let photo = try XCTUnwrap(source.movies.first)
        XCTAssertEqual(photo.title, "S01E02 (2024) {tmdb-42}")
        XCTAssertEqual(photo.mediaKind, .photo)
        XCTAssertNil(photo.episodeInfo); XCTAssertNil(photo.tmdbID); XCTAssertNil(photo.year)
        XCTAssertTrue(photo.metadataFetched)
        photo.metadataFetched = false
        try await metadata.match(photo)
        let appliedMovie = try await metadata.apply(tmdbID: 42, to: photo, force: true)
        let appliedTV = try await metadata.applyTV(seriesID: 42, to: photo, force: true)
        XCTAssertFalse(appliedMovie); XCTAssertFalse(appliedTV)
        XCTAssertEqual(metadataRequests, 0)
        XCTAssertFalse(PlaybackController.isNativeCandidate(photo))
        XCTAssertTrue(LibrarySearch.matches(photo, text: "S01E02", normalized: TitleMatcher.normalize("S01E02")))
        photo.isWatchlisted = true
        XCTAssertTrue(LibraryQuery.watchlist([photo]).isEmpty)
    }
}
