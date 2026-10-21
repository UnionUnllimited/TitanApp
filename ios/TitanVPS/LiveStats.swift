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

    static let tunnelAddress = "172.19.0.1"
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
        guard let prev = last, prev.name == now.name else {
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

    /// The tunnel interface's name and byte counters; nil while there's no tunnel.
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

/// Главная while connected: download and upload speed, a live chart of the last minute and
/// what this session has used.
struct LiveStatsCard: View {
    @ObservedObject var monitor: TrafficMonitor

    private let downFill = LinearGradient(colors: [Palette.primary.opacity(0.4), Palette.primary.opacity(0.02)],
                                          startPoint: .top, endPoint: .bottom)

    var body: some View {
        VStack(spacing: 12) {
            HStack(spacing: 0) {
                speed(icon: "arrow.down", title: "Загрузка", value: monitor.down, color: Palette.primary)
                Rectangle().fill(Palette.outline).frame(width: 1, height: 36)
                speed(icon: "arrow.up", title: "Отдача", value: monitor.up, color: Palette.violet)
            }
            chart
                .frame(height: 64)
            HStack(spacing: 6) {
                Image(systemName: "clock.arrow.circlepath")
                Text("За сессию")
                Spacer()
                Text("↓ \(Fmt.bytes(monitor.sessionDown))   ↑ \(Fmt.bytes(monitor.sessionUp))")
                    .monospacedDigit()
            }
            .font(.scaled(12, weight: .medium))
            .foregroundStyle(.secondary)
        }
        .padding(16)
        .background(Palette.surface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(Palette.outline.opacity(0.5), lineWidth: 1))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Загрузка \(Fmt.speed(monitor.down)), отдача \(Fmt.speed(monitor.up)), за сессию \(Fmt.bytes(monitor.sessionDown + monitor.sessionUp))")
    }

    private func speed(icon: String, title: String, value: Double, color: Color) -> some View {
        let parts = Fmt.speedParts(value)
        return VStack(spacing: 4) {
            Label(title, systemImage: icon)
                .font(.scaled(12, weight: .semibold))
                .foregroundStyle(color)
            (Text(parts.number).font(.scaled(22, weight: .bold)) + Text(" " + parts.unit).font(.scaled(13, weight: .semibold)))
                .monospacedDigit()
                .contentTransition(.numericText())
                .animation(.easeOut(duration: 0.3), value: parts.number)
        }
        .frame(maxWidth: .infinity)
    }

    /// Download as a filled curve, upload as a dashed line, over the last minute.
    private var chart: some View {
        let top = max(monitor.history.map { max($0.down, $0.up) }.max() ?? 0, 1024)
        let lastId = monitor.history.last?.id ?? TrafficMonitor.window
        return Chart(monitor.history) { s in
            AreaMark(x: .value("Время", s.id), y: .value("Загрузка", s.down))
                .interpolationMethod(.catmullRom)
                .foregroundStyle(downFill)
            LineMark(x: .value("Время", s.id), y: .value("Скорость", s.down), series: .value("Направление", "down"))
                .interpolationMethod(.catmullRom)
                .foregroundStyle(Palette.primary)
                .lineStyle(StrokeStyle(lineWidth: 2, lineCap: .round))
            LineMark(x: .value("Время", s.id), y: .value("Скорость", s.up), series: .value("Направление", "up"))
                .interpolationMethod(.catmullRom)
                .foregroundStyle(Palette.violet.opacity(0.85))
                .lineStyle(StrokeStyle(lineWidth: 1.5, lineCap: .round, dash: [3, 3]))
        }
        .chartXAxis(.hidden)
        .chartYAxis(.hidden)
        .chartXScale(domain: (lastId - TrafficMonitor.window + 1)...max(lastId, TrafficMonitor.window))
        .chartYScale(domain: 0...(top * 1.15))
        .animation(.easeOut(duration: 0.35), value: monitor.history.last?.id)
    }
}
