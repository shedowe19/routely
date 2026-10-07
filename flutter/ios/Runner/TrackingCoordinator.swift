import Flutter
import CoreLocation
import UIKit
import UserNotifications
import ActivityKit
import WidgetKit
import CryptoKit

/// Core Location wakes the sole tracking Flutter engine. No timer or artificial
/// audio is used to keep iOS alive; ordinary iOS suspension rules still apply.
final class TrackingCoordinator: NSObject, CLLocationManagerDelegate {
    static let shared = TrackingCoordinator()
    static let appGroup = "group.de.traewelling.app"
    private let store = UserDefaults.standard
    private let manager = CLLocationManager()
    private var configuration: [String: Any]?
    private var snapshot: [String: Any]?
    private var pendingOpen: [String: Any]?
    private var lastStopped: [String: Any]?
    private var endpoints: [TrackingChannel] = []
    private var engine: FlutterEngine?
    private var engineEndpoint: TrackingChannel?
    private var speechHost: TrackingSpeechHost?
    private var running = false
    private var gpsActive = false
    private var locationHealthy = true
    private var alertKeys: [String] = []
    private var pendingAlertKeys = Set<String>()
    private var notificationIds = Set<String>()
    private var startedAt = Date.distantFuture
    private var highestGeneration: Int64 = -1
    private var permissionResult: FlutterResult?
    private var permissionTimeout: DispatchWorkItem?
    private var locationResult: FlutterResult?
    private var oneShotTimeout: DispatchWorkItem?
    private var tripActivity: Activity<TripActivityAttributes>?
    private let clockId = "ios-\(UUID().uuidString)"

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = 5
        manager.activityType = .otherNavigation
        manager.pausesLocationUpdatesAutomatically = false
        manager.showsBackgroundLocationIndicator = true
        if let data = store.data(forKey: "routely.tracking.lastStopped") { lastStopped = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] }
        highestGeneration = (store.object(forKey: "routely.tracking.generation") as? NSNumber)?.int64Value ?? -1
        if (store.object(forKey: "routely.tracking.changeGeneration") as? NSNumber)?.int64Value == highestGeneration { alertKeys = Array((store.stringArray(forKey: "routely.tracking.changeKeys") ?? []).suffix(64)) }
        if let data = store.data(forKey: "routely.tracking.configuration") {
            configuration = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        }
    }
    func attach(_ messenger: FlutterBinaryMessenger, background: Bool) -> TrackingChannel {
        let endpoint = TrackingChannel(messenger, coordinator: self, background: background)
        endpoints.append(endpoint)
        return endpoint
    }
    func configurationMap() -> [String: Any] {
        var result = configuration ?? [:]
        if configuration == nil { result["generation"] = highestGeneration }
        result["running"] = running
        result["gpsAvailable"] = gpsActive && locationHealthy && (configuration?["mode"] as? String != "recognition" || recognitionAuthorized())
        result["preciseAvailable"] = manager.accuracyAuthorization == .fullAccuracy
        result["supported"] = true
        if let lastStopped { result["lastStopped"] = lastStopped }
        result["capabilities"] = ["backgroundLocation": true,
            "widget": FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: Self.appGroup) != nil,
            "liveActivity": ActivityAuthorizationInfo().areActivitiesEnabled,
            "batteryExemption": false]
        return result
    }
    func latestSnapshot() -> [String: Any] { snapshot ?? ["running": false] }
    func identity(_ value: [String: Any]) -> [String: Any] {
        value.filter { ["sessionRevision", "statusId", "generation"].contains($0.key) }
    }
    func matches(_ value: [String: Any]) -> Bool {
        guard let current = configuration else { return false }
        return value["sessionRevision"] as? String == current["sessionRevision"] as? String &&
            (value["statusId"] as? NSNumber)?.intValue == (current["statusId"] as? NSNumber)?.intValue &&
            (value["generation"] as? NSNumber)?.int64Value == (current["generation"] as? NSNumber)?.int64Value
    }
    private func sameAlertOwner(_ next: [String: Any]) -> Bool {
        guard let current = configuration,
            current["sessionRevision"] as? String == next["sessionRevision"] as? String,
            (current["statusId"] as? NSNumber)?.intValue == (next["statusId"] as? NSNumber)?.intValue,
            (current["statusId"] as? NSNumber)?.intValue != 0,
            let oldJourney = (current["route"] as? [String: Any])?["journey"] as? [String: Any],
            let newJourney = (next["route"] as? [String: Any])?["journey"] as? [String: Any] else { return false }
        return oldJourney["tripIdentity"] as? String == newJourney["tripIdentity"] as? String &&
            (oldJourney["contentRevision"] as? NSNumber)?.int64Value == (newJourney["contentRevision"] as? NSNumber)?.int64Value
    }
    private func containsSecret(_ value: Any) -> Bool {
        if let map = value as? [String: Any] {
            return map.contains { key, child in
                ["token", "accesstoken", "access_token", "refreshtoken", "refresh_token", "clientsecret", "client_secret", "password"].contains(key.lowercased()) || containsSecret(child)
            }
        }
        if let list = value as? [Any] { return list.contains(where: containsSecret) }
        return false
    }
    private func persist() {
        if let runtime = configuration?["runtime"] as? [String: Any] { configuration?["runtime"] = runtime.filter { ["version", "journey", "progress", "changes", "sevMaps", "retainedChanges"].contains($0.key) } }
        guard let configuration, let data = try? JSONSerialization.data(withJSONObject: configuration) else { return }
        store.set(data, forKey: "routely.tracking.configuration")
        store.set(highestGeneration, forKey: "routely.tracking.generation")
    }
    private func settings() -> [String: Any] { configuration?["settings"] as? [String: Any] ?? [:] }
    private func flag(_ camel: String, _ snake: String, default fallback: Bool) -> Bool {
        (settings()[camel] ?? settings()[snake]) as? Bool ?? fallback
    }
    private func recognitionAuthorized() -> Bool {
        manager.authorizationStatus == .authorizedAlways && manager.accuracyAuthorization == .fullAccuracy && CLLocationManager.locationServicesEnabled()
    }
    func start(_ args: [String: Any], result: @escaping FlutterResult) {
        guard UIApplication.shared.applicationState == .active else {
            result(FlutterError(code: "visible_start_required", message: "Öffne Routely, um die Fahrtbegleitung zu starten.", details: nil)); return
        }
        guard let revision = args["sessionRevision"] as? String, !revision.isEmpty,
            let status = args["statusId"] as? NSNumber, (status.intValue > 0 || (status.intValue == 0 && args["mode"] as? String == "recognition")),
            let generation = args["generation"] as? NSNumber, generation.int64Value >= highestGeneration,
            generation.int64Value > highestGeneration || matches(args), !containsSecret(args) else {
            result(FlutterError(code: "stale_identity", message: "Veraltete oder ungültige Fahrtkonfiguration.", details: nil)); return
        }
        if args["mode"] as? String == "recognition" && !recognitionAuthorized() {
            result(FlutterError(code: "recognition_permission", message: "Für die Fahrtsuche werden präziser Standort und die Standortfreigabe „Immer“ benötigt.", details: nil)); return
        }
        if running && matches(args) {
            configuration = args.filter { ["sessionRevision", "statusId", "generation", "route", "settings", "runtime", "mode"].contains($0.key) }
            persist(); emit("configuration", configurationMap()); result(["accepted": true, "running": true, "gpsAvailable": gpsActive]); return
        }
        let retainAlerts = sameAlertOwner(args)
        releaseResources()
        clearDisplay()
        configuration = args.filter { ["sessionRevision", "statusId", "generation", "route", "settings", "runtime", "mode"].contains($0.key) }
        if generation.int64Value != highestGeneration {
            if !retainAlerts { alertKeys.removeAll() }
            pendingAlertKeys.removeAll()
            store.set(alertKeys, forKey: "routely.tracking.changeKeys")
            store.set(generation.int64Value, forKey: "routely.tracking.changeGeneration")
        }
        highestGeneration = generation.int64Value
        lastStopped = nil; store.removeObject(forKey: "routely.tracking.lastStopped")
        persist()
        running = true
        locationHealthy = true
        startedAt = Date()
        let trackingEngine = FlutterEngine(name: "routely.tracking", project: nil, allowHeadlessExecution: true)
        engine = trackingEngine
        guard trackingEngine.run(withEntrypoint: "trackingMain") else {
            releaseResources(); clearDisplay(); emit("configuration", configurationMap())
            result(FlutterError(code: "tracking_start", message: "Fahrtbegleitung konnte nicht gestartet werden.", details: nil)); return
        }
        GeneratedPluginRegistrant.register(with: trackingEngine)
        engineEndpoint = attach(trackingEngine.binaryMessenger, background: true)
        speechHost = TrackingSpeechHost(messenger: trackingEngine.binaryMessenger)
        if (args["mode"] as? String == "recognition" || flag("gpsEnabled", "gps_tracking_enabled", default: true)) && permissionSnapshot()["location"] as? Bool == true {
            // This capability is declared in Runner's UIBackgroundModes.
            manager.allowsBackgroundLocationUpdates = true
            manager.startUpdatingLocation()
            gpsActive = true
        }
        emit("configuration", configurationMap())
        result(["accepted": true, "running": true, "gpsAvailable": gpsActive])
    }
    func stop(_ args: [String: Any]) -> Bool {
        guard matches(args) else { return false }
        var stopped = identity(args)
        stopped["reason"] = args["reason"] as? String ?? args["completionReason"] as? String ?? (args["completed"] as? Bool == true ? "completed" : "manual")
        lastStopped = stopped
        if let data = try? JSONSerialization.data(withJSONObject: stopped) { store.set(data, forKey: "routely.tracking.lastStopped") }
        releaseResources()
        configuration = nil
        store.removeObject(forKey: "routely.tracking.configuration")
        clearDisplay()
        emit("configuration", configurationMap())
        return true
    }
    private func releaseResources() {
        running = false
        gpsActive = false
        startedAt = Date.distantFuture
        manager.stopUpdatingLocation()
        manager.allowsBackgroundLocationUpdates = false
        speechHost?.shutdown(); speechHost = nil
        if let endpoint = engineEndpoint { endpoint.detach(); endpoints.removeAll { $0 === endpoint } }
        engineEndpoint = nil
        engine?.destroyContext()
        engine = nil
    }
    private func clearDisplay() {
        snapshot = nil
        sharedSnapshot(["running": false])
        let retiredGeneration = highestGeneration
        let ids = Array(notificationIds); notificationIds.removeAll(); pendingAlertKeys.removeAll()
        let center = UNUserNotificationCenter.current()
        center.removeDeliveredNotifications(withIdentifiers: ids)
        center.removePendingNotificationRequests(withIdentifiers: ids)
        center.getDeliveredNotifications { notes in
            let old = notes.map { $0.request.identifier }.filter { $0 == "routely.trip.\(retiredGeneration)" || $0.hasPrefix("routely.change.\(retiredGeneration).") }
            center.removeDeliveredNotifications(withIdentifiers: old)
        }
        center.getPendingNotificationRequests { requests in
            center.removePendingNotificationRequests(withIdentifiers: requests.map(\.identifier).filter { $0 == "routely.trip.\(retiredGeneration)" || $0.hasPrefix("routely.change.\(retiredGeneration).") })
        }
        let old = tripActivity; tripActivity = nil
        if let old { Task { await old.end(using: nil, dismissalPolicy: .immediate) } }
        emit("snapshot", ["running": false], background: false)
    }
    func publish(_ value: [String: Any]) -> Bool {
        guard running, matches(value), !containsSecret(value) else { return false }
        if let runtime = value["runtime"] as? [String: Any] {
            configuration?["runtime"] = runtime.filter { ["version", "journey", "progress", "changes", "sevMaps", "retainedChanges"].contains($0.key) }
            persist()
        }
        snapshot = value.filter { $0.key != "runtime" }
        sharedSnapshot(snapshot!)
        updateActivity(snapshot!)
        updateNotification(snapshot!)
        publishChangeAlerts(value)
        emit("snapshot", snapshot!, background: false)
        if value["completed"] as? Bool == true { DispatchQueue.main.async { if self.matches(value) { _ = self.stop(value) } } }
        return true
    }
    private func sharedSnapshot(_ value: [String: Any]) {
        guard FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: Self.appGroup) != nil,
            let shared = UserDefaults(suiteName: Self.appGroup) else { return }
        let details = flag("lockScreenDetailsEnabled", "lock_screen_details_enabled", default: true)
        let display = value.filter { ["line", "nextStop", "destination", "timeLabel", "timeSource", "platform", "progress", "statusId", "generation"].contains($0.key) }
        var publicValue: [String: Any] = details ? display : ["running": running, "statusId": value["statusId"] ?? -1,
            "generation": value["generation"] ?? -1, "nextStop": "Fahrtbegleitung ist aktiv", "line": "Routely"]
        publicValue["running"] = running && (value["running"] as? Bool != false)
        publicValue["updatedAtMillis"] = Int64(Date().timeIntervalSince1970 * 1000)
        if let data = try? JSONSerialization.data(withJSONObject: publicValue) { shared.set(data, forKey: "routely.widget.snapshot") }
        WidgetCenter.shared.reloadTimelines(ofKind: "RoutelyTripWidget")
    }
    private func updateActivity(_ value: [String: Any]) {
        let details = flag("lockScreenDetailsEnabled", "lock_screen_details_enabled", default: true)
        guard details, flag("liveProgressEnabled", "live_progress_enabled", default: true),
            ActivityAuthorizationInfo().areActivitiesEnabled else {
            let old = tripActivity; tripActivity = nil
            if let old { Task { await old.end(using: nil, dismissalPolicy: .immediate) } }
            return
        }
        let state = TripActivityAttributes.ContentState(line: value["line"] as? String ?? "Routely",
            nextStop: value["nextStop"] as? String ?? "Fahrtbegleitung", destination: value["destination"] as? String ?? "",
            timeLabel: value["timeLabel"] as? String ?? "", timeSource: value["timeSource"] as? String ?? "",
            platform: value["platform"] as? String ?? "", progress: min(1, max(0, (value["progress"] as? NSNumber)?.doubleValue ?? 0)),
            details: true, updatedAt: Date())
        if let activity = tripActivity {
            let expected = identity(value)
            Task { @MainActor in
                guard self.running, self.matches(expected), self.tripActivity?.id == activity.id else { return }
                if #available(iOS 16.2, *) {
                    await activity.update(ActivityContent(state: state, staleDate: state.updatedAt.addingTimeInterval(120)))
                } else { await activity.update(using: state) }
            }
        } else {
            guard UIApplication.shared.applicationState == .active else { return }
            let attributes = TripActivityAttributes(statusId: (value["statusId"] as? NSNumber)?.intValue ?? 0,
                generation: (value["generation"] as? NSNumber)?.int64Value ?? 0, sessionRevision: value["sessionRevision"] as? String ?? "")
            if #available(iOS 16.2, *) {
                tripActivity = try? Activity.request(attributes: attributes, content: ActivityContent(state: state, staleDate: state.updatedAt.addingTimeInterval(120)), pushType: nil)
            } else { tripActivity = try? Activity.request(attributes: attributes, contentState: state, pushType: nil) }
        }
    }
    private func updateNotification(_ value: [String: Any]) {
        guard tripActivity == nil else { return }
        let details = flag("lockScreenDetailsEnabled", "lock_screen_details_enabled", default: true)
        let content = UNMutableNotificationContent()
        content.title = details ? value["line"] as? String ?? "Routely" : "Routely"
        content.body = details ? [value["nextStop"], value["timeLabel"], value["timeSource"]].compactMap { $0 as? String }.joined(separator: " · ") : "Fahrtbegleitung ist aktiv"
        content.userInfo = identity(value)
        content.threadIdentifier = "routely.trip"
        content.interruptionLevel = .passive
        let expected = identity(value)
        let identifier = "routely.trip.\((value["generation"] as? NSNumber)?.int64Value ?? -1)"
        notificationIds.insert(identifier)
        UNUserNotificationCenter.current().getNotificationSettings { state in
            guard state.authorizationStatus == .authorized || state.authorizationStatus == .provisional else { return }
            DispatchQueue.main.async {
                guard self.running, self.matches(expected), details == self.flag("lockScreenDetailsEnabled", "lock_screen_details_enabled", default: true) else { return }
                UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: identifier, content: content, trigger: nil)) { _ in
                    DispatchQueue.main.async {
                        if !self.running || !self.matches(expected) {
                            UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [identifier])
                            UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [identifier])
                        }
                    }
                }
            }
        }
    }
    private func publishChangeAlerts(_ value: [String: Any]) {
        guard matches(value), flag("tripChangeAlertsEnabled", "trip_change_alerts_enabled", default: true),
            let alerts = value["changeAlerts"] as? [[String: Any]] else { return }
        let expected = identity(value)
        let generation = (value["generation"] as? NSNumber)?.int64Value ?? -1
        let center = UNUserNotificationCenter.current()
        for alert in alerts.prefix(64) {
            guard let rawKey = alert["key"] as? String, let rawMessage = alert["message"] as? String else { continue }
            let key = String(rawKey.prefix(512)), message = String(rawMessage.prefix(1000))
            guard !key.isEmpty, !message.isEmpty, !alertKeys.contains(key) else { continue }
            let reservation = "\(generation):\(key)"
            guard !pendingAlertKeys.contains(reservation) else { continue }
            pendingAlertKeys.insert(reservation)
            let hash = SHA256.hash(data: Data(key.utf8)).map { String(format: "%02x", $0) }.joined()
            let identifier = "routely.change.\(generation).\(hash)"
            center.getNotificationSettings { authorization in
                DispatchQueue.main.async {
                    guard self.running, self.matches(expected), authorization.authorizationStatus == .authorized || authorization.authorizationStatus == .provisional else {
                        self.pendingAlertKeys.remove(reservation); return
                    }
                    let content = UNMutableNotificationContent()
                    content.title = String((alert["title"] as? String ?? "Reiseänderung").prefix(128))
                    content.body = self.flag("lockScreenDetailsEnabled", "lock_screen_details_enabled", default: true) ? message : "Es gibt eine Reiseänderung. Öffne Routely für Details."
                    content.sound = .default
                    content.userInfo = expected
                    content.threadIdentifier = "routely.changes"
                    self.notificationIds.insert(identifier)
                    center.add(UNNotificationRequest(identifier: identifier, content: content, trigger: nil)) { error in
                        DispatchQueue.main.async {
                            self.pendingAlertKeys.remove(reservation)
                            guard self.running, self.matches(expected) else {
                                center.removePendingNotificationRequests(withIdentifiers: [identifier])
                                center.removeDeliveredNotifications(withIdentifiers: [identifier]); return
                            }
                            guard error == nil else { return }
                            self.alertKeys.append(key)
                            while self.alertKeys.count > 64 {
                                let oldKey = self.alertKeys.removeFirst()
                                let oldHash = SHA256.hash(data: Data(oldKey.utf8)).map { String(format: "%02x", $0) }.joined()
                                let oldId = "routely.change.\(generation).\(oldHash)"
                                center.removeDeliveredNotifications(withIdentifiers: [oldId]); center.removePendingNotificationRequests(withIdentifiers: [oldId])
                                self.notificationIds.remove(oldId)
                            }
                            self.store.set(self.alertKeys, forKey: "routely.tracking.changeKeys")
                            self.store.set(generation, forKey: "routely.tracking.changeGeneration")
                        }
                    }
                }
            }
        }
    }
    func openURL(_ url: URL) -> Bool {
        guard url.scheme == "routely", url.host == "status", let status = Int(url.lastPathComponent),
            let query = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems,
            let generation = query.first(where: { $0.name == "generation" })?.value.flatMap(Int64.init),
            let current = configuration,
            (current["statusId"] as? NSNumber)?.intValue == status,
            (current["generation"] as? NSNumber)?.int64Value == generation else { return false }
        openStatus(current)
        return true
    }
    func openStatus(_ value: [String: Any]) {
        if matches(value) {
            pendingOpen = identity(value)
            emit("openStatus", identity(value), background: false)
            if endpoints.contains(where: { !$0.background && $0.listening }) { pendingOpen = nil }
        }
    }
    func takePendingOpen() -> [String: Any]? {
        defer { pendingOpen = nil }
        if let pendingOpen, matches(pendingOpen) { return pendingOpen }
        return nil
    }
    func emit(_ type: String, _ value: [String: Any], background: Bool? = nil) {
        var event = value; event["type"] = type
        endpoints.filter { background == nil || $0.background == background }.forEach { $0.emit(event) }
    }
    func permissionSnapshot() -> [String: Any] {
        let status = manager.authorizationStatus
        let allowed = status == .authorizedAlways || status == .authorizedWhenInUse
        return ["supported": true, "location": allowed, "precise": manager.accuracyAuthorization == .fullAccuracy,
            "background": status == .authorizedAlways, "locationServicesEnabled": CLLocationManager.locationServicesEnabled()]
    }
    func requestPermissions(background: Bool, result: @escaping FlutterResult) {
        guard UIApplication.shared.applicationState == .active else {
            result(FlutterError(code: "visible_start_required", message: "Öffne die App für Berechtigungen.", details: nil)); return
        }
        guard permissionResult == nil else { result(FlutterError(code: "permission_busy", message: "Berechtigungsanfrage läuft bereits.", details: nil)); return }
        permissionResult = result
        let status = manager.authorizationStatus
        if status == .notDetermined {
            if background { manager.requestAlwaysAuthorization() }
            else { manager.requestWhenInUseAuthorization() }
        }
        else if background && status == .authorizedWhenInUse {
            // An Always upgrade may be deferred by iOS without another delegate
            // callback. Return the actual authorization after a bounded wait;
            // never authorize recognition before the system's decision.
            let timeout = DispatchWorkItem { [weak self] in self?.finishPermissionRequest() }
            permissionTimeout = timeout
            DispatchQueue.main.asyncAfter(deadline: .now() + 30, execute: timeout)
            manager.requestAlwaysAuthorization()
        }
        else { finishPermissionRequest() }
    }
    private func finishPermissionRequest() {
        guard let result = permissionResult else { return }
        permissionTimeout?.cancel(); permissionTimeout = nil
        permissionResult = nil
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { accepted, _ in
            DispatchQueue.main.async { var map = self.permissionSnapshot(); map["notifications"] = accepted; result(map) }
        }
    }
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        if running, let current = configuration, current["mode"] as? String == "recognition", !recognitionAuthorized() {
            emit("error", identity(current).merging(["code": "recognition_permission", "message": "Fahrtsuche beendet: Präziser Standort oder Hintergrundfreigabe fehlen."]) { _, new in new })
            _ = stop(current)
        }
        if manager.authorizationStatus != .notDetermined { finishPermissionRequest() }
        if running && permissionSnapshot()["location"] as? Bool != true {
            manager.stopUpdatingLocation()
            gpsActive = false
            emit("locationAvailability", identity(configuration ?? [:]).merging(["available": false]) { _, new in new }, background: true)
        } else if running && !gpsActive && (configuration?["mode"] as? String == "recognition" || flag("gpsEnabled", "gps_tracking_enabled", default: true)) {
            manager.allowsBackgroundLocationUpdates = true
            manager.startUpdatingLocation(); gpsActive = true; locationHealthy = true
            emit("locationAvailability", identity(configuration ?? [:]).merging(["available": true]) { _, new in new }, background: true)
        }
    }
    func requestLocation(result: @escaping FlutterResult) {
        guard UIApplication.shared.applicationState == .active, permissionSnapshot()["location"] as? Bool == true else {
            result(FlutterError(code: "location_permission", message: "Bitte Standortfreigabe erteilen.", details: nil)); return
        }
        guard CLLocationManager.locationServicesEnabled() else {
            result(FlutterError(code: "location_disabled", message: "Bitte Ortungsdienste aktivieren.", details: nil)); return
        }
        guard locationResult == nil else { result(FlutterError(code: "location_busy", message: "Standortsuche läuft bereits.", details: nil)); return }
        locationResult = result
        manager.requestLocation()
        let timeout = DispatchWorkItem { [weak self] in
            guard let self, let callback = self.locationResult else { return }
            self.locationResult = nil
            callback(FlutterError(code: "location_timeout", message: "Kein aktueller Standort verfügbar.", details: nil))
        }
        oneShotTimeout = timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + 15, execute: timeout)
    }
    private func fix(_ location: CLLocation) -> [String: Any] {
        let now = Date()
        let nowNanos = Int64(ProcessInfo.processInfo.systemUptime * 1_000_000_000)
        // CLLocation has no monotonic acquisition timestamp. Its age is mapped
        // onto sampled uptime; Dart rejects clock discontinuities/old samples.
        let sampleNanos = nowNanos - Int64(max(0, now.timeIntervalSince(location.timestamp)) * 1_000_000_000)
        var map: [String: Any] = ["latitude": location.coordinate.latitude, "longitude": location.coordinate.longitude,
            "accuracy": location.horizontalAccuracy, "timeMillis": Int64(location.timestamp.timeIntervalSince1970 * 1000),
            "elapsedRealtimeNanos": sampleNanos, "nowMillis": Int64(now.timeIntervalSince1970 * 1000),
            "nowMonotonicNanos": nowNanos, "clockId": clockId]
        if location.speed >= 0 { map["speed"] = location.speed }
        if location.course >= 0 { map["heading"] = location.course }
        return map
    }
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        for location in locations where location.horizontalAccuracy >= 0 {
            let map = fix(location)
            let age = Date().timeIntervalSince(location.timestamp)
            if let callback = locationResult, age >= 0, age <= 30 {
                locationResult = nil; oneShotTimeout?.cancel(); oneShotTimeout = nil; callback(map)
            }
            if running, location.timestamp >= startedAt, let configuration {
                if !locationHealthy {
                    locationHealthy = true
                    emit("locationAvailability", identity(configuration).merging(["available": true]) { _, new in new }, background: true)
                }
                emit("fix", map.merging(identity(configuration)) { _, new in new }, background: true)
            }
        }
    }
    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        locationHealthy = false
        if running, let current = configuration, current["mode"] as? String == "recognition", !recognitionAuthorized() { _ = stop(current) }
        if let callback = locationResult { locationResult = nil; oneShotTimeout?.cancel(); callback(FlutterError(code: "location_unavailable", message: "Kein aktueller Standort verfügbar.", details: nil)) }
        emit("error", identity(configuration ?? [:]).merging(["code": "location_unavailable", "message": "Standort ist vorübergehend nicht verfügbar."]) { _, new in new })
    }
}

final class TrackingChannel: NSObject, FlutterStreamHandler {
    let background: Bool
    private weak var coordinator: TrackingCoordinator?
    private let owner: [String: Any]
    private let method: FlutterMethodChannel
    private let event: FlutterEventChannel
    private var sink: FlutterEventSink?
    var listening: Bool { sink != nil }
    init(_ messenger: FlutterBinaryMessenger, coordinator: TrackingCoordinator, background: Bool) {
        self.coordinator = coordinator; self.background = background
        owner = background ? coordinator.identity(coordinator.configurationMap()) : [:]
        method = FlutterMethodChannel(name: "routely/tracking", binaryMessenger: messenger)
        event = FlutterEventChannel(name: "routely/tracking_events", binaryMessenger: messenger)
        super.init()
        method.setMethodCallHandler { [weak self] call, result in self?.handle(call, result: result) }
        event.setStreamHandler(self)
    }
    func detach() { method.setMethodCallHandler(nil); event.setStreamHandler(nil); sink = nil }
    func emit(_ value: [String: Any]) {
        if background && ["configuration", "fix", "locationAvailability"].contains(value["type"] as? String ?? "") {
            guard NSDictionary(dictionary: owner).isEqual(to: coordinator?.identity(value) ?? [:]) else { return }
        }
        sink?(value)
    }
    func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        sink = events
        if let coordinator {
            emit(coordinator.configurationMap().merging(["type": "configuration"]) { _, new in new })
            if !background, let open = coordinator.takePendingOpen() { emit(open.merging(["type": "openStatus"]) { _, new in new }) }
        }
        return nil
    }
    func onCancel(withArguments arguments: Any?) -> FlutterError? { sink = nil; return nil }
    private func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard let coordinator else { result(FlutterError(code: "unavailable", message: "Fahrtbegleitung nicht verfügbar.", details: nil)); return }
        let args = call.arguments as? [String: Any] ?? [:]
        switch call.method {
        case "getConfiguration": result(coordinator.configurationMap())
        case "getSnapshot": result(coordinator.latestSnapshot())
        case "publish": result(["accepted": background && NSDictionary(dictionary: owner).isEqual(to: coordinator.identity(args)) && coordinator.publish(args)])
        case "stopTracking": result(["accepted": !background && coordinator.stop(args)])
        case "startTracking", "startRecognition":
            if background { result(FlutterError(code: "visible_start_required", message: "Öffne die App zum Starten.", details: nil)) }
            else { coordinator.start(args, result: result) }
        case "requestPermissions":
            if background { result(FlutterError(code: "visible_start_required", message: "Öffne die App für Berechtigungen.", details: nil)) }
            else { coordinator.requestPermissions(background: args["background"] as? Bool ?? false, result: result) }
        case "requestLocation":
            if background { result(FlutterError(code: "visible_start_required", message: "Öffne die App für die Standortsuche.", details: nil)) }
            else { coordinator.requestLocation(result: result) }
        case "batteryStatus": result(["supported": false, "exempt": false, "lowPowerMode": ProcessInfo.processInfo.isLowPowerModeEnabled])
        case "requestBatteryExemption": result(["supported": false, "opened": false])
        case "openNotificationSettings":
            if background { result(["opened": false]); return }
            let url = URL(string: UIApplication.openSettingsURLString)!
            UIApplication.shared.open(url); result(["opened": true])
        case "legacyImport", "commitLegacyImport": result([:])
        default: result(FlutterMethodNotImplemented)
        }
    }
}
