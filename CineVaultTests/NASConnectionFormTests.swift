import Foundation
import XCTest
@testable import CineVault

final class NASConnectionFormTests: XCTestCase {
    private func config(host: String = "media.local", port: String = "", share: String = "Media",
                        root: String = "", username: String = "", requireShare: Bool = true) throws -> SourceConfig {
        try NASConnectionForm.configuration(id: UUID(), host: host, port: port, share: share,
            rootPath: root, username: username, password: "fixture-password", requireShare: requireShare)
    }

    func testMalformedOrOutOfRangePortCannotSilentlyUseDefault() {
        for port in ["invalid", "0", "65536", "-1", "445.0"] {
            XCTAssertThrowsError(try config(port: port))
        }
    }

    func testAddressPortIsPreservedUnlessExplicitlyOverridden() throws {
        let embedded = try config(host: " smb://media.local:1445/Media ")
        XCTAssertEqual(embedded.host, "media.local")
        XCTAssertEqual(embedded.port, 1445)
        XCTAssertEqual(try config(host: "media.local:1445", port: " 2445 ").port, 2445)
    }

    func testNormalizesShareFolderAndAccountBeforePersistence() throws {
        let result = try config(share: " /Media/ ", root: " Movies\\Classics/ ", username: " viewer ")
        XCTAssertEqual(result.share, "Media")
        XCTAssertEqual(result.rootPath, "Movies/Classics")
        XCTAssertEqual(result.username, "viewer")
        XCTAssertEqual(result.password, "fixture-password")
        XCTAssertEqual(try config(root: "/").rootPath, "")
    }

    func testGuestDoesNotPersistUnusedAccountPassword() throws {
        XCTAssertEqual(try config(username: "  ").password, "")
    }

    func testRejectsMissingAddressShareAndTraversal() {
        XCTAssertThrowsError(try config(host: " "))
        XCTAssertThrowsError(try config(share: " / "))
        XCTAssertThrowsError(try config(root: "Movies/../Private"))
        XCTAssertNoThrow(try config(share: "", requireShare: false))
        XCTAssertNoThrow(try config(root: "Movies/../Private", requireShare: false))
    }
}
