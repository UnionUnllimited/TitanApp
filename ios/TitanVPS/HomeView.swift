import SwiftUI

/// Маршрут (design C «Чистый маршрут», docs/HANDOFF.md): the map with the state as its title and
/// the route drawn to the picked direction; under it the sheet with that direction, «Поехали»
/// and the list. On the way the trip card is over the map instead, the list still under it. One
/// ScrollView (one per column in a wide window), so pull to refresh, Наверх and the status
/// bar's tap to the top work as before; the map is a picture behind it, nothing on it is dragged.
struct HomeView: View {
    @EnvironmentObject var store: AppStore
    @EnvironmentObject var vpn: VPNController
    /// Why a ping or a check failed is shown in Технический режим only.
    @AppStorage(TechMode.key) private var techMode = false
    /// Технический режим's techniques per server (TechniqueStore): their rows say «приёмы».
    @AppStorage(TechniqueStore.key) private var techniquesData = Data()
    @State private var page: ServerGroup = .servers
    @State private var wifiInfo = false
    /// A server row is being dragged sideways: the page stays still.
    @State private var holdingDrag = false
    /// Speed while connected (read only while Маршрут is on screen). Held without observing it:
    /// only the trip card's tiles redraw each second, not all of Маршрут.
    @State private var traffic = TrafficMonitor()
    /// Наверх: shown once the list is about a screen down.
    @State private var showTop = false
    /// When the trip on screen began: kept while the route is built again to another direction
    /// (connecting from on the way), so the trip card stays through a switch.
    @State private var tripSince: Date?
    /// Маршрут is on screen (not another tab): the map's pulse runs only then.
    @State private var onScreen = false
    @Environment(\.openURL) private var openURL
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    var openSettings: () -> Void
    /// Bumped by a tap on Маршрут in the tab bar while it is on screen: back to the top.
    var toTop = 0

    /// The scroll content's top (the list's, and the map column's in a wide window), the list's
    /// own top (under the picked route), and the list's ScrollView, which its depth is read against.
    private static let top = "home.top"
    private static let controlsTop = "home.controlsTop"
    private static let listTop = "home.listTop"
    private static let listSpace = "home.list"
    /// How far the sheet comes up over the map's lower edge.
    private static let sheetLift: CGFloat = 24

    var body: some View {
        GeometryReader { geo in
            let wide = geo.size.width >= 720
            ScrollViewReader { proxy in
                Group {
                    if wide { wideLayout(geo, proxy) } else { narrowLayout(geo, proxy) }
                }
                // A tap on Маршрут in the tab bar while on it.
                .onChange(of: toTop) { _ in scrollUp(proxy, wide: wide) }
            }
        }
        .onAppear {
            onScreen = true
            noteTrip(vpn.state)
            if vpn.isConnected { traffic.start() }
        }
        .onDisappear {
            onScreen = false
            traffic.stop()
            // Changes while away aren't followed: back on screen, the trip starts from the state then.
            tripSince = nil
        }
        .onChange(of: vpn.isConnected) { connected in
            if connected { traffic.start() } else { traffic.stop() }
        }
        .onChange(of: vpn.state) { noteTrip($0) }
        .alert("Недоступно на Wi-Fi", isPresented: $wifiInfo) {
            Button("Понятно", role: .cancel) {}
        } message: {
            Text("Объезды работают только через мобильный интернет и нужны при его ограничениях. На Wi-Fi по ним не проехать — выберите направление во вкладке «Направления».")
        }
        // Wi-Fi came while on a bypass server: move to a regular one (same as Android).
        .onChange(of: store.onWifi) { wifi in
            guard wifi, vpn.isOn, store.blockedOnWifi(store.selected), let to = store.firstRegular else { return }
            store.select(to.id)
            page = .servers
            if !store.resumingAfterPing { Task { await vpn.switchTo(to) } }
            store.message = "Объезды работают только через мобильный интернет. Перестроили маршрут на «\(RouteName.shown(to.name.splitFlag.1))»."
        }
    }

    // MARK: - Layouts

    /// A phone: the map, then the sheet over its lower edge with the list, all in one ScrollView.
    private func narrowLayout(_ geo: GeometryProxy, _ proxy: ScrollViewProxy) -> some View {
        ScrollView {
            VStack(spacing: 0) {
                mapHeader(proxy, wide: false)
                sheet(proxy)
            }
            .animation(.spring(response: 0.45, dampingFraction: 0.85), value: tripMode)
            .frame(maxWidth: 640)
            .frame(maxWidth: .infinity)
            .backgroundPreferenceValue(RouteBandKey.self) { mapArt($0) }
            .background(depth(screen: geo.size.height))
            .id(Self.top)
        }
        .coordinateSpace(name: Self.listSpace)
        .scrollDisabled(holdingDrag)
        .refreshable { await store.refreshFromPull() }
        .overlay(alignment: .bottomTrailing) { topButton(proxy, wide: false) }
        // The map's ground at the top (under the status bar too), the sheet's below: a short
        // list doesn't end the sheet in the middle of the screen, nor the floating tab bar.
        .background {
            VStack(spacing: 0) {
                RouteTone.paper
                RouteTone.sheet
            }
            .ignoresSafeArea()
        }
    }

    /// A desktop-sized window: the map with the picked route (or the trip card) in a column of
    /// its own, the list beside it on the sheet's white; each scrolls by itself.
    private func wideLayout(_ geo: GeometryProxy, _ proxy: ScrollViewProxy) -> some View {
        let mapWidth = min(max(geo.size.width * 0.44, 360), 520)
        return HStack(alignment: .top, spacing: 0) {
            ScrollView {
                mapHeader(proxy, wide: true)
                    .animation(.spring(response: 0.45, dampingFraction: 0.85), value: tripMode)
                    .backgroundPreferenceValue(RouteBandKey.self) { mapArt($0) }
                    .id(Self.controlsTop)
            }
            .scrollDisabled(holdingDrag)
            .frame(width: mapWidth)
            ScrollView {
                list(proxy, wide: true)
                    .padding(16)
                    .padding(.bottom, ScrollTopButton.room)
                    .frame(maxWidth: 680)
                    .frame(maxWidth: .infinity)
                    .background(depth(screen: geo.size.height))
                    .id(Self.top)
            }
            .coordinateSpace(name: Self.listSpace)
            .scrollDisabled(holdingDrag)
            .refreshable { await store.refreshFromPull() }
            .overlay(alignment: .bottomTrailing) { topButton(proxy, wide: true) }
            .background(RouteTone.sheet.ignoresSafeArea())
            .overlay(alignment: .leading) { RouteTone.hair.frame(width: 1).ignoresSafeArea() }
        }
        .background(RouteTone.paper.ignoresSafeArea())
    }

    /// The map picture behind the content, laid by where the route band is (RouteMapArt).
    private func mapArt(_ anchor: Anchor<CGRect>?) -> some View {
        GeometryReader { g in
            if let anchor { RouteMapArt(band: g[anchor]) }
        }
    }

    /// The sheet over the map's lower edge: the picked route and «Поехали» while standing, then
    /// the list. Glass where it lies over the map, solid under it (GlassSheet).
    private func sheet(_ proxy: ScrollViewProxy) -> some View {
        VStack(spacing: 18) {
            if !tripMode {
                parkedControls(proxy)
                    .transition(.opacity)
            }
            list(proxy, wide: false)
        }
        .padding(.horizontal, 16)
        .padding(.top, 22)
        // Room under the last row for Наверх: at the list's end it covers none.
        .padding(.bottom, 16 + ScrollTopButton.room)
        .frame(maxWidth: .infinity)
        .glassSheet()
        .padding(.top, -Self.sheetLift)
    }

    /// Whether the list is about a screen down (Наверх then), read as it scrolls: only a change
    /// of that redraws Маршрут.
    private func depth(screen: CGFloat) -> some View {
        GeometryReader { g in
            let down = -g.frame(in: .named(Self.listSpace)).minY > screen
            Color.clear
                // A new list (the window crossed into the other layout) has no change to report
                // for where it starts: read once as it comes.
                .onAppear { showTop = down }
                .onChange(of: down) { now in
                    withAnimation(.easeInOut(duration: 0.2)) { showTop = now }
                }
        }
    }

    /// Наверх in the list's corner above the tab bar, once it is a screen down.
    @ViewBuilder private func topButton(_ proxy: ScrollViewProxy, wide: Bool) -> some View {
        if showTop {
            ScrollTopButton { scrollUp(proxy, wide: wide) }
                .padding(.trailing, 16)
                .padding(.bottom, 12)
                .transition(.opacity.combined(with: .scale(scale: 0.8)))
        }
    }

    /// Back to the top, animated (both columns in a wide window).
    private func scrollUp(_ proxy: ScrollViewProxy, wide: Bool) {
        withAnimation(.easeInOut(duration: 0.35)) {
            proxy.scrollTo(Self.top, anchor: .top)
            if wide { proxy.scrollTo(Self.controlsTop, anchor: .top) }
        }
    }

    /// To the list's tabs (the picked route's card, «Сменить направление»).
    private func scrollToList(_ proxy: ScrollViewProxy) {
        withAnimation(.easeInOut(duration: 0.35)) { proxy.scrollTo(Self.listTop, anchor: .top) }
    }

    // MARK: - State

    /// Off for a ping: the VPN comes back by itself after it.
    private var paused: Bool { store.vpnPaused && !vpn.isOn }

    /// On the way: the trip card over the map instead of «Поехали» on the sheet; through a
    /// switch to another direction too (connecting again while a trip is on screen).
    private var tripMode: Bool {
        guard !paused else { return false }
        switch vpn.state {
        case .connected: return true
        case .connecting: return tripSince != nil
        case .off, .disconnecting, .error: return false
        }
    }

    private func noteTrip(_ state: VPNController.State) {
        switch state {
        case .connected(let since): tripSince = since
        case .connecting: break
        case .off, .disconnecting, .error: tripSince = nil
        }
    }

    /// The endpoint's rings pulse on the way, while Маршрут and the map are on screen.
    private var pulsing: Bool {
        tripMode && vpn.isConnected && onScreen && scenePhase == .active && !reduceMotion && !showTop
    }

    private var statusTitle: String {
        if paused { return "Пауза на время пинга" }
        switch vpn.state {
        case .off, .error: return "Стоим"
        case .connecting: return tripSince != nil ? "Меняем направление…" : "Строим маршрут…"
        case .connected: return store.selected.map { "В пути · " + destination($0, onTheWay: true) } ?? "В пути"
        case .disconnecting: return "Останавливаемся…"
        }
    }

    private var statusColor: Color {
        if paused { return RouteTone.warn }
        if case .connecting = vpn.state { return RouteTone.accentInk }
        return .primary
    }

    /// Where the route goes, as the pin and the title say it: the flag's country, else the name
    /// as shown. «Лучший маршрут» on the way: the country the check came out in, once known.
    private func destination(_ s: Server, onTheWay: Bool = false) -> String {
        let parts = s.name.splitFlag
        if onTheWay, parts.0 == nil, RouteName.isBest(parts.1), case .ok(let r) = store.check,
           let code = r.country, let country = Fmt.country(code) {
            return country
        }
        if let code = Flags.code(parts.0), let country = Flags.country(code) { return country }
        return RouteTile.title(s.name)
    }

    private var pillPhase: RoutePill.Phase {
        // Off for a ping: still shown on (it comes back by itself).
        if paused { return .on }
        switch vpn.state {
        case .connecting: return .connecting
        case .connected: return .on
        case .disconnecting: return .disconnecting
        case .off, .error: return .off
        }
    }

    /// «Поехали» or «Остановиться» tapped: false when it can't be done now (the pill shakes, with
    /// its own error haptic: the notes here have none). Its outcome is felt (VPNController's byUser).
    private func toggleVPN(_ on: Bool) -> Bool {
        if on {
            guard !vpn.isOn else { return true }
            guard let server = store.selected else {
                store.showToast("Выберите направление в списке ниже", .warning, haptic: false)
                return false
            }
            if store.blockedOnWifi(server) {
                store.showToast("Объезды недоступны на Wi-Fi — выберите направление во вкладке «Направления»", .warning, haptic: false)
                return false
            }
            Task { await vpn.connect(server, byUser: true) }
        } else {
            if vpn.isOn { vpn.disconnect(byUser: true) }
        }
        return true
    }

    // MARK: - The map

    /// The top bar, the account's notices, the state as the title, the route band with the round
    /// buttons; on the way the trip card under it (and in a wide window, standing, the picked
    /// route with «Поехали» on a glass card, the sheet being the other column).
    private func mapHeader(_ proxy: ScrollViewProxy, wide: Bool) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            topBar
            notices
            titleBlock
            band
            if tripMode {
                tripCard(proxy)
                    .padding(.horizontal, 16)
                    .transition(.opacity.combined(with: .move(edge: .bottom)))
            } else if wide {
                parkedControls(proxy)
                    .padding(16)
                    .glassSurface(.card, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
                    .padding(.horizontal, 16)
                    .transition(.opacity)
            }
        }
        // Room for the cards' shadow, and for the sheet coming up over the map.
        .padding(.bottom, wide ? 28 : Self.sheetLift + (tripMode ? 22 : 30))
    }

    private var topBar: some View {
        HStack(spacing: 10) {
            Image("Logo").resizable().scaledToFit().frame(width: 30, height: 30)
                .accessibilityHidden(true)
            Text("Titan").font(.scaled(20, weight: .bold))
            Spacer(minLength: 8)
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }

    /// The account blocked or under maintenance (a tap: support), the pass run out.
    @ViewBuilder private var notices: some View {
        let account = accountNotice
        let expired = store.subscription.map { $0.info.expire > 0 && $0.info.expire <= Int64(Date().timeIntervalSince1970) } ?? false
        if account != nil || expired {
            VStack(spacing: 8) {
                if let account {
                    Button { openURL(store.supportURL) } label: {
                        HStack(spacing: 10) {
                            Image(systemName: "exclamationmark.triangle.fill")
                            Text(account.text).multilineTextAlignment(.leading)
                            Spacer(minLength: 0)
                        }
                        .font(.scaled(14, weight: .semibold))
                        .foregroundStyle(.white)
                        .padding(14)
                        .background(account.blocked ? RouteTone.alarmFill : RouteTone.noticeFill,
                                    in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                    }
                    .buttonStyle(PressableStyle())
                }
                // The pass's details are on Проездной; here only that it has run out.
                if expired { expiredBanner }
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)
        }
    }

    private var accountNotice: (text: String, blocked: Bool)? {
        switch store.account?.status {
        case "BLOCKED"?: return ("Доступ к аккаунту закрыт. Напишите в поддержку", true)
        case "MAINTENANCE"?: return ("Идут технические работы. Маршруты могут не работать", false)
        default: return nil
        }
    }

    #if TITAN_PAYMENTS
    /// One line: a tap opens Тарифы.
    private var expiredBanner: some View {
        Button { store.paywall = .expired } label: {
            HStack(spacing: 8) {
                Image(systemName: "exclamationmark.circle.fill")
                Text("Проездной закончился").lineLimit(1)
                Spacer(minLength: 8)
                Text("Продлить").fontWeight(.bold).lineLimit(1)
                Image(systemName: "chevron.right").font(.scaled(12, weight: .bold))
            }
            .font(.scaled(14, weight: .semibold))
            .foregroundStyle(RouteTone.danger)
            .padding(.horizontal, 14)
            .frame(minHeight: 44)
            .background(RouteTone.badFill, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .buttonStyle(PressableStyle())
        .accessibilityLabel("Проездной закончился. Продлить")
    }
    #else
    /// One line, the same look: only that it has run out (no payments in this build,
    /// docs/HANDOFF.md), nothing to tap.
    private var expiredBanner: some View {
        HStack(spacing: 8) {
            Image(systemName: "exclamationmark.circle.fill")
            Text("Проездной закончился").lineLimit(1)
            Spacer(minLength: 0)
        }
        .font(.scaled(14, weight: .semibold))
        .foregroundStyle(RouteTone.danger)
        .padding(.horizontal, 14)
        .frame(minHeight: 44)
        .background(RouteTone.badFill, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Проездной закончился")
    }
    #endif

    /// «Стоим», «В пути · Финляндия»…, the pause's line, the error in red.
    private var titleBlock: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(statusTitle)
                .font(.scaled(34, weight: .heavy))
                .foregroundStyle(statusColor)
                // A long direction («В пути · Лучший маршрут · Европа»): two lines, smaller.
                .lineLimit(2)
                .minimumScaleFactor(0.6)
                .fixedSize(horizontal: false, vertical: true)
                .animation(.easeInOut(duration: 0.25), value: statusTitle)
                .accessibilityAddTraits(.isHeader)
            if paused {
                Text("Поедем дальше сами, когда пинг закончится")
                    .font(.scaled(15, weight: .medium)).foregroundStyle(RouteTone.ink2)
            }
            if case .error(let message) = vpn.state {
                Text(message)
                    .font(.scaled(14, weight: .medium)).foregroundStyle(RouteTone.danger)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 20)
        .padding(.top, 14)
    }

    /// The route drawn over the map (RouteBand, in the picture's units: its width sets its
    /// height), Поддержка and Обновить проездной as the map's round buttons on its right.
    private var band: some View {
        let s = store.selected
        return RouteBand(mode: tripMode ? .driving : .parked, server: s?.name,
                         destination: s.map { destination($0, onTheWay: tripMode) } ?? "",
                         pulsing: pulsing)
            .aspectRatio(RouteMap.width / RouteMap.bandHeight, contentMode: .fit)
            .anchorPreference(key: RouteBandKey.self, value: .bounds) { $0 }
            .overlay { mapButtons }
    }

    /// Where design C has its compass and locate buttons: right of the destination, clear of it.
    private var mapButtons: some View {
        GeometryReader { g in
            let s = g.size.width / RouteMap.width
            VStack(spacing: 8) {
                mapButton("headphones", label: "Поддержка") { openURL(store.supportURL) }
                mapButton("arrow.clockwise", spinning: store.busy, label: "Обновить проездной") {
                    Task { await store.refreshByHand() }
                }
                .disabled(store.busy)
            }
            .position(x: 351 * s, y: (283 - RouteMap.bandTop) * s)
        }
    }

    private func mapButton(_ icon: String, spinning: Bool = false, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            SpinningIcon(name: icon, spinning: spinning)
                .font(.scaled(17, weight: .semibold))
                .foregroundStyle(RouteTone.accentInk)
                .frame(width: 44, height: 44)
                .glassSurface(.chip, in: Circle())
                .contentShape(Circle())
        }
        .buttonStyle(PressableStyle(scale: 0.9))
        .accessibilityLabel(label)
    }

    // MARK: - Standing

    /// The picked route and «Поехали» under it.
    private func parkedControls(_ proxy: ScrollViewProxy) -> some View {
        VStack(spacing: 14) {
            routeCard(proxy)
            pill
        }
    }

    private var pill: some View {
        RoutePill(phase: pillPhase, locked: store.pingBusy, lockedText: paused ? "Пауза" : "Пинг…",
                  failures: vpn.failures, onToggle: toggleVPN,
                  onLockedTap: { store.showToast("Идёт пинг направлений — кнопка вернётся через пару секунд", .info) })
    }

    /// The picked direction (design C's «Лучший маршрут» card): its tile, name, what it is or how
    /// its road is, its Пинг. A tap: down to the list, to pick another.
    private func routeCard(_ proxy: ScrollViewProxy) -> some View {
        let s = store.selected
        let name = s.map { RouteTile.title($0.name) } ?? "Выберите направление"
        let note = s.flatMap { routeNote($0) }
        let ping = s.flatMap { store.pings[$0.id] }
        let pingSpoken = ping.map { $0 >= 0 ? ", пинг \($0) миллисекунд" : ", пинг: таймаут" } ?? ""
        let spoken = "Куда едем: " + name + (note.map { ", " + $0 } ?? "") + pingSpoken
        return Button { scrollToList(proxy) } label: {
            HStack(spacing: 12) {
                if let s {
                    RouteTile(server: s.name, size: 48)
                } else {
                    Image(systemName: "signpost.right.fill")
                        .font(.scaled(19, weight: .semibold))
                        .foregroundStyle(RouteTone.accentInk)
                        .frame(width: 48, height: 48)
                        .background(Glass.well, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(name)
                        .font(.scaled(17, weight: .semibold))
                        .foregroundStyle(.primary)
                        .lineLimit(1).minimumScaleFactor(0.75)
                    if let note {
                        Text(note)
                            .font(.scaled(13, weight: .medium))
                            .foregroundStyle(RouteTone.ink2)
                            .lineLimit(2)
                    }
                }
                Spacer(minLength: 6)
                if let s {
                    PingChip(ping: ping, pending: store.measuring.contains(s.id))
                }
                Image(systemName: "chevron.right")
                    .font(.scaled(13, weight: .bold))
                    .foregroundStyle(RouteTone.ink3)
            }
            .padding(12)
            .background(RouteTone.well, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        }
        .buttonStyle(PressableStyle(scale: 0.98))
        .accessibilityLabel(spoken)
        .accessibilityHint("Список направлений")
    }

    /// Under the picked direction's name: what it is, else how its road is (its Пинг in words).
    private func routeNote(_ s: Server) -> String? {
        if ServerGroup.isUnlimited(s) { return "без ограничений" }
        if RouteName.isBest(s.name.splitFlag.1) { return "Сам выбирает быстрое направление" }
        let road = RouteTraffic.word(store.pings[s.id])
        if ServerGroup.isLimited(s) { return "Объезд · расходует ГБ" + (road.map { " · " + $0 } ?? "") }
        if ServerGroup.isBypass(s) { return "Объезд" + (road.map { " · " + $0 } ?? "") }
        return road
    }

    // MARK: - On the way

    /// The trip card over the map (design C): time on the way and the direction, the clock, speed
    /// both ways and the road, Проверить маршрут, «Остановиться», and the way to the list.
    private func tripCard(_ proxy: ScrollViewProxy) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) {
                    liveLabel
                    Spacer(minLength: 8)
                    routeChip
                }
                VStack(alignment: .leading, spacing: 8) {
                    liveLabel
                    routeChip
                }
            }
            if case .connected(let since) = vpn.state {
                TripTimer(since: since)
            } else {
                HStack(spacing: 10) {
                    ProgressView()
                    Text("Меняем направление…").font(.scaled(19, weight: .semibold)).lineLimit(1).minimumScaleFactor(0.7)
                }
                .frame(minHeight: 55, alignment: .leading)
            }
            Rectangle().fill(RouteTone.hair).frame(height: 1)
            TripStats(monitor: traffic, check: store.check)
            if vpn.isConnected { routeCheck }
            pill
            Button { scrollToList(proxy) } label: {
                Label("Сменить направление", systemImage: "signpost.right.fill")
                    .font(.scaled(15, weight: .semibold))
                    .foregroundStyle(RouteTone.accentInk)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(PressableStyle())
        }
        .padding(18)
        .glassSurface(.card, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
    }

    private var liveLabel: some View {
        HStack(spacing: 8) {
            Circle().fill(vpn.isConnected ? RouteTone.goodDot : RouteTone.ink3).frame(width: 9, height: 9)
            Text("Время в пути").font(.scaled(15, weight: .semibold)).foregroundStyle(RouteTone.ink2)
        }
        .lineLimit(1)
        .accessibilityHidden(true)
    }

    /// The direction on the way: «✦ Лучший маршрут», else its flag and name.
    @ViewBuilder private var routeChip: some View {
        if let s = store.selected {
            let parts = s.name.splitFlag
            let name = RouteTile.title(s.name)
            HStack(spacing: 6) {
                if parts.0 == nil && RouteName.isBest(parts.1) {
                    Image(systemName: "sparkles").font(.scaled(12, weight: .bold))
                } else {
                    FlagCircle(server: s.name, size: 16)
                }
                Text(name).lineLimit(1)
            }
            .font(.scaled(14, weight: .semibold))
            .foregroundStyle(RouteTone.chipInk)
            .padding(.horizontal, 12)
            .frame(minHeight: 30)
            .background(RouteTone.chipFill, in: Capsule())
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Направление: " + name)
        }
    }

    /// Проверить маршрут (as in Happ): the result of requests over the route, tap to repeat. The
    /// latency itself is on the road tile; here whether it works and from where the sites see you.
    private var routeCheck: some View {
        VStack(alignment: .leading, spacing: 6) {
            Button { store.checkConnection() } label: {
                HStack(spacing: 8) {
                    switch store.check {
                    case .idle:
                        Image(systemName: "waveform.path.ecg").foregroundStyle(RouteTone.accentInk)
                        Text("Проверить маршрут")
                    case .checking:
                        ProgressView().scaleEffect(0.8).frame(width: 18, height: 18)
                        Text("Проверяем маршрут…")
                    case .ok(let r):
                        Circle().fill(Latency.shown(r.ms) != nil || r.ms == nil ? RouteTone.goodDot : RouteTone.midDot)
                            .frame(width: 9, height: 9)
                        // Slow, or Google not measured (Cloudflare answered): it works, no number.
                        Text(r.ms == nil || Latency.shown(r.ms) != nil ? "Маршрут работает" : "Работает · медленно").lineLimit(1)
                        Image(systemName: "arrow.clockwise").font(.scaled(12, weight: .bold)).foregroundStyle(RouteTone.ink3)
                    case .failed:
                        Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(RouteTone.warn)
                        Text("Маршрут не отвечает")
                        Image(systemName: "arrow.clockwise").font(.scaled(12, weight: .bold)).foregroundStyle(RouteTone.ink3)
                    }
                }
                .font(.scaled(14, weight: .semibold))
                .foregroundStyle(.primary)
                .padding(.horizontal, 14)
                .frame(minHeight: 40)
                .background(Glass.well, in: Capsule())
            }
            .buttonStyle(PressableStyle())
            .disabled(store.check == .checking)
            .accessibilityLabel(checkAccessibility)
            switch store.check {
            case .ok(let r):
                // Which country the sites see you in (Cloudflare's), if it said.
                if let code = r.country {
                    HStack(spacing: 6) {
                        Text("Видно как:").foregroundStyle(RouteTone.ink3)
                        if let flag = Fmt.flag(code) {
                            FlagCircle(flag: flag, size: 16, fill: Palette.track, stroke: Palette.outline)
                        }
                        Text(Fmt.country(code) ?? code).foregroundStyle(RouteTone.ink2)
                    }
                    .font(.scaled(13, weight: .medium))
                    .lineLimit(1)
                    .padding(.leading, 6)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("Сайты видят вас как: " + (Fmt.country(code) ?? code))
                }
            case .failed(let why):
                Text(why.isEmpty || !techMode ? "Попробуйте ещё раз или выберите другое направление" : "\(why). Попробуйте другое направление")
                    .font(.scaled(12, weight: .medium)).foregroundStyle(RouteTone.danger)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.leading, 6)
            default:
                EmptyView()
            }
        }
        .animation(.easeInOut(duration: 0.2), value: store.check)
    }

    private var checkAccessibility: String {
        switch store.check {
        case .idle: return "Проверить маршрут"
        case .checking: return "Проверяем маршрут"
        case .ok(let r):
            if let ms = Latency.shown(r.ms) { return "Маршрут работает, \(ms) миллисекунд. Проверить ещё раз" }
            return r.ms == nil ? "Маршрут работает. Проверить ещё раз" : "Маршрут работает, медленно. Проверить ещё раз"
        case .failed: return "Маршрут не отвечает. Проверить ещё раз"
        }
    }

    // MARK: - The list

    /// Направления | Объезды (a group with no servers has no tab), the line about the tab with
    /// Проверить дороги, the Wi-Fi note, then the rows. wide: the tabs' lines on one line when
    /// they fit.
    @ViewBuilder private func list(_ proxy: ScrollViewProxy, wide: Bool) -> some View {
        if let sub = store.subscription {
            let groups = ServerGroup.split(sub.servers)
            // The tab on screen; the first one there is when it has gone (a refreshed pass).
            let current: ServerGroup = groups[page]?.isEmpty == false ? page
                : ServerGroup.allCases.first(where: { groups[$0]?.isEmpty == false }) ?? .servers
            VStack(spacing: 12) {
                // The first that fits at full size; the last one otherwise: the tabs give up
                // their icons («Направления» on a zoomed 320 pt screen, Bold Text), then shrink.
                Group {
                    if wide {
                        ViewThatFits(in: .horizontal) {
                            segment(groups, current, oneLine: true, icons: true)
                            segment(groups, current, oneLine: false, icons: true)
                            segment(groups, current, oneLine: false, icons: false)
                        }
                    } else {
                        ViewThatFits(in: .horizontal) {
                            segment(groups, current, oneLine: false, icons: true)
                            segment(groups, current, oneLine: false, icons: false)
                        }
                    }
                }
                .id(Self.listTop)
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 10) {
                        pageNote(sub, current)
                            .frame(minWidth: 150, idealWidth: 150, maxWidth: .infinity, alignment: .leading)
                        roadsButton(groups, current)
                    }
                    VStack(alignment: .leading, spacing: 8) {
                        pageNote(sub, current)
                        roadsButton(groups, current)
                    }
                }
                if current == .bypass && store.bypassLockedOnWifi {
                    Text("Вы на Wi-Fi. Объезды работают только через мобильный интернет — на Wi-Fi используйте «Направления».")
                        .font(.scaled(13, weight: .medium)).foregroundStyle(RouteTone.warn)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 6)
                }
                rows(groups[current] ?? [], bypassTab: current == .bypass)
            }
        }
    }

    private func segment(_ groups: [ServerGroup: [Server]], _ current: ServerGroup, oneLine: Bool, icons: Bool) -> some View {
        let shown = ServerGroup.allCases.filter { groups[$0]?.isEmpty == false }
        return LiquidSegment(items: shown, selection: current, onSelect: { g in
            guard g != page else { return }
            Haptics.selection()
            withAnimation(Glass.spring(reduceMotion)) { page = g }
        }, spoken: { g in
            "\(g.rawValue), \(ServerGroup.spokenCount(groups[g]?.count ?? 0)), \(g.spokenNetworks)"
        }) { g, on in
            segmentLabel(g, count: groups[g]?.count ?? 0, on: on, oneLine: oneLine, icon: icons)
        }
    }

    /// The name and count, the networks its servers work on under them (beside them on one line).
    /// icon: the tab's sign before the name; without it where the name wouldn't fit.
    private func segmentLabel(_ g: ServerGroup, count: Int, on: Bool, oneLine: Bool, icon: Bool) -> some View {
        let title = HStack(spacing: 6) {
            if icon { Image(systemName: g.icon) }
            Text(g.rawValue).lineLimit(1).minimumScaleFactor(0.6)
            Text("\(count)").font(.scaled(12, weight: .semibold))
                .foregroundStyle(on ? Color.white : RouteTone.ink2)
                .padding(.horizontal, 7).padding(.vertical, 2)
                .background(on ? Palette.primary : Glass.well, in: Capsule())
        }
        let networks = HStack(spacing: 4) {
            Image(systemName: g.networksIcon)
            Text(g.networks).lineLimit(1).minimumScaleFactor(0.7)
        }
        .font(.scaled(oneLine ? 12 : 11, weight: .medium))
        .foregroundStyle(on ? RouteTone.accentInk : RouteTone.ink3)
        return Group {
            if oneLine {
                HStack(spacing: 8) {
                    title
                    networks
                }
            } else {
                VStack(spacing: 2) {
                    title
                    networks
                }
            }
        }
        .font(.scaled(15, weight: .semibold))
        .foregroundStyle(on ? Color.primary : RouteTone.ink2)
        .padding(.horizontal, 4)
    }

    /// What the tab's directions are: no limits on Направления; on Объезды what is left of their
    /// gigabytes (and «Добавить ГБ» with payments), else what they're for.
    private func pageNote(_ sub: Subscription, _ current: ServerGroup) -> some View {
        Group {
            if current == .servers {
                Text("Без ограничений · работают на Wi‑Fi и в мобильной сети")
            } else if sub.info.total > 0 {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Объезды: осталось \(formatBytes(sub.info.remaining)) из \(formatBytes(sub.info.total))")
                    #if TITAN_PAYMENTS
                    // Тарифы (the App Store first).
                    Button("Добавить ГБ") { store.paywall = .traffic }.font(.scaled(13, weight: .semibold))
                    #endif
                }
            } else {
                Text("Для ограничений мобильного интернета")
            }
        }
        .font(.scaled(13, weight: .medium))
        .foregroundStyle(RouteTone.ink2)
        .fixedSize(horizontal: false, vertical: true)
    }

    /// Проверить дороги: a Пинг of every direction (the tab on screen first); Стоп with a progress
    /// ring while it runs, in the same room (the row doesn't switch layouts as it starts or ends).
    private func roadsButton(_ groups: [ServerGroup: [Server]], _ current: ServerGroup) -> some View {
        let busy = store.pinging
        return Button {
            Haptics.tap()
            // In the order shown (favourites first): results fill the list from the top.
            store.pingAll(first: ServerGroup.ordered(groups[current] ?? [], favorites: store.favorites).map(\.id))
        } label: {
            HStack(spacing: 7) {
                Image(systemName: "speedometer").opacity(busy ? 0 : 1).overlay {
                    if busy { ProgressRing(progress: store.pingProgress).frame(width: 16, height: 16) }
                }
                Text("Проверить дороги").opacity(busy ? 0 : 1).overlay {
                    if busy { Text("Стоп") }
                }
            }
            .font(.scaled(14, weight: .semibold))
            .lineLimit(1)
            .foregroundStyle(busy ? Color.white : RouteTone.accentInk)
            .padding(.horizontal, 14)
            .frame(minHeight: 40)
            .background(busy ? AnyShapeStyle(Palette.accentGradient) : AnyShapeStyle(Palette.primary.opacity(0.1)), in: Capsule())
            .animation(.easeInOut(duration: 0.2), value: busy)
        }
        .buttonStyle(PressableStyle())
        .fixedSize()
        .accessibilityLabel(busy ? "Остановить проверку дорог" : "Проверить дороги: пинг всех направлений")
    }

    /// The tab's rows: favourites first, in the pass's order otherwise.
    private func rows(_ items: [Server], bypassTab: Bool) -> some View {
        // Read once for the list, not per row.
        let selectedId = store.selected?.id
        let wifiLock = store.bypassLockedOnWifi
        let tricked = Set(TechniqueStore.decode(techniquesData).keys)
        return VStack(spacing: 4) {
            ForEach(ServerGroup.ordered(items, favorites: store.favorites)) { s in
                // As store.blockedOnWifi(s).
                let locked = wifiLock && ServerGroup.isBypass(s)
                ServerListRow(server: s, ping: store.pings[s.id], pending: store.measuring.contains(s.id),
                              unsupported: store.unsupported.contains(s.id), error: techMode ? store.pingErrors[s.id] : nil,
                              limited: bypassTab && ServerGroup.isLimited(s), favorite: store.isFavorite(s),
                              locked: locked, selected: s.id == selectedId, tricks: tricked.contains(s.name),
                              onPick: { pick(s) }, onLocked: { Haptics.warning(); wifiInfo = true },
                              onPing: { store.pingOne(s) }, onFavorite: { store.toggleFavorite(s) },
                              onHold: { holdingDrag = $0 })
                    .equatable()
            }
        }
    }

    private func pick(_ s: Server) {
        let changed = s.id != store.selected?.id
        if changed { Haptics.selection() }
        store.select(s.id)
        // Coming back after a ping: that reconnect goes over to the pick itself. The switch's
        // outcome is felt (byUser); the tick above is its start.
        if changed, !store.resumingAfterPing { Task { await vpn.switchTo(s, byUser: true) } }
    }
}

/// A tab's look on Маршрут: a signpost for Направления, the detour sign for Объезды.
private extension ServerGroup {
    var icon: String { self == .servers ? "signpost.right.fill" : "arrow.triangle.turn.up.right.diamond.fill" }
    /// Объезды work only over mobile internet (store.bypassLockedOnWifi).
    var networks: String { self == .servers ? "Wi‑Fi / LTE" : "только LTE" }
    var networksIcon: String { self == .servers ? "wifi" : "cellularbars" }
    var spokenNetworks: String { self == .servers ? "работают на Wi-Fi и LTE" : "только LTE" }

    /// 1 направление, 3 направления, 29 направлений.
    static func spokenCount(_ n: Int) -> String {
        let word = (11...14).contains(n % 100) ? "направлений"
            : n % 10 == 1 ? "направление" : (2...4).contains(n % 10) ? "направления" : "направлений"
        return "\(n) \(word)"
    }
}

/// One server's row in Маршрут's list: a ping result redraws only its own row (Equatable on
/// what it shows; the actions are left out). Nothing here observes the store, or every change
/// would redraw it anyway.
struct ServerListRow: View, Equatable {
    let server: Server
    let ping: Int?
    let pending, unsupported: Bool
    let error: String?
    let limited, favorite, locked, selected: Bool
    /// Технический режим has techniques on for it (Techniques).
    let tricks: Bool
    let onPick: () -> Void
    let onLocked: () -> Void
    let onPing: () -> Void
    let onFavorite: () -> Void
    let onHold: (Bool) -> Void

    // An older row's actions do the same: they capture only the store and the VPN, the server
    // (compared here) and HomeView's @State, which all its copies share.
    nonisolated static func == (a: Self, b: Self) -> Bool {
        a.server == b.server && a.ping == b.ping && a.pending == b.pending && a.unsupported == b.unsupported
            && a.error == b.error && a.limited == b.limited && a.favorite == b.favorite
            && a.locked == b.locked && a.selected == b.selected && a.tricks == b.tricks
    }

    var body: some View {
        // Swipe right: favourite (or not any more); swipe left: ping this one.
        SwipeActions(
            right: SwipeAction(icon: favorite ? "star.slash.fill" : "star.fill",
                               title: favorite ? "Убрать" : "В избранное", color: .orange) {
                withAnimation(.spring(response: 0.4, dampingFraction: 0.85)) { onFavorite() }
            },
            left: SwipeAction(icon: "speedometer", title: "Пинг", color: Palette.primary, perform: onPing),
            onHold: onHold
        ) {
            ServerRow(server: server, ping: ping, pending: pending, unsupported: unsupported,
                      onPing: onPing, error: error, limited: limited, favorite: favorite, locked: locked,
                      selected: selected, tricks: tricks) {
                if locked { onLocked(); return }
                onPick()
            }
        }
        .contextMenu {
            Button(action: onPick) { Label("Выбрать", systemImage: "checkmark.circle") }
                .disabled(locked)
            Button(action: onPing) { Label("Пинг", systemImage: "speedometer") }
            Button(action: onFavorite) {
                favorite ? Label("Убрать из избранного", systemImage: "star.slash") : Label("В избранное", systemImage: "star")
            }
            // Технический режим: the ping's whole reason, for the support chat.
            if let error, ping == -1 {
                Button { UIPasteboard.general.string = "\(server.name): \(error)" } label: {
                    Label("Скопировать ошибку", systemImage: "doc.on.doc")
                }
            }
        }
    }
}

/// A row of the list (design C): the direction's tile, its name, how its road is in words (its
/// Пинг: свободно, плотное движение, пробки; nothing while unknown) and the Пинг as a tinted chip,
/// a tap on which measures just this one. Opaque in the sheet's white with the swipe's own
/// corners (SwipeActions draws its action under it), a hairline under it as in the design.
struct ServerRow: View {
    let server: Server
    let ping: Int?
    let pending: Bool
    /// A protocol the iPhone app can't run yet (Naive, TUIC…).
    var unsupported: Bool = false
    var onPing: () -> Void = {}
    /// Why the last ping failed.
    var error: String? = nil
    /// A bypass server that spends gigabytes.
    var limited: Bool = false
    var favorite: Bool = false
    /// A bypass server while on Wi-Fi: dimmed, a tap explains why.
    var locked: Bool = false
    let selected: Bool
    /// Технический режим has techniques on for it: «приёмы» on the line under the name.
    var tricks: Bool = false
    let onTap: () -> Void
    @Environment(\.displayScale) private var displayScale

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 16, style: .continuous)
        Button(action: onTap) {
            HStack(spacing: 12) {
                RouteTile(server: server.name, size: 40)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 4) {
                        if favorite {
                            Image(systemName: "star.fill").font(.scaled(11)).foregroundStyle(RouteTone.star)
                                .accessibilityLabel("Избранное")
                        }
                        MarqueeText(text: RouteTile.title(server.name), font: .scaled(16, weight: .semibold))
                    }
                    subtitle
                    if let error, ping == -1, !pending {
                        // The whole text: long press → Скопировать ошибку.
                        Text(error).font(.scaled(10)).foregroundStyle(RouteTone.danger).lineLimit(3)
                    }
                }
                Spacer(minLength: 4)
                // Tap the ping to measure just this server (again to stop).
                Button(action: onPing) {
                    PingChip(ping: ping, pending: pending)
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(TapOnlyButtonStyle())
                ZStack {
                    Circle().strokeBorder(selected ? Palette.primary : Palette.outline, lineWidth: selected ? 2 : 1.5)
                    if selected { Circle().fill(Palette.primary).frame(width: 12, height: 12) }
                }
                .frame(width: 24, height: 24)
                .accessibilityHidden(true)
            }
            .padding(.leading, 10)
            .padding(.trailing, 12)
            // A tall row (tech mode's error) keeps clear of its edges.
            .padding(.vertical, 6)
            .frame(minHeight: 62)
            .background(selected ? RouteTone.picked : RouteTone.sheet, in: shape)
            .overlay(shape.strokeBorder(selected ? Palette.primary.opacity(0.5) : Color.clear, lineWidth: 1.5))
            .overlay(alignment: .bottom) {
                if !selected {
                    RouteTone.hair.frame(height: 1 / displayScale).padding(.leading, 62).padding(.trailing, 12)
                }
            }
            .opacity(unsupported || locked ? 0.45 : 1)
            .contentShape(Rectangle())
        }
        .buttonStyle(TapOnlyButtonStyle())
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    /// Under the name: what keeps it from being picked or what it is, then the road in words,
    /// and «приёмы» in Технический режим.
    @ViewBuilder private var subtitle: some View {
        let note = self.note
        if limited || note != nil || tricks {
            HStack(spacing: 0) {
                if limited {
                    Text("расходует ГБ").foregroundStyle(RouteTone.warn)
                }
                if let note {
                    Text((limited ? " · " : "") + note).foregroundStyle(RouteTone.ink3)
                }
                if tricks {
                    Text((limited || note != nil ? " · " : "") + "приёмы").foregroundStyle(RouteTone.ink3)
                }
            }
            .font(.scaled(12, weight: .medium))
            .lineLimit(1)
        }
    }

    private var note: String? {
        if unsupported { return "Не поддерживается в этой версии" }
        let parts = server.name.splitFlag
        let road = RouteTraffic.word(ping)
        if parts.0 == nil && UnlimitedServer.isNamed(parts.1) {
            return "без ограничений" + (road.map { " · " + $0 } ?? "")
        }
        return road
    }
}

/// One line that scrolls to its end and back when it doesn't fit (Android's basicMarquee).
struct MarqueeText: View {
    let text: String
    let font: Font
    @State private var textWidth: CGFloat = 0
    @State private var boxWidth: CGFloat = 0
    @State private var offset: CGFloat = 0

    var body: some View {
        // The layout is one invisible short line (its height, any width); the text itself is
        // an overlay, so a long name never widens the row (or the screen).
        Text("Ag").font(font).lineLimit(1).opacity(0)
            .frame(maxWidth: .infinity, alignment: .leading)
            .overlay(alignment: .leading) {
                Text(text).font(font).lineLimit(1).fixedSize()
                    .background(GeometryReader { g in
                        Color.clear.onAppear { textWidth = g.size.width }.onChange(of: g.size.width) { textWidth = $0 }
                    })
                    .offset(x: offset)
            }
            .background(GeometryReader { g in
                Color.clear.onAppear { boxWidth = g.size.width }.onChange(of: g.size.width) { boxWidth = $0 }
            })
            .clipped()
            // VoiceOver reads the name once, not the invisible line.
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(text)
            .task(id: "\(text)|\(Int(textWidth))|\(Int(boxWidth))") {
                offset = 0
                let distance = textWidth - boxWidth
                guard distance > 1 else { return }
                let duration = Double(distance) / 30
                while !Task.isCancelled {
                    try? await Task.sleep(nanoseconds: 2_000_000_000)
                    if Task.isCancelled { break }
                    withAnimation(.linear(duration: duration)) { offset = -distance }
                    try? await Task.sleep(nanoseconds: UInt64((duration + 2.5) * 1_000_000_000))
                    if Task.isCancelled { break }
                    withAnimation(.easeInOut(duration: 0.4)) { offset = 0 }
                    try? await Task.sleep(nanoseconds: 400_000_000)
                }
            }
    }
}
