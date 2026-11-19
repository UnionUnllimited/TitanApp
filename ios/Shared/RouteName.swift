import Foundation

/// A server's name as the screens show it («Маршрут», docs/HANDOFF.md): the panel's АВТО row as
/// «Лучший маршрут», the unlimited servers' row as UnlimitedServer.shownName, any other as the
/// panel sends it. Display only, in the app, the Live Activity and Siri alike: Server.name stays
/// as parsed, since favourites, Технический режим's techniques, the VPN profile and the rules
/// that match "авто", "обход" and "безлимитн" in it go by it.
enum RouteName {
    /// The АВТО row's name on screen.
    static let best = "Лучший маршрут"

    /// The panel's АВТО among the regular servers, by its name without the flag: the row
    /// parseServers puts the unlimited one after. Sparkles for its flag (FlagCircle).
    static func isBest(_ name: String) -> Bool {
        name.lowercased().contains("авто") && !TunnelSwitch.isBypass(name)
    }

    /// A name without its flag (String.splitFlag in the app), as shown.
    static func shown(_ name: String) -> String {
        if UnlimitedServer.isNamed(name) { return UnlimitedServer.shownName }
        if isBest(name) { return best }
        return name
    }
}
