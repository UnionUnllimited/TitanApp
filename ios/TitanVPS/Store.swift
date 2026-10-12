import Foundation
import Network
import SwiftUI

@MainActor
final class AppStore: ObservableObject {
    @Published var subscription: Subscription?
    @Published var selectedId: String?
    @Published var busy = false
    @Published var message: String?
    @Published var pings: [String: Int] = [:]
    /// Why the last ping of a server failed (Xray's error), shown under it.
    @Published var pingErrors: [String: String] = [:]
    @Published var pinging = false
    /// Devices and their limit from the bot (nil until loaded).
    @Published var account: AccountAPI.Info?

    private let subKey = "titan.subscription"
    private let selKey = "titan.selected"

    init() {
        if let data = UserDefaults.standard.data(forKey: subKey),
           let sub = try? JSONDecoder().decode(Subscription.self, from: data) {
            subscription = sub
        }
        selectedId = UserDefaults.standard.string(forKey: selKey)
    }

    var selected: Server? {
        guard let sub = subscription else { return nil }
        return sub.servers.first { $0.id == selectedId } ?? sub.servers.first
    }

    func activate(_ text: String) async {
        guard let url = SubscriptionAPI.findLink(text) else {
            message = "Это не ключ Titan VPS"
            return
        }
        await load(url)
        if subscription != nil { message = "Подписка подключена" }
    }

    func refresh() async {
        guard let url = subscription?.url else { return }
        await load(url)
        await loadAccount()
    }

    func loadAccount() async {
        guard let url = subscription?.url else { return }
        if let info = await AccountAPI.fetch(url) { account = info }
    }

    private func load(_ url: String) async {
        busy = true
        defer { busy = false }
        do {
            let sub = try await SubscriptionAPI.fetch(url)
            subscription = sub
            if let data = try? JSONEncoder().encode(sub) { UserDefaults.standard.set(data, forKey: subKey) }
            if !sub.servers.contains(where: { $0.id == selectedId }) { select(sub.servers.first?.id) }
        } catch {
            message = error.localizedDescription
        }
    }

    func select(_ id: String?) {
        selectedId = id
        UserDefaults.standard.set(id, forKey: selKey)
    }

    func logout() {
        subscription = nil
        pings = [:]
        select(nil)
        UserDefaults.standard.removeObject(forKey: subKey)
    }

    /// Servers being measured right now (spinners).
    @Published var measuring: Set<String> = []
    private var pingTask: Task<Void, Never>?

    /// Every server through Xray, the tab on screen first, all in one batch where they fit;
    /// pressed again while measuring: stops (old values stay).
    func pingAll(first: [String] = []) {
        if pinging { stopPing(); return }
        guard let servers = subscription?.servers else { return }
        let order = servers.filter { first.contains($0.id) } + servers.filter { !first.contains($0.id) }
        pinging = true
        measuring = Set(order.map(\.id))
        pingTask = Task {
            await XrayPing.pingMany(order) { [weak self] id, ms, error in
                guard let self, self.pinging else { return }
                self.pings[id] = ms
                self.pingErrors[id] = error
                self.measuring.remove(id)
            }
            if !Task.isCancelled { measuring = []; pinging = false }
        }
    }

    func stopPing() {
        pingTask?.cancel()
        pingTask = nil
        measuring = []
        pinging = false
    }

    /// One server; pressed again while it is measured: stops it.
    func pingOne(_ server: Server) {
        if measuring.contains(server.id) { measuring.remove(server.id); return }
        measuring.insert(server.id)
        Task {
            await XrayPing.pingMany([server]) { [weak self] id, ms, error in
                guard let self, self.measuring.contains(id) else { return }
                self.pings[id] = ms
                self.pingErrors[id] = error
                self.measuring.remove(id)
            }
        }
    }

    nonisolated static func tcpPing(host: String?, port: Int?) async -> Int {
        guard let host, let port, let p = NWEndpoint.Port(rawValue: UInt16(clamping: port)) else { return -1 }
        return await withCheckedContinuation { cont in
            let conn = NWConnection(host: NWEndpoint.Host(host), port: p, using: .tcp)
            let start = Date()
            var done = false
            let lock = NSLock()
            func finish(_ v: Int) {
                lock.lock(); defer { lock.unlock() }
                if done { return }
                done = true
                conn.cancel()
                cont.resume(returning: v)
            }
            conn.stateUpdateHandler = { state in
                switch state {
                case .ready: finish(Int(Date().timeIntervalSince(start) * 1000))
                case .failed, .cancelled: finish(-1)
                default: break
                }
            }
            conn.start(queue: .global())
            DispatchQueue.global().asyncAfter(deadline: .now() + 4) { finish(-1) }
        }
    }
}
