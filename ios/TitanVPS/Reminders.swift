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
    private static let askedKey = "titan.remindersAsked"
    /// A tap on a reminder opens Подписка (RootView listens); the traffic one Тарифы over it.
    /// The notification's object is the reminder's id.
    static let openSubscription = Notification.Name("titan.openSubscription")
    static let trafficId = "titan.traffic"

    static var enabled: Bool { UserDefaults.standard.object(forKey: key) as? Bool ?? true }

    /// May notify now (without asking).
    static func authorized() async -> Bool {
        switch await UNUserNotificationCenter.current().notificationSettings().authorizationStatus {
        case .authorized, .provisional, .ephemeral: return true
        default: return false
        }
    }

    /// Asked once, after the first connection (not during the key entry, next to the VPN prompt).
    static func askOnce(for info: SubscriptionInfo?) async {
        let defaults = UserDefaults.standard
        guard enabled, !defaults.bool(forKey: askedKey) else { return }
        defaults.set(true, forKey: askedKey)
        if await allowed(), let info { await update(for: info) }
    }

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
        guard enabled, await authorized() else { return }
        if info.expire > 0 {
            let end = Date(timeIntervalSince1970: TimeInterval(info.expire))
            for (id, days) in zip(expiryIds, [3, 1]) {
                guard let day = Calendar.current.date(byAdding: .day, value: -days, to: end) else { continue }
                var when = Calendar.current.dateComponents([.year, .month, .day], from: day)
                when.hour = 12
                guard let fire = Calendar.current.date(from: when), fire > Date(), fire < end else { continue }
                let content = UNMutableNotificationContent()
                content.title = "Подписка Titan VPS"
                content.body = "Подписка закончится \(endText(end)). Если ещё не продлили — продлите её на вкладке «Подписка», чтобы VPN не отключился."
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
            content.body = "Осталось \(formatBytes(info.remaining)) из \(formatBytes(info.total)). Добавить ГБ можно на вкладке «Подписка»."
            content.sound = .default
            try? await center.add(UNNotificationRequest(identifier: trafficId, content: content,
                                                        trigger: UNTimeIntervalNotificationTrigger(timeInterval: 2, repeats: false)))
        } else if info.total > 0 {
            defaults.removeObject(forKey: warnedKey)
        }
    }

    /// "13 июля в 00:30" in the phone's time zone.
    private static func endText(_ end: Date) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "ru_RU")
        f.dateFormat = "d MMMM 'в' HH:mm"
        return f.string(from: end)
    }

    /// The switch turned off: nothing more comes.
    static func cancelAll() {
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: expiryIds + [trafficId])
    }

    /// Signed out: no reminders for a subscription that isn't here any more.
    static func reset() {
        cancelAll()
        UserDefaults.standard.removeObject(forKey: warnedKey)
    }
}

/// Notifications show as banners with the app open too.
final class NotificationPresenter: NSObject, UNUserNotificationCenterDelegate {
    static let shared = NotificationPresenter()

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let id = response.notification.request.identifier
        DispatchQueue.main.async { NotificationCenter.default.post(name: Reminders.openSubscription, object: id) }
        completionHandler()
    }
}
