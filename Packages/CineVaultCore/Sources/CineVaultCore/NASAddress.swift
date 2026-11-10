import Foundation

public enum NASAddressError: LocalizedError {
    case invalidAddress, invalidPort

    public var errorDescription: String? {
        switch self {
        case .invalidAddress: return "Enter a valid NAS hostname or IP address. Enter credentials in the sign-in fields."
        case .invalidPort: return "Port must be a number from 1 to 65535."
        }
    }
}

/// A validated server address, independent of share names and credentials.
public struct NASAddress: Sendable, Equatable {
    public let host: String
    public let port: Int?

    public var authority: String {
        (host.contains(":") ? "[\(host.replacingOccurrences(of: "%", with: "%25"))]" : host) +
            (port.map { ":\($0)" } ?? "")
    }

    public var serverURL: URL? { URL(string: "smb://\(authority)") }

    /// Accepts a hostname/IP, SMB URL or Windows network path. An explicit port takes priority.
    public static func parse(_ raw: String, port: Int? = nil) throws -> NASAddress {
        if let port, !(1...65535).contains(port) { throw NASAddressError.invalidPort }
        var text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let isURL = text.lowercased().hasPrefix("smb://")
        if isURL { text = String(text.dropFirst(6)) }
        guard !text.contains("://"), !text.contains("?"), !text.contains("#") else { throw NASAddressError.invalidAddress }
        text = text.replacingOccurrences(of: "\\", with: "/")
        if text.hasPrefix("//") { text = String(text.dropFirst(2)) }
        let authority = String(text.prefix { $0 != "/" })
        guard !authority.isEmpty, !authority.contains(where: { $0.isWhitespace || $0 == "@" }) else {
            throw NASAddressError.invalidAddress
        }
        var host: String
        var embeddedPort: Int?
        func parsePort(_ value: String) throws -> Int {
            guard !value.isEmpty, value.allSatisfy({ "0123456789".contains($0) }),
                  let parsed = Int(value), (1...65535).contains(parsed) else { throw NASAddressError.invalidPort }
            return parsed
        }
        if authority.hasPrefix("[") {
            guard let end = authority.firstIndex(of: "]") else { throw NASAddressError.invalidAddress }
            host = String(authority[authority.index(after: authority.startIndex)..<end])
            let suffix = String(authority[authority.index(after: end)...])
            guard suffix.isEmpty || suffix.hasPrefix(":"), host.contains(":") else { throw NASAddressError.invalidAddress }
            if !suffix.isEmpty { embeddedPort = try parsePort(String(suffix.dropFirst())) }
        } else if authority.filter({ $0 == ":" }).count == 1 {
            let parts = authority.components(separatedBy: ":")
            host = parts[0]
            embeddedPort = try parsePort(parts[1])
        } else { host = authority }
        if isURL { host = host.replacingOccurrences(of: "%25", with: "%") }
        guard host.contains(":") ? validIPv6(host) : validHostname(host) else { throw NASAddressError.invalidAddress }
        return NASAddress(host: host, port: port ?? embeddedPort)
    }

    private static func validHostname(_ host: String) -> Bool {
        let name = host.hasSuffix(".") ? String(host.dropLast()) : host
        if host.contains("."), host.allSatisfy({ "0123456789.".contains($0) }) { return validIPv4(host) }
        return (1...253).contains(host.count) && name.components(separatedBy: ".").allSatisfy { label in
            !label.isEmpty && label.count <= 63 && !label.hasPrefix("-") && !label.hasSuffix("-") &&
                label.allSatisfy { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" }
        }
    }

    private static func validIPv4(_ address: String) -> Bool {
        let octets = address.components(separatedBy: ".")
        return octets.count == 4 && octets.allSatisfy { octet in
            !octet.isEmpty && octet.allSatisfy { "0123456789".contains($0) } &&
                Int(octet).map { (0...255).contains($0) } == true
        }
    }

    private static func validIPv6(_ host: String) -> Bool {
        let parts = host.components(separatedBy: "%")
        let address = parts[0]
        if parts.count > 1 {
            guard parts.count == 2, !parts[1].isEmpty,
                  parts[1].allSatisfy({ $0.isLetter || $0.isNumber || "_.-".contains($0) }) else { return false }
        }
        guard !address.contains(":::"), address.components(separatedBy: "::").count <= 2 else { return false }
        let compressed = address.contains("::")
        if address.hasPrefix(":") && !address.hasPrefix("::") ||
            address.hasSuffix(":") && !address.hasSuffix("::") { return false }
        let groups = address.split(separator: ":").map(String.init)
        var units = 0
        for (index, group) in groups.enumerated() {
            if group.contains(".") {
                guard index == groups.count - 1, address.hasSuffix(group), validIPv4(group) else { return false }
                units += 2
            } else {
                guard (1...4).contains(group.count), group.allSatisfy({ "0123456789abcdefABCDEF".contains($0) }) else { return false }
                units += 1
            }
        }
        return compressed ? units < 8 : units == 8
    }
}
