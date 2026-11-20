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
    /// 56 % of the way (markerWay): the arrow's place in the design, «Вы здесь» beside it on the way.
    static let marker = CGPoint(x: 202.14, y: 292.86)
    static let markerWay = 0.56
    /// The part of the way the arrow glides over on the way (RouteBand): clear of «Вы здесь», the
    /// destination's label and the map's round buttons.
    static let glideFrom = 0.36
    static let glideTo = 0.72

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

    /// The route at even steps along its length, in band units at scale 1: where the gliding
    /// arrow is and which way it points, without measuring the curves each frame.
    private static let steps: [CGPoint] = {
        let path = RouteMap.route(scale: 1)
        let count = 64
        return (0...count).map { i in
            guard i > 0 else { return RouteMap.point(RouteMap.origin, scale: 1) }
            return path.trimmedPath(from: 0, to: CGFloat(i) / CGFloat(count)).currentPoint
                ?? RouteMap.point(RouteMap.end, scale: 1)
        }
    }()

    /// The road's heading at each step, in degrees clockwise from up (as RouteArrow points).
    private static let headings: [Double] = RouteMap.steps.indices.map { i in
        let a = RouteMap.steps[max(i - 1, 0)]
        let b = RouteMap.steps[min(i + 1, RouteMap.steps.count - 1)]
        return atan2(Double(b.x - a.x), Double(a.y - b.y)) * 180 / .pi
    }

    /// [way] along the route by its length (0 … 1): the point there, in a band [scale] times the
    /// map units, and the road's heading.
    static func along(_ way: Double, scale s: CGFloat) -> (point: CGPoint, angle: Double) {
        let last = steps.count - 1
        let x = min(max(way, 0), 1) * Double(last)
        let i = min(Int(x), last - 1)
        let t = x - Double(i)
        let a = steps[i], b = steps[i + 1]
        let p = CGPoint(x: (a.x + (b.x - a.x) * CGFloat(t)) * s, y: (a.y + (b.y - a.y) * CGFloat(t)) * s)
        return (p, headings[i] + (headings[i + 1] - headings[i]) * t)
    }
}

/// A light parallax of the map in a phone's layout: as the sheet scrolls up over it, the picture
/// and the route on it go up a little slower (down by [factor] of the way the content went, at
/// most [limit]); the map's round buttons and the title stay with the content. Read in the two
/// named coordinate spaces, the scroll content's and the ScrollView's: nothing is stored, so
/// only the views that move are drawn again as it scrolls, not Маршрут.
@MainActor
struct MapParallax {
    let content: String
    let scroll: String
    static let factor: CGFloat = 0.3
    static let limit: CGFloat = 80

    /// How far down the map goes for a view in the content at [g]: none on a pull to refresh.
    func shift(_ g: GeometryProxy) -> CGFloat {
        let scrolled = g.frame(in: .named(content)).minY - g.frame(in: .named(scroll)).minY
        return min(max(scrolled, 0) * Self.factor, Self.limit)
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
/// ground fading it out at the top, under the title (design C). Nothing in it moves by itself;
/// with [parallax] (a phone's layout, not with Reduce Motion) the picture goes up a little
/// slower than the content as it scrolls, the fade staying with the title.
struct RouteMapArt: View {
    let band: CGRect
    var parallax: MapParallax? = nil
    @Environment(\.titanReduceMotion) private var reduceMotion

    var body: some View {
        GeometryReader { g in
            let s = band.width / RouteMap.width
            let top = band.minY - (RouteMap.bandTop - RouteMap.top) * s
            let shift = reduceMotion ? 0 : parallax?.shift(g) ?? 0
            ZStack(alignment: .topLeading) {
                Image("RouteMap")
                    .resizable()
                    .interpolation(.high)
                    .frame(width: RouteMap.width * s, height: RouteMap.height * s)
                    .offset(x: band.minX, y: top + shift)
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
///
/// Its motion, as a navigator's: while the route is built its dots run toward the pin (on the
/// way, the line's white dashes); as it comes up the solid line draws in over the dots, the pin
/// hops as the line gets there and the rings come up; stopped, the line fades back to the dots.
/// On the way the arrow glides along the middle of the route. A new destination's pin drops in,
/// its label crossfading. With Reduce Motion it all fades instead, and nothing repeats: the
/// repeating ones (flowing, gliding, pulsing) Маршрут switches on only while it and the map are
/// on screen, the app active and Reduce Motion off.
struct RouteBand: View {
    enum Mode: Equatable { case parked, driving }

    let mode: Mode
    /// The picked server's whole name; nil: nothing picked, «Вы здесь» only.
    let server: String?
    /// On the pin's label.
    let destination: String
    /// The endpoint's rings pulse: on the way, the route up.
    var pulsing = false
    /// The arrow glides along the route: on the way, through a switch to another direction too.
    var gliding = false
    /// The route is being built (Строим маршрут…, Меняем направление…): its dots, or the solid
    /// line's dashes on the way, run toward the pin.
    var flowing = false
    /// A phone's layout: the route goes with the map picture's parallax (MapParallax).
    var parallax: MapParallax? = nil

    /// How much of the way the solid line is drawn (0 … 1): from nothing as the route comes up.
    @State private var drawn: CGFloat = 1
    /// The solid line's opacity: it fades back to the dots on a stop.
    @State private var solid: Double
    /// Bumped as the route comes up: the pin hops (BumpEffect).
    @State private var hops: CGFloat = 0
    /// When the arrow's glide began, set back so that it begins at the arrow's place in the design.
    @State private var glideStart: Date
    @Environment(\.titanReduceMotion) private var reduceMotion

    init(mode: Mode, server: String?, destination: String, pulsing: Bool = false, gliding: Bool = false,
         flowing: Bool = false, parallax: MapParallax? = nil) {
        self.mode = mode
        self.server = server
        self.destination = destination
        self.pulsing = pulsing
        self.gliding = gliding
        self.flowing = flowing
        self.parallax = parallax
        _solid = State(initialValue: mode == .driving ? 1 : 0)
        _glideStart = State(initialValue: Date().addingTimeInterval(-RouteBand.glideLead))
    }

    var body: some View {
        GeometryReader { g in
            let s = g.size.width / RouteMap.width
            let o = RouteMap.point(RouteMap.origin, scale: s)
            let e = RouteMap.point(RouteMap.end, scale: s)
            let m = RouteMap.point(RouteMap.marker, scale: s)
            let driving = mode == .driving
            ZStack(alignment: .topLeading) {
                if server != nil {
                    line(RouteMap.route(scale: s))
                    // The rings come up with the route (Маршрут's spring); standing, the pin's
                    // shadow on the road instead.
                    endpoint
                        .scaleEffect(driving || reduceMotion ? 1 : 0.4)
                        .opacity(driving ? 1 : 0)
                        .position(e)
                    pinShadow
                        .opacity(driving ? 0 : 1)
                        .position(x: e.x, y: e.y + 1)
                }
                origin.position(o)
                if server != nil { arrow(scale: s, driving: driving) }
                if let server {
                    destinationPin(server).position(x: e.x, y: e.y - 25.5)
                    pinLabel(width: max(e.x - 26 - 10, 60)).position(x: (e.x - 26) - max(e.x - 26 - 10, 60) / 2, y: e.y - 31)
                }
                youAreHere(o: o, m: m)
            }
            // With the picture behind it, as the sheet scrolls up over the map.
            .offset(y: reduceMotion ? 0 : parallax?.shift(g) ?? 0)
        }
        // A picture: taps go through to what is over it (Маршрут's round buttons).
        .allowsHitTesting(false)
        .onChange(of: mode) { routeChanged($0) }
        .onChange(of: gliding) { now in
            if now { glideStart = Date().addingTimeInterval(-RouteBand.glideLead) }
        }
        // One element for VoiceOver: where the route goes and whether we're on it.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(server == nil ? "Карта: вы здесь" : "Карта: маршрут до «\(destination)»")
        .accessibilityValue(mode == .driving ? "в пути" : "стоим")
        .accessibilityAddTraits(.isImage)
    }

    /// The route came up (on the way): the solid line draws in from «Вы здесь» over the dots and
    /// the pin hops as it gets there. Stopped: the line fades back to the dots. With Reduce
    /// Motion a fade either way.
    private func routeChanged(_ now: Mode) {
        guard now == .driving else {
            withAnimation(.easeOut(duration: reduceMotion ? 0.2 : 0.45)) { solid = 0 }
            return
        }
        var still = Transaction()
        still.disablesAnimations = true
        if reduceMotion {
            withTransaction(still) { drawn = 1 }
            withAnimation(.easeInOut(duration: 0.25)) { solid = 1 }
            return
        }
        withTransaction(still) {
            drawn = 0
            solid = 1
        }
        // A frame later, once it is drawn from nothing.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.02) {
            withAnimation(.easeInOut(duration: 0.8)) { drawn = 1 }
            withAnimation(.linear(duration: 0.55).delay(0.62)) { hops += 1 }
        }
    }

    /// Blue to violet along the way, in the band's unit space (the route's two ends).
    private var gradient: LinearGradient {
        let w = RouteMap.width, h = RouteMap.bandHeight
        return LinearGradient(colors: [Palette.primary, Palette.violet],
                              startPoint: UnitPoint(x: RouteMap.origin.x / w, y: (RouteMap.origin.y - RouteMap.bandTop) / h),
                              endPoint: UnitPoint(x: RouteMap.end.x / w, y: (RouteMap.end.y - RouteMap.bandTop) / h))
    }

    /// The dotted route, and over it the solid line, drawn in (drawn) and faded (solid) as the
    /// route comes up and stops. While flowing, the dots (standing) or the solid line's white
    /// dashes (on the way) run toward the pin, 30 frames a second; the line's glow and casing
    /// aren't drawn again for it.
    @ViewBuilder private func line(_ path: Path) -> some View {
        let driving = mode == .driving
        let dots = flowing && !driving
        let dashes = flowing && driving
        TimelineView(.animation(minimumInterval: 1.0 / 30, paused: !dots)) { ctx in
            let phase = dots ? Self.flow(ctx.date, length: 11.1) : 0
            ZStack {
                path.stroke(RouteTone.casing, style: StrokeStyle(lineWidth: 9, lineCap: .round, lineJoin: .round))
                path.stroke(gradient, style: StrokeStyle(lineWidth: 6, lineCap: .round, lineJoin: .round,
                                                         dash: [0.1, 11], dashPhase: phase))
            }
        }
        let shown = path.trim(from: 0, to: drawn)
        ZStack {
            shown.stroke(Palette.violet.opacity(0.4), style: StrokeStyle(lineWidth: 16, lineCap: .round, lineJoin: .round))
                .blur(radius: 6)
            shown.stroke(RouteTone.casing, style: StrokeStyle(lineWidth: 14, lineCap: .round, lineJoin: .round))
            shown.stroke(gradient, style: StrokeStyle(lineWidth: 9, lineCap: .round, lineJoin: .round))
            TimelineView(.animation(minimumInterval: 1.0 / 30, paused: !dashes)) { ctx in
                let phase = dashes ? Self.flow(ctx.date, length: 16) : 0
                shown.stroke(Color.white.opacity(0.75),
                             style: StrokeStyle(lineWidth: 2.4, lineCap: .round, dash: [3, 13], dashPhase: phase))
            }
        }
        .opacity(solid)
    }

    /// A dash pattern [length] long moved along the route toward the pin, 24 points a second:
    /// its phase at [date] (a smaller phase moves the dashes on).
    private static func flow(_ date: Date, length: CGFloat) -> CGFloat {
        let time = date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 3600)
        let moved = CGFloat(time * 24).truncatingRemainder(dividingBy: length)
        return length - moved
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

    /// The arrow on the way: while gliding it goes along the middle of the route toward the pin
    /// (a loop every glideLoop seconds, slow at its ends, in and out by a fade; 20 frames a
    /// second), else it is at its place in the design. Standing it isn't seen: stopped mid-glide
    /// it fades out where it was.
    private func arrow(scale s: CGFloat, driving: Bool) -> some View {
        TimelineView(.animation(minimumInterval: 1.0 / 20, paused: !gliding)) { ctx in
            let date: Date? = reduceMotion ? nil : gliding ? ctx.date : driving ? nil : Date()
            let g = glide(at: date)
            let at = RouteMap.along(g.way, scale: s)
            marker(angle: at.angle)
                .scaleEffect(driving || reduceMotion ? 1 : 0.6)
                .opacity(driving ? g.opacity : 0)
                .position(at.point)
        }
    }

    /// The gliding arrow at [date] (nil: still, at its place in the design): how far along the
    /// way it is and how much of it is seen.
    private func glide(at date: Date?) -> (way: Double, opacity: Double) {
        guard let date else { return (RouteMap.markerWay, 1) }
        let loop = RouteBand.glideLoop
        let elapsed = date.timeIntervalSince(glideStart).truncatingRemainder(dividingBy: loop)
        let u = (elapsed < 0 ? elapsed + loop : elapsed) / loop
        let eased = (1 - cos(.pi * min(u / RouteBand.glideMoving, 1))) / 2
        let way = RouteMap.glideFrom + (RouteMap.glideTo - RouteMap.glideFrom) * eased
        // In over the loop's first 8 %, out over the 8 % before it stops, then unseen to its end.
        let fade = 0.08
        let opacity = u < fade ? u / fade : min(max((RouteBand.glideMoving - u) / fade, 0), 1)
        return (way, opacity)
    }

    /// A glide's loop, 9 s, moving for 92 % of it.
    private static let glideLoop = 9.0
    private static let glideMoving = 0.92
    /// How far into a loop the arrow is at its place in the design: a glide begins there.
    private static let glideLead: Double = {
        let eased = (RouteMap.markerWay - RouteMap.glideFrom) / (RouteMap.glideTo - RouteMap.glideFrom)
        return acos(1 - 2 * eased) / .pi * RouteBand.glideMoving * RouteBand.glideLoop
    }()

    /// The arrow, pointing [angle] degrees clockwise from up (the road's heading where it is).
    private func marker(angle: Double) -> some View {
        ZStack {
            Circle().fill(RadialGradient(colors: [Palette.primary.opacity(0.28), Palette.primary.opacity(0)],
                                         center: .center, startRadius: 0, endRadius: 28))
                .frame(width: 56, height: 56)
            Circle().fill(Color.white).frame(width: 30, height: 30)
                .shadow(color: .black.opacity(0.22), radius: 3, y: 3)
            RouteArrow().fill(Palette.primary).frame(width: 13, height: 15)
                .rotationEffect(.degrees(angle))
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

    /// The pin, its tip on the route's end: a new destination's drops in from a little above (the
    /// one before fading down into the road), and it hops as the route comes up (hops).
    private func destinationPin(_ server: String) -> some View {
        ZStack {
            pin(server)
                .id(server)
                .transition(pinChange)
        }
        .modifier(BumpEffect(animatableData: hops, lift: 9))
        .animation(reduceMotion ? .easeInOut(duration: 0.2) : .spring(response: 0.42, dampingFraction: 0.6), value: server)
    }

    private var pinChange: AnyTransition {
        reduceMotion ? .opacity
            : .asymmetric(insertion: .offset(y: -18).combined(with: .opacity),
                          removal: .opacity.combined(with: .scale(scale: 0.6, anchor: .bottom)))
    }

    /// The pin in the brand gradient with the destination's flag (sparkles for «Лучший маршрут»,
    /// the bolt for the unlimited row), its tip at the bottom middle.
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

    /// The destination's name, left of the pin's head; a new one crossfades in.
    private func pinLabel(width: CGFloat) -> some View {
        ZStack(alignment: .trailing) {
            mapLabel(destination)
                .id(destination)
                .transition(.opacity)
        }
        .frame(width: width, alignment: .trailing)
        .animation(.easeInOut(duration: 0.3), value: destination)
    }

    /// «Вы здесь» over the dot while standing; beside the arrow's place on the way.
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
