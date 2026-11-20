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
    /// «Авто» picked over the «Авто · объезды» the mobile internet check switched to
    /// (AppStore.detectionDeclined): kept through a restart of the app.
    static let declinedKey = "titan.detectionDeclined"

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

    /// «Авто · объезды»: a free bypass (not ServerGroup.isLimited: it spends no GB) while there is
    /// one left to try, as the app goes there by itself too (the mobile internet check, the
    /// reconnect after Проверить дороги, «Поехать объездом»); one that spends GB only once every
    /// free one was checked and didn't answer. In each of the two: see best(). None answered at
    /// all: the first free bypass, else the first bypass. [skip]: ids the app can't run.
    static func bypassServer(_ servers: [Server], pings: [String: Int], skip: Set<String> = []) -> Server? {
        let bypass = servers.filter { ServerGroup.isBypass($0) && !ServerGroup.isHidden($0) && !skip.contains($0.id) }
        let free = bypass.filter { !ServerGroup.isLimited($0) }
        let paid = bypass.filter { ServerGroup.isLimited($0) }
        return best(free, pings: pings) ?? best(paid, pings: pings) ?? free.first ?? bypass.first
    }

    /// Of [servers], those not checked yet or that answered the list's cold check (Проверить
    /// дороги; [pings] in ms, -1 for no answer): the panel's own bypass АВТО (a bypass with «авто»
    /// in its name), else the quickest that answered with its road not «пробки» (RouteTraffic, by
    /// the panel's thresholds, RemoteConfig.roads, as the list says it), else the quickest that
    /// answered, else the first not checked yet. Nil when each one was checked and none answered.
    private static func best(_ servers: [Server], pings: [String: Int]) -> Server? {
        let left = servers.filter { (pings[$0.id] ?? 0) >= 0 }
        if let own = left.first(where: { $0.name.lowercased().contains("авто") }) { return own }
        var answered: [(server: Server, ms: Int)] = []
        for s in left {
            if let ms = pings[s.id] { answered.append((s, ms)) }
        }
        let clear = answered.filter { RouteTraffic.level($0.ms) != .jam }
        if let quickest = clear.min(by: { $0.ms < $1.ms }) ?? answered.min(by: { $0.ms < $1.ms }) { return quickest.server }
        return left.first
    }
}
