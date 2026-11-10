import SwiftData
import SwiftUI

@MainActor
struct NASSetupView: View {
    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var scanner: LibraryScanner
    @StateObject private var discovery = NASDiscovery()
    @State private var smb = SMBService(timeout: 10)
    private enum Step: String { case server = "Device", signIn = "Sign in", shares = "Shared folder", folders = "Media folder" }
    private enum Focus: Hashable { case address, username, connect, share(String), folder(String), save }
    @FocusState private var focus: Focus?
    @State private var step: Step = .server
    @State private var name = ""
    @State private var host = ""
    @State private var port = ""
    @State private var username = ""
    @State private var password = ""
    @State private var share = ""
    @State private var path = ""
    @State private var ignoredFolders: [String] = []
    @State private var ignoredFiles: [String] = []
    @State private var exclusionsNotice: String?
    @State private var shares: [String] = []
    @State private var folders: [String] = []
    @State private var folderReady = false
    @State private var manualAddress = false
    @State private var advanced = false
    @State private var guestAccess = true
    @State private var showManualShare = false
    @State private var errorText: String?
    @State private var busy = false
    @State private var progressText = ""
    @State private var requestID = UUID()
    @State private var request: Task<Void, Never>?
    #if DEBUG
    @State private var previewing = false
    #endif

    var body: some View {
        NavigationStack {
            List {
                Section {
                    VStack(alignment: .leading, spacing: 10) {
                        if step == .server {
                            Text("Add your media library").font(.title2.bold())
                            Text("Choose a NAS on your network or enter its address.").foregroundStyle(.secondary)
                        } else {
                            Label(name.isEmpty ? host : name, systemImage: "externaldrive.connected.to.line.below").font(.headline)
                            Text(step == .folders ? "\(host) / \(share)\(path.isEmpty ? "" : " / \(path)")" : host)
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    if busy { ProgressView(progressText) }
                    if let errorText { Label(errorText, systemImage: "exclamationmark.circle").foregroundStyle(.orange) }
                }
                switch step {
                case .server: deviceSections
                case .signIn: signInSections
                case .shares: shareSections
                case .folders: folderSections
                }
                Section {
                    Button(busy ? "Cancel connection" : (step == .server ? "Cancel" : "Back")) { goBack() }
                }
            }
            .navigationTitle(step == .server ? "Add NAS" : step.rawValue)
            .onExitCommand { goBack() }
        }
        .tint(CinemaTheme.accent)
        .preferredColorScheme(.dark)
        .onAppear {
            #if DEBUG
            if previewing { focus = .save; return }
            #endif
            if step == .server { discovery.start() }
        }
        .onDisappear { discovery.stop(); cancelRequest(); password = "" }
    }

    @ViewBuilder private var deviceSections: some View {
        Section("Devices on your network") {
            if discovery.searching { ProgressView("Looking for devices…") }
            ForEach(discovery.devices) { device in
                Button {
                    invalidate(clearSelection: true)
                    name = device.name; host = device.host; port = device.port == 445 ? "" : String(device.port)
                    username = ""; password = ""; guestAccess = true
                    connect()
                } label: {
                    HStack {
                        Image(systemName: "externaldrive.connected.to.line.below")
                        VStack(alignment: .leading) {
                            Text(device.name)
                            Text(device.host).font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Image(systemName: "chevron.right").font(.caption)
                    }
                }
            }
            if let error = discovery.error { Text(error).font(.caption).foregroundStyle(.secondary) }
            if !discovery.searching && discovery.devices.isEmpty && discovery.error == nil {
                Text("No devices found yet. Your NAS may still be available by address.").foregroundStyle(.secondary)
            }
            Button("Enter an address") { manualAddress = true; focus = .address }
            Button("Search again") { discovery.start() }.disabled(discovery.searching)
        }
        if manualAddress {
            Section("Device") {
                TextField("NAS name or IP address", text: Binding(get: { host }, set: {
                    host = $0; name = ""; username = ""; password = ""; invalidate(clearSelection: true)
                })).focused($focus, equals: .address)
                Toggle("Advanced", isOn: $advanced)
                if advanced {
                    TextField("Port (default 445)", text: Binding(get: { port }, set: {
                        port = $0; invalidate(clearSelection: true)
                    })).keyboardType(.numberPad)
                }
                Button("Continue") { guestAccess = true; connect() }.buttonStyle(.borderedProminent)
                Button("Sign in with a NAS account") {
                    guard configuration(requireShare: false) != nil else { return }
                    discovery.stop(); guestAccess = false; step = .signIn; focus = .username
                }
            }
        }
    }

    @ViewBuilder private var signInSections: some View {
        if !busy {
            Section {
                Toggle("Guest access", isOn: $guestAccess)
                if !guestAccess {
                    TextField("Username", text: $username).focused($focus, equals: .username)
                    SecureField("Password", text: $password)
                }
                Button("Connect") { connect() }.buttonStyle(.borderedProminent).focused($focus, equals: .connect)
                Button("Enter a shared folder") {
                    guard accountIsValid, configuration(requireShare: false) != nil else { return }
                    errorText = nil; showManualShare = true; step = .shares
                }
                Button("Edit NAS address") { step = .server; manualAddress = true; errorText = nil; discovery.start(); focus = .address }
            } header: { Text("Sign in") } footer: {
                Text(guestAccess ? "For shared folders that do not require an account." : "Use the account you normally use to open files on this NAS.")
            }
        }
    }

    @ViewBuilder private var shareSections: some View {
        Section("Shared folder") {
            ForEach(shares, id: \.self) { item in
                Button { browse(share: item, path: "") } label: { Label(item, systemImage: "folder") }
                    .focused($focus, equals: .share(item))
            }
            if shares.isEmpty && !busy {
                Text("This NAS did not list any shared folders. Enter a known share or try another account.").foregroundStyle(.secondary)
            }
        }.disabled(busy)
        Section {
            Toggle("Enter a shared folder manually", isOn: $showManualShare)
            if showManualShare {
                TextField("Shared folder name", text: Binding(get: { share }, set: {
                    if share != $0 { clearExclusions() }; share = $0; folderReady = false
                }))
                TextField("Folder inside share (optional)", text: Binding(get: { path }, set: {
                    if path != $0 { clearExclusions() }; path = $0; folderReady = false
                }))
                Button("Open folder") { browse(share: share, path: path) }
            }
            Button("Change sign-in") { errorText = nil; step = .signIn; guestAccess = false; focus = .username }
        }.disabled(busy)
    }

    @ViewBuilder private var folderSections: some View {
        Section {
            Text("All media in this folder and its subfolders will be added.").font(.caption).foregroundStyle(.secondary)
            TextField("Library name (optional)", text: $name).disabled(busy)
            Button("Use this folder & scan") { save() }.buttonStyle(.borderedProminent)
                .focused($focus, equals: .save).disabled(busy || !folderReady || scanner.isScanning)
            if scanner.isScanning { Text("A library scan is running. You can add this folder when it finishes.").foregroundStyle(.secondary) }
        }
        Section("Folders") {
            if !path.isEmpty {
                Button { browse(share: share, path: parentPath) } label: { Label("Up one folder", systemImage: "arrow.up") }
            }
            ForEach(folders, id: \.self) { folder in
                Button { browse(share: share, path: [path, folder].filter { !$0.isEmpty }.joined(separator: "/")) } label: {
                    HStack { Label(folder, systemImage: "folder"); Spacer(); Image(systemName: "chevron.right").font(.caption) }
                }.focused($focus, equals: .folder(folder))
            }
            if folderReady && folders.isEmpty && errorText == nil {
                Text("No subfolders here. You can use this folder.").foregroundStyle(.secondary)
            }
            Button(errorText == nil ? "Refresh folder" : "Try again") { browse(share: share, path: path) }
            Button("Change shared folder") { errorText = nil; step = .shares; folderReady = false }
            if errorText != nil {
                Button("Change sign-in") { step = .signIn; guestAccess = false; errorText = nil; focus = .username }
            }
        }.disabled(busy)
        if let exclusionsNotice { Text(exclusionsNotice).font(.caption).foregroundStyle(.secondary) }
        SourceExclusionsEditor(folders: $ignoredFolders, files: $ignoredFiles,
            configuration: SourceConfig(id: requestID, host: host, port: Int(port), share: share, rootPath: path,
                username: guestAccess ? "" : username, password: guestAccess ? "" : password,
                ignoredFolders: ignoredFolders, ignoredFiles: ignoredFiles))
            .disabled(busy || !folderReady)
    }

    private var parentPath: String { path.split(separator: "/").dropLast().joined(separator: "/") }

    private var accountIsValid: Bool {
        if !guestAccess && username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            errorText = "Enter your NAS username, or turn on Guest access."; focus = .username; return false
        }
        return true
    }

    private func cancelRequest() {
        requestID = UUID(); request?.cancel(); request = nil; busy = false
    }

    private func invalidate(clearSelection: Bool = false) {
        cancelRequest(); shares = []; folders = []; folderReady = false; errorText = nil
        if clearSelection { clearExclusions(); share = ""; path = "" }
    }

    private func clearExclusions() {
        if !ignoredFolders.isEmpty || !ignoredFiles.isEmpty {
            ignoredFolders = []; ignoredFiles = []
            exclusionsNotice = "The media folder changed. Choose ignored paths again for this folder."
        }
    }

    private func goBack() {
        let wasBusy = busy
        cancelRequest(); errorText = nil
        if wasBusy { return }
        switch step {
        case .folders:
            if !path.isEmpty { browse(share: share, path: parentPath) }
            else { step = .shares; folders = []; folderReady = false }
        case .shares: step = .signIn
        case .signIn: step = .server; discovery.start()
        case .server: dismiss()
        }
    }

    private func configuration(requireShare: Bool) -> SourceConfig? {
        do {
            return try NASConnectionForm.configuration(id: UUID(), host: host, port: port, share: share,
                rootPath: path, username: guestAccess ? "" : username, password: guestAccess ? "" : password,
                requireShare: requireShare, ignoredFolders: ignoredFolders, ignoredFiles: ignoredFiles)
        } catch { errorText = error.localizedDescription; return nil }
    }

    private func run(_ config: SourceConfig, progress: String, operation: @escaping @MainActor (UUID) async throws -> Void) {
        cancelRequest(); discovery.stop()
        let token = requestID
        let service = smb
        busy = true; errorText = nil; progressText = progress
        request = Task { @MainActor in
            do { try await operation(token) }
            catch is CancellationError { /* A replacement request owns the screen. */ }
            catch {
                if requestID == token && !Task.isCancelled {
                    errorText = error.localizedDescription
                    if step == .signIn && SMBService.isAuthenticationFailure(error) { guestAccess = false }
                }
            }
            await service.reset(sourceID: config.id)
            if requestID == token {
                busy = false; request = nil
                await Task.yield()
                guard requestID == token else { return }
                switch step {
                case .signIn: focus = guestAccess ? .connect : .username
                case .shares: focus = shares.first.map { .share($0) }
                case .folders: focus = folderReady ? .save : nil
                case .server: break
                }
            }
        }
    }

    private func connect() {
        guard accountIsValid else { return }
        guard let config = configuration(requireShare: false) else { return }
        host = config.host; port = config.port.map(String.init) ?? ""
        shares = []; folders = []; folderReady = false; step = .signIn
        run(config, progress: guestAccess ? "Checking guest access…" : "Signing in…") { token in
            let result = try await smb.listShares(config)
            try Task.checkCancellation()
            guard requestID == token else { return }
            clearExclusions(); shares = result; share = ""; path = ""; showManualShare = result.isEmpty; step = .shares
        }
    }

    private func browse(share selectedShare: String, path selectedPath: String) {
        if selectedShare != share || selectedPath != path { clearExclusions() }
        share = selectedShare; path = selectedPath
        guard let config = configuration(requireShare: true) else { return }
        host = config.host; port = config.port.map(String.init) ?? ""; share = config.share; path = config.rootPath
        folders = []; folderReady = false; step = .folders
        run(config, progress: "Reading folder…") { token in
            let result = try await smb.listFolders(config)
            try Task.checkCancellation()
            guard requestID == token else { return }
            folders = result; path = config.rootPath; folderReady = true
        }
    }

    private func save() {
        guard folderReady, !busy, !scanner.isScanning, let config = configuration(requireShare: true) else { return }
        let savedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        run(config, progress: "Adding your media folder…") { token in
            _ = try await smb.listFolders(config)
            try Task.checkCancellation()
            guard requestID == token else { return }
            let source = MediaSource(name: savedName.isEmpty ? config.host : savedName,
                host: config.host, port: config.port, share: config.share, rootPath: config.rootPath, username: config.username)
            try source.applyIgnoredPaths(folders: config.ignoredFolders, files: config.ignoredFiles)
            try source.setPassword(config.password)
            context.insert(source)
            do { try context.save() }
            catch {
                let saveError = error
                context.delete(source)
                do { try Keychain.delete("source-\(source.id.uuidString)") }
                catch { throw NSError(domain: "NASSetup", code: 1, userInfo: [NSLocalizedDescriptionKey:
                    "The source could not be saved, and its temporary saved password could not be removed."]) }
                throw saveError
            }
            Task { @MainActor in await scanner.scan(source, context: context) }
            dismiss()
        }
    }

    #if DEBUG
    static var folderPreview: NASSetupView {
        var view = NASSetupView()
        view._previewing = State(initialValue: true)
        view._step = State(initialValue: .folders)
        view._name = State(initialValue: "Living Room NAS")
        view._host = State(initialValue: "media.local")
        view._share = State(initialValue: "Media")
        view._folders = State(initialValue: ["Documentaries", "Movies", "Music", "TV Shows"])
        view._folderReady = State(initialValue: true)
        return view
    }
    #endif
}
