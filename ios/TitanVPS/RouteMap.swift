import SwiftUI

/// The map behind Маршрут's title (design C): a static picture, light and dark (Assets:
/// RouteMap, drawn from the design's map, docs/HANDOFF.md), and over it in SwiftUI the route,
/// «Вы здесь», the destination's pin and, on the way, the arrow and the pulsing endpoint. All
/// decorative: the app has no location (nor asks for one), the route goes the same way to
/// whichever direction is picked. Positions are in map units, the design's phone 390 across;
/// the screen scales them by its width.
enum RouteMap {
    static let width: CGFloat = 390
    /// The picture's top in map units and its height: it reaches above and below the design's
    /// phone, for the title and banners over it and the trip card under the route.
    static let top: CGFloat = -120
    static let height: CGFloat = 1020
    /// The band the route is drawn in (RouteBand): the picture is laid by it, so the route runs
    /// on the picture's road however tall the title above it is.
    static let bandTop: CGFloat = 176
    static let bandBottom: CGFloat = 356
    static var bandHeight: CGFloat { bandBottom - bandTop }
    /// Along the picture's main road: «Вы здесь» to the destination.
    static let origin = CGPoint(x: 78, y: 340)
    static let end = CGPoint(x: 290, y: 237.3)
    /// 56 % of the way, and the road's heading there (the arrow while on the way).
    static let marker = CGPoint(x: 202.14, y: 292.86)
    static let markerAngle: Double = 73.81

    /// The route in a band [scale] times the map units.
    static func route(scale s: CGFloat) -> Path {
        func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: x * s, y: (y - bandTop) * s) }
        var path = Path()
        path.move(to: p(78, 340))
        path.addCurve(to: p(150, 308), control1: p(110, 340), control2: p(122, 316))
        path.addLine(to: p(212, 290))
        path.addCurve(to: p(262, 252), control1: p(236, 283), control2: p(240, 262))
        path.addLine(to: p(290, 237.3))
        return path
    }

    /// A map point in a band [scale] times the map units.
    static func point(_ p: CGPoint, scale s: CGFloat) -> CGPoint {
        CGPoint(x: p.x * s, y: (p.y - bandTop) * s)
    }
}

/// Where the route band is on screen: the map picture behind the title is placed by it.
struct RouteBandKey: PreferenceKey {
    static let defaultValue: Anchor<CGRect>? = nil
    static func reduce(value: inout Anchor<CGRect>?, nextValue: () -> Anchor<CGRect>?) {
        value = value ?? nextValue()
    }
}

/// The map picture behind a view whose route band is at [band] (in this view's space), and the
/// ground fading it out at the top, under the title (design C). Drawn once: nothing in it moves.
struct RouteMapArt: View {
    let band: CGRect

    var body: some View {
        GeometryReader { g in
            let s = band.width / RouteMap.width
            let top = band.minY - (RouteMap.bandTop - RouteMap.top) * s
            ZStack(alignment: .topLeading) {
                Image("RouteMap")
                    .resizable()
                    .interpolation(.high)
                    .frame(width: RouteMap.width * s, height: RouteMap.height * s)
                    .offset(x: band.minX, y: top)
                LinearGradient(stops: [
                    .init(color: RouteTone.paper.opacity(0.96), location: 0),
                    .init(color: RouteTone.paper.opacity(0.85), location: 0.45),
                    .init(color: RouteTone.paper.opacity(0), location: 1),
                ], startPoint: .top, endPoint: .bottom)
                .frame(width: g.size.width, height: max(band.minY + 24, 1))
            }
            .frame(width: g.size.width, height: g.size.height, alignment: .topLeading)
            .clipped()
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// The route over the map: dotted while standing, a solid gradient line on the way, with the
/// arrow along it and the endpoint's rings. Its size: the width, RouteMap.bandHeight times the
/// width's scale.
struct RouteBand: View {
    enum Mode: Equatable { case parked, driving }

    let mode: Mode
    /// The picked server's whole name; nil: nothing picked, «Вы здесь» only.
    let server: String?
    /// On the pin's label.
    let destination: String
    /// The endpoint's rings pulse: on the way, on screen, without Reduce Motion.
    var pulsing = false

    var body: some View {
        GeometryReader { g in
            let s = g.size.width / RouteMap.width
            let o = RouteMap.point(RouteMap.origin, scale: s)
            let e = RouteMap.point(RouteMap.end, scale: s)
            let m = RouteMap.point(RouteMap.marker, scale: s)
            ZStack(alignment: .topLeading) {
                if server != nil {
                    line(RouteMap.route(scale: s))
                    if mode == .driving { endpoint.position(e) } else { pinShadow.position(x: e.x, y: e.y + 1) }
                }
                origin.position(o)
                if mode == .driving && server != nil { marker.position(m) }
                if let server {
                    pin(server).position(x: e.x, y: e.y - 25.5)
                    pinLabel(width: max(e.x - 26 - 10, 60)).position(x: (e.x - 26) - max(e.x - 26 - 10, 60) / 2, y: e.y - 31)
                }
                youAreHere(o: o, m: m)
            }
        }
        // A picture: taps go through to what is over it (Маршрут's round buttons).
        .allowsHitTesting(false)
        // One element for VoiceOver: where the route goes and whether we're on it.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(server == nil ? "Карта: вы здесь" : "Карта: маршрут до «\(destination)»")
        .accessibilityValue(mode == .driving ? "в пути" : "стоим")
        .accessibilityAddTraits(.isImage)
    }

    /// Blue to violet along the way, in the band's unit space (the route's two ends).
    private var gradient: LinearGradient {
        let w = RouteMap.width, h = RouteMap.bandHeight
        return LinearGradient(colors: [Palette.primary, Palette.violet],
                              startPoint: UnitPoint(x: RouteMap.origin.x / w, y: (RouteMap.origin.y - RouteMap.bandTop) / h),
                              endPoint: UnitPoint(x: RouteMap.end.x / w, y: (RouteMap.end.y - RouteMap.bandTop) / h))
    }

    @ViewBuilder private func line(_ path: Path) -> some View {
        if mode == .driving {
            path.stroke(Palette.violet.opacity(0.4), style: StrokeStyle(lineWidth: 16, lineCap: .round, lineJoin: .round))
                .blur(radius: 6)
            path.stroke(RouteTone.casing, style: StrokeStyle(lineWidth: 14, lineCap: .round, lineJoin: .round))
            path.stroke(gradient, style: StrokeStyle(lineWidth: 9, lineCap: .round, lineJoin: .round))
            path.stroke(Color.white.opacity(0.75), style: StrokeStyle(lineWidth: 2.4, lineCap: .round, dash: [3, 13]))
        } else {
            path.stroke(RouteTone.casing, style: StrokeStyle(lineWidth: 9, lineCap: .round, lineJoin: .round))
            path.stroke(gradient, style: StrokeStyle(lineWidth: 6, lineCap: .round, lineJoin: .round, dash: [0.1, 11]))
        }
    }

    /// «Вы здесь»: a blue dot in a white ring while standing; on the way a small ring where the
    /// trip began (the arrow is where we are).
    @ViewBuilder private var origin: some View {
        if mode == .driving {
            Circle().fill(Color.white).frame(width: 13, height: 13)
                .overlay(Circle().stroke(Palette.primary, lineWidth: 3))
        } else {
            ZStack {
                Circle().fill(RadialGradient(colors: [Palette.primary.opacity(0.28), Palette.primary.opacity(0)],
                                             center: .center, startRadius: 0, endRadius: 24))
                    .frame(width: 48, height: 48)
                Circle().fill(Color.white).frame(width: 22, height: 22)
                    .shadow(color: .black.opacity(0.22), radius: 3, y: 3)
                Circle().fill(Palette.primary).frame(width: 15, height: 15)
            }
        }
    }

    private var marker: some View {
        ZStack {
            Circle().fill(RadialGradient(colors: [Palette.primary.opacity(0.28), Palette.primary.opacity(0)],
                                         center: .center, startRadius: 0, endRadius: 28))
                .frame(width: 56, height: 56)
            Circle().fill(Color.white).frame(width: 30, height: 30)
                .shadow(color: .black.opacity(0.22), radius: 3, y: 3)
            RouteArrow().fill(Palette.primary).frame(width: 13, height: 15)
                .rotationEffect(.degrees(RouteMap.markerAngle))
        }
    }

    private var pinShadow: some View {
        Ellipse().fill(Color.black.opacity(0.14)).frame(width: 18, height: 7)
    }

    /// The endpoint's glow and rings; the outer ring spreads out and fades, again every 2 s
    /// (20 frames a second, only while pulsing: on the way, on screen, without Reduce Motion).
    private var endpoint: some View {
        ZStack {
            Circle().fill(RadialGradient(colors: [Palette.violet.opacity(0.35), Palette.violet.opacity(0)],
                                         center: .center, startRadius: 0, endRadius: 46))
                .frame(width: 92, height: 92)
            Circle().stroke(Palette.violet.opacity(0.18), lineWidth: 1.5).frame(width: 60, height: 60)
            Circle().stroke(Palette.violet.opacity(0.32), lineWidth: 2).frame(width: 40, height: 40)
            Circle().stroke(Palette.violet.opacity(0.5), lineWidth: 2.5).frame(width: 24, height: 24)
            if pulsing {
                TimelineView(.animation(minimumInterval: 1.0 / 20, paused: !pulsing)) { ctx in
                    let t = ctx.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 2) / 2
                    Circle().stroke(Palette.violet.opacity(0.5 * (1 - t)), lineWidth: 2)
                        .frame(width: 24 + 60 * t, height: 24 + 60 * t)
                }
            }
        }
    }

    /// The pin in the brand gradient with the destination's flag (sparkles for «Лучший маршрут»,
    /// the bolt for the unlimited row), its tip on the route's end.
    private func pin(_ server: String) -> some View {
        ZStack(alignment: .top) {
            RoutePin()
                .fill(LinearGradient(colors: [Palette.primary, Palette.violet], startPoint: .top, endPoint: .bottom))
                .shadow(color: .black.opacity(0.22), radius: 3, y: 3)
            Circle().fill(Color.white).frame(width: 29, height: 29).padding(.top, 5.5)
            FlagCircle(server: server, size: 24).padding(.top, 8)
        }
        .frame(width: 40, height: 51)
    }

    /// The destination's name, left of the pin's head.
    private func pinLabel(width: CGFloat) -> some View {
        mapLabel(destination).frame(width: width, alignment: .trailing)
    }

    /// «Вы здесь» over the dot while standing; beside the arrow on the way.
    @ViewBuilder private func youAreHere(o: CGPoint, m: CGPoint) -> some View {
        if mode == .driving && server != nil {
            mapLabel("Вы здесь").frame(width: 140, alignment: .leading).position(x: m.x + 14 + 70, y: m.y + 30)
        } else {
            mapLabel("Вы здесь").position(x: o.x, y: o.y - 40)
        }
    }

    private func mapLabel(_ text: String) -> some View {
        Text(text)
            .font(.scaled(13, weight: .semibold))
            .lineLimit(1)
            .padding(.horizontal, 12)
            .frame(minHeight: 28)
            .glassSurface(.chip, in: Capsule())
    }
}

/// A map pin, 40 by 51: a round head of radius 20 over a tip at the bottom middle (design C).
struct RoutePin: Shape {
    func path(in rect: CGRect) -> Path {
        let sx = rect.width / 40, sy = rect.height / 51
        func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: rect.minX + x * sx, y: rect.minY + y * sy) }
        var path = Path()
        path.move(to: p(20, 51))
        path.addCurve(to: p(0, 20), control1: p(16, 41), control2: p(0, 35))
        path.addArc(center: p(20, 20), radius: 20 * sx, startAngle: .degrees(180), endAngle: .degrees(360), clockwise: false)
        path.addCurve(to: p(20, 51), control1: p(40, 35), control2: p(24, 41))
        path.closeSubpath()
        return path
    }
}

/// The arrow on the way, pointing up (rotated to the road's heading).
struct RouteArrow: Shape {
    func path(in rect: CGRect) -> Path {
        let sx = rect.width / 13, sy = rect.height / 15
        func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: rect.minX + x * sx, y: rect.minY + y * sy) }
        var path = Path()
        path.move(to: p(6.5, 0))
        path.addLine(to: p(13, 15))
        path.addLine(to: p(6.5, 11.6))
        path.addLine(to: p(0, 15))
        path.closeSubpath()
        return path
    }
}
