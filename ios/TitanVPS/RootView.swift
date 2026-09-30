import SwiftUI

enum Tab: Hashable { case home, subscription, profile }

struct RootView: View {
    @EnvironmentObject var store: AppStore
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
                    ProfileView()
                        .tabItem { Label("Профиль", systemImage: "person") }.tag(Tab.profile)
                }
            }
        }
        .task {
            try? await Task.sleep(nanoseconds: 900_000_000)
            withAnimation { splash = false }
            await store.refresh()
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
    @State private var key = ""

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                Image("Logo").resizable().scaledToFit().frame(width: 120, height: 120).padding(.top, 60)
                Text("Titan VPS").font(.system(size: 32, weight: .bold))
                Text("Вставьте ключ подписки из бота или личного кабинета")
                    .multilineTextAlignment(.center).foregroundStyle(.secondary)
                TextField("https://api1.titanvps.su/…", text: $key)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                    .padding(14)
                    .background(Palette.surface, in: RoundedRectangle(cornerRadius: 14))
                Button {
                    if let s = UIPasteboard.general.string { key = s.trimmingCharacters(in: .whitespacesAndNewlines) }
                } label: {
                    Label("Вставить из буфера", systemImage: "doc.on.clipboard").frame(maxWidth: .infinity, minHeight: 50)
                }
                .buttonStyle(.bordered)
                Button {
                    Task { await store.activate(key) }
                } label: {
                    Group {
                        if store.busy { ProgressView().tint(.white) } else { Text("Подключить").fontWeight(.semibold) }
                    }
                    .frame(maxWidth: .infinity, minHeight: 50)
                }
                .buttonStyle(.borderedProminent)
                .disabled(key.isEmpty || store.busy)
                Link("Нет ключа? Получить в боте", destination: Config.telegram).padding(.top, 8)
            }
            .padding(24)
            .frame(maxWidth: 480)
            .frame(maxWidth: .infinity)
        }
    }
}
