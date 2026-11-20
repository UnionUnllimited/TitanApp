import Foundation

/// How Маршрут picks the direction (the owner's ask, November 2026: «Авто» to start with, the
/// list tucked away under «Выбрать направление вручную»): «Авто», «Авто · объезды», or one
/// picked in the list. Saved, so «Авто · объезды» stays picked while the bypass it goes to
/// changes. The picked server itself stays AppStore.selectedId: the Live Activity, Siri and
/// Пункт управления know nothing of this and go by it.
enum RouteChoice: String {
    /// The panel's АВТО (autoServer).
    case auto
    /// «Авто · объезды», tapped (or «Поехать объездом» on the trip card).
    case bypass
    /// «Авто · объезды» the app went over to by itself: mobile internet looked restricted
    /// (MobileNetCheck). Back to «Авто» by itself once it opens again.
    case bypassDetected
    /// A direction picked in the list (or by the Live Activity's ‹ ›, Siri, iOS Settings).
    case manual

    static let key = "titan.routeChoice"
    /// The pick as the app itself last wrote it (AppStore.setPick): another "titan.selected" at
    /// launch was written without it (the Live Activity's ‹ › with the app not running).
    static let pickKey = "titan.routeChoicePick"
    /// «Выбрать направление вручную» open (HomeView); Сбросить настройки closes it.
    static let listOpenKey = "titan.routeListOpen"

    var isBypass: Bool { self == .bypass || self == .bypassDetected }

    /// «Авто»: the panel's АВТО among the regular directions (RouteName.isBest), else the unlimited
    /// row (UnlimitedServer), else the first regular one; the unlimited row first where the
    /// panel's config says so (RemoteConfig.routes.auto «unlimited»). [skip]: ids the app can't run.
    static func autoServer(_ servers: [Server], skip: Set<String> = [], prefer: RemoteConfig.AutoPick = .best) -> Server? {
        let regular = servers.filter { !ServerGroup.isBypass($0) && !ServerGroup.isHidden($0) && !skip.contains($0.id) }
        let best = regular.first { RouteName.isBest($0.name.splitFlag.1) }
        let unlimited = regular.first(where: ServerGroup.isUnlimited)
        return (prefer == .unlimited ? unlimited ?? best : best ?? unlimited) ?? regular.first
    }

    /// «Авто · объезды»: the panel's own bypass АВТО (a bypass with «авто» in its name) if there is
    /// one. Else a bypass the app picks by the list's cold check (Проверить дороги; [pings] in ms,
    /// -1 for no answer): of those that answered, the quickest free one (not ServerGroup.isLimited)
    /// whose road isn't «пробки» (RouteTraffic), else the quickest of all; with none answered, the
    /// first free bypass, else the first bypass. [skip]: ids the app can't run. «пробки» by the
    /// panel's thresholds (RemoteConfig.roads), as the list says it.
    static func bypassServer(_ servers: [Server], pings: [String: Int], skip: Set<String> = []) -> Server? {
        let bypass = servers.filter { ServerGroup.isBypass($0) && !ServerGroup.isHidden($0) && !skip.contains($0.id) }
        if let own = bypass.first(where: { $0.name.lowercased().contains("авто") }) { return own }
        var answered: [(server: Server, ms: Int)] = []
        for s in bypass {
            if let ms = pings[s.id], ms >= 0 { answered.append((s, ms)) }
        }
        let clear = answered.filter { !ServerGroup.isLimited($0.server) && RouteTraffic.level($0.ms) != .jam }
        if let best = clear.min(by: { $0.ms < $1.ms }) ?? answered.min(by: { $0.ms < $1.ms }) { return best.server }
        return bypass.first { !ServerGroup.isLimited($0) } ?? bypass.first
    }
}
