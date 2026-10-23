import SwiftUI

enum Tab: Hashable { case home, subscription, profile }

struct RootView: View {
    @EnvironmentObject var store: AppStore
    @EnvironmentObject var vpn: VPNController
    @AppStorage("autoconnect") private var autoconnect = false
    @AppStorage(TechMode.key) private var techMode = false
    @State private var tab: Tab = .home
    @State private var splash = true
    /// The app went to the background: back in it is an opening for Автоподключение.
    @State private var wasInBackground = false
    /// The phone's text size: the screens are built again when it changes (Font.scaled).
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ZStack {
            Palette.background.ignoresSafeArea()
            if splash {
                SplashView()
            } else if store.subscription == nil {
                WelcomeView()
            } else {
                tabs
            }
        }
        .overlay(alignment: .top) {
            if let toast = store.toast {
                ToastView(toast: toast)
                    .padding(.top, 6)
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .onTapGesture { store.toast = nil }
                    .task(id: toast.id) {
                        try? await Task.sleep(nanoseconds: toast.kind == .warning ? 4_000_000_000 : 2_600_000_000)
                        if store.toast?.id == toast.id { store.toast = nil }
                    }
            }
        }
        .animation(.spring(response: 0.4, dampingFraction: 0.85), value: store.toast)
        // Проверить соединение: once right after connecting; the old result goes with the connection.
        .onChange(of: vpn.state) { state in
            if case .connected = state {
                store.checkConnection(auto: true)
                Task { await Reminders.askOnce(for: store.subscription?.info) }
                Task { await store.followTunnel() }
                // Up without the app (iOS Settings) or on a profile saved before Не отключать VPN:
                // the profile's on demand as switched there, and its config as this build makes it.
                // The app's own connects set both.
                if !vpn.connectsUnderWay, !store.pingBusy, !store.vpnPaused {
                    vpn.setKeepAlive(VPNController.keepAlive, rebuilding: store.subscription?.servers ?? [])
                }
            } else {
                store.resetCheck()
            }
            // Пункт управления's toggle and the Live Activity follow the VPN.
            LiveActivityController.vpnChanged(state, server: store.selected?.name, paused: store.vpnPaused)
        }
        // A tap on a reminder: Подписка.
        .onReceive(NotificationCenter.default.publisher(for: Reminders.openSubscription)) { _ in
            if store.subscription != nil { tab = .subscription }
        }
        .onChange(of: scenePhase) { phase in
            // Left mid-ping (or mid-check): those stop and the VPN comes back now, in background
            // time. Once the profile is saved with on demand again, iOS keeps it up even if the
            // app is suspended.
            if phase == .background {
                store.leftApp()
                wasInBackground = true
            }
            guard phase == .active else { return }
            // Back in the app: the VPN may have changed while it was away (the tunnel fell, iOS
            // Settings); the Live Activity and Пункт управления's toggle follow it again.
            LiveActivityController.sync(vpn.state, server: store.selected?.name, paused: store.vpnPaused)
            // Opened again (not just Пункт управления pulled down over it): Автоподключение too.
            // During the splash the launch's own check below does it.
            let opened = wasInBackground
            wasInBackground = false
            if opened, !splash { Task { await autoconnectOnOpen() } }
            // After a while: the subscription again (renewed in the bot meanwhile?), which also
            // moves the reminders.
            guard let sub = store.subscription, Date().timeIntervalSince(sub.fetchedAt) > 1800 else { return }
            Task { await store.refresh(quiet: true) }
        }
        .task {
            LiveActivityController.sync(vpn.state, server: store.selected?.name, paused: store.vpnPaused)
            try? await Task.sleep(nanoseconds: 900_000_000)
            // Started by iOS in the background for Пункт управления or Siri (maybe to turn it off).
            // Read after the splash, when a tap's launch is in the foreground (a scene-based app
            // starts in the background), and before the refresh: leaving the app meanwhile still connects.
            let inBackground = UIApplication.shared.applicationState == .background
            // Opened later: then Автоподключение (scenePhase above).
            if inBackground { wasInBackground = true }
            withAnimation { splash = false }
            await store.refresh(quiet: true)
            if !inBackground { await autoconnectOnOpen() }
        }
        // Технический режим switched: a bypass server's on demand follows it (Wi-Fi or not).
        .onChange(of: techMode) { _ in Task { await vpn.applyOnDemandRules() } }
        // titanvps://import/<key>, titanvps://add?url=<key> (same as Android): add and connect.
        .onOpenURL { url in
            Task {
                guard await store.activate(url.absoluteString) else { return }
                tab = .home
                // Coming back after a ping: that reconnect goes over to the pick itself.
                if let s = store.selected, !store.blockedOnWifi(s), !store.resumingAfterPing {
                    if vpn.isOn { await vpn.switchTo(s) } else { await vpn.connect(s) }
                }
            }
        }
        .alert(store.message ?? "", isPresented: Binding(get: { store.message != nil }, set: { if !$0 { store.message = nil } })) {
            Button("OK", role: .cancel) {}
        }
    }

    /// The three tabs. TabView keeps each one's state; its own bar is hidden for TitanTabBar,
    /// which is thinner and sits lower, so Главная shows more servers.
    private var tabs: some View {
        GeometryReader { geo in
            VStack(spacing: 0) {
                TabView(selection: $tab) {
                    HomeView(openSubscription: { tab = .subscription }, openSettings: { tab = .profile })
                        .toolbar(.hidden, for: .tabBar)
                        .tabItem { Label("Главная", systemImage: "house.fill") }.tag(Tab.home)
                    SubscriptionView()
                        .toolbar(.hidden, for: .tabBar)
                        .tabItem { Label("Подписка", systemImage: "crown") }.tag(Tab.subscription)
                    ProfileView(openSubscription: { tab = .subscription }, openHome: { tab = .home })
                        .toolbar(.hidden, for: .tabBar)
                        .tabItem { Label("Профиль", systemImage: "person") }.tag(Tab.profile)
                }
                // Of the home indicator's 34 pt it needs 20 under the bar.
                TitanTabBar(selection: $tab, bottom: min(geo.safeAreaInsets.bottom, 20))
            }
            // Down to the screen's edge. A keyboard (in a sheet over it) doesn't lift the bar:
            // no tab has a text field of its own.
            .ignoresSafeArea([.container, .keyboard], edges: .bottom)
        }
        .id(typeSize)
    }

    /// Автоподключение: on each time the app is opened, at launch or back from the background
    /// (Не отключать VPN keeps it on after that). Not while it is on, going off, being connected
    /// or paused for a ping; the profile's own status first, as the app's may lag after a
    /// suspension (iOS may have brought it back meanwhile).
    private func autoconnectOnOpen() async {
        guard autoconnect, !vpn.isOn, !(await TunnelSwitch.isOn()) else { return }
        guard autoconnect, !vpn.isOn, vpn.state != .disconnecting, !vpn.connectsUnderWay,
              !store.pingBusy, !store.vpnPaused, let s = store.selected, !store.blockedOnWifi(s) else { return }
        await vpn.connect(s)
    }
}

/// Главная / Подписка / Профиль: the picked one in the brand colour. Its surface goes down to
/// the screen's edge; [bottom] is the room kept there for the home indicator.
struct TitanTabBar: View {
    @Binding var selection: Tab
    var bottom: CGFloat = 0
    @Environment(\.displayScale) private var displayScale

    private struct Item: Identifiable {
        let id: Tab
        let title: String
        let icon: String
    }

    private let items = [
        Item(id: .home, title: "Главная", icon: "house.fill"),
        Item(id: .subscription, title: "Подписка", icon: "crown"),
        Item(id: .profile, title: "Профиль", icon: "person"),
    ]

    var body: some View {
        HStack(spacing: 0) {
            ForEach(items) { item in
                let on = item.id == selection
                Button {
                    guard !on else { return }
                    Haptics.selection()
                    selection = item.id
                } label: {
                    VStack(spacing: 2) {
                        // Filled, as the system bar drew them.
                        Image(systemName: item.icon).symbolVariant(.fill)
                            .font(.scaled(20, weight: .semibold))
                            .frame(height: 22)
                        Text(item.title).font(.scaled(10, weight: .semibold)).lineLimit(1)
                    }
                    .foregroundStyle(on ? Palette.primary : Color.secondary)
                    .frame(maxWidth: .infinity, minHeight: 46)
                    .contentShape(Rectangle())
                }
                .buttonStyle(PressableStyle(scale: 0.92))
                .accessibilityLabel(item.title)
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
        // On an iPad the items stay together in the middle.
        .frame(maxWidth: 560)
        .frame(maxWidth: .infinity)
        .padding(.bottom, bottom)
        .background(Palette.surface)
        .overlay(alignment: .top) {
            Palette.outline.frame(height: 1 / displayScale)
        }
    }
}

struct SplashView: View {
    @State private var appeared = false

    var body: some View {
        ZStack {
            AuroraBackground()
            VStack(spacing: 20) {
                GlowingLogo(size: 150)
                    .scaleEffect(appeared ? 1 : 0.8)
                    .opacity(appeared ? 1 : 0)
                VStack(spacing: 6) {
                    Text("Titan VPS").font(.scaled(40, weight: .heavy))
                        .foregroundStyle(Palette.logoGradient)
                    Text("Подключение в одно касание").font(.scaled(15, weight: .medium)).foregroundStyle(.secondary)
                }
                .opacity(appeared ? 1 : 0)
                .offset(y: appeared ? 0 : 14)
            }
        }
        .onAppear { withAnimation(.spring(response: 0.6, dampingFraction: 0.72)) { appeared = true } }
    }
}

struct WelcomeView: View {
    @EnvironmentObject var store: AppStore
    @EnvironmentObject var vpn: VPNController
    /// Read so that a text size change redraws it (Font.scaled).
    @Environment(\.dynamicTypeSize) private var typeSize
    @State private var key = ""
    @State private var appeared = false
    @FocusState private var editing: Bool

    /// The key as typed has our subscription link in it.
    private var valid: Bool { SubscriptionAPI.findLink(key) != nil }

    var body: some View {
        ScrollView {
            VStack(spacing: 22) {
                VStack(spacing: 12) {
                    GlowingLogo(size: 110)
                    Text("Titan VPS").font(.scaled(34, weight: .heavy))
                        .foregroundStyle(Palette.logoGradient)
                    Text("Вставьте ключ подписки из бота или личного кабинета — и VPN включится сразу")
                        .font(.scaled(15))
                        .multilineTextAlignment(.center).foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.top, 36)
                .opacity(appeared ? 1 : 0)
                .offset(y: appeared ? 0 : 16)

                keyCard
                    .opacity(appeared ? 1 : 0)
                    .offset(y: appeared ? 0 : 24)

                steps
                    .opacity(appeared ? 1 : 0)
                    .offset(y: appeared ? 0 : 32)
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 32)
            .frame(maxWidth: 480)
            .frame(maxWidth: .infinity)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(AuroraBackground())
        .onAppear { withAnimation(.spring(response: 0.7, dampingFraction: 0.8).delay(0.05)) { appeared = true } }
    }

    private var keyCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Ключ подписки").font(.scaled(13, weight: .semibold)).kerning(0.4)
                .foregroundStyle(.secondary)
            HStack(spacing: 10) {
                Image(systemName: "key.fill")
                    .foregroundStyle(valid ? Palette.green : Palette.primary)
                    .frame(width: 22)
                TextField("https://api1.titanvps.su/…", text: $key)
                    .font(.scaled(15))
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                    .keyboardType(.URL)
                    .submitLabel(.go)
                    .focused($editing)
                    .onSubmit(connect)
                if !key.isEmpty {
                    Image(systemName: valid ? "checkmark.circle.fill" : "exclamationmark.circle.fill")
                        .foregroundStyle(valid ? Palette.green : Color.orange)
                        .transition(.scale.combined(with: .opacity))
                    Button { key = "" } label: {
                        Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Очистить")
                }
            }
            .padding(.horizontal, 14)
            .frame(minHeight: 56)
            .background(Palette.background.opacity(0.7), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .stroke(fieldBorder, lineWidth: editing || !key.isEmpty ? 1.5 : 1)
            )
            .animation(.easeInOut(duration: 0.2), value: valid)
            .animation(.easeInOut(duration: 0.2), value: editing)
            if !key.isEmpty && !valid {
                Text("Это не похоже на ключ Titan VPS. Скопируйте ссылку подписки из бота целиком.")
                    .font(.scaled(12)).foregroundStyle(.orange)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Button {
                Haptics.tap()
                if let s = UIPasteboard.general.string {
                    withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) { key = s.trimmingCharacters(in: .whitespacesAndNewlines) }
                }
            } label: {
                Label("Вставить из буфера", systemImage: "doc.on.clipboard.fill")
            }
            .buttonStyle(SecondaryButtonStyle(height: 50))
            Button(action: connect) {
                if store.busy {
                    HStack(spacing: 10) {
                        ProgressView().tint(.white)
                        Text("Проверяем ключ…")
                    }
                } else {
                    Label("Подключить", systemImage: "bolt.shield.fill")
                }
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(key.isEmpty || store.busy)
        }
        .padding(18)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 26, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 26, style: .continuous).stroke(Color.white.opacity(0.12), lineWidth: 1))
        .shadow(color: .black.opacity(0.18), radius: 24, y: 12)
        .animation(.easeInOut(duration: 0.2), value: key.isEmpty)
    }

    private var fieldBorder: Color {
        if key.isEmpty { return editing ? Palette.primary : Palette.outline }
        return valid ? Palette.green : .orange
    }

    /// Где взять ключ: the bot, the subscription, the link.
    private var steps: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Где взять ключ").font(.scaled(16, weight: .bold))
            step(1, "Откройте нашего бота в Telegram")
            step(2, "Оформите подписку или продлите её")
            step(3, "Скопируйте ссылку-ключ и вставьте её выше")
            Link(destination: Config.telegram) {
                Label("Открыть бота", systemImage: "paperplane.fill")
                    .font(.scaled(16, weight: .semibold))
                    .foregroundStyle(Palette.primary)
                    .frame(maxWidth: .infinity, minHeight: 48)
                    .background(Palette.primary.opacity(0.12), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
            .buttonStyle(PressableStyle())
            .padding(.top, 2)
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 26, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 26, style: .continuous).stroke(Color.white.opacity(0.1), lineWidth: 1))
    }

    private func step(_ n: Int, _ text: String) -> some View {
        HStack(spacing: 12) {
            Text("\(n)")
                .font(.scaled(14, weight: .bold))
                .foregroundStyle(.white)
                .frame(width: 28, height: 28)
                .background(Palette.accentGradient, in: Circle())
            Text(text).font(.scaled(15)).fixedSize(horizontal: false, vertical: true)
        }
    }

    /// Key added: connect right away (same as Android).
    private func connect() {
        guard !key.isEmpty, !store.busy else { return }
        editing = false
        Task {
            if await store.activate(key), let s = store.selected, !store.blockedOnWifi(s) { await vpn.connect(s) }
        }
    }
}
