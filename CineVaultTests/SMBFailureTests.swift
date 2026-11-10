import Foundation
import XCTest
@testable import CineVault

final class SMBFailureTests: XCTestCase {
    func testAccessDenialAndExplicitLogonFailuresPromptForAnAccount() {
        XCTAssertTrue(SMBService.isAuthenticationFailure(POSIXError(.EACCES)))
        let login = NSError(domain: NSPOSIXErrorDomain, code: Int(POSIXErrorCode.ECONNREFUSED.rawValue),
            userInfo: [NSLocalizedDescriptionKey: "Session setup failed with (0xc000006d) STATUS_LOGON_FAILURE."])
        XCTAssertTrue(SMBService.isAuthenticationFailure(login))
        XCTAssertTrue(SMBService.isAuthenticationFailure(NSError(domain: "Wrapper", code: 1,
            userInfo: [NSUnderlyingErrorKey: login])))
    }

    func testNetworkAndMissingFolderFailuresDoNotPromptForCredentials() {
        let codes: [POSIXErrorCode] = [.ECONNREFUSED, .ETIMEDOUT, .ENOENT, .ENOTDIR, .ENETUNREACH]
        for code in codes {
            XCTAssertFalse(SMBService.isAuthenticationFailure(POSIXError(code)))
        }
        XCTAssertFalse(SMBService.isAuthenticationFailure(NSError(domain: NSCocoaErrorDomain,
            code: Int(POSIXErrorCode.EACCES.rawValue))))
    }
}
