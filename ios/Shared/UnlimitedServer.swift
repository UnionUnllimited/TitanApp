import Foundation

/// The subscription's «👇БЕЗЛИМИТНЫЕ👇» row (a placeholder flag before it): it looks like a
/// section header, yet it is a working config, a balancer over the unlimited servers with
/// YouTube through nodes of its own. The app lists it first in «Серверы» under one name
/// everywhere (Главная, Настройки, the Live Activity, Siri), with АВТО's bolt for its flag.
enum UnlimitedServer {
    /// Its name in the app, given once where the subscription is parsed (SubscriptionAPI.parseServers).
    static let name = "⚡ Безлимитные · все серверы"
    /// What VoiceOver says for its bolt.
    static let spokenName = "Безлимитные, все серверы"
    /// Its id: the same after each refresh, wherever the panel puts the row.
    static let id = "json-unlimited"

    /// The panel's name for it, whatever emoji or flag go round the word.
    static func matches(_ panelName: String) -> Bool { panelName.lowercased().contains("безлимитн") }

    /// The name as shown (without a leading flag): this row's.
    static func isNamed(_ shown: String) -> Bool { shown.trimmingCharacters(in: .whitespaces) == name }
}
