import XCTest
@testable import CineVaultCore

final class NASAddressTests: XCTestCase {
    func testPastedNetworkAddressesDoNotTreatTheShareAsTheHost() throws {
        for input in ["nas.local", " nas.local ", "SMB://nas.local/Media/Movies", "\\\\nas.local\\Media\\Movies"] {
            XCTAssertEqual(try NASAddress.parse(input), NASAddress(host: "nas.local", port: nil))
        }
        XCTAssertEqual(try NASAddress.parse("smb://192.168.1.20/media").host, "192.168.1.20")
    }

    func testEmbeddedPortIsRetainedAndAnExplicitPortTakesPriority() throws {
        XCTAssertEqual(try NASAddress.parse("smb://nas.local:1445/media"), NASAddress(host: "nas.local", port: 1445))
        let address = try NASAddress.parse("nas.local:1445", port: 2445)
        XCTAssertEqual(address.port, 2445)
        XCTAssertEqual(address.serverURL?.absoluteString, "smb://nas.local:2445")
    }

    func testIPv6UsesAnUnbracketedConnectionHostAndBracketedURL() throws {
        for input in ["2001:db8::1", "[2001:db8::1]", "smb://[2001:db8::1]/media"] {
            XCTAssertEqual(try NASAddress.parse(input), NASAddress(host: "2001:db8::1", port: nil))
        }
        XCTAssertEqual(try NASAddress.parse("smb://[2001:db8::1]:1445/media").serverURL?.absoluteString,
                       "smb://[2001:db8::1]:1445")
        XCTAssertEqual(try NASAddress.parse("::ffff:192.0.2.1").host, "::ffff:192.0.2.1")
        XCTAssertEqual(try NASAddress.parse("smb://[fe80::1%25en0]").host, "fe80::1%en0")
    }

    func testInvalidOrCredentialBearingAddressesAreRejectedWithoutEchoingThem() {
        for input in ["", "bad host", "https://nas.local", "smb://user:private-password@nas.local/media",
                      "nas.local?secret=value", "nas.local#fragment", "[nas.local]", "2001:::1", "1::2::3",
                      "[::1", "::gggg", ":1::", "1::2:", "192.0.2.1::", "999.1.1.1", "nas..local"] {
            XCTAssertThrowsError(try NASAddress.parse(input)) { error in
                XCTAssertEqual(error.localizedDescription,
                    "Enter a valid NAS hostname or IP address. Enter credentials in the sign-in fields.")
            }
        }
    }

    func testInvalidPortsDoNotSilentlyFallBackToTheDefault() {
        for input in ["nas:", "nas:0", "nas:65536", "nas:-1", "nas:abc", "[::1]:999999999999"] {
            XCTAssertThrowsError(try NASAddress.parse(input)) { error in
                XCTAssertEqual(error.localizedDescription, "Port must be a number from 1 to 65535.")
            }
        }
        for port in [-1, 0, 65536] { XCTAssertThrowsError(try NASAddress.parse("nas", port: port)) }
    }
}
