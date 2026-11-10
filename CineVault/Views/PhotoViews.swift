import CineVaultCore
import SwiftData
import SwiftUI

private struct PhotoLoaderKey: EnvironmentKey {
    static let defaultValue = PhotoImageLoader.shared
}
extension EnvironmentValues {
    var photoLoader: PhotoImageLoader {
        get { self[PhotoLoaderKey.self] }
        set { self[PhotoLoaderKey.self] = newValue }
    }
}

@MainActor
struct PhotosView: View {
    @Query private var items: [Movie]
    private var albums: [PhotoAlbum] { PhotoLibrary.albums(items) }
    @FocusState private var focusedAlbum: PhotoAlbum.ID?
    private let columns = [GridItem(.adaptive(minimum: 380, maximum: 380), spacing: 42)]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 32) {
                HStack(alignment: .bottom) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("YOUR MEMORIES").font(.system(size: 18, weight: .semibold)).tracking(3).foregroundStyle(CinemaTheme.accent)
                        Text("Photos").font(.system(size: 62, weight: .bold))
                    }
                    Spacer()
                    Text("\(albums.count) albums · \(albums.reduce(0) { $0 + $1.photos.count }) photos").foregroundStyle(CinemaTheme.secondaryText)
                }
                if albums.isEmpty {
                    ContentUnavailableView("Your photos belong here", systemImage: "photo.on.rectangle.angled",
                        description: Text("Add a NAS photo folder in Sources, then scan. Albums follow your folders, and your photos stay on your NAS."))
                        .padding(.top, 100)
                } else {
                    LazyVGrid(columns: columns, spacing: 48) {
                        ForEach(albums) { album in
                            NavigationLink {
                                PhotoAlbumView(albumID: album.id)
                            } label: {
                                VStack(alignment: .leading, spacing: 10) {
                                    if let photo = album.photos.first {
                                        PhotoThumbnail(photo: photo, width: 380, height: 230, focused: focusedAlbum == album.id)
                                    }
                                    Text(album.title).font(.system(size: 26, weight: .semibold)).lineLimit(1)
                                    Text("\(album.sourceName) · \(album.photos.count) photos").font(.system(size: 20)).foregroundStyle(CinemaTheme.secondaryText)
                                    if !album.folder.isEmpty { Text(album.folder).font(.caption2).foregroundStyle(CinemaTheme.secondaryText).lineLimit(1) }
                                }
                                .frame(width: 380, alignment: .leading)
                            }.buttonStyle(.plain).focused($focusedAlbum, equals: album.id)
                        }
                    }.padding(.vertical, 24)
                }
            }.padding(.horizontal, 70).padding(.bottom, 100)
        }.background(CinemaBackground())
    }
}

@MainActor
struct PhotoAlbumView: View {
    let albumID: PhotoAlbum.ID
    @Query private var items: [Movie]
    @Environment(\.dismiss) private var dismiss
    private var album: PhotoAlbum? { PhotoLibrary.albums(items).first { $0.id == albumID } }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                HStack {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(album?.title ?? "Album").font(.system(size: 54, weight: .bold))
                        if let album { Text("\(album.sourceName) · \(album.photos.count) photos").foregroundStyle(CinemaTheme.secondaryText) }
                    }
                    Spacer()
                    Button { dismiss() } label: { Label("Back", systemImage: "chevron.left") }
                }
                if let album, !album.photos.isEmpty {
                    PhotoGrid(photos: album.photos, focusesFirstPhoto: true)
                } else {
                    ContentUnavailableView("No photos in this album", systemImage: "photo", description: Text("Scan its source to check for photos."))
                }
            }.padding(.horizontal, 70).padding(.bottom, 100)
        }.background(CinemaBackground())
    }
}

/// Reused by albums and search. A selected photo always opens its complete local album.
@MainActor
struct PhotoGrid: View {
    let photos: [Movie]
    var focusesFirstPhoto = false
    @FocusState private var focusedPhoto: UUID?
    @State private var selected: Movie?
    private let columns = [GridItem(.adaptive(minimum: 350, maximum: 350), spacing: 38)]

    var body: some View {
        LazyVGrid(columns: columns, spacing: 38) {
            ForEach(photos) { photo in
                Button { selected = photo } label: {
                    VStack(alignment: .leading, spacing: 10) {
                        PhotoThumbnail(photo: photo, width: 350, height: 220, focused: focusedPhoto == photo.id)
                        Text(PhotoLibrary.title(photo)).font(.system(size: 22, weight: .semibold)).lineLimit(1)
                    }.frame(width: 350, alignment: .leading)
                }.buttonStyle(.plain).focused($focusedPhoto, equals: photo.id)
            }
        }.padding(.vertical, 24)
        .fullScreenCover(item: $selected) { PhotoDestination(photo: $0) }
        .onAppear { if focusesFirstPhoto && focusedPhoto == nil { focusedPhoto = photos.first?.id } }
    }
}

private struct PhotoLoadIdentity: Equatable {
    let id: UUID
    let path: String
    let size: Int64
    let config: SourceConfig?
    let allowed: Bool
    let attempt: Int
    let scanDate: Date?
}

@MainActor
struct PhotoThumbnail: View {
    let photo: Movie
    let width: CGFloat
    let height: CGFloat
    var focused = false
    @Environment(\.photoLoader) private var loader
    @State private var image: UIImage?
    @State private var failed = false

    private var identity: PhotoLoadIdentity {
        PhotoLoadIdentity(id: photo.id, path: photo.filePath, size: photo.fileSize,
            config: try? PhotoLibrary.configuration(for: photo), allowed: PhotoLibrary.isAvailable(photo), attempt: 0,
            scanDate: photo.source?.lastScanDate)
    }

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 16).fill(.white.opacity(0.06))
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else if failed {
                VStack(spacing: 10) {
                    Image(systemName: "photo.badge.exclamationmark").font(.system(size: 36))
                    Text("Open to retry").font(.caption)
                }.foregroundStyle(CinemaTheme.secondaryText)
            } else { ProgressView().tint(.white) }
        }
        .frame(width: width, height: height).clipped().clipShape(RoundedRectangle(cornerRadius: 16))
        .overlay { RoundedRectangle(cornerRadius: 16).strokeBorder(.white.opacity(focused ? 0.9 : 0.1), lineWidth: focused ? 3 : 1) }
        .scaleEffect(focused ? 1.04 : 1).animation(.easeOut(duration: 0.18), value: focused)
        .task(id: identity) {
            let request = identity
            image = nil; failed = false
            do {
                guard let config = request.config, request.allowed else { throw PhotoLoadingError.unavailable }
                let result = try await loader.image(config: config, path: request.path, knownSize: request.size,
                    maxPixelSize: PhotoImageLoader.thumbnailPixels, revision: request.scanDate)
                try Task.checkCancellation()
                guard identity == request else { return }
                image = result
            } catch is CancellationError { }
            catch { if identity == request && !Task.isCancelled { failed = true } }
        }
        .accessibilityLabel(PhotoLibrary.title(photo))
    }
}

@MainActor
struct PhotoDestination: View {
    let photo: Movie
    @Query private var items: [Movie]
    var body: some View {
        PhotoViewer(photos: PhotoLibrary.album(containing: photo, in: items), initialID: photo.id)
    }
}

@MainActor
struct PhotoViewer: View {
    let photos: [Movie]
    let initialID: UUID
    @Environment(\.dismiss) private var dismiss
    @Environment(\.photoLoader) private var loader
    private enum Control: Hashable { case photo, previous, next, retry, back }
    @FocusState private var focus: Control?
    @State private var selectedID: UUID?
    @State private var image: UIImage?
    @State private var error: String?
    @State private var attempt = 0
    private var available: [Movie] { photos.filter(PhotoLibrary.isAvailable) }
    private var index: Int? { available.firstIndex { $0.id == (selectedID ?? initialID) } }
    private var current: Movie? { index.map { available[$0] } }
    private var identity: PhotoLoadIdentity? {
        current.map { PhotoLoadIdentity(id: $0.id, path: $0.filePath, size: $0.fileSize,
            config: try? PhotoLibrary.configuration(for: $0), allowed: PhotoLibrary.isAvailable($0), attempt: attempt,
            scanDate: $0.source?.lastScanDate) }
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(spacing: 20) {
                ZStack {
                    if let image { Image(uiImage: image).resizable().scaledToFit() }
                    else if let error {
                        VStack(spacing: 18) {
                            Image(systemName: "photo.badge.exclamationmark").font(.system(size: 56))
                            Text(error).font(.title3).multilineTextAlignment(.center).frame(maxWidth: 950)
                            if current != nil { Button("Try again") { attempt += 1 }.focused($focus, equals: .retry) }
                        }
                    } else if current != nil { ProgressView("Opening photo…") }
                    else { Text("This photo is no longer available.").font(.title3) }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .contentShape(Rectangle()).focusable().focusEffectDisabled().focused($focus, equals: .photo)
                .onMoveCommand { direction in
                    if direction == .left { move(-1) }
                    else if direction == .right { move(1) }
                }
                VStack(spacing: 8) {
                    Text(current.map(PhotoLibrary.title) ?? "Photo").font(.title3).lineLimit(1)
                    if let current, let index {
                        Text("\(index + 1) of \(available.count) · \(current.source?.name ?? "NAS") · \(PhotoLibrary.parentFolder(current.filePath))")
                            .font(.caption).foregroundStyle(CinemaTheme.secondaryText).lineLimit(1)
                    }
                }
                HStack(spacing: 24) {
                    Button { move(-1) } label: { Label("Previous", systemImage: "chevron.left") }
                        .focused($focus, equals: .previous).disabled((index ?? 0) <= 0)
                    Button { move(1) } label: { Label("Next", systemImage: "chevron.right") }
                        .focused($focus, equals: .next).disabled(index.map { $0 + 1 >= available.count } ?? true)
                    Button { dismiss() } label: { Label("Back", systemImage: "xmark") }.focused($focus, equals: .back)
                }
            }.padding(.horizontal, 60).padding(.vertical, 36)
        }
        .onExitCommand { dismiss() }
        .onAppear { focus = .photo }
        .task(id: identity) {
            let request = identity
            image = nil; error = nil
            guard let request else { focus = .back; return }
            do {
                guard let config = request.config, request.allowed else { throw PhotoLoadingError.unavailable }
                let result = try await loader.image(config: config, path: request.path, knownSize: request.size,
                    maxPixelSize: PhotoImageLoader.viewingPixels, revision: request.scanDate)
                try Task.checkCancellation()
                guard identity == request else { return }
                image = result
            } catch is CancellationError { }
            catch {
                if identity == request && !Task.isCancelled { self.error = error.localizedDescription; focus = .retry }
            }
        }
    }

    private func move(_ delta: Int) {
        guard let index, available.indices.contains(index + delta) else { return }
        selectedID = available[index + delta].id
        image = nil; error = nil; focus = .photo
    }
}
