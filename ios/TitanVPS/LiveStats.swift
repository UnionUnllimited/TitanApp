import Darwin
import SwiftUI

/// Speed of the VPN: the byte counters of our tunnel's interface (the utun with
/// the tunnel's address, see PacketTunnelProvider), read once a second while Маршрут is on
/// screen with the route up (the trip card's tiles, TripStats; HomeView starts and stops it).
/// In = what the tunnel hands to the phone (загрузка), out = what goes into it.
@MainActor
final class TrafficMonitor: ObservableObject {
    private struct Sample {
        let down: Double
        let up: Double
    }

    /// Bytes per second now: the mean of the last [smoothing] seconds. A video comes in bursts
    /// (a few seconds of it in one, then nothing while it plays from its buffer): second by
    /// second the tiles would mostly say 0 while it plays.
    @Published private(set) var down: Double = 0
    @Published private(set) var up: Double = 0

    nonisolated static let tunnelAddress = "172.19.0.1"
    private static let smoothing = 3

    /// The last [smoothing] seconds, each one's own speed.
    private var recent: [Sample] = []

    private var last: (name: String, down: UInt32, up: UInt32, at: Date)?
    private var task: Task<Void, Never>?

    func start() {
        guard task == nil else { return }
        task = Task { [weak self] in
            while !Task.isCancelled {
                self?.sample()
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            }
        }
    }

    func stop() {
        task?.cancel()
        task = nil
        reset()
    }

    /// Nothing measured yet: the tiles say 0 until the next two looks.
    private func reset() {
        last = nil
        recent = []
        if down != 0 { down = 0 }
        if up != 0 { up = 0 }
    }

    private func sample() {
        // The tunnel's interface is gone (the route is being built again): nothing goes now.
        guard let now = Self.counters() else {
            if last != nil { reset() }
            return
        }
        let date = Date()
        defer { last = (now.name, now.down, now.up, date) }
        // A new tunnel interface, or a long gap (the app was in the background): start over.
        guard let prev = last, prev.name == now.name, date.timeIntervalSince(prev.at) < 3 else {
            recent = []
            return
        }
        // The counters are 32-bit: they wrap after 4 GB (a wrapping difference handles it).
        let d = UInt64(now.down &- prev.down)
        let u = UInt64(now.up &- prev.up)
        let seconds = max(date.timeIntervalSince(prev.at), 0.2)
        recent.append(Sample(down: Double(d) / seconds, up: Double(u) / seconds))
        if recent.count > Self.smoothing { recent.removeFirst(recent.count - Self.smoothing) }
        let count = Double(recent.count)
        down = recent.reduce(0.0) { $0 + $1.down } / count
        up = recent.reduce(0.0) { $0 + $1.up } / count
    }

    /// The tunnel interface's name and byte counters; nil while there's no tunnel. The Live
    /// Activity's speed comes from it too (LiveActivityController).
    nonisolated static func counters() -> (name: String, down: UInt32, up: UInt32)? {
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { return nil }
        defer { freeifaddrs(list) }
        var name: String?
        var cursor: UnsafeMutablePointer<ifaddrs>? = first
        while let p = cursor {
            if let addr = p.pointee.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET) {
                var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                if getnameinfo(addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0,
                   String(cString: host) == tunnelAddress {
                    name = String(cString: p.pointee.ifa_name)
                    break
                }
            }
            cursor = p.pointee.ifa_next
        }
        guard let name else { return nil }
        cursor = first
        while let p = cursor {
            if let addr = p.pointee.ifa_addr, addr.pointee.sa_family == UInt8(AF_LINK),
               String(cString: p.pointee.ifa_name) == name, let raw = p.pointee.ifa_data {
                let data = raw.assumingMemoryBound(to: if_data.self).pointee
                return (name, data.ifi_ibytes, data.ifi_obytes)
            }
            cursor = p.pointee.ifa_next
        }
        return nil
    }
}
