#if DEBUG
import SwiftData
import SwiftUI

/// Synthetic local pictures exercise the real image decoder and photo views without a NAS or uploads.
@MainActor
struct PhotoDesignPreview: View {
    static var isEnabled: Bool {
        ProcessInfo.processInfo.arguments.contains("--ui-preview-photos") || ProcessInfo.processInfo.arguments.contains("--ui-preview-photo")
    }
    private let container: ModelContainer
    private let loader: PhotoImageLoader
    private let featured: Movie

    init() {
        do {
            container = try ModelContainer(for: MediaSource.self, Movie.self,
                configurations: ModelConfiguration(isStoredInMemoryOnly: true))
            let source = MediaSource(name: "Family NAS", host: "preview.invalid", share: "Photos", rootPath: "", username: "")
            container.mainContext.insert(source)
            let samples = [("Summer Trip/Golden Hour.jpg", UIColor(red: 0.85, green: 0.36, blue: 0.21, alpha: 1)),
                ("Summer Trip/Lake Morning.jpg", UIColor(red: 0.21, green: 0.52, blue: 0.62, alpha: 1)),
                ("Summer Trip/The Long Way Home.jpg", UIColor(red: 0.38, green: 0.42, blue: 0.62, alpha: 1)),
                ("Weekend Walks/Through the Pines.jpg", UIColor(red: 0.22, green: 0.48, blue: 0.39, alpha: 1))]
            var payloads: [String: Data] = [:]
            var first: Movie?
            for (index, sample) in samples.enumerated() {
                let data = Self.picture(color: sample.1, index: index)
                payloads[sample.0] = data
                let photo = Movie(filePath: sample.0, fileSize: Int64(data.count), source: source)
                photo.resetToFileName()
                container.mainContext.insert(photo)
                if first == nil { first = photo }
            }
            try container.mainContext.save()
            featured = first!
            let bytes = payloads
            loader = PhotoImageLoader(fileSize: { _, path in
                guard let data = bytes[path] else { throw PhotoLoadingError.unavailable }; return Int64(data.count)
            }, read: { _, path, offset, length in
                guard let data = bytes[path], offset >= 0, offset < Int64(data.count) else { throw PhotoLoadingError.unavailable }
                return data.subdata(in: Int(offset)..<min(data.count, Int(offset) + length))
            })
        } catch { fatalError("Unable to create photo preview: \(error)") }
    }

    var body: some View {
        Group {
            if ProcessInfo.processInfo.arguments.contains("--ui-preview-photo") { PhotoDestination(photo: featured) }
            else { NavigationStack { PhotosView().movieDestination() } }
        }
        .modelContainer(container)
        .environment(\.photoLoader, loader)
        .preferredColorScheme(.dark)
        .tint(CinemaTheme.accent)
    }

    private static func picture(color: UIColor, index: Int) -> Data {
        let format = UIGraphicsImageRendererFormat(); format.scale = 1; format.opaque = true
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 1200, height: 800), format: format)
        return renderer.jpegData(withCompressionQuality: 0.9) { drawing in
            let context = drawing.cgContext
            let colors = [color.cgColor, UIColor(red: 0.08, green: 0.13, blue: 0.22, alpha: 1).cgColor] as CFArray
            if let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors, locations: [0, 1]) {
                context.drawLinearGradient(gradient, start: .zero, end: CGPoint(x: 0, y: 800), options: [])
            }
            UIColor(red: 1, green: 0.85, blue: 0.63, alpha: 1).setFill()
            context.fillEllipse(in: CGRect(x: CGFloat(780 - index * 100), y: 120, width: 140, height: 140))
            for layer in 0..<3 {
                context.setFillColor(UIColor(white: 0.04 + Double(layer) * 0.035, alpha: 0.7).cgColor)
                context.beginPath(); context.move(to: CGPoint(x: 0, y: 800))
                context.addLine(to: CGPoint(x: 0, y: CGFloat(480 + layer * 100)))
                context.addCurve(to: CGPoint(x: 1200, y: CGFloat(420 + layer * 110)),
                    control1: CGPoint(x: 280, y: CGFloat(200 + layer * 110)), control2: CGPoint(x: 850, y: CGFloat(720 - layer * 35)))
                context.addLine(to: CGPoint(x: 1200, y: 800)); context.closePath(); context.fillPath()
            }
        }
    }
}
#endif
