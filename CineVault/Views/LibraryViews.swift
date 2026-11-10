import CineVaultCore
import SwiftData
import SwiftUI

enum LibrarySort: String, CaseIterable, Identifiable {
    case title = "Title", recentlyAdded = "Recently Added", year = "Year", rating = "Rating", lastPlayed = "Last Played"
    var id: String { rawValue }

    func sorted(_ movies: [Movie]) -> [Movie] {
        switch self {
        case .title: return movies.sorted { $0.sortTitle < $1.sortTitle }
        case .recentlyAdded: return movies.sorted { $0.dateAdded > $1.dateAdded }
        case .year: return movies.sorted { ($0.year ?? 0) > ($1.year ?? 0) }
        case .rating: return movies.sorted { ($0.rating ?? 0) > ($1.rating ?? 0) }
        case .lastPlayed: return movies.sorted { ($0.lastPlayedAt ?? .distantPast) > ($1.lastPlayedAt ?? .distantPast) }
        }
    }
}

enum LibraryFilter: String, CaseIterable, Identifiable {
    case all = "All", unwatched = "Unwatched", inProgress = "In Progress", watched = "Watched", hidden = "Hidden", watchlist = "Watchlist"
    var id: String { rawValue }

    func matches(_ movie: Movie) -> Bool {
        switch self {
        case .all: return !movie.isHidden
        case .unwatched: return !movie.isHidden && !movie.isWatched
        case .inProgress: return !movie.isHidden && movie.isInProgress
        case .watched: return !movie.isHidden && movie.isWatched
        case .hidden: return movie.isHidden
        case .watchlist: return !movie.isHidden && movie.isWatchlisted
        }
    }
}

enum LibraryQuery {
    static func visible(_ movies: [Movie], sort: LibrarySort, filter: LibraryFilter,
                        genres: Set<String> = [], sourceIDs: Set<UUID> = [], decades: Set<Int> = []) -> [Movie] {
        sort.sorted(movies.filter { movie in
            !movie.isMissing && filter.matches(movie)
                && (genres.isEmpty || !genres.isDisjoint(with: movie.genres))
                && (sourceIDs.isEmpty || movie.source.map { sourceIDs.contains($0.id) } == true)
                && (decades.isEmpty || movie.year.map { decades.contains($0 / 10 * 10) } == true)
        })
    }

    static func watchlist(_ movies: [Movie]) -> [Movie] {
        visible(movies, sort: .title, filter: .watchlist)
    }

}

@MainActor
final class LibraryPreferences: ObservableObject {
    private let defaults: UserDefaults
    @Published var sort: LibrarySort { didSet { defaults.set(sort.rawValue, forKey: "librarySort") } }
    @Published var filter: LibraryFilter { didSet { defaults.set(filter.rawValue, forKey: "libraryFilter") } }
    @Published var genres: Set<String> { didSet { defaults.set(genres.sorted(), forKey: "libraryGenres") } }
    @Published var sourceIDs: Set<UUID> { didSet { defaults.set(sourceIDs.map(\.uuidString).sorted(), forKey: "librarySourceIDs") } }
    @Published var decades: Set<Int> { didSet { defaults.set(decades.sorted(), forKey: "libraryDecades") } }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        sort = defaults.string(forKey: "librarySort").flatMap(LibrarySort.init(rawValue:)) ?? .title
        filter = defaults.string(forKey: "libraryFilter").flatMap(LibraryFilter.init(rawValue:)) ?? .all
        // An explicitly saved empty collection means All, even if an older single value exists.
        let savedGenres = defaults.stringArray(forKey: "libraryGenres") ?? [defaults.string(forKey: "libraryGenre") ?? ""]
        genres = Set(savedGenres.filter { !$0.isEmpty })
        let savedSources = defaults.stringArray(forKey: "librarySourceIDs") ?? [defaults.string(forKey: "librarySourceID") ?? ""]
        sourceIDs = Set(savedSources.compactMap(UUID.init(uuidString:)))
        let savedDecades = defaults.array(forKey: "libraryDecades") as? [Int] ?? [defaults.integer(forKey: "libraryDecade")]
        decades = Set(savedDecades.filter { $0 > 0 && $0 % 10 == 0 })
    }

    func sourcesChanged(from previousIDs: [UUID], to sources: [MediaSource]) {
        let removed = Set(previousIDs).subtracting(sources.map(\.id))
        if !sourceIDs.isDisjoint(with: removed) {
            sourceIDs.subtract(removed)
        }
    }

    func clearFilters() {
        filter = .all
        genres.removeAll()
        sourceIDs.removeAll()
        decades.removeAll()
    }
}

enum LibrarySearch {
    static func matches(_ movie: Movie, text: String, normalized: String) -> Bool {
        !movie.isMissing && !movie.isHidden &&
            ((!normalized.isEmpty && TitleMatcher.normalize(movie.title).contains(normalized))
             || movie.genres.contains { $0.localizedCaseInsensitiveContains(text) }
             || movie.fileName.localizedCaseInsensitiveContains(text))
    }
}

struct LibraryView: View {
    private enum Control: String, Hashable { case sort = "Sort", show = "Show", genre = "Genre", source = "Source", decade = "Decade", section = "Section" }
    @State private var kind: MediaKind = .movie
    @State private var control = Control.sort
    @State private var presentingChoices = false
    @FocusState private var focusedControl: Control?
    @Query private var movies: [Movie]
    @Query(sort: \MediaSource.name) private var sources: [MediaSource]
    @StateObject private var preferences = LibraryPreferences()

    private var filterCandidates: [Movie] {
        movies.filter { !$0.isMissing && preferences.filter.matches($0) && $0.mediaKind == kind }
    }

    private var genres: [String] {
        var values = Set(filterCandidates.flatMap(\.genres).filter { !$0.isEmpty })
        values.formUnion(preferences.genres)
        return values.sorted()
    }

    private var decades: [Int] {
        var values = Set(filterCandidates.compactMap(\.year)
            .filter { $0 > 0 }.map { $0 / 10 * 10 })
        values.formUnion(preferences.decades)
        return values.sorted(by: >)
    }

    private var selectedSourceIDs: Set<UUID> {
        preferences.sourceIDs.intersection(sources.map(\.id))
    }

    private var visible: [Movie] {
        LibraryQuery.visible(movies.filter { $0.mediaKind == kind }, sort: preferences.sort, filter: preferences.filter,
                             genres: preferences.genres, sourceIDs: selectedSourceIDs, decades: preferences.decades)
    }

    var body: some View {
        let results = visible
        ScrollView {
            VStack(alignment: .leading, spacing: 32) {
                HStack(alignment: .bottom) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("YOUR COLLECTION")
                            .font(.system(size: 18, weight: .semibold))
                            .tracking(3)
                            .foregroundStyle(CinemaTheme.accent)
                        Text(kind.label).font(.system(size: 62, weight: .bold))
                    }
                    Spacer()
                    Button("Clear filters") { preferences.clearFilters() }
                        .disabled(preferences.filter == .all && preferences.genres.isEmpty && selectedSourceIDs.isEmpty && preferences.decades.isEmpty)
                    Text(kind == .tvShow ? "\(results.count) episodes" : "\(results.count) titles")
                        .font(.callout)
                        .foregroundStyle(CinemaTheme.secondaryText)
                }

                HStack(spacing: 24) {
                    filterButton(.sort, value: preferences.sort.rawValue)
                    filterButton(.show, value: preferences.filter.rawValue)
                    filterButton(.genre, value: selectionLabel(preferences.genres.sorted(), all: "All Genres"))
                }

                HStack(spacing: 24) {
                    filterButton(.source, value: selectionLabel(sources.filter { preferences.sourceIDs.contains($0.id) }.map(\.name), all: "All Sources"))
                    filterButton(.decade, value: selectionLabel(preferences.decades.sorted().map { "\($0)s" }, all: "All Decades"))
                    filterButton(.section, value: kind.label)
                }

                if !movies.contains(where: { $0.mediaKind == kind }) {
                    ContentUnavailableView("No \(kind.label.lowercased()) yet", systemImage: kind == .music ? "music.note" : "film",
                                           description: Text("Add the parent media folder in Sources. Its subfolders are scanned automatically."))
                        .padding(.top, 100)
                } else if results.isEmpty {
                    ContentUnavailableView("No matching titles", systemImage: "line.3.horizontal.decrease.circle",
                                           description: Text("Try another filter, source, decade or genre."))
                        .padding(.top, 100)
                } else if kind == .tvShow {
                    TVShowBrowserView(episodes: results)
                } else {
                    PosterGrid(movies: results)
                }
            }
            .padding(.horizontal, 70)
            .padding(.bottom, 100)
        }
        .background(CinemaBackground())
        .sheet(isPresented: $presentingChoices, onDismiss: { focusedControl = control }) { choiceSheet }
        .onChange(of: kind) { _, _ in
            preferences.genres.removeAll(); preferences.decades.removeAll(); preferences.filter = .all
        }
        .onChange(of: sources.map(\.id)) { previousIDs, _ in
            preferences.sourcesChanged(from: previousIDs, to: sources)
        }
    }

    private func selectionLabel(_ values: [String], all: String) -> String {
        if values.count > 1 { return "\(values.count) selected" }
        return values.first ?? all
    }

    private func filterButton(_ item: Control, value: String) -> some View {
        Button {
            control = item
            presentingChoices = true
        } label: {
            HStack {
                VStack(alignment: .leading) {
                    Text(item.rawValue).font(.caption)
                    Text(value).font(.headline).lineLimit(1)
                }
                Spacer()
                Image(systemName: "chevron.down").accessibilityHidden(true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .focused($focusedControl, equals: item)
        .accessibilityLabel(item.rawValue)
        .accessibilityValue(value)
    }

    @ViewBuilder private var choiceSheet: some View {
        switch control {
        case .sort:
            LibraryChoiceSheet(title: "Sort", options: LibrarySort.allCases, selection: [preferences.sort], label: { $0.rawValue }) {
                if let value = $0.first { preferences.sort = value }
            }
        case .show:
            LibraryChoiceSheet(title: "Show", options: LibraryFilter.allCases, selection: [preferences.filter], label: { $0.rawValue }) {
                if let value = $0.first { preferences.filter = value }
            }
        case .section:
            LibraryChoiceSheet(title: "Section", options: MediaKind.allCases, selection: [kind], label: { $0.label }) {
                if let value = $0.first { kind = value }
            }
        case .genre:
            LibraryChoiceSheet(title: "Genre", options: genres, selection: preferences.genres, all: "All Genres", label: { $0 }) {
                preferences.genres = $0
            }
        case .source:
            let names = Dictionary(uniqueKeysWithValues: sources.map { ($0.id, $0.name) })
            LibraryChoiceSheet(title: "Source", options: sources.map(\.id), selection: selectedSourceIDs, all: "All Sources", label: { names[$0] ?? "" }) {
                preferences.sourceIDs = $0.intersection(sources.map(\.id))
            }
        case .decade:
            LibraryChoiceSheet(title: "Decade", options: decades, selection: preferences.decades, all: "All Decades", label: { "\($0)s" }) {
                preferences.decades = $0
            }
        }
    }
}

#if DEBUG
extension LibraryView {
    init(previewingGenres preferences: LibraryPreferences) {
        self.init()
        _preferences = StateObject(wrappedValue: preferences)
        _control = State(initialValue: .genre)
        _presentingChoices = State(initialValue: true)
    }
}
#endif

struct HomeView: View {
    @Query(sort: \Movie.dateAdded, order: .reverse) private var movies: [Movie]
    @EnvironmentObject private var scanner: LibraryScanner

    private var available: [Movie] { movies.filter { !$0.isHidden && !$0.isMissing } }
    private var continuing: [Movie] {
        available.filter { $0.isInProgress && $0.mediaKind != .music }
            .sorted { ($0.lastPlayedAt ?? .distantPast) > ($1.lastPlayedAt ?? .distantPast) }
    }
    private var featured: Movie? {
        let unwatched = available.filter { !$0.isInProgress && !$0.isWatched && $0.mediaKind != .music }
        return unwatched.first { $0.backdropPath != nil } ?? unwatched.first ?? available.first
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 48) {
                HStack(alignment: .bottom) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("YOUR PERSONAL CINEMA")
                            .font(.system(size: 18, weight: .semibold))
                            .tracking(3)
                            .foregroundStyle(CinemaTheme.accent)
                        Text("Welcome to Cine-Media")
                            .font(.system(size: 54, weight: .bold))
                    }
                    Spacer()
                    if let status = scanner.status {
                        Text(status)
                            .font(.caption)
                            .foregroundStyle(CinemaTheme.secondaryText)
                            .lineLimit(1)
                    }
                }

                if available.isEmpty {
                    ContentUnavailableView("Your movie collection starts here", systemImage: "play.rectangle.on.rectangle",
                                           description: Text("Add a NAS source, then add a TMDB key for artwork and details."))
                        .padding(.top, 100)
                } else {
                    ContinueWatchingRow(movies: Array(continuing.prefix(20)))
                    CollectionPosterRow(title: "Watchlist", movies: LibraryQuery.watchlist(movies))
                    if let featured {
                        FeaturedMovieCard(movie: featured)
                    }
                    ForEach(MediaKind.allCases, id: \.self) { kind in
                        let collection = available.filter { $0.mediaKind == kind }
                        if !collection.isEmpty {
                            if kind == .tvShow { TVShowRow(episodes: collection) }
                            else { PosterRow(title: kind.label, movies: Array(collection.prefix(20))) }
                        }
                    }
                    CollectionPosterRow(title: "Unwatched", movies: available.filter { !$0.isWatched })
                }
            }
            .padding(.horizontal, 70)
            .padding(.bottom, 100)
        }
        .background(CinemaBackground())
    }
}

struct SearchView: View {
    @Query private var movies: [Movie]
    @State private var text = ""

    private var results: [Movie] {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return [] }
        let needle = TitleMatcher.normalize(text)
        return movies.filter { LibrarySearch.matches($0, text: text, normalized: needle) }
        .sorted { $0.sortTitle < $1.sortTitle }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 32) {
                Text("Search").font(.system(size: 62, weight: .bold))
                if text.isEmpty {
                    ContentUnavailableView("Search your collection", systemImage: "magnifyingglass",
                                           description: Text("Find a movie by title, genre or file name."))
                        .padding(.top, 100)
                } else if results.isEmpty {
                    ContentUnavailableView("No results", systemImage: "magnifyingglass",
                                           description: Text("Try a different search."))
                        .padding(.top, 100)
                } else {
                    Text("\(results.count) results")
                        .foregroundStyle(CinemaTheme.secondaryText)
                    PosterGrid(movies: results)
                }
            }
            .padding(.horizontal, 70)
            .padding(.bottom, 100)
        }
        .background(CinemaBackground())
        .searchable(text: $text, prompt: "Titles, genres, files")
    }
}

struct PosterGrid: View {
    let movies: [Movie]
    @FocusState private var focusedMovie: UUID?
    private let columns = [GridItem(.adaptive(minimum: CinemaTheme.posterWidth, maximum: CinemaTheme.posterWidth), spacing: 48)]

    var body: some View {
        LazyVGrid(columns: columns, spacing: 55) {
            ForEach(movies) { movie in
                NavigationLink(value: movie) {
                    PosterView(movie: movie, isFocused: focusedMovie == movie.id)
                }
                .buttonStyle(.plain)
                .focused($focusedMovie, equals: movie.id)
            }
        }
        .padding(.vertical, 24)
    }
}

struct PosterRow: View {
    let title: String
    let movies: [Movie]
    @FocusState private var focusedMovie: UUID?

    var body: some View {
        if !movies.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.system(size: 32, weight: .semibold))
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(alignment: .top, spacing: 42) {
                        ForEach(movies) { movie in
                            NavigationLink(value: movie) {
                                PosterView(movie: movie, isFocused: focusedMovie == movie.id)
                            }
                            .buttonStyle(.plain)
                            .focused($focusedMovie, equals: movie.id)
                        }
                    }
                    .padding(.vertical, 24)
                }
                .scrollClipDisabled()
            }
        }
    }
}

struct FeaturedMovieCard: View {
    let movie: Movie

    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .leading) {
                MovieBackdrop(movie: movie)
                    .frame(width: geometry.size.width, height: geometry.size.height)
                LinearGradient(
                    stops: [
                        .init(color: .black.opacity(0.95), location: 0),
                        .init(color: .black.opacity(0.75), location: 0.35),
                        .init(color: .black.opacity(0.10), location: 1),
                    ],
                    startPoint: .leading,
                    endPoint: .trailing
                )
                LinearGradient(colors: [.clear, .black.opacity(0.5)],
                               startPoint: .top, endPoint: .bottom)

                VStack(alignment: .leading, spacing: 18) {
                    Text("FEATURED MOVIE")
                        .font(.system(size: 18, weight: .semibold))
                        .tracking(3)
                        .foregroundStyle(CinemaTheme.accent)
                    Text(movie.title)
                        .font(.system(size: 52, weight: .bold))
                        .lineLimit(2)
                    Text(MoviePresentation.metadata(for: movie))
                        .font(.callout)
                        .foregroundStyle(CinemaTheme.secondaryText)
                    if let overview = movie.overview, !overview.isEmpty {
                        Text(overview)
                            .font(.callout)
                            .foregroundStyle(.white.opacity(0.85))
                            .lineLimit(2)
                    }
                    NavigationLink(value: movie) {
                        Label("View Movie", systemImage: "play.fill")
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.white)
                    .foregroundStyle(.black)
                    .padding(.top, 8)
                }
                .frame(maxWidth: 720, alignment: .leading)
                .padding(48)
            }
            .clipShape(RoundedRectangle(cornerRadius: 26, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 26, style: .continuous)
                    .strokeBorder(.white.opacity(0.10), lineWidth: 1)
            }
        }
        .frame(height: 500)
    }
}

struct ContinueWatchingRow: View {
    let movies: [Movie]
    @FocusState private var focusedMovie: UUID?

    var body: some View {
        if !movies.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                Text("Continue Watching").font(.system(size: 32, weight: .semibold))
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(alignment: .top, spacing: 36) {
                        ForEach(movies) { movie in
                            NavigationLink(value: movie) {
                                ContinueWatchingCard(movie: movie, isFocused: focusedMovie == movie.id)
                            }
                            .buttonStyle(.plain)
                            .focused($focusedMovie, equals: movie.id)
                        }
                    }
                    .padding(.vertical, 24)
                }
                .scrollClipDisabled()
            }
        }
    }
}

struct ContinueWatchingCard: View {
    let movie: Movie
    let isFocused: Bool
    private let width: CGFloat = 400

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            MovieBackdrop(movie: movie, usePosterFallback: true)
                .frame(width: width, height: 225)
                .overlay(alignment: .bottom) {
                    LinearGradient(colors: [.clear, .black.opacity(0.75)],
                                   startPoint: .top, endPoint: .bottom)
                        .frame(height: 90)
                }
                .overlay(alignment: .bottom) {
                    ProgressView(value: movie.progress)
                        .tint(CinemaTheme.accent)
                        .padding(.horizontal, 14)
                        .padding(.bottom, 12)
                }
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .strokeBorder(.white.opacity(isFocused ? 0.9 : 0.10), lineWidth: isFocused ? 3 : 1)
                }
            Text(movie.title)
                .font(.system(size: 24, weight: .semibold))
                .foregroundStyle(.white)
                .lineLimit(1)
            Text(movie.displayYear.isEmpty ? "Continue watching" : movie.displayYear)
                .font(.system(size: 20))
                .foregroundStyle(CinemaTheme.secondaryText)
        }
        .frame(width: width, alignment: .leading)
        .scaleEffect(isFocused ? 1.04 : 1)
        .animation(.easeOut(duration: 0.18), value: isFocused)
    }
}

struct PosterView: View {
    let movie: Movie
    var isFocused = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ZStack {
                ArtworkFallback(title: movie.title)
                VStack(spacing: 18) {
                    Image(systemName: "film")
                        .font(.system(size: 55, weight: .ultraLight))
                        .foregroundStyle(.white.opacity(0.5))
                    Text(movie.title)
                        .font(.headline)
                        .multilineTextAlignment(.center)
                        .lineLimit(3)
                        .padding(.horizontal, 18)
                }
                AsyncImage(url: TMDB.imageURL(movie.posterPath, size: "w342")) { image in
                    image.resizable().aspectRatio(contentMode: .fill)
                } placeholder: {
                    Color.clear
                }
            }
            .frame(width: CinemaTheme.posterWidth, height: CinemaTheme.posterHeight)
            .clipped()
            .overlay(alignment: .bottom) {
                if movie.isInProgress {
                    ProgressView(value: movie.progress)
                        .tint(CinemaTheme.accent)
                        .padding(10)
                        .background(.black.opacity(0.45))
                }
            }
            .overlay(alignment: .topTrailing) {
                if movie.isWatched {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 26))
                        .foregroundStyle(.white)
                        .padding(10)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .strokeBorder(.white.opacity(isFocused ? 0.9 : 0.10), lineWidth: isFocused ? 3 : 1)
            }
            .shadow(color: .black.opacity(isFocused ? 0.6 : 0.25), radius: isFocused ? 20 : 8, y: 10)

            Text(movie.title)
                .font(.system(size: 23, weight: .semibold))
                .foregroundStyle(.white)
                .lineLimit(1)
            Text(movie.displayYear.isEmpty ? "Movie" : movie.displayYear)
                .font(.system(size: 19))
                .foregroundStyle(CinemaTheme.secondaryText)
        }
        .frame(width: CinemaTheme.posterWidth, alignment: .leading)
        .scaleEffect(isFocused ? 1.04 : 1)
        .animation(.easeOut(duration: 0.18), value: isFocused)
    }
}
