import Foundation
import ImageIO
import Vision

guard CommandLine.arguments.count >= 3 else {
    fputs("Usage: verify_preview <screenshot> <expected text> [more expected text ...]\n", stderr)
    exit(2)
}

let url = URL(fileURLWithPath: CommandLine.arguments[1])
guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
      let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else {
    fputs("Could not read screenshot at \(url.path)\n", stderr)
    exit(2)
}

let request = VNRecognizeTextRequest()
request.recognitionLevel = .accurate
request.usesLanguageCorrection = false

do {
    try VNImageRequestHandler(cgImage: image, options: [:]).perform([request])
    let text = (request.results ?? [])
        .compactMap { $0.topCandidates(1).first?.string }
        .joined(separator: " ")
    for expected in CommandLine.arguments.dropFirst(2) {
        guard text.localizedCaseInsensitiveContains(expected) else {
            fputs("Expected preview text not visible: \(expected)\n", stderr)
            exit(1)
        }
        print("Verified preview text: \(expected)")
    }
} catch {
    fputs("Could not inspect screenshot: \(error)\n", stderr)
    exit(2)
}
