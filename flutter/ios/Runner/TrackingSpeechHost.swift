import Flutter
import AVFoundation

/// flutter_tts-compatible background endpoint with native ownership of speech.
/// It guarantees that a route/session change stops its utterance synchronously.
/// The visible Flutter engine continues to use the package's ordinary plugin.
final class TrackingSpeechHost: NSObject, AVSpeechSynthesizerDelegate {
    private let channel: FlutterMethodChannel
    private let synthesizer = AVSpeechSynthesizer()
    private var completion: FlutterResult?
    private var utterance: AVSpeechUtterance?
    private var awaitCompletion = false
    private var language = "de-DE"
    private var voice: AVSpeechSynthesisVoice?
    private var rate = AVSpeechUtteranceDefaultSpeechRate
    private var pitch: Float = 1
    private var volume: Float = 1
    init(messenger: FlutterBinaryMessenger) {
        channel = FlutterMethodChannel(name: "flutter_tts", binaryMessenger: messenger)
        super.init()
        synthesizer.delegate = self
        channel.setMethodCallHandler { [weak self] call, result in self?.handle(call, result: result) }
    }
    func shutdown() {
        stop()
        synthesizer.delegate = nil
        channel.setMethodCallHandler(nil)
    }
    private func stop() {
        let callback = completion; completion = nil; utterance = nil
        synthesizer.stopSpeaking(at: .immediate)
        callback?(0)
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
    private func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "awaitSpeakCompletion": awaitCompletion = call.arguments as? Bool ?? false; result(1)
        case "setLanguage": language = call.arguments as? String ?? "de-DE"; voice = nil; result(AVSpeechSynthesisVoice(language: language) == nil ? 0 : 1)
        case "setVoice":
            let values = call.arguments as? [String: String] ?? [:]
            voice = AVSpeechSynthesisVoice.speechVoices().first { ($0.name == values["name"] || $0.identifier == values["name"]) && (values["locale"] == nil || $0.language == values["locale"]) }
            result(voice == nil ? 0 : 1)
        case "clearVoice": voice = nil; result(1)
        case "setSpeechRate": rate = min(AVSpeechUtteranceMaximumSpeechRate, max(AVSpeechUtteranceMinimumSpeechRate, (call.arguments as? NSNumber)?.floatValue ?? AVSpeechUtteranceDefaultSpeechRate)); result(1)
        case "setPitch": pitch = min(2, max(0.5, (call.arguments as? NSNumber)?.floatValue ?? 1)); result(1)
        case "setVolume": volume = min(1, max(0, (call.arguments as? NSNumber)?.floatValue ?? 1)); result(1)
        case "speak":
            guard let text = call.arguments as? String, !text.isEmpty else { result(0); return }
            stop()
            do {
                try AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio, options: [.duckOthers])
                try AVAudioSession.sharedInstance().setActive(true)
                let speech = AVSpeechUtterance(string: text)
                speech.voice = voice ?? AVSpeechSynthesisVoice(language: language)
                speech.rate = rate; speech.pitchMultiplier = pitch; speech.volume = volume
                utterance = speech
                if awaitCompletion { completion = result } else { result(1) }
                synthesizer.speak(speech)
            } catch { result(0); channel.invokeMethod("speak.onError", arguments: "Audioausgabe nicht verfügbar.") }
        case "stop": stop(); result(1)
        case "pause": result(synthesizer.pauseSpeaking(at: .word) ? 1 : 0)
        case "getLanguages": result(Array(Set(AVSpeechSynthesisVoice.speechVoices().map(\.language))))
        case "getVoices": result(AVSpeechSynthesisVoice.speechVoices().map { ["name": $0.name, "locale": $0.language, "identifier": $0.identifier] })
        case "isLanguageAvailable": result(AVSpeechSynthesisVoice(language: call.arguments as? String ?? "") != nil)
        case "setSharedInstance", "autoStopSharedSession", "setIosAudioCategory": result(1)
        default: result(FlutterMethodNotImplemented)
        }
    }
    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didStart utterance: AVSpeechUtterance) {
        if self.utterance === utterance { channel.invokeMethod("speak.onStart", arguments: nil) }
    }
    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        guard self.utterance === utterance else { return }
        self.utterance = nil
        let callback = completion; completion = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        callback?(1)
        channel.invokeMethod("speak.onComplete", arguments: nil)
    }
    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        guard self.utterance === utterance else { return }
        self.utterance = nil
        let callback = completion; completion = nil; callback?(0)
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        channel.invokeMethod("speak.onCancel", arguments: nil)
    }
}
