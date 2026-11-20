import SafariServices
import SwiftUI
import UIKit

/// The panel's blocks in one place on screen (RemoteBlock, the config's «blocks»,
/// docs/BACKEND-CONFIG.md): what AppStore.blocks(for:) gives now, three at most, one under
/// another in the panel's order. A card is glass (Glass.swift; tinted for accent, warn and
/// success, on an opaque base so it reads over Маршрут's map too) with its icon on a tile, its
/// title, line and button; a button is the app's full-width one; a row is a list row as Профиль's
/// on a card of its own. Their words are the panel's own. Looked at again as the config, the pass
/// or the route changes (it observes the VPN: «onTrip») and each minute (when's from and until,
/// the pass running out). Nothing at all while none is shown, not even [padding]: a screen puts
/// it in a stack without spacing. A tap does the block's action (run), a × closes it for good on
/// this device (AppStore.closeBlock); in and out with the app's own transition, a fade with
/// Reduce Motion (titanReduceMotion).
struct ServerBlocks: View {
    let slot: RemoteBlock.Slot
    /// Around them, while there are some.
    var padding = EdgeInsets()
    /// The place's own word on an action: false where it has nothing to go to here (Тарифы: the
    /// packs without their card), and the block isn't shown in it.
    var canHere: ((RemoteBlock.Action) -> Bool)? = nil
    /// The place's own way with an action, before the usual one (Тарифы: its tariffs and packs are
    /// on the same screen): true when it did it.
    var handle: ((RemoteBlock.Action) -> Bool)? = nil
    @EnvironmentObject var store: AppStore
    /// Observed only: «onTrip» follows the route going up and stopping.
    @EnvironmentObject var vpn: VPNController
    @Environment(\.openURL) private var openURL
    @Environment(\.titanReduceMotion) private var reduceMotion
    /// The block whose words were just copied: «Скопировано» on it for a moment (flashCopied).
    @State private var copied: String?

    var body: some View {
        // Each minute as well: from and until pass, the pass runs out while the screen is open.
        TimelineView(.everyMinute) { _ in
            let shown = store.blocks(for: slot, now: Date(), here: canHere)
            VStack(spacing: 10) {
                ForEach(shown) { block in
                    ServerBlockView(block: block, copied: copied == block.id,
                                    run: { run(block) }, close: { close(block) })
                        .transition(change)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(shown.isEmpty ? EdgeInsets() : padding)
            .animation(motion, value: shown.map(\.id))
        }
    }

    /// Маршрут's choice cards' way in and out (HomeView); a fade with Reduce Motion.
    private var change: AnyTransition {
        reduceMotion ? .opacity : .opacity.combined(with: .scale(scale: 0.96, anchor: .top))
    }

    /// Маршрут's unfold (HomeView): a spring, a plain fade with Reduce Motion.
    private var motion: Animation {
        reduceMotion ? .easeInOut(duration: 0.2) : .spring(response: 0.45, dampingFraction: 0.85)
    }

    /// The block's action, by the app's own ways to each: links in Safari (inApp: Safari's sheet
    /// in the app), the bot and support as Проездной's and Профиль's buttons, the words copied with
    /// «Скопировано» on the block (flashCopied); Тарифы, the packs, Личный кабинет and a tab by
    /// RootView (AppStore.blockRequest). Not if it can't be done any more (the config or the pass
    /// changed since it was drawn).
    private func run(_ block: RemoteBlock) {
        guard let action = block.action, store.canDo(action), canHere?(action) ?? true else { return }
        Haptics.selection()
        if let handle, handle(action) { return }
        switch action {
        case .url(let url, inApp: let inApp):
            if !(inApp && InAppBrowser.open(url)) { openURL(url) }
        case .bot:
            openURL(Config.telegram)
        case .support:
            openURL(store.supportURL)
        case .copy(let text):
            UIPasteboard.general.string = text
            flashCopied(block.id)
        case .tariffs, .packs, .cabinet, .screen:
            store.blockRequest = action
        }
    }

    /// «Скопировано» on the block itself for a moment, as Личный кабинет's ID has it (its title,
    /// a tick for its mark and icon), not RootView's toast: that one is under a sheet such as
    /// Тарифы. VoiceOver hears it.
    private func flashCopied(_ id: String) {
        withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) { copied = id }
        VoiceOver.announce("Скопировано")
        Task {
            try? await Task.sleep(nanoseconds: 1_800_000_000)
            if copied == id { withAnimation { copied = nil } }
        }
    }

    private func close(_ block: RemoteBlock) {
        withAnimation(motion) { store.closeBlock(block.id) }
    }
}

/// One block, as its kind draws it.
private struct ServerBlockView: View {
    let block: RemoteBlock
    /// Its words were just copied: «Скопировано» for its title (a card with a button of its own:
    /// for that button's words), a tick for its mark and icon, in green.
    let copied: Bool
    let run: () -> Void
    let close: () -> Void

    var body: some View {
        switch block.kind {
        case .card: card
        case .button: button
        case .row: row
        }
    }

    private var title: String { copied ? "Скопировано" : block.title }

    /// What VoiceOver reads for it: the title, then the line.
    private var spoken: String { phrase(title) }

    private func phrase(_ title: String) -> String {
        [title, block.text].compactMap { $0 }.joined(separator: ". ")
    }

    private var hint: String {
        if case .copy = block.action { return "Скопировать" }
        return "Открыть"
    }

    // MARK: Card

    /// Glass with the icon's tile, the title and the line; a button under them where it has one
    /// (and an action), else the whole card is the button (with an action). × in its corner.
    private var card: some View {
        let shape = RoundedRectangle(cornerRadius: Palette.cardRadius, style: .continuous)
        let whole = block.action != nil && block.button == nil
        return HStack(alignment: .top, spacing: 0) {
            if whole {
                Button(action: run) { cardContent(whole: true).contentShape(Rectangle()) }
                    .buttonStyle(PressableStyle(scale: 0.98))
                    .accessibilityLabel(spoken)
                    .accessibilityHint(hint)
            } else {
                cardContent(whole: false)
            }
            if block.dismissible {
                ServerBlockClose(title: block.title, action: close)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .modifier(ServerBlockSurface(tone: block.tone, shape: shape))
    }

    /// [whole]: it is all one button; else its words are one element to VoiceOver and its own
    /// button (block.button) another.
    private func cardContent(whole: Bool) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            if whole {
                cardHead(mark: true)
            } else {
                cardHead(mark: false)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(phrase(block.title))
                if let label = block.button, block.action != nil {
                    Button(action: run) {
                        Text(copied ? title : label).lineLimit(1).minimumScaleFactor(0.8)
                    }
                    .buttonStyle(ServerBlockButtonStyle(tone: block.tone, height: 44))
                    .accessibilityHint(hint)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 14)
        .padding(.leading, 14)
        .padding(.trailing, block.dismissible ? 0 : 14)
    }

    /// [mark]: the whole card is the button (its arrow on the right; «Скопировано» for its title).
    private func cardHead(mark: Bool) -> some View {
        let swapped = copied && mark
        // A title alone in the middle of its tile; with a line, both from the top.
        return HStack(alignment: block.text == nil ? .center : .top, spacing: 12) {
            if let icon = block.icon {
                IconTile(icon: copied ? "checkmark" : icon.symbol, color: copied ? Palette.green : block.tone.tile, size: 34)
            }
            VStack(alignment: .leading, spacing: 3) {
                Text(swapped ? title : block.title)
                    .font(.scaled(16, weight: .semibold))
                    .foregroundStyle(swapped ? RouteTone.good : Color.primary)
                if let text = block.text {
                    Text(text)
                        .font(.scaled(14))
                        .foregroundStyle(RouteTone.ink2)
                }
            }
            .multilineTextAlignment(.leading)
            .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
            if mark, let action = block.action {
                Image(systemName: copied ? "checkmark" : action.mark)
                    .font(.scaled(13, weight: .bold))
                    .foregroundStyle(copied ? RouteTone.good : RouteTone.ink3)
                    .padding(.top, block.text == nil ? 0 : 3)
                    .accessibilityHidden(true)
            }
        }
    }

    // MARK: Button

    /// The app's full-width button, its icon before the title; × beside it on a glass chip.
    /// Without an action only its look (read as text).
    private var button: some View {
        HStack(spacing: 6) {
            if block.action != nil {
                Button(action: run) { buttonLabel }
                    .buttonStyle(ServerBlockButtonStyle(tone: block.tone))
                    .accessibilityLabel(title)
                    .accessibilityHint(hint)
            } else {
                ServerBlockPill(tone: block.tone, height: 50) { buttonLabel }
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(block.title)
            }
            if block.dismissible {
                ServerBlockClose(title: block.title, action: close, chip: true)
            }
        }
    }

    private var buttonLabel: some View {
        HStack(spacing: 8) {
            if copied {
                Image(systemName: "checkmark").accessibilityHidden(true)
            } else if let icon = block.icon {
                Image(systemName: icon.symbol).accessibilityHidden(true)
            }
            Text(title)
                .lineLimit(2)
                .multilineTextAlignment(.center)
                .minimumScaleFactor(0.8)
        }
    }

    // MARK: Row

    /// A list row as Профиль's (SettingsRow): the icon's tile, the title (in the tone's ink) and
    /// the line, an arrow where it does something; on a card of its own, × at its end.
    private var row: some View {
        Card {
            HStack(spacing: 0) {
                if block.action != nil {
                    Button(action: run) { rowContent }
                        .buttonStyle(RowButtonStyle())
                        .accessibilityLabel(spoken)
                        .accessibilityHint(hint)
                } else {
                    rowContent
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel(spoken)
                }
                if block.dismissible {
                    ServerBlockClose(title: block.title, action: close)
                        .padding(.trailing, 4)
                }
            }
        }
    }

    private var rowContent: some View {
        HStack(spacing: 14) {
            if let icon = block.icon {
                IconTile(icon: copied ? "checkmark" : icon.symbol, color: copied ? Palette.green : block.tone.tile)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.scaled(16, weight: .medium))
                    .foregroundStyle(copied ? RouteTone.good : block.tone.ink)
                if let text = block.text {
                    Text(text)
                        .font(.scaled(12))
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .multilineTextAlignment(.leading)
            Spacer(minLength: 8)
            if copied {
                Image(systemName: "checkmark")
                    .font(.scaled(13, weight: .semibold))
                    .foregroundStyle(RouteTone.good)
                    .accessibilityHidden(true)
            } else if let action = block.action {
                Image(systemName: action.mark)
                    .font(.scaled(13, weight: .semibold))
                    .foregroundStyle(.tertiary)
                    .accessibilityHidden(true)
            }
        }
        .padding(.leading, 16)
        .padding(.trailing, block.dismissible ? 4 : 16)
        .padding(.vertical, 10)
        .frame(minHeight: 58)
        .contentShape(Rectangle())
    }
}

/// A card's glass: plain (GlassSurface, .card) for neutral; the tone's tinted glass (TintedGlass)
/// on the app's surface for the others, opaque, so its words read over the map as on a page.
private struct ServerBlockSurface<S: InsettableShape>: ViewModifier {
    let tone: RemoteBlock.Tone
    let shape: S

    @ViewBuilder func body(content: Content) -> some View {
        if let tint = tone.tint {
            content
                .tintedGlass(tint, in: shape, lift: false)
                .background(Palette.surface, in: shape)
        } else {
            content.glassSurface(.card, in: shape)
        }
    }
}

/// A block's button (ServerBlockPill), pressed as the app's own.
private struct ServerBlockButtonStyle: ButtonStyle {
    let tone: RemoteBlock.Tone
    var height: CGFloat = 50

    func makeBody(configuration: Configuration) -> some View {
        ServerBlockPill(tone: tone, height: height, pressed: configuration.isPressed) { configuration.label }
    }
}

/// The look of a block's button: accent as PrimaryButtonStyle (the brand's gradient, white
/// words), the others as SecondaryButtonStyle (the tone's light fill and ink; grey for neutral)
/// on the app's surface, opaque over the map.
private struct ServerBlockPill<Content: View>: View {
    let tone: RemoteBlock.Tone
    let height: CGFloat
    var pressed = false
    @ViewBuilder var content: Content

    var body: some View {
        let accent = tone == .accent
        let shape = RoundedRectangle(cornerRadius: accent ? 16 : 14, style: .continuous)
        return content
            .font(.scaled(height < 50 ? 16 : 17, weight: .semibold))
            .foregroundStyle(accent ? Color.white : tone.ink)
            .frame(maxWidth: .infinity, minHeight: height)
            .padding(.horizontal, 16)
            .background {
                if accent {
                    shape.fill(Palette.accentGradient)
                } else {
                    ZStack {
                        shape.fill(Palette.surface)
                        shape.fill((tone.tint ?? Color.primary).opacity(pressed ? 0.2 : 0.12))
                    }
                }
            }
            .overlay {
                if accent {
                    shape.stroke(Color.white.opacity(0.18), lineWidth: 1)
                }
            }
            .shadow(color: accent ? Palette.primary.opacity(0.35) : Color.clear, radius: 12, y: 6)
            .scaleEffect(pressed ? 0.97 : 1)
            .animation(.spring(response: 0.25, dampingFraction: 0.7), value: pressed)
    }
}

/// A block's ×, «Закрыть: <its title>» to VoiceOver: told apart from Тарифы' own «Закрыть» and
/// from another block's (a close here is for good on this device). [chip]: beside a button, on
/// a glass chip of its own.
private struct ServerBlockClose: View {
    let title: String
    let action: () -> Void
    var chip = false

    var body: some View {
        Button(action: action) {
            mark.contentShape(Rectangle())
        }
        .buttonStyle(PressableStyle(scale: 0.9))
        .accessibilityLabel("Закрыть: " + title)
    }

    @ViewBuilder private var mark: some View {
        let x = Image(systemName: "xmark")
            .font(.scaled(12, weight: .bold))
            .foregroundStyle(RouteTone.ink3)
        if chip {
            x.frame(width: 40, height: 40)
                .glassSurface(.chip, in: Circle())
                .frame(width: 44, height: 44)
        } else {
            x.frame(width: 44, height: 44)
        }
    }
}

private extension RemoteBlock.Tone {
    /// A card's tint and a button's fill (nil: plain glass, a grey fill).
    var tint: Color? {
        switch self {
        case .accent: return Palette.primary
        case .warn: return RouteTone.warn
        case .success: return RouteTone.good
        case .neutral: return nil
        }
    }

    /// Words in the tone (a row's title, a button's): AA on the app's surfaces, light and dark,
    /// and on the tone's own light fill.
    var ink: Color {
        switch self {
        case .accent: return RouteTone.accentInkOnTint
        case .warn: return RouteTone.warn
        case .success: return RouteTone.good
        case .neutral: return Color.primary
        }
    }

    /// The icon's tile (IconTile: a white symbol on it), as Профиль's tiles.
    var tile: Color {
        switch self {
        case .accent, .neutral: return Palette.primary
        case .warn: return RouteTone.noticeFill
        case .success: return Palette.green
        }
    }
}

private extension RemoteBlock.Action {
    /// The arrow at the end of what does it: out of the app, copying, or a screen in it.
    var mark: String {
        switch self {
        case .url(_, inApp: let inApp): return inApp ? "chevron.right" : "arrow.up.right"
        case .bot, .support: return "arrow.up.right"
        case .copy: return "doc.on.doc"
        case .tariffs, .packs, .cabinet, .screen: return "chevron.right"
        }
    }
}

/// Safari's own sheet in the app (SFSafariViewController) over whatever is on screen (a sheet
/// such as Тарифы too): a block's https link with «inApp».
@MainActor
enum InAppBrowser {
    /// False when there is no window to show it over (the caller opens it in Safari then).
    static func open(_ url: URL) -> Bool {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        guard let scene = scenes.first(where: { $0.activationState == .foregroundActive }) ?? scenes.first,
              var top = scene.keyWindow?.rootViewController else { return false }
        while let next = top.presentedViewController, !next.isBeingDismissed { top = next }
        let safari = SFSafariViewController(url: url)
        safari.preferredControlTintColor = UIColor(Palette.primary)
        safari.dismissButtonStyle = .close
        top.present(safari, animated: true)
        return true
    }
}
