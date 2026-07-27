import SwiftUI
import UserNotifications

@main
struct SiempreCercaMonitorApp: App {

    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    var body: some Scene {
        WindowGroup {
            if AppPreferences.shared.isSetupComplete {
                StatusView()
            } else {
                SetupView()
            }
        }
    }
}

class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {

        // Solicitar permisos de notificaciones
        UNUserNotificationCenter.current().delegate = self
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge, .criticalAlert]) { _, _ in }

        // Si ya esta configurado, iniciar BLE
        if AppPreferences.shared.isSetupComplete {
            _ = FlicBleManager.shared
            APIClient.shared.login()
        }

        return true
    }

    // Mostrar notificaciones incluso con la app en foreground
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound, .badge])
    }
}
