import Foundation
import UserNotifications

/// Напоминания: the subscription ends in 3 days and tomorrow (at noon), and less than 10 % of
/// the bypass traffic is left. Local notifications, scheduled again after each update of the
/// subscription; on by default, a switch in Настройки.
enum Reminders {
    static let key = "reminders"
    private static let expiryIds = ["titan.expiry.3d", "titan.expiry.1d"]
    /// The traffic total the "running out" note was shown for (once per paid amount).
    private static let warnedKey = "titan.trafficWarned"

    static var enabled: Bool { UserDefaults.standard.object(forKey: key) as? Bool ?? true }

    /// Asks the first time; false when notifications are off for the app.
    static func allowed() async -> Bool {
        let center = UNUserNotificationCenter.current()
        switch await center.notificationSettings().authorizationStatus {
        case .authorized, .provisional, .ephemeral: return true
        case .notDetermined: return (try? await center.requestAuthorization(options: [.alert, .sound])) ?? false
        default: return false
        }
    }

    static func update(for info: SubscriptionInfo) async {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: expiryIds)
        guard enabled, await allowed() else { return }
        if info.expire > 0 {
            let end = Date(timeIntervalSince1970: TimeInterval(info.expire))
            for (id, days) in zip(expiryIds, [3, 1]) {
                guard let day = Calendar.current.date(byAdding: .day, value: -days, to: end) else { continue }
                var when = Calendar.current.dateComponents([.year, .month, .day], from: day)
                when.hour = 12
                guard let fire = Calendar.current.date(from: when), fire > Date(), fire < end else { continue }
                let content = UNMutableNotificationContent()
                content.title = "Подписка Titan VPS"
                content.body = days == 1
                    ? "Подписка заканчивается завтра. Продлите её в личном кабинете, чтобы VPN не отключился."
                    : "Подписка закончится через 3 дня. Продлите её заранее в личном кабинете."
                content.sound = .default
                let request = UNNotificationRequest(identifier: id, content: content,
                                                    trigger: UNCalendarNotificationTrigger(dateMatching: when, repeats: false))
                try? await center.add(request)
            }
        }
        // Bypass traffic: once per paid amount, when less than 10 % of it is left.
        let defaults = UserDefaults.standard
        if info.total > 0 && info.remaining * 10 < info.total {
            guard (defaults.object(forKey: warnedKey) as? NSNumber)?.int64Value != info.total else { return }
            defaults.set(NSNumber(value: info.total), forKey: warnedKey)
            let content = UNMutableNotificationContent()
            content.title = "Трафик на обходах заканчивается"
            content.body = "Осталось \(formatBytes(info.remaining)) из \(formatBytes(info.total)). Докупить ГБ можно в личном кабинете."
            content.sound = .default
            try? await center.add(UNNotificationRequest(identifier: "titan.traffic", content: content,
                                                        trigger: UNTimeIntervalNotificationTrigger(timeInterval: 2, repeats: false)))
        } else if info.total > 0 {
            defaults.removeObject(forKey: warnedKey)
        }
    }

    /// The switch turned off: nothing more comes.
    static func cancelAll() {
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: expiryIds + ["titan.traffic"])
    }
}

/// Notifications show as banners with the app open too.
final class NotificationPresenter: NSObject, UNUserNotificationCenterDelegate {
    static let shared = NotificationPresenter()

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }
}
