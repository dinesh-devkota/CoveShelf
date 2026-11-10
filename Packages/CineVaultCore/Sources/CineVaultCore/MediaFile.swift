import Foundation
#if canImport(UniformTypeIdentifiers)
import UniformTypeIdentifiers
#endif

public enum MediaKind: String, CaseIterable, Hashable, Sendable {
    case movie, tvShow, documentary, music, photo
    public var label: String {
        switch self { case .movie: return "Movies"; case .tvShow: return "TV Shows"; case .documentary: return "Documentaries"; case .music: return "Music"; case .photo: return "Photos" }
    }
}

public enum MediaFile {
    private static let audio: Set<String> = ["mp3", "m4a", "aac", "flac", "wav", "aiff", "aif", "ogg", "opus", "wma"]
    private static let photos: Set<String> = ["jpg", "jpeg", "png", "webp", "heic", "heif"]
    private static func parts(_ path: String) -> [String] {
        path.replacingOccurrences(of: "\\", with: "/").split(separator: "/").map(String.init)
    }
    private static func isTypeScriptDeclaration(_ path: String) -> Bool {
        parts(path).last?.lowercased().hasSuffix(".d.ts") == true
    }
    public static func isAudio(path: String) -> Bool { audio.contains((path as NSString).pathExtension.lowercased()) }
    public static func isPhoto(path: String) -> Bool { photos.contains((path as NSString).pathExtension.lowercased()) }
    public static func kind(path: String, genres: [String] = [], matched: Bool = false, isEpisode: Bool? = nil) -> MediaKind {
        if isPhoto(path: path) { return .photo }
        if isAudio(path: path) { return .music }
        if isEpisode ?? (EpisodeParser.parse(path: path) != nil) { return .tvShow }
        if matched {
            let names = genres.map { $0.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() }
            if names.contains("documentary") { return .documentary }
            if names.contains("music") { return .music }
            if names.contains(where: { !$0.isEmpty }) { return .movie }
        }
        for folder in parts(path).dropLast().reversed() {
            switch folder.lowercased().filter({ !$0.isWhitespace && !"._-".contains($0) }) {
            case "documentary", "documentaries", "documentaryfilms", "documentarymovies": return .documentary
            case "music", "musicvideos", "concerts", "concertfilms", "concertvideos": return .music
            case "movies", "films": return .movie
            default: continue
            }
        }
        return .movie
    }
    public static func shouldSkipDirectory(name: String) -> Bool {
        let lower = name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return lower.first.map { ".@#$".contains($0) } == true ||
            ["system volume information", "lost+found", "recycler", "recycle.bin", "node_modules"].contains(lower)
    }
    public static func shouldIndex(path: String, size: Int64) -> Bool {
        guard size > 0, !isTypeScriptDeclaration(path), !parts(path).contains(where: { shouldSkipDirectory(name: $0) }) else { return false }
        return isPhoto(path: path) || audio.contains((path as NSString).pathExtension.lowercased()) ||
            VideoFile.shouldIndex(path: path.replacingOccurrences(of: "\\", with: "/"), size: size, minimumSize: 1)
    }
    static func isPlaybackPathAllowed(_ path: String) -> Bool {
        !isTypeScriptDeclaration(path) && !parts(path).contains(where: { shouldSkipDirectory(name: $0) })
    }
    /// .ts is also a source-code extension; callers must probe before emitting these candidates.
    public static func requiresContentCheck(path: String) -> Bool {
        (path as NSString).pathExtension.lowercased() == "ts" && !isTypeScriptDeclaration(path) &&
            !parts(path).contains(where: { shouldSkipDirectory(name: $0) })
    }

    /// Inspect up to 1024 bytes: at least three complete, consistently aligned transport packets.
    public static func isTransportStream(prefix: Data) -> Bool {
        let bytes = Array(prefix.prefix(1024))
        return [(188, 0), (192, 4), (204, 0)].contains { layout in
            let (packetSize, syncOffset) = layout
            let packets = bytes.count / packetSize
            return packets >= 3 && (0..<packets).allSatisfy { packet in
                let start = packet * packetSize + syncOffset
                return bytes[start] == 0x47 && (bytes[start + 3] & 0x30) != 0
            }
        }
    }
    /// Native container candidate; AVAsset must still verify the file and its codecs before playback.
    public static func isPlayable(path: String) -> Bool {
        guard isPlaybackPathAllowed(path) else { return false }
        let ext = (path as NSString).pathExtension.lowercased()
        return ["mp3", "m4a", "aac", "flac", "wav", "aiff", "aif"].contains(ext) || VideoFile.isNativelyPlayable(path: path)
    }
    public static func contentType(path: String) -> String {
        #if canImport(UniformTypeIdentifiers)
        if let type = UTType(filenameExtension: (path as NSString).pathExtension) { return type.identifier }
        #endif
        return VideoFile.contentType(path: path)
    }
}
