import SwiftUI

struct HomeView: View {
    @EnvironmentObject var store: AppStore
    @EnvironmentObject var vpn: VPNController
    @Environment(\.colorScheme) private var scheme
    @AppStorage("theme") private var theme = "system"
    @State private var page: ServerGroup = .servers
    @State private var expanded = false
    @State private var wifiInfo = false
    /// The Выкл | Вкл knob is being dragged sideways: the page stays still.
    @State private var holdingKnob = false
    @Environment(\.openURL) private var openURL
    var openSubscription: () -> Void
    var openSettings: () -> Void

    var body: some View {
        GeometryReader { geo in
            let wide = geo.size.width >= 720
            VStack(spacing: 0) {
                header
                if wide {
                    HStack(alignment: .top, spacing: 0) {
                        ScrollView { VStack(spacing: 12) { controls }.padding(16) }.scrollDisabled(holdingKnob)
                        ScrollView { VStack(spacing: 10) { list }.padding(16) }.refreshable { await store.refreshFromPull() }
                    }
                } else {
                    ScrollView {
                        VStack(spacing: 12) {
                            controls
                            list
                        }
                        .padding(16)
                        .frame(maxWidth: 640)
                        .frame(maxWidth: .infinity)
                    }
                    .scrollDisabled(holdingKnob)
                    .refreshable { await store.refreshFromPull() }
                }
            }
            .frame(maxWidth: wide ? 1200 : .infinity)
            .frame(maxWidth: .infinity)
        }
        .background(Palette.background.ignoresSafeArea())
        .alert("Недоступно на Wi-Fi", isPresented: $wifiInfo) {
            Button("Понятно", role: .cancel) {}
        } message: {
            Text("Обходы работают только через мобильный интернет и нужны при его ограничениях. На Wi-Fi подключиться к ним нельзя — выберите сервер во вкладке «Серверы».")
        }
        // Wi-Fi came while on a bypass server: move to a regular one (same as Android).
        .onChange(of: store.onWifi) { wifi in
            guard wifi, vpn.isOn, store.blockedOnWifi(store.selected), let to = store.firstRegular else { return }
            store.select(to.id)
            page = .servers
            Task { await vpn.switchTo(to) }
            store.message = "Обходы работают только через мобильный интернет. Переключили на «\(to.name.splitFlag.1)»."
        }
    }

    /// Off for a ping: the VPN comes back by itself after it.
    private var paused: Bool { store.vpnPaused && !vpn.isOn }

    private var statusTitle: String {
        if paused { return "Пауза на время пинга" }
        switch vpn.state {
        case .off, .error: return "Не подключено"
        case .connecting: return "Подключаемся…"
        case .connected: return "Подключено"
        case .disconnecting: return "Отключаемся…"
        }
    }

    private var statusColor: Color {
        if paused { return .orange }
        switch vpn.state {
        case .connected: return Palette.green
        case .connecting: return Palette.primary
        default: return .primary
        }
    }

    private var header: some View {
        HStack {
            squareButton("headphones", label: "Поддержка") { openURL(store.supportURL) }
            Spacer()
            HStack(spacing: 8) {
                Image("Logo").resizable().scaledToFit().frame(width: 40, height: 40)
                Text("Titan VPS").font(.system(size: 22, weight: .bold))
            }
            Spacer()
            squareButton(scheme == .dark ? "sun.max.fill" : "moon.fill", label: "Тема") {
                Haptics.selection()
                theme = scheme == .dark ? "light" : "dark"
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }

    private func squareButton(_ icon: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon).font(.system(size: 19, weight: .semibold)).foregroundStyle(Palette.primary)
                .frame(width: 48, height: 48)
                .background(Palette.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Palette.outline.opacity(0.5), lineWidth: 1))
                .shadow(color: .black.opacity(0.06), radius: 3, y: 1)
        }
        .buttonStyle(PressableStyle(scale: 0.9))
        .accessibilityLabel(label)
    }

    @ViewBuilder private var controls: some View {
        if let sub = store.subscription {
            if let status = store.account?.status, let text = ["BLOCKED": "Аккаунт заблокирован. Напишите в поддержку",
                                                               "MAINTENANCE": "Идут технические работы. Подключение может не работать"][status] {
                Button { openURL(store.supportURL) } label: {
                    HStack(spacing: 10) {
                        Image(systemName: "exclamationmark.triangle.fill")
                        Text(text).multilineTextAlignment(.leading)
                        Spacer(minLength: 0)
                    }
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(.white)
                    .padding(14)
                    .background(status == "BLOCKED" ? Color.red : Color.orange, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
                .buttonStyle(PressableStyle())
            }
            subscriptionCard(sub)
            PowerSlider(phase: powerPhase, locked: store.pingBusy, lockedText: paused ? "Пауза" : "Пинг…",
                        failures: vpn.failures, onToggle: toggleVPN,
                        onLockedTap: { store.showToast("Идёт пинг серверов — переключатель вернётся через пару секунд", .info) },
                        onHold: { holdingKnob = $0 })
                .padding(.top, 6)
            statusBlock
        }
    }

    private var powerPhase: PowerSlider.Phase {
        // Off for a ping: still shown on (it comes back by itself).
        if paused { return .on }
        switch vpn.state {
        case .connecting: return .connecting
        case .connected: return .on
        case .disconnecting: return .disconnecting
        case .off, .error: return .off
        }
    }

    /// The slider moved: false when it can't be done now (the knob goes back).
    private func toggleVPN(_ on: Bool) -> Bool {
        if on {
            guard !vpn.isOn else { return true }
            guard let server = store.selected else {
                store.showToast("Выберите сервер в списке ниже", .warning)
                return false
            }
            if store.blockedOnWifi(server) {
                store.showToast("Обходы недоступны на Wi-Fi — выберите сервер во вкладке «Серверы»", .warning)
                return false
            }
            Task { await vpn.connect(server) }
        } else {
            if vpn.isOn { vpn.disconnect() }
        }
        return true
    }

    private var statusBlock: some View {
        VStack(spacing: 6) {
            Text(statusTitle).font(.system(size: 26, weight: .heavy))
                .foregroundStyle(statusColor)
                .animation(.easeInOut(duration: 0.25), value: statusTitle)
            if paused {
                Text("VPN включится сам, когда пинг закончится")
                    .font(.system(size: 14)).foregroundStyle(.secondary)
            } else if case .connected(let since) = vpn.state {
                TimelineView(.periodic(from: .now, by: 1)) { ctx in
                    let s = max(Int(ctx.date.timeIntervalSince(since)), 0)
                    Text(String(format: "%02d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60))
                        .font(.system(size: 15, weight: .medium).monospacedDigit()).foregroundStyle(.secondary)
                }
            }
            HStack(spacing: 6) {
                if let s = store.selected {
                    let parts = s.name.splitFlag
                    if let flag = parts.0 {
                        Text(flag)
                    } else if parts.1.lowercased().contains("авто") {
                        Image(systemName: "bolt.fill").foregroundStyle(.yellow)
                    }
                    Text(parts.1 + (store.pings[s.id].flatMap { $0 >= 0 ? " · \($0) мс" : nil } ?? ""))
                        .lineLimit(1)
                } else {
                    Text("Выберите сервер")
                }
            }
            .font(.system(size: 16, weight: .medium))
            .foregroundStyle(.secondary)
            if case .error(let message) = vpn.state {
                Text(message).font(.system(size: 13)).foregroundStyle(.red).multilineTextAlignment(.center)
            }
            if vpn.isConnected && !paused {
                connectionCheck.padding(.top, 4)
                    .transition(.opacity.combined(with: .scale(scale: 0.9)))
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 4)
        .animation(.spring(response: 0.4, dampingFraction: 0.85), value: vpn.isConnected)
    }

    /// Проверить соединение (as in Happ): the result of a request through the VPN, tap to repeat.
    private var connectionCheck: some View {
        VStack(spacing: 6) {
            Button { store.checkConnection() } label: {
                HStack(spacing: 8) {
                    switch store.check {
                    case .idle:
                        Image(systemName: "waveform.path.ecg").foregroundStyle(Palette.primary)
                        Text("Проверить соединение")
                    case .checking:
                        ProgressView().scaleEffect(0.8).frame(width: 18, height: 18)
                        Text("Проверяем соединение…")
                    case .ok(let r):
                        Circle().fill(ServerRow.color(r.ms)).frame(width: 9, height: 9)
                        Text("\(r.ms) мс").monospacedDigit().foregroundStyle(ServerRow.color(r.ms))
                        if let code = r.country {
                            Text("·").foregroundStyle(.secondary)
                            Text([Fmt.flag(code), Fmt.country(code) ?? code].compactMap { $0 }.joined(separator: " "))
                                .lineLimit(1)
                        }
                        Image(systemName: "arrow.clockwise").font(.system(size: 12, weight: .bold)).foregroundStyle(.secondary)
                    case .failed:
                        Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
                        Text("Нет ответа через VPN")
                        Image(systemName: "arrow.clockwise").font(.system(size: 12, weight: .bold)).foregroundStyle(.secondary)
                    }
                }
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(.primary)
                .padding(.horizontal, 16)
                .frame(height: 40)
                .background(Palette.surface, in: Capsule())
                .overlay(Capsule().stroke(Palette.outline.opacity(0.6), lineWidth: 1))
            }
            .buttonStyle(PressableStyle())
            .disabled(store.check == .checking)
            .accessibilityLabel(checkAccessibility)
            switch store.check {
            case .ok(let r):
                if let ip = r.ip {
                    Text("IP: \(ip)").font(.system(size: 12).monospacedDigit()).foregroundStyle(.secondary)
                        .textSelection(.enabled)
                }
            case .failed(let why):
                Text(why.isEmpty ? "Попробуйте ещё раз или выберите другой сервер" : "\(why). Попробуйте другой сервер")
                    .font(.system(size: 12)).foregroundStyle(.red.opacity(0.85)).multilineTextAlignment(.center)
            default:
                EmptyView()
            }
        }
        .animation(.easeInOut(duration: 0.2), value: store.check)
    }

    private var checkAccessibility: String {
        switch store.check {
        case .idle: return "Проверить соединение"
        case .checking: return "Проверяем соединение"
        case .ok(let r): return "Соединение работает, \(r.ms) миллисекунд. Проверить ещё раз"
        case .failed: return "Нет ответа через VPN. Проверить ещё раз"
        }
    }

    private func subscriptionCard(_ sub: Subscription) -> some View {
        let now = Int64(Date().timeIntervalSince1970)
        let active = sub.info.expire <= 0 || sub.info.expire > now
        return Card {
            HStack(spacing: 10) {
                Button(action: openSubscription) {
                    HStack(spacing: 14) {
                        Image(systemName: "crown.fill")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundStyle(.white)
                            .frame(width: 44, height: 44)
                            .background(active ? AnyShapeStyle(Palette.accentGradient) : AnyShapeStyle(Color.red), in: Circle())
                        VStack(alignment: .leading, spacing: 2) {
                            HStack(spacing: 4) {
                                Text(active ? "Подписка активна" : "Подписка истекла").font(.system(size: 16, weight: .semibold))
                                Image(systemName: "chevron.right").font(.system(size: 12, weight: .bold)).foregroundStyle(.tertiary)
                            }
                            Text(sub.info.expire > 0 ? "До " + Fmt.date(sub.info.expire) : "Бессрочно")
                                .font(.system(size: 14)).foregroundStyle(.secondary)
                        }
                        Spacer(minLength: 0)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(PressableStyle(scale: 0.98))
                CircleIconButton(icon: "arrow.clockwise", spinning: store.busy, size: 44) {
                    Task { await store.refreshByHand() }
                }
                .disabled(store.busy)
                .accessibilityLabel("Обновить подписку")
            }
            .padding(.horizontal, 14).padding(.vertical, 12)
            Divider().padding(.horizontal, 14)
            Button {
                Haptics.selection()
                withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) { expanded.toggle() }
            } label: {
                HStack(spacing: 4) {
                    Text(expanded ? "Скрыть" : "Подробнее")
                    Image(systemName: "chevron.down")
                        .rotationEffect(.degrees(expanded ? 180 : 0))
                }
                .font(.system(size: 13, weight: .medium)).foregroundStyle(.secondary)
                .frame(maxWidth: .infinity).padding(.vertical, 8)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            if expanded {
                VStack(spacing: 6) {
                    if sub.info.expire > 0 { detail("Осталось", "\(max((sub.info.expire - now) / 86400, 0)) дн.") }
                    detail("Обычные серверы", "Безлимит")
                    detail("Трафик на обходах", sub.info.total > 0 ? "\(formatBytes(sub.info.remaining)) из \(formatBytes(sub.info.total))" : "Безлимит")
                    detail("Обновлено", Fmt.ago(sub.fetchedAt))
                }
                .padding(.horizontal, 16).padding(.bottom, 14)
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
    }

    private func detail(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).foregroundStyle(.secondary)
            Spacer()
            Text(value).fontWeight(.medium)
        }
        .font(.system(size: 14))
    }

    @ViewBuilder private var list: some View {
        if let sub = store.subscription {
            let groups = ServerGroup.split(sub.servers)
            HStack(spacing: 8) {
                HStack(spacing: 2) {
                    ForEach(ServerGroup.allCases) { g in
                        if let items = groups[g], !items.isEmpty { tabChip(g, items.count) }
                    }
                }
                .padding(4)
                .background(Palette.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                pingButton(groups)
            }
            Group {
                if page == .servers {
                    Text("Безлимит на обычных серверах")
                } else if sub.info.total > 0 {
                    HStack(spacing: 6) {
                        Text("Трафик: \(formatBytes(sub.info.remaining)) из \(formatBytes(sub.info.total))")
                        Button("Докупить ГБ", action: openSubscription).font(.system(size: 13, weight: .semibold))
                    }
                } else {
                    Text("Для ограничений мобильного интернета")
                }
            }
            .font(.system(size: 13)).foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading).padding(.leading, 6)

            if page == .bypass && store.bypassLockedOnWifi {
                Text("Вы подключены к Wi-Fi. Обходы работают только через мобильный интернет — на Wi-Fi используйте «Серверы».")
                    .font(.system(size: 13)).foregroundStyle(.orange)
                    .frame(maxWidth: .infinity, alignment: .leading).padding(.leading, 6)
            }
            // Favourites first, in the subscription's order otherwise.
            let items = groups[page] ?? groups[.servers] ?? []
            ForEach(items.filter(store.isFavorite) + items.filter { !store.isFavorite($0) }) { s in
                let locked = store.blockedOnWifi(s)
                ServerRow(server: s, ping: store.pings[s.id], pending: store.measuring.contains(s.id),
                          unsupported: Plugins.endpoint(s).map { !$0.kind.supportedOnIOS } ?? false,
                          onPing: { store.pingOne(s) }, error: store.pingErrors[s.id], limited: page == .bypass && ServerGroup.isLimited(s),
                          favorite: store.isFavorite(s), locked: locked,
                          selected: s.id == store.selected?.id) {
                    if locked { Haptics.warning(); wifiInfo = true; return }
                    pick(s)
                }
                .contextMenu {
                    Button { pick(s) } label: { Label("Выбрать", systemImage: "checkmark.circle") }
                        .disabled(locked)
                    Button { store.pingOne(s) } label: { Label("Пинг", systemImage: "speedometer") }
                    Button { store.toggleFavorite(s) } label: {
                        store.isFavorite(s) ? Label("Убрать из избранного", systemImage: "star.slash") : Label("В избранное", systemImage: "star")
                    }
                }
            }
        }
    }

    /// Пинг for every server (the tab on screen first); Стоп with a progress ring while it runs.
    private func pingButton(_ groups: [ServerGroup: [Server]]) -> some View {
        Button {
            Haptics.tap()
            // In the order shown (favourites first): results fill the list from the top.
            let tab = groups[page] ?? []
            store.pingAll(first: (tab.filter(store.isFavorite) + tab.filter { !store.isFavorite($0) }).map(\.id))
        } label: {
            HStack(spacing: 7) {
                if store.pinging {
                    ProgressRing(progress: store.pingProgress).frame(width: 17, height: 17)
                } else {
                    Image(systemName: "speedometer")
                }
                Text(store.pinging ? "Стоп" : "Пинг").fontWeight(.semibold)
            }
            .font(.system(size: 14))
            .foregroundStyle(store.pinging ? Color.white : Color.primary)
            .padding(.horizontal, 14).frame(height: 52)
            .background(store.pinging ? AnyShapeStyle(Palette.accentGradient) : AnyShapeStyle(Palette.surface),
                        in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .animation(.easeInOut(duration: 0.2), value: store.pinging)
        }
        .buttonStyle(PressableStyle())
        .accessibilityLabel(store.pinging ? "Остановить пинг" : "Пинг всех серверов")
    }

    private func pick(_ s: Server) {
        let changed = s.id != store.selected?.id
        if changed { Haptics.selection() }
        store.select(s.id)
        if changed { Task { await vpn.switchTo(s) } }
    }

    private func tabChip(_ g: ServerGroup, _ count: Int) -> some View {
        let selected = g == page
        return Button {
            if !selected { Haptics.selection() }
            withAnimation(.easeInOut(duration: 0.2)) { page = g }
        } label: {
            HStack(spacing: 6) {
                Image(systemName: g == .servers ? "server.rack" : "shuffle")
                Text(g.rawValue).lineLimit(1)
                Text("\(count)").font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(selected ? .white : .secondary)
                    .padding(.horizontal, 7).padding(.vertical, 2)
                    .background(selected ? Palette.primary : Palette.outline, in: RoundedRectangle(cornerRadius: 10))
            }
            .font(.system(size: 14, weight: .medium))
            .foregroundStyle(selected ? Palette.primary : .secondary)
            .frame(maxWidth: .infinity, minHeight: 44)
            .background(selected ? Palette.primary.opacity(0.08) : .clear, in: RoundedRectangle(cornerRadius: 13))
            .overlay(RoundedRectangle(cornerRadius: 13).stroke(selected ? Palette.primary.opacity(0.35) : .clear))
        }
        .buttonStyle(PressableStyle(scale: 0.97))
    }
}

struct ServerRow: View {
    let server: Server
    let ping: Int?
    let pending: Bool
    /// A protocol the iPhone app can't run yet (Naive, TUIC…).
    var unsupported: Bool = false
    var onPing: () -> Void = {}
    /// Why the last ping failed.
    var error: String? = nil
    var limited: Bool = false
    var favorite: Bool = false
    /// A bypass server while on Wi-Fi: dimmed, a tap explains why.
    var locked: Bool = false
    let selected: Bool
    let onTap: () -> Void

    var body: some View {
        let parts = server.name.splitFlag
        let flag = parts.0
        let name = parts.1
        Button(action: onTap) {
            HStack(spacing: 14) {
                ZStack {
                    Circle().fill(Palette.track)
                    if let flag {
                        Text(flag).font(.system(size: 30))
                    } else if name.lowercased().contains("авто") {
                        Image(systemName: "bolt.fill").foregroundStyle(Palette.primary)
                    } else {
                        Image(systemName: "server.rack").foregroundStyle(.secondary)
                    }
                }
                .frame(width: 34, height: 34)
                .clipShape(Circle())
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 4) {
                        if favorite { Image(systemName: "star.fill").font(.system(size: 11)).foregroundStyle(.yellow) }
                        MarqueeText(text: name, font: .system(size: 16, weight: .semibold))
                    }
                    Text(unsupported ? "Пока не работает на iPhone" : limited ? "Лимитный · расходует ГБ" : "Безлимит")
                        .font(.system(size: 11))
                        .foregroundStyle(unsupported ? Color.secondary : limited ? Color.orange : Palette.green)
                    if let error, ping == -1, !pending {
                        Text(error).font(.system(size: 10)).foregroundStyle(.red.opacity(0.8)).lineLimit(6)
                    }
                }
                Spacer()
                // Tap the ping to measure just this server (again to stop).
                Button(action: onPing) {
                    HStack(spacing: 5) {
                        if pending { ProgressView().scaleEffect(0.7) }
                        if let ping {
                            Text(ping >= 0 ? "\(ping) мс" : "таймаут").font(.system(size: 14))
                                .foregroundStyle(Self.color(ping)).opacity(pending ? 0.5 : 1)
                        } else if !pending {
                            Image(systemName: "speedometer").foregroundStyle(.secondary.opacity(0.6))
                        }
                    }
                    .frame(minWidth: 44, minHeight: 44)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                ZStack {
                    Circle().stroke(selected ? Palette.primary : Palette.outline, lineWidth: selected ? 2 : 1.5)
                    if selected { Circle().fill(Palette.primary).frame(width: 13, height: 13) }
                }
                .frame(width: 26, height: 26)
            }
            .padding(.horizontal, 14)
            .frame(minHeight: 60)
            .background(selected ? Palette.primary.opacity(0.08) : Palette.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(selected ? Palette.primary : .clear, lineWidth: 1.5))
            .opacity(unsupported || locked ? 0.45 : 1)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    static func color(_ ms: Int) -> Color {
        if ms < 0 { return .red }
        if ms < 500 { return Palette.green }
        if ms < 1500 { return .orange }
        return .red
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
