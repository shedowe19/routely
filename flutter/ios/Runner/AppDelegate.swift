import Flutter
import UIKit
import UserNotifications

@main
@objc class AppDelegate: FlutterAppDelegate, FlutterImplicitEngineDelegate {
  private var trackingChannel: TrackingChannel?
  override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
  ) -> Bool {
    _ = TrackingCoordinator.shared
    UNUserNotificationCenter.current().delegate = self
    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }

  func didInitializeImplicitFlutterEngine(_ engineBridge: FlutterImplicitEngineBridge) {
    GeneratedPluginRegistrant.register(with: engineBridge.pluginRegistry)
    trackingChannel = TrackingCoordinator.shared.attach(engineBridge.applicationRegistrar.messenger(), background: false)
  }

  override func userNotificationCenter(_ center: UNUserNotificationCenter,
    didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void) {
    let value = response.notification.request.content.userInfo.reduce(into: [String: Any]()) { map, item in
      if let key = item.key as? String { map[key] = item.value }
    }
    TrackingCoordinator.shared.openStatus(value)
    completionHandler()
  }
}
