import CineVaultCore
import Foundation

enum PhotoLocation {
    static func contains(path: String, root: String) -> Bool {
        guard let file = ScanExclusions.normalizeFile(path: path) else { return false }
        let trimmed = root.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty || trimmed == "/" { return true }
        guard let folder = ScanExclusions.normalizeFolder(path: trimmed) else { return false }
        return file.lowercased().hasPrefix(folder.lowercased() + "/")
    }
}

struct PhotoAlbum: Identifiable {
    struct ID: Hashable { let sourceID: UUID; let folder: String }
    let id: ID
    let title: String
    let sourceName: String
    let folder: String
    let photos: [Movie]
}

@MainActor
enum PhotoLibrary {
    static func isAvailable(_ photo: Movie) -> Bool {
        MediaFile.isPhoto(path: photo.filePath) && !photo.isDeleted && !photo.isHidden && !photo.isMissing &&
            photo.source.map { !$0.isDeleted && PhotoLocation.contains(path: photo.filePath, root: $0.rootPath) &&
                !$0.isIgnored(filePath: photo.filePath) } == true
    }

    static func parentFolder(_ path: String) -> String {
        path.replacingOccurrences(of: "\\", with: "/").split(separator: "/").dropLast().joined(separator: "/")
    }

    static func title(_ photo: Movie) -> String {
        photo.title.isEmpty ? (photo.fileName as NSString).deletingPathExtension : photo.title
    }

    static func albums(_ items: [Movie]) -> [PhotoAlbum] {
        let photos = items.filter(isAvailable)
        let groups = Dictionary(grouping: photos) { photo in
            PhotoAlbum.ID(sourceID: photo.source!.id, folder: parentFolder(photo.filePath))
        }
        return groups.compactMap { id, items in
            guard let source = items.first?.source else { return nil }
            let sorted = items.sorted {
                let order = title($0).localizedStandardCompare(title($1))
                return order == .orderedSame ? $0.filePath < $1.filePath : order == .orderedAscending
            }
            return PhotoAlbum(id: id, title: id.folder.split(separator: "/").last.map(String.init) ?? source.share,
                sourceName: source.name, folder: id.folder, photos: sorted)
        }.sorted {
            let sourceOrder = $0.sourceName.localizedStandardCompare($1.sourceName)
            if sourceOrder != .orderedSame { return sourceOrder == .orderedAscending }
            let folderOrder = $0.folder.localizedStandardCompare($1.folder)
            return folderOrder == .orderedSame ? $0.id.sourceID.uuidString < $1.id.sourceID.uuidString : folderOrder == .orderedAscending
        }
    }

    static func album(containing photo: Movie, in items: [Movie]) -> [Movie] {
        guard let source = photo.source else { return [] }
        let key = PhotoAlbum.ID(sourceID: source.id, folder: parentFolder(photo.filePath))
        return albums(items).first { $0.id == key }?.photos ?? []
    }

    static func configuration(for photo: Movie) throws -> SourceConfig {
        guard isAvailable(photo), let source = photo.source else { throw PhotoLoadingError.unavailable }
        return try source.config()
    }
}
