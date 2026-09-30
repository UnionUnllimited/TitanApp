import SwiftUI

struct HomeView: View {
    @EnvironmentObject var store: AppStore
    @Environment(\.colorScheme) private var scheme
    @AppStorage("theme") private var theme = "system"
    @State private var page: ServerGroup = .servers
    @State private var expanded = false
    @State private var vpnInfo = false
    var openSubscription: () -> Void
    var openSettings: () -> Void

    var body: some View {
        GeometryReader { geo in
            let wide = geo.size.width >= 720
            VStack(spacing: 0) {
                header
                if wide {
                    HStack(alignment: .top, spacing: 0) {
                        ScrollView { VStack(spacing: 12) { controls }.padding(16) }
                        ScrollView { VStack(spacing: 10) { list }.padding(16) }.refreshable { await store.refresh() }
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
                    .refreshable { await store.refresh() }
                }
            }
            .frame(maxWidth: wide ? 1200 : .infinity)
            .frame(maxWidth: .infinity)
        }
        .background(Palette.background.ignoresSafeArea())
        .alert("VPN скоро", isPresented: $vpnInfo) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("Это тестовая сборка для iPhone: подписка, серверы и пинг работают, а подключение VPN появится в версии из TestFlight.")
        }
    }

    private var header: some View {
        HStack {
            squareButton("gearshape", action: openSettings)
            Spacer()
            HStack(spacing: 8) {
                Image("Logo").resizable().scaledToFit().frame(width: 40, height: 40)
                Text("Titan VPS").font(.system(size: 22, weight: .bold))
            }
            Spacer()
            squareButton(scheme == .dark ? "sun.max" : "moon") {
                theme = scheme == .dark ? "light" : "dark"
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }

    private func squareButton(_ icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon).font(.system(size: 19)).foregroundStyle(.primary)
                .frame(width: 48, height: 48)
                .background(Palette.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                .shadow(color: .black.opacity(0.06), radius: 3, y: 1)
        }
    }

    @ViewBuilder private var controls: some View {
        if let sub = store.subscription {
            subscriptionCard(sub)
            powerSwitch
            VStack(spacing: 4) {
                Text("Не подключено").font(.system(size: 24, weight: .bold))
                Text(store.selected.map { $0.name.splitFlag.1 } ?? "Выберите сервер")
                    .foregroundStyle(.secondary).lineLimit(1)
            }
            .padding(.vertical, 4)
        }
    }

    private func subscriptionCard(_ sub: Subscription) -> some View {
        let now = Int64(Date().timeIntervalSince1970)
        let active = sub.info.expire <= 0 || sub.info.expire > now
        return Card {
            Button(action: openSubscription) {
                HStack(spacing: 14) {
                    Image(systemName: "crown.fill").foregroundStyle(active ? Palette.primary : .red)
                        .frame(width: 44, height: 44)
                        .background(Palette.primary.opacity(0.1), in: Circle())
                    VStack(alignment: .leading, spacing: 2) {
                        Text(active ? "Подписка активна" : "Подписка истекла").font(.system(size: 16, weight: .semibold))
                        Text(expiryText(sub.info.expire)).font(.system(size: 14)).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Image(systemName: "chevron.right").foregroundStyle(.secondary)
                }
                .padding(.horizontal, 14).padding(.vertical, 12)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            Divider().padding(.horizontal, 14)
            Button { withAnimation { expanded.toggle() } } label: {
                HStack(spacing: 4) {
                    Text("Подробнее")
                    Image(systemName: expanded ? "chevron.up" : "chevron.down")
                }
                .font(.system(size: 13)).foregroundStyle(.secondary)
                .frame(maxWidth: .infinity).padding(.vertical, 8)
            }
            .buttonStyle(.plain)
            if expanded {
                VStack(spacing: 6) {
                    if sub.info.expire > 0 { detail("Осталось", "\(max((sub.info.expire - now) / 86400, 0)) дн.") }
                    detail("Обычные серверы", "Безлимит")
                    detail("Трафик на обходах", sub.info.total > 0 ? "\(formatBytes(sub.info.remaining)) из \(formatBytes(sub.info.total))" : "Безлимит")
                }
                .padding(.horizontal, 16).padding(.bottom, 14)
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

    private var powerSwitch: some View {
        HStack(spacing: 0) {
            Button {} label: {
                Label("Выкл", systemImage: "power").font(.system(size: 18, weight: .semibold))
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Palette.outline, in: Capsule())
            }
            Button { vpnInfo = true } label: {
                Label("Вкл", systemImage: "checkmark.shield").font(.system(size: 18, weight: .semibold))
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .foregroundStyle(.primary)
        .buttonStyle(.plain)
        .padding(5)
        .frame(height: 68)
        .background(Palette.track, in: Capsule())
        .padding(.top, 6)
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
                Button { Task { await store.pingAll() } } label: {
                    HStack(spacing: 6) {
                        if store.pinging { ProgressView() } else { Image(systemName: "speedometer") }
                        Text("Пинг").fontWeight(.semibold)
                    }
                    .font(.system(size: 14))
                    .padding(.horizontal, 14).frame(height: 52)
                    .background(Palette.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
                .buttonStyle(.plain)
                .disabled(store.pinging)
            }
            Group {
                if page == .servers {
                    Text("Безлимит на обычных серверах")
                } else if sub.info.total > 0 {
                    Text("Трафик: \(formatBytes(sub.info.remaining)) из \(formatBytes(sub.info.total))")
                } else {
                    Text("Для ограничений мобильного интернета")
                }
            }
            .font(.system(size: 13)).foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading).padding(.leading, 6)

            ForEach(groups[page] ?? groups[.servers] ?? []) { s in
                ServerRow(server: s, ping: store.pings[s.id], pending: store.pinging && store.pings[s.id] == nil,
                          selected: s.id == store.selected?.id) { store.select(s.id) }
            }
        }
    }

    private func tabChip(_ g: ServerGroup, _ count: Int) -> some View {
        let selected = g == page
        return Button { page = g } label: {
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
        .buttonStyle(.plain)
    }

    private func expiryText(_ expire: Int64) -> String {
        guard expire > 0 else { return "Бессрочно" }
        let f = DateFormatter()
        f.locale = Locale(identifier: "ru_RU")
        f.dateFormat = "d MMMM yyyy"
        return "До " + f.string(from: Date(timeIntervalSince1970: TimeInterval(expire)))
    }
}

struct ServerRow: View {
    let server: Server
    let ping: Int?
    let pending: Bool
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
                Text(name).font(.system(size: 16, weight: .semibold)).lineLimit(1)
                Spacer()
                if pending {
                    ProgressView().scaleEffect(0.7)
                } else if let ping {
                    Text(ping >= 0 ? "\(ping) мс" : "—").foregroundStyle(.secondary).font(.system(size: 15))
                }
                ZStack {
                    Circle().stroke(selected ? Palette.primary : Palette.outline, lineWidth: selected ? 2 : 1.5)
                    if selected { Circle().fill(Palette.primary).frame(width: 13, height: 13) }
                }
                .frame(width: 26, height: 26)
            }
            .padding(.horizontal, 14)
            .frame(minHeight: 58)
            .background(selected ? Palette.primary.opacity(0.08) : Palette.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(selected ? Palette.primary : .clear, lineWidth: 1.5))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
