import SwiftUI
import UIKit

/// A little of «Линза»'s liquid glass (the owner's other app; docs/HANDOFF.md, design C) and only
/// where Маршрут floats something over the map or the page: the tab bar, the sheet and the trip
/// card, the chips and round buttons on the map, and the raised thumb of a segmented control.
/// Everything else stays a plain card. One layer of glass: the system's thinnest material under
/// a white gradient (Линза's --card-a / --card-b, --float-a / --float-b), a thin white specular
/// edge lit from the top left (its ::before rim) and a soft shadow. Dark, as on an iPhone: a
/// solid cell of the theme's surface with a faint white edge and no shadow. Reduce Transparency:
/// solid in both. Drawn alike on iOS 16 to 26 (the app keeps its own look on iOS 26:
/// UIDesignRequiresCompatibility), so no system glass.
enum GlassKind {
    /// The floating tab bar: its items are read over the list as it scrolls under.
    case bar
    /// A card over the map: the trip card, the controls in a wide window.
    case card
    /// A chip, a label or a round button on the map.
    case chip
}

enum Glass {
    /// The rim: light catches the top-left and the bottom-right edges (Линза's --spec-1…4).
    static let specular = LinearGradient(stops: [
        .init(color: .white, location: 0),
        .init(color: .white.opacity(0.35), location: 0.2),
        .init(color: .white.opacity(0), location: 0.42),
        .init(color: .white.opacity(0), location: 0.6),
        .init(color: .white.opacity(0.4), location: 0.82),
        .init(color: .white.opacity(0.95), location: 1),
    ], startPoint: .topLeading, endPoint: .bottomTrailing)

    /// An inset well under a segmented control or a button on a sheet (Линза's --well: the
    /// system's grey fill).
    static let well = Color(UIColor {
        UIColor(red: 118 / 255, green: 118 / 255, blue: 128 / 255, alpha: $0.userInterfaceStyle == .dark ? 0.24 : 0.1)
    })

    /// Линза's spring (--spring) for a thumb sliding over; a plain fade with Reduce Motion.
    static func spring(_ reduceMotion: Bool) -> Animation {
        reduceMotion ? .easeInOut(duration: 0.2) : .spring(response: 0.42, dampingFraction: 0.82)
    }
}

/// The glass surface behind a view, in [shape].
struct GlassSurface<S: InsettableShape>: ViewModifier {
    let kind: GlassKind
    let shape: S
    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    func body(content: Content) -> some View {
        let dark = scheme == .dark
        return content
            .background { fill(dark: dark) }
            .overlay { edge(dark: dark).allowsHitTesting(false).accessibilityHidden(true) }
            // Under an opaque fill only the shape casts it, not the text on it.
            .shadow(color: dark ? .clear : .black.opacity(shadow.opacity), radius: shadow.radius, y: shadow.y)
    }

    /// White over the material, whiter at the top (Линза's gradients, a little denser: text on
    /// them sits over a busy map).
    private var white: (top: Double, bottom: Double) {
        switch kind {
        case .bar: return (0.68, 0.48)
        case .card: return (0.86, 0.72)
        case .chip: return (0.82, 0.66)
        }
    }

    private var shadow: (opacity: Double, radius: CGFloat, y: CGFloat) {
        switch kind {
        case .bar: return (0.14, 16, 8)
        case .card: return (0.12, 16, 8)
        case .chip: return (0.12, 6, 3)
        }
    }

    @ViewBuilder private func fill(dark: Bool) -> some View {
        if reduceTransparency {
            shape.fill(dark ? Palette.surface : Color.white)
        } else if dark {
            if kind == .card {
                shape.fill(Palette.surface)
            } else {
                ZStack {
                    shape.fill(.ultraThinMaterial)
                    shape.fill(Palette.surface.opacity(kind == .bar ? 0.78 : 0.86))
                }
            }
        } else {
            ZStack {
                shape.fill(.ultraThinMaterial)
                shape.fill(LinearGradient(colors: [.white.opacity(white.top), .white.opacity(white.bottom)],
                                          startPoint: .top, endPoint: .bottom))
            }
        }
    }

    @ViewBuilder private func edge(dark: Bool) -> some View {
        if dark {
            shape.strokeBorder(Color.white.opacity(kind == .bar ? 0.16 : 0.1), lineWidth: 0.75)
        } else {
            ZStack {
                shape.strokeBorder(Glass.specular, lineWidth: 1.2)
                // Its outline on a light page (Линза's 0.5 px --hairline).
                shape.stroke(Color.black.opacity(0.07), lineWidth: 0.5)
            }
        }
    }
}

/// Tinted glass (Линза's .lgb): a light fill of [tint] with a white highlight at the top and the
/// specular edge, for an action that is not the screen's one solid button («Остановиться»).
/// Dark: a flat fill of the tint.
struct TintedGlass<S: InsettableShape>: ViewModifier {
    let tint: Color
    let shape: S
    var lift = true
    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    func body(content: Content) -> some View {
        let dark = scheme == .dark
        return content
            .background {
                ZStack {
                    shape.fill(tint.opacity(dark ? 0.2 : 0.13))
                    if !dark && !reduceTransparency {
                        shape.fill(LinearGradient(colors: [.white.opacity(0.45), .white.opacity(0)],
                                                  startPoint: .top, endPoint: .center))
                    }
                }
            }
            .overlay {
                if !dark {
                    ZStack {
                        shape.strokeBorder(Glass.specular, lineWidth: 1.2)
                        shape.stroke(tint.opacity(0.3), lineWidth: 0.5)
                    }
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
                }
            }
            .shadow(color: dark || !lift ? .clear : tint.opacity(0.22), radius: 10, y: 5)
    }
}

/// The sheet over the map's lower edge that holds the list (design C). Glass only where it lies
/// over the map, its top [glassDepth]: below that it runs the length of the list over the plain
/// page, solid, so a long list doesn't blur what's under it all the way down. Rounded at the top.
struct GlassSheet: ViewModifier {
    var radius: CGFloat = 32
    var glassDepth: CGFloat = 72
    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    func body(content: Content) -> some View {
        let shape = TopRoundedRectangle(radius: radius)
        let dark = scheme == .dark
        let solid = RouteTone.sheet
        return content
            .background(alignment: .top) {
                VStack(spacing: 0) {
                    if dark || reduceTransparency {
                        solid.frame(height: glassDepth)
                    } else {
                        ZStack {
                            Rectangle().fill(.ultraThinMaterial)
                            // Into the solid white below, without a seam.
                            LinearGradient(colors: [.white.opacity(0.8), solid], startPoint: .top, endPoint: .bottom)
                        }
                        .frame(height: glassDepth)
                    }
                    solid
                }
                .clipShape(shape)
                .shadow(color: dark ? .clear : .black.opacity(0.08), radius: 16, y: -6)
            }
            .overlay(alignment: .top) {
                // The specular edge along its top only.
                ZStack {
                    shape.strokeBorder(dark ? Color.white.opacity(0.12) : Color.white, lineWidth: dark ? 0.75 : 1.2)
                    if !dark { shape.stroke(Color.black.opacity(0.06), lineWidth: 0.5) }
                }
                .mask(alignment: .top) {
                    LinearGradient(colors: [.black, .clear], startPoint: .top, endPoint: .bottom).frame(height: radius + 12)
                }
                .allowsHitTesting(false)
                .accessibilityHidden(true)
            }
    }
}

/// The raised thumb of a segmented control (Линза's .lgs): white glass with the specular edge
/// and a small shadow, on a well. tinted: the accent's light tint instead (the tab bar, white
/// glass itself). Dark: a flat lighter cell.
struct GlassThumb<S: InsettableShape>: View {
    let shape: S
    var tinted = false
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        if scheme == .dark {
            shape.fill(tinted ? Color.white.opacity(0.12) : Palette.knob)
                .overlay(shape.strokeBorder(Color.white.opacity(tinted ? 0.14 : 0.06), lineWidth: 0.5))
        } else {
            shape.fill(tinted ? Palette.primary.opacity(0.1) : Color.white)
                .overlay(shape.strokeBorder(Glass.specular, lineWidth: 1))
                .overlay(shape.stroke(tinted ? Palette.primary.opacity(0.18) : Color.black.opacity(0.08), lineWidth: 0.5))
                .shadow(color: .black.opacity(tinted ? 0 : 0.14), radius: 5, y: 2)
        }
    }
}

/// Two or three choices in a well with the raised glass thumb sliding to the picked one
/// (Линза's liquid indicator, matchedGeometryEffect and its spring). Taps only, no drag: a
/// sideways drag here is the swipe between the tabs (TabSwipe).
struct LiquidSegment<Item: Identifiable & Hashable, Content: View>: View {
    let items: [Item]
    let selection: Item
    let radius: CGFloat
    let onSelect: (Item) -> Void
    /// What VoiceOver says for each.
    let spoken: (Item) -> String
    private let label: (Item, Bool) -> Content
    @Namespace private var thumb

    init(items: [Item], selection: Item, radius: CGFloat = 19, onSelect: @escaping (Item) -> Void,
         spoken: @escaping (Item) -> String, @ViewBuilder label: @escaping (Item, Bool) -> Content) {
        self.items = items
        self.selection = selection
        self.radius = radius
        self.onSelect = onSelect
        self.spoken = spoken
        self.label = label
    }

    var body: some View {
        let inner = RoundedRectangle(cornerRadius: radius - 4, style: .continuous)
        return HStack(spacing: 2) {
            ForEach(items) { item in
                let on = item == selection
                Button { onSelect(item) } label: {
                    label(item, on)
                        .frame(maxWidth: .infinity, minHeight: 50)
                        .background {
                            if on {
                                GlassThumb(shape: inner).matchedGeometryEffect(id: "thumb", in: thumb)
                            }
                        }
                        .contentShape(inner)
                }
                .buttonStyle(PressableStyle(scale: 0.97))
                .accessibilityLabel(spoken(item))
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
        .padding(4)
        .background(Glass.well, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
    }
}

/// A rectangle rounded at its top corners only (a sheet's): iOS 16 has no uneven one.
struct TopRoundedRectangle: InsettableShape {
    var radius: CGFloat
    var insetAmount: CGFloat = 0

    func path(in rect: CGRect) -> Path {
        let r = rect.insetBy(dx: insetAmount, dy: insetAmount)
        let c = max(min(radius - insetAmount, r.width / 2, r.height), 0)
        var p = Path()
        p.move(to: CGPoint(x: r.minX, y: r.maxY))
        p.addLine(to: CGPoint(x: r.minX, y: r.minY + c))
        p.addArc(center: CGPoint(x: r.minX + c, y: r.minY + c), radius: c,
                 startAngle: .degrees(180), endAngle: .degrees(270), clockwise: false)
        p.addLine(to: CGPoint(x: r.maxX - c, y: r.minY))
        p.addArc(center: CGPoint(x: r.maxX - c, y: r.minY + c), radius: c,
                 startAngle: .degrees(270), endAngle: .degrees(360), clockwise: false)
        p.addLine(to: CGPoint(x: r.maxX, y: r.maxY))
        p.closeSubpath()
        return p
    }

    func inset(by amount: CGFloat) -> TopRoundedRectangle {
        var s = self
        s.insetAmount += amount
        return s
    }
}

extension View {
    /// Glass behind this view, in [shape] (GlassSurface).
    func glassSurface<S: InsettableShape>(_ kind: GlassKind, in shape: S) -> some View {
        modifier(GlassSurface(kind: kind, shape: shape))
    }

    /// Tinted glass behind this view, in [shape] (TintedGlass).
    func tintedGlass<S: InsettableShape>(_ tint: Color, in shape: S, lift: Bool = true) -> some View {
        modifier(TintedGlass(tint: tint, shape: shape, lift: lift))
    }

    /// The list's sheet over the map (GlassSheet).
    func glassSheet(radius: CGFloat = 32, glassDepth: CGFloat = 72) -> some View {
        modifier(GlassSheet(radius: radius, glassDepth: glassDepth))
    }
}
