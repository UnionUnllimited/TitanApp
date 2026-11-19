import Foundation

/// A server's name as the screens show it («Маршрут», docs/HANDOFF.md): the panel's АВТО row as
/// «Лучший маршрут», the unlimited servers' row as UnlimitedServer.shownName, the app's own
/// «Сервер N» as «Направление N», any other as the panel sends it. Display only, in the app, the
/// Live Activity and Siri alike: Server.name stays as parsed, since favourites, Технический
/// режим's techniques, the VPN profile, the server's id and the rules that match "авто", "обход"
/// and "безлимитн" in it go by it.
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
        if isBest(name) { return bestSuffix(name).map { "\(best) · \($0)" } ?? best }
        if let n = placeholderNumber(name) { return "Направление \(n)" }
        return name
    }

    /// What the panel adds to its АВТО word («⚡ АВТО | Европа» → «Европа»), so that two such
    /// balancers stay apart on screen; nil when the name is the word and its emoji only.
    private static func bestSuffix(_ name: String) -> String? {
        let words = name.split(whereSeparator: { $0.isWhitespace || $0 == "|" })
            .filter { !$0.lowercased().contains("авто") }
        // The emoji, separators and brackets left round what the panel added.
        let edges = CharacterSet.whitespaces.union(.punctuationCharacters).union(.symbols)
            .union(CharacterSet(charactersIn: "\u{FE0F}\u{200D}"))
        let rest = words.joined(separator: " ").trimmingCharacters(in: edges)
        return rest.contains(where: { $0.isLetter || $0.isNumber }) ? rest : nil
    }

    /// N of the app's own «Сервер N», the name parseServers gives a row the panel sent without one.
    private static func placeholderNumber(_ name: String) -> Substring? {
        let prefix = "Сервер "
        guard name.hasPrefix(prefix) else { return nil }
        let n = name.dropFirst(prefix.count)
        return !n.isEmpty && n.allSatisfy({ $0.isASCII && $0.isNumber }) ? n : nil
    }
}
