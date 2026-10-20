import SwiftUI

enum Tab: Hashable { case home, subscription, profile }

struct RootView: View {
    @EnvironmentObject var store: AppStore
    @EnvironmentObject var vpn: VPNController
    @AppStorage("autoconnect") private var autoconnect = false
    @State private var tab: Tab = .home
    @State private var splash = true

    var body: some View {
        ZStack {
            Palette.background.ignoresSafeArea()
            if splash {
                SplashView()
            } else if store.subscription == nil {
                WelcomeView()
            } else {
                TabView(selection: $tab) {
                    HomeView(openSubscription: { tab = .subscription }, openSettings: { tab = .profile })
                        .tabItem { Label("Главная", systemImage: "house.fill") }.tag(Tab.home)
                    SubscriptionView()
                        .tabItem { Label("Подписка", systemImage: "crown") }.tag(Tab.subscription)
                    ProfileView(openSubscription: { tab = .subscription }, openHome: { tab = .home })
                        .tabItem { Label("Профиль", systemImage: "person") }.tag(Tab.profile)
                }
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
            if case .connected = state { store.checkConnection(auto: true) } else { store.resetCheck() }
        }
        .task {
            try? await Task.sleep(nanoseconds: 900_000_000)
            withAnimation { splash = false }
            await store.refresh(quiet: true)
            // Автоподключение: on at launch too (iOS brings it back after sleep itself).
            if autoconnect, !vpn.isOn, let s = store.selected, !store.blockedOnWifi(s) { await vpn.connect(s) }
        }
        // titanvps://import/<key>, titanvps://add?url=<key> (same as Android): add and connect.
        .onOpenURL { url in
            Task {
                guard await store.activate(url.absoluteString) else { return }
                tab = .home
                if let s = store.selected, !store.blockedOnWifi(s) {
                    if vpn.isOn { await vpn.switchTo(s) } else { await vpn.connect(s) }
                }
            }
        }
        .alert(store.message ?? "", isPresented: Binding(get: { store.message != nil }, set: { if !$0 { store.message = nil } })) {
            Button("OK", role: .cancel) {}
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
                    Text("Titan VPS").font(.system(size: 40, weight: .heavy))
                        .foregroundStyle(Palette.logoGradient)
                    Text("Подключение в одно касание").font(.system(size: 15, weight: .medium)).foregroundStyle(.secondary)
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
                    Text("Titan VPS").font(.system(size: 34, weight: .heavy))
                        .foregroundStyle(Palette.logoGradient)
                    Text("Вставьте ключ подписки из бота или личного кабинета — и VPN включится сразу")
                        .font(.system(size: 15))
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
            Text("Ключ подписки").font(.system(size: 13, weight: .semibold)).kerning(0.4)
                .foregroundStyle(.secondary)
            HStack(spacing: 10) {
                Image(systemName: "key.fill")
                    .foregroundStyle(valid ? Palette.green : Palette.primary)
                    .frame(width: 22)
                TextField("https://api1.titanvps.su/…", text: $key)
                    .font(.system(size: 15))
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
                    .font(.system(size: 12)).foregroundStyle(.orange)
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
            Text("Где взять ключ").font(.system(size: 16, weight: .bold))
            step(1, "Откройте нашего бота в Telegram")
            step(2, "Оформите подписку или продлите её")
            step(3, "Скопируйте ссылку-ключ и вставьте её выше")
            Link(destination: Config.telegram) {
                Label("Открыть бота", systemImage: "paperplane.fill")
                    .font(.system(size: 16, weight: .semibold))
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
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(.white)
                .frame(width: 28, height: 28)
                .background(Palette.accentGradient, in: Circle())
            Text(text).font(.system(size: 15)).fixedSize(horizontal: false, vertical: true)
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
