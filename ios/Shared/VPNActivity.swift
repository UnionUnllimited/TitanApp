import ActivityKit
import Foundation

/// The Live Activity (Lock Screen, Dynamic Island) while the VPN is on: the app starts, updates
/// and ends it (LiveActivity.swift), TitanWidgets draws it.
@available(iOS 16.1, *)
struct VPNActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        /// The server's name without its flag.
        var server: String
        var flag: String?
        var connectedSince: Date
        /// false while it connects again (another server, a ping's pause).
        var connected: Bool
    }
}
