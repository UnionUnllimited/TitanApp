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
    @Published var pinging = false

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

    /// TCP connect time to each server (no VPN core on iOS yet, so no real ping).
    func pingAll() async {
        guard let servers = subscription?.servers, !pinging else { return }
        pinging = true
        pings = [:]
        await withTaskGroup(of: (String, Int).self) { group in
            for s in servers {
                group.addTask { (s.id, await Self.tcpPing(host: s.host, port: s.port)) }
            }
            for await (id, ms) in group { pings[id] = ms }
        }
        pinging = false
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
