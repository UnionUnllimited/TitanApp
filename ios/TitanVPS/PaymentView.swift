import Combine
import SwiftUI
import UIKit
import WebKit

/// Оплата inside the app: the payment provider's pages in a web view under the app's own bar.
/// Bank apps (СБП), mail and phone links open in the system; once the provider sends the
/// payment back to the site and the site has answered, the screen closes by itself
/// (the cabinet then reloads).
struct PaymentView: View {
    /// The tariff and its price, under the title.
    let subtitle: String
    /// The screen closes by itself: the provider sent the payment back to the site.
    let onReturn: () -> Void

    @Environment(\.dismiss) private var dismiss
    @AppStorage(TechMode.key) private var techMode = false
    @StateObject private var page: PaymentPage

    /// [home]: the site's host (CabinetAPI.host); coming back to it ends the payment.
    init(url: URL, subtitle: String, home: String?, onReturn: @escaping () -> Void = {}) {
        self.subtitle = subtitle
        self.onReturn = onReturn
        _page = StateObject(wrappedValue: PaymentPage(url: url, home: home))
    }

    var body: some View {
        VStack(spacing: 0) {
            bar
            ZStack {
                PaymentWebView(webView: page.webView)
                    // WebKit keeps the focused field above the keyboard itself.
                    .ignoresSafeArea(edges: .bottom)
                if let failure = page.failure {
                    failureView(failure).transition(.opacity)
                }
            }
        }
        .background(Palette.background.ignoresSafeArea())
        .overlay(alignment: .top) {
            if let toast = page.toast {
                ToastView(toast: toast)
                    .padding(.top, 60)
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .onTapGesture { page.toast = nil }
                    .task(id: toast.id) {
                        try? await Task.sleep(nanoseconds: toast.kind == .warning ? 4_000_000_000 : 2_600_000_000)
                        if page.toast?.id == toast.id { page.toast = nil }
                    }
            }
        }
        .animation(.spring(response: 0.4, dampingFraction: 0.85), value: page.toast)
        .animation(.easeOut(duration: 0.2), value: page.failure)
        // The page's alert(), confirm() and prompt().
        .alert(page.dialog?.title ?? "", isPresented: dialogShown, presenting: page.dialog) { d in
            if d.kind == .prompt { TextField("", text: $page.dialogText) }
            Button("OK") { page.answer(true, to: d.id) }
            if d.kind != .alert { Button("Отмена", role: .cancel) { page.answer(false, to: d.id) } }
        } message: { d in
            Text(d.message)
        }
        // A pull down on the page mustn't drop a payment halfway (3-D Secure): closed by the button only.
        .interactiveDismissDisabled()
        .onChange(of: page.returned) { back in
            guard back else { return }
            Haptics.success()
            onReturn()
            dismiss()
        }
        .onDisappear { page.close() }
    }

    /// Hidden other than by its buttons: Отмена (after a tap's own answer, if there was one).
    private var dialogShown: Binding<Bool> {
        let p = page
        return Binding(get: { p.dialog != nil }, set: { shown in
            guard !shown, let id = p.dialog?.id else { return }
            Task { @MainActor in p.answer(false, to: id) }
        })
    }

    /// The title in the middle, close on the left, reload on the right; the load line under it.
    private var bar: some View {
        ZStack {
            VStack(spacing: 1) {
                Text("Оплата").font(.scaled(17, weight: .semibold))
                if !subtitle.isEmpty {
                    Text(subtitle).font(.scaled(12, weight: .medium)).foregroundStyle(.secondary)
                        .lineLimit(1).minimumScaleFactor(0.8)
                }
            }
            .padding(.horizontal, 52)
            HStack {
                Button { dismiss() } label: {
                    Image(systemName: "xmark")
                        .font(.scaled(13, weight: .bold))
                        .foregroundStyle(.secondary)
                        .frame(width: 32, height: 32)
                        .background(Palette.track, in: Circle())
                }
                .buttonStyle(PressableStyle(scale: 0.9))
                .accessibilityLabel("Закрыть")
                Spacer()
                CircleIconButton(icon: "arrow.clockwise", size: 32) { page.reload() }
                    .accessibilityLabel("Обновить страницу")
            }
        }
        .padding(.horizontal, 16)
        .frame(minHeight: 54)
        .overlay(alignment: .bottom) {
            ZStack(alignment: .bottomLeading) {
                Rectangle().fill(Palette.outline.opacity(0.7)).frame(height: 0.5)
                GeometryReader { g in
                    Capsule().fill(Palette.accentGradient).frame(width: g.size.width * page.progress)
                }
                .frame(height: 2.5)
                .opacity(page.loading ? 1 : 0)
            }
            .accessibilityHidden(true)
        }
    }

    private func failureView(_ detail: String) -> some View {
        VStack(spacing: 14) {
            Image(systemName: "wifi.exclamationmark")
                .font(.scaled(38, weight: .semibold))
                .foregroundStyle(Palette.primary)
                .frame(width: 88, height: 88)
                .background(Palette.primary.opacity(0.12), in: Circle())
            Text("Страница оплаты не открылась").font(.scaled(19, weight: .bold)).multilineTextAlignment(.center)
            Text(techMode ? detail : "Проверьте интернет и попробуйте ещё раз")
                .font(.scaled(14)).multilineTextAlignment(.center).foregroundStyle(.secondary)
            Button { page.retry() } label: { Label("Повторить", systemImage: "arrow.clockwise") }
                .buttonStyle(PrimaryButtonStyle(height: 50))
                .frame(maxWidth: 240)
                .padding(.top, 4)
        }
        .padding(32)
        .padding(.top, 24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(Palette.background)
    }
}

/// The payment's web view in SwiftUI (PaymentPage owns it, with its delegates).
private struct PaymentWebView: UIViewRepresentable {
    let webView: WKWebView
    func makeUIView(context: Context) -> WKWebView { webView }
    func updateUIView(_ view: WKWebView, context: Context) {}
}

/// A page's alert(), confirm() or prompt().
struct PageDialog: Identifiable {
    enum Kind { case alert, confirm, prompt }
    let id = UUID()
    let kind: Kind
    /// Whose it is: the page's host.
    let title: String
    let message: String
}

/// The web view of a payment and what the screen shows of it: the load line, a failed load,
/// the page's dialogs, the return to the site.
@MainActor
final class PaymentPage: NSObject, ObservableObject, WKNavigationDelegate, WKUIDelegate {
    @Published private(set) var progress = 0.0
    @Published private(set) var loading = false
    /// The first page didn't load, in technical words (shown in Технический режим only).
    @Published private(set) var failure: String?
    /// The provider sent the payment back to the site: the screen closes.
    @Published private(set) var returned = false
    @Published var toast: Toast?
    /// A dialog of the page, waiting for an answer (the page waits too).
    @Published private(set) var dialog: PageDialog?
    /// What's typed in a prompt().
    @Published var dialogText = ""

    let webView: WKWebView
    private let start: URL
    private let home: String?
    /// Set once a page off the site has opened: only after that does coming back end the payment.
    private var left = false
    /// The site's page after the provider is on its way: closed on the site's answer.
    private var homeward = false
    /// The next load is the site's page from a new window (an offer, a policy), not the return.
    private var sideTrip = false
    private var committed = false
    private var retryURL: URL?
    /// WebKit wants exactly one answer to each dialog.
    private var reply: (@MainActor (Bool, String) -> Void)?
    private var closed = false
    private var blank: BlankWindow?
    private var bag = Set<AnyCancellable>()

    /// Loaded in place; anything else goes to the system.
    private static let inPlace: Set<String> = ["http", "https", "about", "data", "blob", "javascript"]

    init(url: URL, home: String?) {
        start = url
        self.home = home.map { PaymentPage.bare($0.lowercased()) }
        let config = WKWebViewConfiguration()
        // Persistent, and apart from the cabinet's session (that one lives in CabinetAPI only):
        // 3-D Secure, Apple Pay and the banks' pages keep their own cookies.
        config.websiteDataStore = .default()
        config.allowsInlineMediaPlayback = true
        // window.open without a tap still reaches createWebViewWith (opened here, below).
        config.preferences.javaScriptCanOpenWindowsAutomatically = true
        // WebKit's own mobile user agent, not the app's (Titan(...) reads as a bot to sites).
        // No injected scripts either: WebKit turns Apple Pay off in a web view that has them.
        webView = WKWebView(frame: .zero, configuration: config)
        super.init()
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.allowsBackForwardNavigationGestures = true
        // See-through until the first page: the app's background, not a white flash in the dark.
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.publisher(for: \.estimatedProgress)
            .sink { [weak self] p in
                guard let self else { return }
                // Forward smoothly; a new page starting over (back to ~0.1) jumps.
                if p > self.progress {
                    withAnimation(.easeOut(duration: 0.25)) { self.progress = p }
                } else {
                    self.progress = p
                }
            }
            .store(in: &bag)
        webView.publisher(for: \.isLoading)
            .sink { [weak self] on in
                if on { self?.loading = true } else { withAnimation(.easeOut(duration: 0.35)) { self?.loading = false } }
            }
            .store(in: &bag)
        webView.load(URLRequest(url: url))
    }

    func retry() {
        failure = nil
        webView.load(URLRequest(url: retryURL ?? webView.url ?? start))
    }

    func reload() {
        if failure != nil || webView.url == nil { retry() } else { webView.reload() }
    }

    func answer(_ ok: Bool, to id: UUID) {
        guard dialog?.id == id, let reply else { return }
        dialog = nil
        self.reply = nil
        reply(ok, dialogText)
    }

    /// The screen is going away: a dialog still open, or one asked from now on, counts as Отмена.
    func close() {
        closed = true
        if let d = dialog { answer(false, to: d.id) }
    }

    // MARK: WKNavigationDelegate

    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                 decisionHandler: @escaping @MainActor (WKNavigationActionPolicy) -> Void) {
        guard let url = action.request.url else { decisionHandler(.cancel); return }
        let scheme = url.scheme?.lowercased() ?? ""
        guard Self.inPlace.contains(scheme) else {
            openOutside(url, asked: action.navigationType == .linkActivated || action.targetFrame?.isMainFrame != false)
            decisionHandler(.cancel)
            return
        }
        // The whole page only: frames inside don't count, nor does a new window
        // (targetFrame nil; its page loads here next, through open()).
        if action.targetFrame?.isMainFrame == true, scheme == "http" || scheme == "https" {
            let trip = sideTrip
            sideTrip = false
            if (trip || action.navigationType == .backForward) && isHome(url) {
                // The site's page from a new window on the provider's, or swiped back (or forward)
                // to: a look, not the return. Back on the provider's page, the return counts again.
                left = false
                homeward = false
            } else if isHome(url) {
                // Back on the site: it gets the return (it may settle the payment there),
                // the screen closes on its answer.
                homeward = left
            } else {
                left = true
                homeward = false
            }
        }
        decisionHandler(.allow)
    }

    func webView(_ webView: WKWebView, decidePolicyFor response: WKNavigationResponse,
                 decisionHandler: @escaping @MainActor (WKNavigationResponsePolicy) -> Void) {
        if homeward, response.isForMainFrame, let url = response.response.url, isHome(url) {
            // The site has had the return; its page itself isn't needed.
            returned = true
            decisionHandler(.cancel)
            return
        }
        decisionHandler(.allow)
    }

    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        failure = nil
    }

    func webView(_ webView: WKWebView, didCommit navigation: WKNavigation!) {
        // From the first page on, the page's own background (most pages leave it to the default white).
        if !committed {
            committed = true
            webView.isOpaque = true
        }
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        fail(error)
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        fail(error)
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        // The page's process was dropped (memory): a blank view otherwise.
        webView.reload()
    }

    // MARK: WKUIDelegate

    /// target=_blank and window.open: in this same view (the page can be swiped back to);
    /// bank apps and the like straight to the system (window.open skips decidePolicyFor).
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                 for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        guard action.targetFrame == nil else { return nil }
        if let url = action.request.url, !url.absoluteString.isEmpty, url.scheme?.lowercased() != "about" {
            open(action.request)
            return nil
        }
        // A bare window.open() (the page sets its address next): a hidden stand-in, so the page
        // gets a window back; its first page opens here.
        let window = BlankWindow(configuration: configuration) { [weak self] stand, request in
            self?.open(request)
            // Let go once WebKit is done with it.
            Task { @MainActor in if self?.blank === stand { self?.blank = nil } }
        }
        blank = window
        return window.webView
    }

    func webView(_ webView: WKWebView, runJavaScriptAlertPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping @MainActor () -> Void) {
        ask(.alert, message, frame) { _, _ in completionHandler() }
    }

    func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping @MainActor (Bool) -> Void) {
        ask(.confirm, message, frame) { ok, _ in completionHandler(ok) }
    }

    func webView(_ webView: WKWebView, runJavaScriptTextInputPanelWithPrompt prompt: String, defaultText: String?,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping @MainActor (String?) -> Void) {
        ask(.prompt, prompt, frame, text: defaultText ?? "") { ok, text in completionHandler(ok ? text : nil) }
    }

    // MARK: -

    private func ask(_ kind: PageDialog.Kind, _ message: String, _ frame: WKFrameInfo, text: String = "",
                     reply: @escaping @MainActor (Bool, String) -> Void) {
        guard !closed else { reply(false, text); return }
        // One at a time: one still open counts as Отмена.
        if let d = dialog { answer(false, to: d.id) }
        let host = frame.securityOrigin.host
        dialogText = text
        self.reply = reply
        dialog = PageDialog(kind: kind, title: host.isEmpty ? "Оплата" : host, message: message)
    }

    /// A page for a new window: http(s) here, bank apps and the like to the system.
    /// The provider sends the payment back in the same window, never a new one:
    /// the site's page from here is a side trip, not the return.
    private func open(_ request: URLRequest) {
        guard let url = request.url else { return }
        let scheme = url.scheme?.lowercased() ?? ""
        if scheme == "http" || scheme == "https" {
            sideTrip = isHome(url)
            webView.load(request)
        } else if !Self.inPlace.contains(scheme) {
            openOutside(url, asked: true)
        }
    }

    /// Bank apps (СБП), tel:, mailto:, the App Store (itms-apps): the system opens them and the
    /// page stays where it is. [asked]: a tap or the whole page, so a word if nothing opened.
    private func openOutside(_ url: URL, asked: Bool) {
        UIApplication.shared.open(url, options: [:]) { [weak self] ok in
            guard !ok, asked else { return }
            Task { @MainActor in
                Haptics.warning()
                self?.toast = Toast(text: "Не удалось открыть приложение", kind: .warning)
            }
        }
    }

    private func fail(_ error: Error) {
        let e = error as NSError
        // Not failures: a load cut short by the next one, or a link handed to the system
        // (WebKit: 101 can't show, 102 interrupted by policy, 204 handled by a plug-in).
        if e.domain == NSURLErrorDomain && e.code == NSURLErrorCancelled { return }
        if e.domain == "WebKitErrorDomain" && [101, 102, 204].contains(e.code) { return }
        let detail = "\(e.localizedDescription) (\(e.domain) \(e.code))"
        guard committed else {
            retryURL = (e.userInfo[NSURLErrorFailingURLErrorKey] as? URL) ?? webView.url
            failure = detail
            return
        }
        // A page is on screen (the payment form): it stays, only the next one didn't open.
        Haptics.warning()
        let tech = UserDefaults.standard.bool(forKey: TechMode.key)
        toast = Toast(text: tech ? "Страница не открылась: \(detail)" : "Страница не открылась. Попробуйте ещё раз",
                      kind: .warning)
    }

    /// The site itself: its host with or without www., and its subdomains.
    private func isHome(_ url: URL) -> Bool {
        guard let home, let host = url.host?.lowercased() else { return false }
        let h = Self.bare(host)
        return h == home || h.hasSuffix("." + home)
    }

    private static func bare(_ host: String) -> String { host.hasPrefix("www.") ? String(host.dropFirst(4)) : host }
}

/// The window of a bare window.open(), never shown: its first page goes to [onLoad] instead.
@MainActor
private final class BlankWindow: NSObject, WKNavigationDelegate {
    let webView: WKWebView
    private let onLoad: @MainActor (BlankWindow, URLRequest) -> Void

    init(configuration: WKWebViewConfiguration, onLoad: @escaping @MainActor (BlankWindow, URLRequest) -> Void) {
        webView = WKWebView(frame: .zero, configuration: configuration)
        self.onLoad = onLoad
        super.init()
        webView.navigationDelegate = self
    }

    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                 decisionHandler: @escaping @MainActor (WKNavigationActionPolicy) -> Void) {
        // Its own about:blank is fine; the page's address for it isn't loaded here.
        guard let url = action.request.url, !url.absoluteString.isEmpty, url.scheme?.lowercased() != "about" else {
            decisionHandler(.allow)
            return
        }
        decisionHandler(.cancel)
        onLoad(self, action.request)
    }
}
