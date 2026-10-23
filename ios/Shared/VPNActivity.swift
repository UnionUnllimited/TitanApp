import ActivityKit
import Foundation

/// The Live Activity (Lock Screen, Dynamic Island), with the VPN on and off: the app starts,
/// updates and ends it (LiveActivity.swift), TitanWidgets draws it.
@available(iOS 16.1, *)
struct VPNActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        /// The server's name without its flag: the one running, or while off the one ⏻ starts.
        var server: String
        var flag: String?
        /// Up. Builds before phase: false while it connects again (another server, a ping's pause).
        var connected: Bool
        // Added later, all optional: a state an older build sent still decodes.
        /// nil in an older build's state: connected ? .on : .switching.
        var phase: Phase?
        /// When the VPN came up (the timer).
        var since: Date?
        /// Bytes per second as at statsAt, as Главная's speed line (nil until two looks a moment apart).
        var down: Double?
        var up: Double?
        /// ↓ + ↑ since the VPN came up.
        var traffic: Int64?
        /// The server's ping in ms, measured at most 2 min before statsAt.
        var ping: Int?
        /// When the numbers were read. They go stale 15 s on: the widget then hides the speed.
        var statsAt: Date?

        enum Phase: String, Codable, Hashable {
            /// «Подключено», with the numbers.
            case on
            /// Connecting again: another server, a ping's pause («Переключаемся…»).
            case switching
            /// Coming on from off («Подключаемся…»).
            case connecting
            /// «Выключено»: ‹ › only move the pick, ⏻ turns it on.
            case off
        }

        var shown: Phase { phase ?? (connected ? .on : .switching) }
    }
}

/// Speed and traffic as Главная writes them (Fmt.speed in the app is this one too).
enum TrafficText {
    /// "2.4 МБ/с" as number and unit.
    static func speedParts(_ bytesPerSecond: Double) -> (number: String, unit: String) {
        let b = max(bytesPerSecond, 0)
        if b >= 1_048_576 { return (String(format: "%.1f", b / 1_048_576), "МБ/с") }
        if b >= 1024 { return (String(format: "%.0f", b / 1024), "КБ/с") }
        return (String(format: "%.0f", b), "Б/с")
    }

    static func speed(_ bytesPerSecond: Double) -> String {
        let p = speedParts(bytesPerSecond)
        return p.number + " " + p.unit
    }

    /// "2.4М", "140К": the compact Dynamic Island's.
    static func shortSpeed(_ bytesPerSecond: Double) -> String {
        let p = speedParts(bytesPerSecond)
        return p.number + String(p.unit.prefix(1))
    }

    /// "120 МБ", "1.4 ГБ" as formatBytes in the app; under a megabyte in КБ (a session's start).
    static func bytes(_ count: Int64) -> String {
        let b = Double(max(count, 0))
        if b >= 1_073_741_824 { return String(format: "%.1f ГБ", b / 1_073_741_824) }
        if b >= 1_048_576 { return String(format: "%.0f МБ", b / 1_048_576) }
        return String(format: "%.0f КБ", b / 1024)
    }
}
