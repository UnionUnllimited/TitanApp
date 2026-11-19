import Foundation

/// A server's name as the screens show it («Маршрут», docs/HANDOFF.md): the panel's АВТО row as
/// «Лучший маршрут», the unlimited servers' row as UnlimitedServer.shownName, the app's own
/// «Сервер N» as «Направление N», any other as the panel sends it, with a bypass's «обход…» as
/// «объезд…» and without the words the screens never show (hiddenStems: «безлимит…», «VPN»,
/// «сервер», «блокир…») and the pointers, separators and brackets they leave. Display only, in
/// the app, the Live Activity and Siri alike: Server.name stays as parsed, since favourites,
/// Технический режим's techniques, the VPN profile, the server's id and the rules that match
/// "авто", "обход" and "безлимитн" in it go by it.
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
        return tidy(name, bypass: TunnelSwitch.isBypass(name))
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
        let stripped = strip(rest)
        let shown = stripped.dropped ? stripped.text : rest
        return shown.contains(where: { $0.isLetter || $0.isNumber }) ? shown : nil
    }

    /// N of the app's own «Сервер N», the name parseServers gives a row the panel sent without one.
    private static func placeholderNumber(_ name: String) -> Substring? {
        let prefix = "Сервер "
        guard name.hasPrefix(prefix) else { return nil }
        let n = name.dropFirst(prefix.count)
        return !n.isEmpty && n.allSatisfy({ $0.isASCII && $0.isNumber }) ? n : nil
    }

    /// Words of a panel's name the screens never show (the owner's rule, docs/HANDOFF.md), whole
    /// words by how they start, in any case: «безлимит…» (the list says «без ограничений»),
    /// «сервер…», «блокир…» and the service's own kind. «обход…» is swapped instead (bypassStem).
    private static let hiddenStems = ["безлимит", "сервер", "блокир", "vpn", "впн", "vps", "впс"]

    /// A bypass's own word, «объезд» on screen, in place: the rest reads as the panel put it
    /// («Обход белых списков» → «Объезд белых списков», «LTE Обход 2» → «LTE Объезд 2»). Only a
    /// bypass's name has it (TunnelSwitch.isBypass: "обход" in the name).
    private static let bypassStem = "обход"
    private static let bypassShown = "объезд"

    /// Pointing emoji and arrows round a panel's word («👇БЕЗЛИМИТНЫЕ👇»), by their first scalar:
    /// nothing is left to point at once the word is gone.
    private static let pointers: Set<UInt32> = [0x1F447, 0x1F446, 0x1F449, 0x1F448, 0x261D, 0x2B07, 0x2B06,
                                                0x27A1, 0x2B05, 0x2193, 0x2191, 0x2192, 0x2190]

    /// What separates the parts of a panel's name.
    private static let separators = CharacterSet(charactersIn: "|·•-–—:/,;")

    /// [name] without hiddenStems' words and with «обход…» as «объезд…» (swapped); nothing else
    /// changes if none of them is in it. Where a word went the pointers go too, the separators
    /// left doubled or at an end, and the brackets round all that is left («VPN (Германия)» →
    /// «Германия»). dropped: a word went; droppedFirst: the name's first word did («VPN 2» → «2»).
    private static func strip(_ name: String) -> (text: String, dropped: Bool, droppedFirst: Bool) {
        var out = ""
        var token = ""
        var tokenIsWord = false
        var seenWord = false
        var dropped = false
        var droppedFirst = false
        func flush() {
            guard !token.isEmpty else { return }
            if tokenIsWord {
                let lower = token.lowercased()
                if lower.hasPrefix(bypassStem) {
                    out += swapped(token)
                } else if hiddenStems.contains(where: { lower.hasPrefix($0) }) {
                    dropped = true
                    if !seenWord { droppedFirst = true }
                } else {
                    out += token
                }
                seenWord = true
            } else {
                out += token
            }
            token = ""
        }
        for c in name {
            let isWord = c.isLetter || c.isNumber
            if isWord != tokenIsWord {
                flush()
                tokenIsWord = isWord
            }
            token.append(c)
        }
        flush()
        guard dropped else { return (out, false, false) }

        var kept: [String] = []
        for part in out.split(whereSeparator: { $0.isWhitespace }) {
            let piece = String(part.filter { c in !(c.unicodeScalars.first.map { pointers.contains($0.value) } ?? false) })
            if piece.isEmpty || piece == "()" || piece == "[]" { continue }
            // A separator after nothing or after another one.
            if isSeparators(piece), kept.last.map(isSeparators) ?? true { continue }
            kept.append(piece)
        }
        while let last = kept.last, isSeparators(last) { kept.removeLast() }
        let text = kept.joined(separator: " ").trimmingCharacters(in: separators.union(.whitespaces))
        return (unwrapped(text), true, droppedFirst)
    }

    private static func isSeparators(_ s: String) -> Bool {
        !s.isEmpty && s.unicodeScalars.allSatisfy { separators.contains($0) }
    }

    /// An «обход…» word as «объезд…», in its case and with its ending: «ОБХОДЫ» → «ОБЪЕЗДЫ»,
    /// «Обход» → «Объезд», «обходной» → «объездной».
    private static func swapped(_ word: String) -> String {
        let stem = String(word.prefix(bypassStem.count))
        let ending = String(word.dropFirst(bypassStem.count))
        if stem == stem.uppercased() { return bypassShown.uppercased() + ending }
        if stem.first?.isUppercase == true { return bypassShown.capitalized + ending }
        return bypassShown + ending
    }

    /// What is left without the brackets round it all: «(Мегафон)» → «Мегафон», and «(Европа»
    /// where bestSuffix trimmed the other one. A bracket inside the name stays («LTE (Мегафон)»).
    private static func unwrapped(_ text: String) -> String {
        var t = text
        for (open, close) in [("(", ")"), ("[", "]")] as [(Character, Character)] {
            let opens = t.filter { $0 == open }.count
            let closes = t.filter { $0 == close }.count
            guard opens <= 1, closes <= 1, opens + closes > 0,
                  opens == 0 || t.first == open, closes == 0 || t.last == close else { continue }
            var inner = Substring(t)
            if inner.first == open { inner = inner.dropFirst() }
            if inner.last == close { inner = inner.dropLast() }
            t = inner.trimmingCharacters(in: .whitespaces)
        }
        return t
    }

    /// A panel's name as shown: strip's, never empty. Where a word went from its start and a
    /// number is left first, or only a number (or nothing) is left, the theme's own word goes
    /// before it, unless it is there already: «Направление 2 Германия», «Направление 2».
    private static func tidy(_ name: String, bypass: Bool) -> String {
        let stripped = strip(name)
        let text = stripped.text
        guard stripped.dropped else { return text }
        let word = bypass ? "Объезд" : "Направление"
        guard text.contains(where: { $0.isLetter }) else {
            let digits = text.filter { $0.isNumber }
            return digits.isEmpty ? word : "\(word) \(digits)"
        }
        if stripped.droppedFirst, text.first?.isNumber == true,
           !text.lowercased().contains(word.lowercased()) { return "\(word) \(text)" }
        return text
    }
}
