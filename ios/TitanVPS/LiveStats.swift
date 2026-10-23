import Charts
import Darwin
import SwiftUI

/// Speed and traffic of the VPN: the byte counters of our tunnel's interface (the utun with
/// the tunnel's address, see PacketTunnelProvider), read once a second while Главная is on
/// screen. In = what the tunnel hands to the phone (загрузка), out = what goes into it.
@MainActor
final class TrafficMonitor: ObservableObject {
    struct Sample: Identifiable {
        let id: Int
        let down: Double
        let up: Double
    }

    /// Bytes per second now.
    @Published private(set) var down: Double = 0
    @Published private(set) var up: Double = 0
    /// Since the tunnel came up (its interface counts from zero).
    @Published private(set) var sessionDown: UInt64 = 0
    @Published private(set) var sessionUp: UInt64 = 0
    /// The last [window] seconds, for the chart.
    @Published private(set) var history: [Sample] = []

    nonisolated static let tunnelAddress = "172.19.0.1"
    static let window = 60

    private var last: (name: String, down: UInt32, up: UInt32, at: Date)?
    private var tick = 0
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
        last = nil
        down = 0
        up = 0
        // Back on Главная later: the curve starts fresh, not with a minute from before.
        history = []
    }

    /// A new connection: the chart and the totals start over.
    func reset() {
        last = nil
        history = []
        sessionDown = 0
        sessionUp = 0
        down = 0
        up = 0
    }

    private func sample() {
        guard let now = Self.counters() else { return }
        let date = Date()
        defer { last = (now.name, now.down, now.up, date) }
        // A new tunnel interface, or a long gap (the app was in the background): start over.
        guard let prev = last, prev.name == now.name, date.timeIntervalSince(prev.at) < 3 else {
            if last != nil { history = [] }
            // First look (or a new tunnel interface): what it counted so far is this session.
            sessionDown = UInt64(now.down)
            sessionUp = UInt64(now.up)
            return
        }
        // The counters are 32-bit: they wrap after 4 GB (a wrapping difference handles it).
        let d = UInt64(now.down &- prev.down)
        let u = UInt64(now.up &- prev.up)
        let seconds = max(date.timeIntervalSince(prev.at), 0.2)
        sessionDown += d
        sessionUp += u
        down = Double(d) / seconds
        up = Double(u) / seconds
        tick += 1
        history.append(Sample(id: tick, down: down, up: up))
        if history.count > Self.window { history.removeFirst(history.count - Self.window) }
    }

    /// The tunnel interface's name and byte counters; nil while there's no tunnel. The Live
    /// Activity's speed and traffic come from it too (LiveActivityController).
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

/// Under the connection timer: the speed now, quietly.
struct SpeedLine: View {
    @ObservedObject var monitor: TrafficMonitor

    var body: some View {
        Text("↓ \(Fmt.speed(monitor.down))    ↑ \(Fmt.speed(monitor.up))")
            .font(.scaled(12, weight: .medium).monospacedDigit())
            .foregroundStyle(.tertiary)
            .accessibilityLabel("Загрузка \(Fmt.speed(monitor.down)), отдача \(Fmt.speed(monitor.up))")
    }
}

/// Behind the status: the last minute of download as a faint curve, barely there.
struct SpeedBackdrop: View {
    @ObservedObject var monitor: TrafficMonitor

    var body: some View {
        let top = max(monitor.history.map(\.down).max() ?? 0, 64 * 1024)
        let lastId = monitor.history.last?.id ?? TrafficMonitor.window
        Chart(monitor.history) { s in
            AreaMark(x: .value("Время", s.id), y: .value("Загрузка", s.down))
                .interpolationMethod(.catmullRom)
                .foregroundStyle(LinearGradient(colors: [Palette.primary.opacity(0.14), Palette.primary.opacity(0)],
                                                startPoint: .top, endPoint: .bottom))
        }
        .chartXAxis(.hidden)
        .chartYAxis(.hidden)
        .chartXScale(domain: (lastId - TrafficMonitor.window + 1)...lastId)
        .chartYScale(domain: 0...(top * 1.2))
        .animation(.easeOut(duration: 0.35), value: monitor.history.last?.id)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}
