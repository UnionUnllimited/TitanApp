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
                        try? await Task.sleep(nanoseconds: 2_600_000_000)
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
    var body: some View {
        VStack(spacing: 12) {
            Image("Logo").resizable().scaledToFit().frame(width: 150, height: 150)
            Text("Titan VPS").font(.system(size: 38, weight: .bold))
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Palette.surface.ignoresSafeArea())
    }
}

struct WelcomeView: View {
    @EnvironmentObject var store: AppStore
    @EnvironmentObject var vpn: VPNController
    @State private var key = ""

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                Image("Logo").resizable().scaledToFit().frame(width: 120, height: 120).padding(.top, 60)
                    .shadow(color: Palette.primary.opacity(0.35), radius: 24, y: 8)
                Text("Titan VPS").font(.system(size: 32, weight: .bold))
                Text("Вставьте ключ подписки из бота или личного кабинета")
                    .multilineTextAlignment(.center).foregroundStyle(.secondary)
                HStack(spacing: 10) {
                    Image(systemName: "key.fill").foregroundStyle(Palette.primary)
                    TextField("https://api1.titanvps.su/…", text: $key)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    if !key.isEmpty {
                        Button { key = "" } label: {
                            Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Очистить")
                    }
                }
                .padding(.horizontal, 14)
                .frame(minHeight: 54)
                .background(Palette.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Palette.outline, lineWidth: 1))
                Button {
                    Haptics.tap()
                    if let s = UIPasteboard.general.string { key = s.trimmingCharacters(in: .whitespacesAndNewlines) }
                } label: {
                    Label("Вставить из буфера", systemImage: "doc.on.clipboard")
                }
                .buttonStyle(SecondaryButtonStyle(height: 52))
                Button {
                    // Key added: connect right away (same as Android).
                    Task {
                        if await store.activate(key), let s = store.selected, !store.blockedOnWifi(s) { await vpn.connect(s) }
                    }
                } label: {
                    if store.busy { ProgressView().tint(.white) } else { Label("Подключить", systemImage: "bolt.shield.fill") }
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(key.isEmpty || store.busy)
                Link(destination: Config.telegram) {
                    Label("Нет ключа? Получить в боте", systemImage: "paperplane.fill")
                        .font(.system(size: 15, weight: .semibold))
                }
                .padding(.top, 8)
            }
            .padding(24)
            .frame(maxWidth: 480)
            .frame(maxWidth: .infinity)
        }
    }
}
