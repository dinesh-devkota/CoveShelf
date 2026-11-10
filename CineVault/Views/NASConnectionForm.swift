import CineVaultCore
import Foundation

enum NASConnectionFormError: LocalizedError {
    case address, port, share, folder

    var errorDescription: String? {
        switch self {
        case .address: return "Enter the NAS name or IP address."
        case .port: return "Enter a port from 1 to 65535, or leave it empty for 445."
        case .share: return "Choose a shared folder, or enter its name."
        case .folder: return "Choose a folder inside this share."
        }
    }
}

/// The editor and setup flow validate the same values before opening a connection or saving.
enum NASConnectionForm {
    static func configuration(id: UUID, host: String, port: String, share: String, rootPath: String,
                              username: String, password: String, requireShare: Bool = true,
                              ignoredFolders: [String] = [], ignoredFiles: [String] = []) throws -> SourceConfig {
        let address = host.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !address.isEmpty else { throw NASConnectionFormError.address }
        let portText = port.trimmingCharacters(in: .whitespacesAndNewlines)
        let number = Int(portText)
        guard portText.isEmpty || number.map({ (1...65535).contains($0) }) == true else {
            throw NASConnectionFormError.port
        }
        let shareName = share.trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "/\\"))
        guard !requireShare || !shareName.isEmpty else { throw NASConnectionFormError.share }
        let folder = requireShare ? rootPath.trimmingCharacters(in: .whitespacesAndNewlines) : ""
        let normalized: String
        if folder.isEmpty || folder == "/" { normalized = "" }
        else if let path = ScanExclusions.normalizeFolder(path: folder) { normalized = path }
        else { throw NASConnectionFormError.folder }
        let user = username.trimmingCharacters(in: .whitespacesAndNewlines)
        let server = try NASAddress.parse(address, port: number)
        return SourceConfig(id: id, host: server.host, port: server.port, share: shareName, rootPath: normalized,
            username: user, password: user.isEmpty ? "" : password,
            ignoredFolders: ignoredFolders, ignoredFiles: ignoredFiles)
    }
}
