import CineVaultCore
import SwiftData
import SwiftUI
#if CINEVAULT_COMMERCIAL
import CineVaultCommercial
#endif

struct SourcesView: View {
    @Environment(\.modelContext) private var context
    @EnvironmentObject private var scanner: LibraryScanner
    @Query(sort: \MediaSource.name) private var sources: [MediaSource]
    @State private var editing: MediaSource?
    @State private var adding = false

    var body: some View {
        List {
            Section {
                Button { adding = true } label: { Label("Add NAS", systemImage: "plus") }.buttonStyle(.borderedProminent)
                if sources.isEmpty {
                    Text("Connect a NAS, choose your media folder, and Cine-Media will add its movies, shows and music.")
                        .foregroundStyle(.secondary)
                }
                if let status = scanner.status {
                    if scanner.isScanning { ProgressView(status) }
                    else { Text(status).font(.caption).foregroundStyle(.secondary) }
                }
            }
            ForEach(sources) { source in
                Section {
                    VStack(alignment: .leading, spacing: 8) {
                        Label(source.name, systemImage: "externaldrive.connected.to.line.below").font(.headline)
                        Text(location(of: source)).font(.caption).foregroundStyle(.secondary)
                        if let lastScan = source.lastScanDate {
                            Text("\(source.movies.filter { !$0.isMissing }.count) items · Scanned \(lastScan.formatted(date: .abbreviated, time: .shortened))")
                                .font(.caption2).foregroundStyle(.secondary)
                        } else {
                            Text("Ready for its first scan").font(.caption2).foregroundStyle(.secondary)
                        }
                    }
                    HStack {
                        Button { Task { await scanner.scan(source, context: context) } } label: { Label("Scan", systemImage: "arrow.clockwise") }
                            .disabled(scanner.isScanning)
                        Button { editing = source } label: { Label("Edit", systemImage: "slider.horizontal.3") }
                    }
                }
            }
            if !sources.isEmpty {
                Section {
                    Button("Scan all sources") { Task { await scanner.scanAll(context: context) } }.disabled(scanner.isScanning)
                }
            }
        }
        .navigationTitle("Sources")
        .sheet(isPresented: $adding) { NASSetupView() }
        .sheet(item: $editing) { source in NavigationStack { SourceEditorView(source: source) } }
    }

    private func location(of source: MediaSource) -> String {
        let address = (try? NASAddress.parse(source.host, port: source.port))?.authority ?? "NAS"
        return ([address, source.share, source.rootPath].filter { !$0.isEmpty }).joined(separator: " / ")
    }
}

@MainActor
struct SourceEditorView: View {
    let source: MediaSource?
    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var scanner: LibraryScanner
    @State private var name = ""
    @State private var host = ""
    @State private var port = ""
    @State private var share = ""
    @State private var rootPath = ""
    @State private var ignoredFolders: [String] = []
    @State private var ignoredFiles: [String] = []
    @State private var exclusionsNotice: String?
    @State private var loaded = false
    @State private var username = ""
    @State private var password = ""
    @State private var guestAccess = true
    @State private var advanced = false
    @State private var manualPath = false
    @State private var shares: [String] = []
    @State private var message: String?
    @State private var busy = false
    @State private var progress = ""
    @State private var draftID = UUID()
    @State private var credentialReadFailed = false
    @State private var deletedSourceID: UUID?
    @State private var confirmingDelete = false
    @State private var requestID = UUID()
    @State private var request: Task<Void, Never>?
    @State private var smb = SMBService(timeout: 10)

    private func configuration(requireShare: Bool = true, connectionID: UUID? = nil) throws -> SourceConfig {
        try NASConnectionForm.configuration(id: connectionID ?? source?.id ?? draftID, host: host, port: port,
            share: share, rootPath: rootPath, username: guestAccess ? "" : username,
            password: guestAccess ? "" : password, requireShare: requireShare,
            ignoredFolders: ignoredFolders, ignoredFiles: ignoredFiles)
    }

    private var fieldsDisabled: Bool { busy || scanner.isScanning || deletedSourceID != nil }

    var body: some View {
        Form {
            if busy || message != nil || scanner.isScanning {
                Section {
                    if busy { ProgressView(progress) }
                    if let message { Text(message).foregroundStyle(.secondary) }
                    if scanner.isScanning { Text("A library scan is running. Editing will be available when it finishes.").foregroundStyle(.secondary) }
                }
            }
            Section("Device") {
                TextField("Library name", text: $name)
                TextField("NAS name or IP address", text: Binding(get: { host }, set: { value in
                    if host != value { clearExclusionsForChangedRoot(); shares = []; message = nil }; host = value
                }))
                Toggle("Advanced", isOn: $advanced)
                if advanced {
                    TextField("Port (default 445)", text: Binding(get: { port }, set: { value in
                        if port != value { clearExclusionsForChangedRoot(); shares = []; message = nil }; port = value
                    })).keyboardType(.numberPad)
                }
            }.disabled(fieldsDisabled)
            Section("Sign in") {
                Toggle("Guest access", isOn: Binding(get: { guestAccess }, set: {
                    guestAccess = $0; shares = []; message = nil
                }))
                if !guestAccess {
                    TextField("Username", text: Binding(get: { username }, set: { username = $0; shares = []; message = nil }))
                    SecureField("Password", text: Binding(get: { password }, set: { password = $0; shares = []; message = nil }))
                }
                if credentialReadFailed {
                    Button("Retry loading saved password") { loadPassword() }
                }
            }.disabled(fieldsDisabled)
            Section("Shared folder") {
                TextField("Shared folder name", text: Binding(get: { share }, set: setShare))
                if !shares.isEmpty {
                    Picker("Shared folder", selection: Binding(get: { share }, set: setShare)) {
                        if !shares.contains(share) { Text(share.isEmpty ? "Choose a shared folder" : share).tag(share) }
                        ForEach(shares, id: \.self) { Text($0).tag($0) }
                    }
                }
                Button("Find shared folders") { listShares() }.disabled(credentialReadFailed && !guestAccess)
            }.disabled(fieldsDisabled)
            Section("Media folder") {
                Label(rootPath.isEmpty ? "Entire shared folder" : rootPath, systemImage: "folder")
                if let config = try? configuration() {
                    NavigationLink("Choose a media folder") {
                        NASPathPicker(configuration: config, mode: .scanRoot) { path, _ in setRootPath(path) }
                    }.disabled(credentialReadFailed && !guestAccess)
                }
                Toggle("Enter a folder path manually", isOn: $manualPath)
                if manualPath {
                    TextField("Folder inside share (empty for entire share)", text: Binding(get: { rootPath }, set: setRootPath))
                }
                Text("Includes all media in this folder and its subfolders.").font(.caption).foregroundStyle(.secondary)
                if let exclusionsNotice { Text(exclusionsNotice).font(.caption).foregroundStyle(.secondary) }
            }.disabled(fieldsDisabled)
            if let config = try? configuration() {
                SourceExclusionsEditor(folders: $ignoredFolders, files: $ignoredFiles, configuration: config)
                    .disabled(fieldsDisabled || (credentialReadFailed && !guestAccess))
            }
            Section {
                if let deletedSourceID {
                    Button("Retry removing stored password") { removePassword(for: deletedSourceID) }
                } else {
                    Button("Save & scan") { save() }.buttonStyle(.borderedProminent)
                        .disabled(fieldsDisabled || (credentialReadFailed && !guestAccess))
                    Button("Test connection") { testConnection() }.disabled(fieldsDisabled || (credentialReadFailed && !guestAccess))
                    if source != nil {
                        Button("Remove source", role: .destructive) { confirmingDelete = true }.disabled(fieldsDisabled)
                    }
                }
                Button(busy ? "Cancel connection" : "Back") {
                    if busy { cancelRequest() } else { dismiss() }
                }
            }
        }
        .navigationTitle(source == nil ? "Add NAS" : "Edit NAS")
        .confirmationDialog("Remove this source from Cine-Media? Your NAS files will be kept.", isPresented: $confirmingDelete, titleVisibility: .visible) {
            Button("Remove source", role: .destructive) { delete() }
            Button("Cancel", role: .cancel) { }
        }
        .onAppear { if !loaded { load(); loaded = true } }
        .onDisappear { cancelRequest() }
        .onExitCommand { if busy { cancelRequest() } else { dismiss() } }
    }

    private func clearExclusionsForChangedRoot() {
        if !ignoredFolders.isEmpty || !ignoredFiles.isEmpty {
            ignoredFolders = []; ignoredFiles = []
            exclusionsNotice = "The media folder changed. Choose ignored paths again for this folder."
        }
    }

    private func setRootPath(_ value: String) {
        if rootPath != value { clearExclusionsForChangedRoot(); message = nil }; rootPath = value
    }

    private func setShare(_ value: String) {
        if share != value { clearExclusionsForChangedRoot(); rootPath = ""; message = nil }; share = value
    }

    private func load() {
        guard let source else { return }
        name = source.name; host = source.host; port = source.port == 445 ? "" : (source.port.map(String.init) ?? "")
        advanced = source.port != nil && source.port != 445
        share = source.share; rootPath = source.rootPath
        ignoredFolders = source.ignoredFolders ?? []; ignoredFiles = source.ignoredFiles ?? []
        username = source.username; guestAccess = source.username.isEmpty
        loadPassword()
    }

    private func loadPassword() {
        guard let source else { return }
        do { password = try source.password(); credentialReadFailed = false; message = nil }
        catch { credentialReadFailed = true; message = "Could not read the saved password. Retry loading it before saving." }
    }

    private func checkedConfiguration(requireShare: Bool = true, connectionID: UUID? = nil) -> SourceConfig? {
        guard guestAccess || !username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            message = "Enter your NAS username, or turn on Guest access."; return nil
        }
        do { return try configuration(requireShare: requireShare, connectionID: connectionID) }
        catch { message = error.localizedDescription; return nil }
    }

    private func cancelRequest() {
        requestID = UUID(); request?.cancel(); request = nil; busy = false
    }

    private func run(_ config: SourceConfig, progress: String, work: @escaping @MainActor (UUID) async throws -> Void) {
        cancelRequest()
        let token = requestID
        let service = smb
        busy = true; message = nil; self.progress = progress
        request = Task { @MainActor in
            do { try await work(token) }
            catch is CancellationError { /* Closing the editor invalidates this operation. */ }
            catch { if requestID == token && !Task.isCancelled { message = error.localizedDescription } }
            await service.reset(sourceID: config.id)
            if requestID == token { busy = false; request = nil }
        }
    }

    private func listShares() {
        guard !fieldsDisabled, let c = checkedConfiguration(requireShare: false, connectionID: UUID()) else { return }
        run(c, progress: "Finding shared folders…") { token in
            let result = try await smb.listShares(c)
            try Task.checkCancellation()
            guard requestID == token else { return }
            shares = result
            message = result.isEmpty ? "No shared folders were listed. You can still enter a known shared folder name." : "Choose a shared folder below."
        }
    }

    private func testConnection() {
        guard !fieldsDisabled, let c = checkedConfiguration(connectionID: UUID()) else { return }
        run(c, progress: "Checking access to your media folder…") { token in
            try await smb.testConnection(c)
            try Task.checkCancellation()
            guard requestID == token else { return }
            message = "Connected. This media folder is available."
        }
    }

    private func save() {
        guard !fieldsDisabled, !(credentialReadFailed && !guestAccess), let c = checkedConfiguration() else { return }
        let target = source ?? MediaSource(name: "", host: "", share: "", rootPath: "", username: "")
        if source == nil { target.id = c.id }
        let oldPassword: String
        do { oldPassword = try target.password(); try target.setPassword(c.password) }
        catch { message = "Could not save the password: \(error.localizedDescription)"; return }
        let oldValues = (target.name, target.host, target.port, target.share, target.rootPath, target.username, target.ignoredFolders, target.ignoredFiles)
        let oldMissingFlags = target.movies.map { ($0, $0.isMissing) }
        let savedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        target.name = savedName.isEmpty ? c.host : savedName
        target.host = c.host; target.port = c.port; target.share = c.share; target.rootPath = c.rootPath; target.username = c.username
        if source == nil { context.insert(target) }
        do {
            try target.applyIgnoredPaths(folders: c.ignoredFolders, files: c.ignoredFiles)
            try context.save()
        } catch {
            if source == nil { context.delete(target) }
            else {
                (target.name, target.host, target.port, target.share, target.rootPath, target.username, target.ignoredFolders, target.ignoredFiles) = oldValues
                for (movie, missing) in oldMissingFlags { movie.isMissing = missing }
            }
            do { try target.setPassword(oldPassword); message = "Could not save the source: \(error.localizedDescription)" }
            catch let restoreError { message = "Could not save the source, and its password could not be restored: \(restoreError.localizedDescription)" }
            return
        }
        Task { await SMBService.shared.reset(sourceID: target.id); await scanner.scan(target, context: context) }
        dismiss()
    }

    private func delete() {
        guard let source, !fieldsDisabled else { return }
        let id = source.id
        context.delete(source)
        do {
            try context.save(); deletedSourceID = id
            Task { await SMBService.shared.reset(sourceID: id) }
            removePassword(for: id)
        } catch { message = "Could not remove the source: \(error.localizedDescription)" }
    }

    private func removePassword(for id: UUID) {
        do { try Keychain.delete("source-\(id.uuidString)"); dismiss() }
        catch { message = "Source removed, but its saved password could not be deleted: \(error.localizedDescription)" }
    }
}

struct SettingsView: View {
    @Environment(\.modelContext) private var context
    @EnvironmentObject private var playbackServices: PlaybackServices
    @State private var apiKey = ""
    @State private var message: String?
    @State private var credentialReadFailed = false

    var body: some View {
        Form {
            #if CINEVAULT_COMMERCIAL
            Section("Playback") {
                NavigationLink("Playback & Purchases") {
                    CommercialAccountView(services: playbackServices.commercial)
                }
            }
            #endif
            Section("Help") {
                NavigationLink("Media Guide & Help") {
                    MediaGuideView()
                }
            }
            Section {
                SecureField("TMDB API key or read access token", text: $apiKey)
                Button("Save Key & Fetch Missing Metadata") {
                    do {
                        try MetadataService.shared.setAPIKey(apiKey)
                        if apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                            message = "TMDB key removed."
                        } else {
                            message = "Fetching metadata…"
                            Task { await fetchMetadata(refreshAll: false) }
                        }
                    } catch {
                        message = "Could not save the TMDB key: \(error.localizedDescription)"
                    }
                }
                .disabled(credentialReadFailed)
                Button("Refresh All Metadata") {
                    message = "Refreshing metadata…"
                    Task { await fetchMetadata(refreshAll: true) }
                }
            } header: {
                Text("Metadata (themoviedb.org)")
            } footer: {
                Text(message ?? "Get a free key at themoviedb.org → Settings → API. Posters and details are fetched automatically after each scan.")
            }
            Section("About") {
                Text("Cine-Media plays MP4/M4V/MOV directly from your NAS over SMB. Other video containers require conversion to a supported container and compatible codecs.")
                    .font(.caption)
                Text("This product uses the TMDB API but is not endorsed or certified by TMDB.").font(.caption)
            }
        }
        .navigationTitle("Settings")
        .onAppear {
            do {
                apiKey = try MetadataService.shared.apiKey
                credentialReadFailed = false
            } catch {
                credentialReadFailed = true
                message = "Could not read the TMDB key: \(error.localizedDescription)"
            }
        }
    }

    private func fetchMetadata(refreshAll: Bool) async {
        do {
            guard try MetadataService.shared.hasKey else {
                message = "Add a TMDB key in Settings first."
                return
            }
            var count: Int?
            if refreshAll {
                let movies = try context.fetch(FetchDescriptor<Movie>())
                movies.forEach { $0.metadataFetched = false }
                try context.save()
                count = movies.count
            }
            let result = try await MetadataService.shared.fetchMissing(in: context)
            message = result?.failureMessage ?? count.map { "Refreshed \($0) movies." } ?? "Metadata update complete."
        } catch {
            message = "Could not update metadata: \(error.localizedDescription)"
        }
    }
}
