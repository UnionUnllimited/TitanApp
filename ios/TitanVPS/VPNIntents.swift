import AppIntents
import Foundation

/// Siri and Команды: Titan VPS on, off and the other way round, without opening the app.
/// They run in the app's process (in the background if it isn't open) and work with the VPN
/// profile through TunnelSwitch, as Пункт управления's toggle does (SetVPNIntent); on comes on
/// the server picked in the app.
struct TurnOnVPNIntent: AppIntent {
    static var title: LocalizedStringResource = "Включить Titan VPS"
    static var description = IntentDescription("Подключает VPN к серверу, выбранному в приложении.")
    static var openAppWhenRun = false

    func perform() async throws -> some IntentResult & ProvidesDialog {
        if await TunnelSwitch.isOn() { return .result(dialog: "Titan VPS уже включён") }
        let name = try await VPNIntents.turnOn()
        return .result(dialog: "Titan VPS включён: \(name)")
    }
}

struct TurnOffVPNIntent: AppIntent {
    static var title: LocalizedStringResource = "Выключить Titan VPS"
    static var description = IntentDescription("Отключает VPN — сам он не включится, пока его не включат снова.")
    static var openAppWhenRun = false

    func perform() async throws -> some IntentResult & ProvidesDialog {
        let wasOn = await TunnelSwitch.isOn()
        // Off for a ping in the app: it would have come back after it.
        let paused = await MainActor.run { AppStore.current?.vpnPaused == true }
        // Off anyway: Не отключать VPN doesn't bring it back, nor does the ping's end.
        await VPNIntents.turnOff()
        if wasOn || paused { return .result(dialog: "Titan VPS выключен") }
        return .result(dialog: "Titan VPS уже выключен")
    }
}

struct ToggleVPNIntent: AppIntent {
    static var title: LocalizedStringResource = "Переключить Titan VPS"
    static var description = IntentDescription("Включает VPN, если он выключен, и выключает, если включён.")
    static var openAppWhenRun = false

    func perform() async throws -> some IntentResult & ProvidesDialog {
        if await TunnelSwitch.isOn() {
            await VPNIntents.turnOff()
            return .result(dialog: "Titan VPS выключен")
        }
        let name = try await VPNIntents.turnOn()
        return .result(dialog: "Titan VPS включён: \(name)")
    }
}

/// "Привет, Siri, включи Titan VPS" and the app's actions in Команды.
struct TitanShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: TurnOnVPNIntent(), phrases: [
            "Включи \(.applicationName)",
            "Включить \(.applicationName)",
            "Подключи \(.applicationName)",
            "Включи VPN в \(.applicationName)",
        ])
        AppShortcut(intent: TurnOffVPNIntent(), phrases: [
            "Выключи \(.applicationName)",
            "Выключить \(.applicationName)",
            "Отключи \(.applicationName)",
            "Выключи VPN в \(.applicationName)",
        ])
        AppShortcut(intent: ToggleVPNIntent(), phrases: [
            "Переключи \(.applicationName)",
            "Переключить \(.applicationName)",
        ])
    }
}

// Run in the background, these may start the Live Activity too (iOS 18+, as the activity).
@available(iOS 18.0, *)
extension TurnOnVPNIntent: LiveActivityIntent {}

@available(iOS 18.0, *)
extension ToggleVPNIntent: LiveActivityIntent {}

enum VPNIntents {
    /// Returns the server's name without its flag (for Siri to say).
    static func turnOn() async throws -> String {
        // Пункт управления's toggle then shows what the VPN really did, failed or not: run in the
        // background (Siri, Команды), the app has no screen to see the change.
        defer { VPNControl.reload() }
        // The activity's «Выключено» says "Подключаемся…" at once: the tunnel takes a few seconds.
        await LiveActivityController.comingOn()
        do {
            let name = try await TunnelSwitch.start(picked(), onDemand: VPNController.keepAlive,
                                                    bypassOnWifi: UserDefaults.standard.bool(forKey: TechMode.key))
            await LiveActivityController.turnedOn()
            return name.splitFlag.1
        } catch {
            // Didn't come up: «Выключено» again.
            if !(await TunnelSwitch.isOn()) { await LiveActivityController.turnedOff() }
            throw error
        }
    }

    @MainActor
    static func turnOff() async {
        defer { VPNControl.reload() }
        // A switch of the activity's ‹ › (or the app's connect) under way stays off too.
        switchGeneration += 1
        offs += 1
        switchingTo = nil
        VPNController.current?.cancelConnects()
        await TunnelSwitch.stop()
        // The activity stays: «Выключено», its ⏻ turns the VPN on again.
        await LiveActivityController.turnedOff()
    }

    // MARK: The Live Activity's ‹ ›

    /// The server asked for last while switches are under way: a quick next tap goes on from it.
    @MainActor private static var switchingTo: String?
    @MainActor private static var switchesInFlight = 0
    /// Bumped on each tap and on off: a switch still under way gives way to the newest (or stops).
    @MainActor private static var switchGeneration = 0
    @MainActor private static var switchTask: Task<Void, Never>?
    /// Bumped on off only: a background switch already starting the tunnel stops it again.
    @MainActor private static var offs = 0

    /// The previous or the next server (SwitchServerIntent): in the current one's tab (Серверы
    /// or Обходы) as Главная lists it, round the end; on Wi-Fi from a bypass server to a regular
    /// one, as in the app.
    /// Through the app's own objects when they are alive (its screen follows), else with the
    /// VPN profile as Siri does. VPN off: only the pick moves, and the activity shows it.
    @MainActor
    static func switchServer(forward: Bool) async {
        defer { VPNControl.reload() }
        let defaults = UserDefaults.standard
        guard let all = (AppStore.current?.subscription ?? savedSubscription())?.servers else { return }
        let servers = all.filter { !ServerGroup.isHidden($0) }
        // The slow reads first: from here to the pick nothing waits, so each of quick taps
        // steps one server further.
        let on = await TunnelSwitch.isOn()
        let running = await TunnelSwitch.serverName()
        var wifiLocked = false
        if servers.contains(where: ServerGroup.isBypass) { wifiLocked = await bypassLockedOnWifi() }

        let store = AppStore.current
        // A ping holds the VPN off and brings it back by itself.
        if let store, store.pingBusy || store.vpnPaused { return }
        // From the server asked for last, else the one the tunnel runs, else the pick.
        let asked = switchingTo
        let pickedId = store?.selectedId ?? defaults.string(forKey: "titan.selected")
        var found = servers.first(where: { $0.id == asked })
        if found == nil, on { found = servers.first(where: { $0.name == running }) }
        if found == nil { found = servers.first(where: { $0.id == pickedId }) ?? servers.first }
        guard let current = found else { return }
        let bypass = ServerGroup.isBypass(current)
        let favorites = store?.favorites ?? Set(defaults.stringArray(forKey: "titan.favorites") ?? [])
        let next: Server
        if bypass && wifiLocked {
            // On a bypass server on Wi-Fi (joined while the app was closed): off it to a regular
            // one, as the app moves (AppStore.firstRegular: a favourite first).
            let regular = ServerGroup.ordered(servers.filter { !ServerGroup.isBypass($0) }, favorites: favorites)
            guard let to = forward ? regular.first : regular.last else { return }
            next = to
        } else {
            let list = ServerGroup.ordered(servers.filter { ServerGroup.isBypass($0) == bypass }, favorites: favorites)
            guard list.count > 1, let i = list.firstIndex(where: { $0.id == current.id }) else { return }
            next = list[(i + (forward ? 1 : list.count - 1)) % list.count]
        }

        if let store { store.select(next.id) } else { defaults.set(next.id, forKey: "titan.selected") }
        // Off: only the pick. A switch under way has the tunnel down for a moment: still on.
        guard on || switchesInFlight > 0 else {
            // «Выключено» with the new pick (⏻ connects to it); one left saying "Подключено" by a
            // VPN that went off without the app (iOS Settings, the tunnel fell) says so now. Not
            // while the app reconnects: it comes up on the server it is connecting to.
            if VPNController.current?.connectsUnderWay != true { await LiveActivityController.picked() }
            return
        }
        switchingTo = next.id
        switchGeneration += 1
        let gen = switchGeneration
        switchesInFlight += 1
        defer {
            switchesInFlight -= 1
            if switchesInFlight == 0 { switchingTo = nil }
        }
        await LiveActivityController.switching(to: next.name)
        // One at a time: each waits for the one before and goes on only if it is still the last.
        let previous = switchTask
        let task = Task { @MainActor in
            await previous?.value
            guard gen == switchGeneration else { return }
            if let vpn = VPNController.current {
                await switchInApp(vpn, to: next, gen: gen)
            } else {
                await switchProfile(to: next, gen: gen)
            }
        }
        switchTask = task
        await task.value
        if gen == switchGeneration { switchTask = nil }
    }

    /// As a tap on the server in Главная: VPNController reconnects and the app's screen follows.
    @MainActor private static func switchInApp(_ vpn: VPNController, to server: Server, gen: Int) async {
        await vpn.loaded()
        // Turned off meanwhile: switchTo / connect would want it on again.
        guard gen == switchGeneration else { return }
        // Down between two quick taps: on again on this one.
        if vpn.isOn { await vpn.switchTo(server) } else { await vpn.connect(server) }
        // Up before iOS may suspend the app again, so the activity says so (no screen may be
        // watching the VPN to tell it).
        for i in 0..<60 {
            guard gen == switchGeneration, !vpn.isConnected else { break }
            // Fell: with the tunnel's reason, or plain off a second after the start.
            if case .error = vpn.state { break }
            if i >= 5, case .off = vpn.state { break }
            try? await Task.sleep(nanoseconds: 200_000_000)
        }
        await settle(gen)
    }

    /// Launched in the background for the tap: the VPN profile, as Siri turns it on.
    @MainActor private static func switchProfile(to server: Server, gen: Int) async {
        let onDemand = VPNController.keepAlive
        let bypassOnWifi = UserDefaults.standard.bool(forKey: TechMode.key)
        // Can't be run: the VPN stays as it is, the activity back to what runs.
        guard runnable(server) else {
            await settle(gen)
            return
        }
        await TunnelSwitch.stop()
        await TunnelSwitch.waitUntilDown()
        // The network's own routes and DNS back first (as VPNController.switchTo waits): the new
        // server's host is resolved by the phone's network, not the old tunnel.
        try? await Task.sleep(nanoseconds: 700_000_000)
        // A newer tap (or an off) takes it from here.
        guard gen == switchGeneration else { return }
        let offsBefore = offs
        // Fell on the way: settle shows the activity off with the VPN.
        if let config = try? target(server) {
            _ = try? await TunnelSwitch.start(config, onDemand: onDemand, bypassOnWifi: bypassOnWifi)
        } else {
            // Not built after all: the profile's last server again.
            _ = try? await TunnelSwitch.start(nil, onDemand: onDemand, bypassOnWifi: bypassOnWifi)
        }
        // Turned off while the profile was saved: stays off (and Не отключать VPN with it).
        // Not on a newer tap: that one restarts the tunnel itself.
        if offs != offsBefore {
            await TunnelSwitch.stop()
            return
        }
        await settle(gen)
    }

    /// What target needs no network for (a plugin's host is resolved there).
    private static func runnable(_ server: Server) -> Bool {
        server.xrayJson != nil && server.proxyTag != nil
            && (Plugins.endpoint(server).map { $0.kind.supportedOnIOS } ?? true)
    }

    /// A switch is over: the activity shows the server up, or «Выключено» if the VPN is off.
    /// Not when a newer tap or an off has taken over since: that one sees to the activity.
    @MainActor private static func settle(_ gen: Int) async {
        let on = await TunnelSwitch.isOn()
        guard gen == switchGeneration else { return }
        if on { await LiveActivityController.turnedOn() } else { await LiveActivityController.turnedOff() }
    }

    /// On Wi-Fi bypass servers are off limits, except in Технический режим (AppStore.bypassLockedOnWifi).
    @MainActor private static func bypassLockedOnWifi() async -> Bool {
        if let store = AppStore.current { return store.bypassLockedOnWifi }
        if UserDefaults.standard.bool(forKey: TechMode.key) { return false }
        return await TunnelSwitch.onWifi()
    }

    /// The subscription as the app saved it last (AppStore keeps it under this key).
    private static func savedSubscription() -> Subscription? {
        guard let data = UserDefaults.standard.data(forKey: "titan.subscription") else { return nil }
        return try? JSONDecoder().decode(Subscription.self, from: data)
    }

    /// The server picked in the app (AppStore keeps the pick under this key), with its config.
    private static func picked() throws -> TunnelSwitch.Target {
        guard let sub = savedSubscription() else {
            throw TunnelSwitchError("Добавьте ключ подписки в приложении Titan VPS")
        }
        guard let server = pickedServer(in: sub) else {
            throw TunnelSwitchError("В подписке нет серверов. Обновите её в приложении Titan VPS")
        }
        return try target(server)
    }

    private static func pickedServer(in sub: Subscription) -> Server? {
        let id = UserDefaults.standard.string(forKey: "titan.selected")
        return sub.servers.first(where: { $0.id == id }) ?? sub.servers.first
    }

    /// The name (with its flag) of the server turnOn starts: what the Live Activity shows while
    /// the VPN is off. The store's pick while the app runs, else the saved one.
    @MainActor static var pickedName: String? {
        if let store = AppStore.current, store.subscription != nil { return store.selected?.name }
        return savedSubscription().flatMap { pickedServer(in: $0) }?.name
    }

    /// A server's config for the tunnel, built as VPNController.connect builds it.
    private static func target(_ server: Server) throws -> TunnelSwitch.Target {
        let prepared = try Plugins.prepare(server, forTunnel: true)
        var provider: [String: Any] = ["config": try XrayRunConfig.build(serverJson: prepared.json),
                                       "source": VPNController.source(server)]
        if let plugin = prepared.plugin { provider["plugin"] = plugin }
        return TunnelSwitch.Target(name: server.name, provider: provider)
    }
}
