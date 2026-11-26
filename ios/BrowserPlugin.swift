import AuthenticationServices
import UIKit
import WebKit

/// Opens pages in an in-app browser (`BrowserViewController`) or Safari, and runs sign-in flows with
/// `ASWebAuthenticationSession`. Only one in-app browser or sign-in flow is open at a time.
final class BrowserPlugin: WefterPlugin {

    private static let validModes: Set<String> = ["webview", "external"]

    private weak var activeController: BrowserViewController?
    private var activeAuthSession: ASWebAuthenticationSession?
    private var activeAuthSessionId: String?
    private var pendingAuthCancelReason: String?
    private let authContextProvider = AuthContextProvider()

    /// Opens `url` in the in-app browser, or in Safari with `mode: "external"`. The result only says it
    /// started; `browser:opened` and `browser:closed` follow.
    // @WefterMethod
    func open(payload: [String: Any], callback: @escaping (Result<Any, Error>) -> Void) throws {
        guard let url = payload["url"] as? String, !url.isEmpty else {
            reject(callback, code: "INVALID_URL", message: "A URL must be provided.")
            return
        }

        let mode = payload["mode"] as? String ?? "webview"
        guard BrowserPlugin.validModes.contains(mode) else {
            reject(
                callback,
                code: "INVALID_MODE",
                message: "Invalid browser mode: \(mode). Valid modes are: \(BrowserPlugin.validModes.sorted().joined(separator: ", "))."
            )
            return
        }

        let id = payload["id"] as? String

        if mode == "external" {
            openExternal(url: url, id: id, callback: callback)
            return
        }

        let title = payload["title"] as? String
        let showToolbar = payload["showToolbar"] as? Bool ?? true
        let showNavigationButtons = payload["showNavigationButtons"] as? Bool ?? true
        let shareButton = payload["shareButton"] as? Bool ?? true
        let desktopMode = payload["desktopMode"] as? Bool ?? false

        DispatchQueue.main.async { [weak self] in
            self?.present(
                url: url,
                title: title,
                showToolbar: showToolbar,
                showNavigationButtons: showNavigationButtons,
                shareButton: shareButton,
                desktopMode: desktopMode,
                id: id
            )
        }

        resolve(callback, data: ["started": true])
    }

    /// Closes the sign-in flow or in-app browser with the given `id`, or whichever is open without one.
    // @WefterMethod
    func close(payload: [String: Any], callback: @escaping (Result<Any, Error>) -> Void) throws {
        let id = payload["id"] as? String

        if activeAuthSession != nil, id == nil || activeAuthSessionId == id {
            DispatchQueue.main.async { [weak self] in
                self?.cancelActiveAuthSession(reason: "closed_by_app")
            }
            resolve(callback, data: ["closed": true])
            return
        }

        guard let controller = activeController, id == nil || controller.sessionId == id else {
            resolve(callback, data: ["closed": false])
            return
        }

        DispatchQueue.main.async {
            controller.finish(reason: "closed_by_app")
        }
        resolve(callback, data: ["closed": true])
    }

    /// Starts a sign-in flow that ends when the page redirects to `redirectUri`. The outcome is emitted as
    /// `browser:authCompleted` or `browser:closed`.
    // @WefterMethod
    func auth(payload: [String: Any], callback: @escaping (Result<Any, Error>) -> Void) throws {
        guard let urlString = payload["url"] as? String, !urlString.isEmpty,
              let url = URL(string: urlString) else {
            reject(callback, code: "INVALID_URL", message: "An authorize URL must be provided.")
            return
        }

        guard let redirectUriString = payload["redirectUri"] as? String,
              let redirectUri = URL(string: redirectUriString),
              let scheme = redirectUri.scheme, !scheme.isEmpty else {
            reject(callback, code: "INVALID_REDIRECT_URI", message: "A valid redirectUri with a scheme must be provided.")
            return
        }

        let ephemeral = payload["ephemeral"] as? Bool ?? true
        let id = payload["id"] as? String

        DispatchQueue.main.async { [weak self] in
            self?.presentAuth(url: url, callbackScheme: scheme, ephemeral: ephemeral, id: id)
        }

        resolve(callback, data: ["started": true])
    }

    /// Hands the URL to Safari.
    private func openExternal(url: String, id: String?, callback: @escaping (Result<Any, Error>) -> Void) {
        guard let parsedUrl = URL(string: url) else {
            reject(callback, code: "INVALID_URL", message: "Could not parse URL: \(url)")
            return
        }

        DispatchQueue.main.async { [weak self] in
            self?.cancelActiveAuthSession(reason: "replaced")
            UIApplication.shared.open(parsedUrl, options: [:]) { success in
                if success {
                    self?.dispatchOpened(url: url, mode: "external", id: id)
                } else {
                    self?.dispatchClosed(reason: "launch_failed", id: id)
                }
            }
        }

        resolve(callback, data: ["started": true])
    }

    /// Presents the in-app browser full screen from the key window's root view controller.
    private func present(url: String, title: String?, showToolbar: Bool, showNavigationButtons: Bool, shareButton: Bool, desktopMode: Bool, id: String?) {
        let rootViewController = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first(where: { $0.isKeyWindow })?.rootViewController

        guard let presenter = rootViewController else {
            dispatchClosed(reason: "no_root_view_controller", id: id)
            return
        }

        activeController?.finish(reason: "replaced")
        cancelActiveAuthSession(reason: "replaced")

        let controller = BrowserViewController(
            url: url,
            title: title,
            showToolbar: showToolbar,
            showNavigationButtons: showNavigationButtons,
            shareButton: shareButton,
            desktopMode: desktopMode,
            id: id
        )
        controller.onOpened = { [weak self] in
            self?.dispatchOpened(url: url, mode: "webview", id: id)
        }
        controller.onClosed = { [weak self, weak controller] reason in
            guard let self = self else { return }
            if self.activeController === controller {
                self.activeController = nil
            }
            self.dispatchClosed(reason: reason, id: id)
        }
        controller.modalPresentationStyle = .fullScreen
        activeController = controller
        presenter.present(controller, animated: true)
    }

    /// Cancels the running sign-in flow, reporting `reason` as the close reason.
    private func cancelActiveAuthSession(reason: String) {
        guard activeAuthSession != nil else { return }
        pendingAuthCancelReason = reason
        activeAuthSession?.cancel()
    }

    /// Starts the `ASWebAuthenticationSession` and reports its result.
    private func presentAuth(url: URL, callbackScheme: String, ephemeral: Bool, id: String?) {
        cancelActiveAuthSession(reason: "replaced")

        let session = ASWebAuthenticationSession(url: url, callbackURLScheme: callbackScheme) { [weak self] callbackUrl, error in
            guard let self = self else { return }
            let cancelReason = self.pendingAuthCancelReason
            self.activeAuthSession = nil
            self.activeAuthSessionId = nil
            self.pendingAuthCancelReason = nil

            if let cancelReason = cancelReason {
                self.dispatchClosed(reason: cancelReason, id: id)
                return
            }

            if let error = error {
                let reason = (error as? ASWebAuthenticationSessionError)?.code == .canceledLogin ? "user_cancelled" : "auth_failed"
                self.dispatchClosed(reason: reason, id: id)
                return
            }

            guard let callbackUrl = callbackUrl else {
                self.dispatchClosed(reason: "auth_failed", id: id)
                return
            }

            self.dispatchAuthCompleted(
                callbackUrl: callbackUrl.absoluteString,
                params: BrowserPlugin.parseCallbackParams(callbackUrl),
                id: id
            )
        }

        session.prefersEphemeralWebBrowserSession = ephemeral
        session.presentationContextProvider = authContextProvider

        activeAuthSession = session
        activeAuthSessionId = id

        session.start()
    }

    /// Emits `browser:opened`.
    private func dispatchOpened(url: String, mode: String, id: String?) {
        var payload: [String: Any] = ["url": url, "mode": mode]
        if let id = id { payload["id"] = id }
        emit("browser:opened", payload)
    }

    /// Emits `browser:closed`.
    private func dispatchClosed(reason: String, id: String?) {
        var payload: [String: Any] = ["reason": reason]
        if let id = id { payload["id"] = id }
        emit("browser:closed", payload)
    }

    /// Emits `browser:authCompleted`.
    private func dispatchAuthCompleted(callbackUrl: String, params: [String: String], id: String?) {
        var payload: [String: Any] = ["callbackUrl": callbackUrl, "params": params]
        if let id = id { payload["id"] = id }
        emit("browser:authCompleted", payload)
    }

    /// Query and fragment parameters of a callback URL (fragment values win), as used by OAuth implicit and
    /// code flows.
    private static func parseCallbackParams(_ url: URL) -> [String: String] {
        var result: [String: String] = [:]

        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: true) else {
            return result
        }

        components.queryItems?.forEach { item in
            result[item.name] = item.value ?? ""
        }

        if let fragment = components.fragment, !fragment.isEmpty {
            URLComponents(string: "?" + fragment)?.queryItems?.forEach { item in
                result[item.name] = item.value ?? ""
            }
        }

        return result
    }
}

/// Presents sign-in sessions over the app's key window.
private final class AuthContextProvider: NSObject, ASWebAuthenticationPresentationContextProviding {
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first(where: { $0.isKeyWindow }) ?? ASPresentationAnchor()
    }
}

/// The in-app browser: a web view under a header with the page title and host, a back or close button, a
/// progress bar, and a menu with share, copy link and open in Safari.
final class BrowserViewController: UIViewController, WKNavigationDelegate {

    private static let headerHeight: CGFloat = 52

    private let pageUrl: String
    private let titleOverride: String?
    private let showToolbar: Bool
    private let showNavigationButtons: Bool
    private let shareButtonEnabled: Bool
    private let desktopMode: Bool
    let sessionId: String?

    var onOpened: (() -> Void)?
    var onClosed: ((String) -> Void)?

    private var webView: WKWebView!
    private var titleLabel: UILabel?
    private var subtitleLabel: UILabel?
    private var progressView: UIProgressView?
    private var backButton: UIButton?
    private var overflowButton: UIButton?
    private var finished = false
    private var openedFired = false
    private var progressObservation: NSKeyValueObservation?

    /// Creates the browser for `url`. `id` is echoed back in events.
    init(url: String, title: String?, showToolbar: Bool, showNavigationButtons: Bool, shareButton: Bool, desktopMode: Bool, id: String?) {
        self.pageUrl = url
        self.titleOverride = title
        self.showToolbar = showToolbar
        self.showNavigationButtons = showNavigationButtons
        self.shareButtonEnabled = shareButton
        self.desktopMode = desktopMode
        self.sessionId = id
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground

        setUpWebView()
        if showToolbar { setUpToolbar() }
        layoutWebView()

        guard let url = URL(string: pageUrl) else {
            finish(reason: "invalid_url")
            return
        }
        webView.load(URLRequest(url: url))
    }

    /// Creates the web view, with a desktop user agent when `desktopMode` is set.
    private func setUpWebView() {
        let configuration = WKWebViewConfiguration()
        webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = self
        webView.allowsBackForwardNavigationGestures = true
        webView.translatesAutoresizingMaskIntoConstraints = false
        webView.backgroundColor = .systemBackground

        if desktopMode {
            webView.customUserAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"
        }

        view.addSubview(webView)

        progressObservation = webView.observe(\.estimatedProgress, options: [.new]) { [weak self] webView, _ in
            guard let self = self, let progressView = self.progressView else { return }
            progressView.progress = Float(webView.estimatedProgress)
            progressView.isHidden = webView.estimatedProgress >= 1.0
        }
    }

    /// Places the web view below the header (or full screen without a toolbar).
    private func layoutWebView() {
        NSLayoutConstraint.activate([
            webView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            webView.topAnchor.constraint(equalTo: showToolbar ? view.safeAreaLayoutGuide.topAnchor : view.topAnchor, constant: showToolbar ? Self.headerHeight : 0),
            webView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
    }

    /// Builds the header: back or close button, title and host, overflow menu and progress bar.
    private func setUpToolbar() {
        let bar = UIView()
        bar.backgroundColor = .systemBackground
        bar.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(bar)

        let separator = UIView()
        separator.backgroundColor = .separator
        separator.translatesAutoresizingMaskIntoConstraints = false
        bar.addSubview(separator)

        let back = UIButton(type: .system)
        back.setImage(UIImage(systemName: "chevron.left")?.withRenderingMode(.alwaysTemplate), for: .normal)
        styleIconButton(back)
        back.addTarget(self, action: #selector(backOrCloseTapped), for: .touchUpInside)
        bar.addSubview(back)
        backButton = back

        let titleStack = UIStackView()
        titleStack.axis = .vertical
        titleStack.alignment = .center
        titleStack.spacing = 1
        titleStack.translatesAutoresizingMaskIntoConstraints = false
        bar.addSubview(titleStack)

        let label = UILabel()
        label.text = titleOverride ?? (URL(string: pageUrl)?.host ?? pageUrl)
        label.textColor = .label
        label.font = .systemFont(ofSize: 15, weight: .semibold)
        label.textAlignment = .center
        label.lineBreakMode = .byTruncatingMiddle
        titleStack.addArrangedSubview(label)
        titleLabel = label

        let subtitle = UILabel()
        subtitle.textColor = .secondaryLabel
        subtitle.font = .systemFont(ofSize: 12, weight: .regular)
        subtitle.textAlignment = .center
        subtitle.lineBreakMode = .byTruncatingMiddle
        if let devTitle = titleOverride, !devTitle.isEmpty {
            subtitle.text = devTitle
        } else {
            subtitle.isHidden = true
        }
        titleStack.addArrangedSubview(subtitle)
        subtitleLabel = subtitle

        let progress = UIProgressView(progressViewStyle: .default)
        progress.translatesAutoresizingMaskIntoConstraints = false
        bar.addSubview(progress)
        progressView = progress

        let overflow = UIButton(type: .system)
        overflow.setImage(UIImage(systemName: "ellipsis")?.withRenderingMode(.alwaysTemplate), for: .normal)
        styleIconButton(overflow)
        overflow.menu = makeOverflowMenu()
        overflow.showsMenuAsPrimaryAction = true
        bar.addSubview(overflow)
        overflowButton = overflow

        NSLayoutConstraint.activate([
            bar.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            bar.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            bar.topAnchor.constraint(equalTo: view.topAnchor),
            bar.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: Self.headerHeight),

            separator.leadingAnchor.constraint(equalTo: bar.leadingAnchor),
            separator.trailingAnchor.constraint(equalTo: bar.trailingAnchor),
            separator.bottomAnchor.constraint(equalTo: bar.bottomAnchor),
            separator.heightAnchor.constraint(equalToConstant: 1 / UIScreen.main.scale),

            back.leadingAnchor.constraint(equalTo: bar.safeAreaLayoutGuide.leadingAnchor, constant: 8),
            back.centerYAnchor.constraint(equalTo: bar.bottomAnchor, constant: -26),
            back.widthAnchor.constraint(equalToConstant: 36),
            back.heightAnchor.constraint(equalToConstant: 36),

            overflow.trailingAnchor.constraint(equalTo: bar.safeAreaLayoutGuide.trailingAnchor, constant: -8),
            overflow.centerYAnchor.constraint(equalTo: back.centerYAnchor),
            overflow.widthAnchor.constraint(equalToConstant: 36),
            overflow.heightAnchor.constraint(equalToConstant: 36),

            titleStack.centerXAnchor.constraint(equalTo: bar.centerXAnchor),
            titleStack.centerYAnchor.constraint(equalTo: back.centerYAnchor),
            titleStack.leadingAnchor.constraint(greaterThanOrEqualTo: back.trailingAnchor, constant: 8),
            titleStack.trailingAnchor.constraint(lessThanOrEqualTo: overflow.leadingAnchor, constant: -8),

            progress.leadingAnchor.constraint(equalTo: bar.leadingAnchor),
            progress.trailingAnchor.constraint(equalTo: bar.trailingAnchor),
            progress.bottomAnchor.constraint(equalTo: bar.bottomAnchor),
        ])
    }

    /// Applies the header button style.
    private func styleIconButton(_ button: UIButton) {
        button.tintColor = .label
        button.translatesAutoresizingMaskIntoConstraints = false
    }

    /// The overflow menu: share (when enabled), copy link and open in Safari.
    private func makeOverflowMenu() -> UIMenu {
        var actions: [UIAction] = [
            UIAction(title: "Open in Safari", image: UIImage(systemName: "safari")) { [weak self] _ in
                self?.openInExternalBrowser()
            },
            UIAction(title: "Refresh", image: UIImage(systemName: "arrow.clockwise")) { [weak self] _ in
                self?.webView.reload()
            },
            UIAction(title: "Copy Link", image: UIImage(systemName: "doc.on.doc")) { [weak self] _ in
                self?.copyLink()
            },
        ]

        if shareButtonEnabled {
            actions.append(
                UIAction(title: "Share…", image: UIImage(systemName: "square.and.arrow.up")) { [weak self] _ in
                    self?.shareTapped()
                }
            )
        }

        return UIMenu(title: "", children: actions)
    }

    /// Opens the current page in Safari.
    private func openInExternalBrowser() {
        guard let url = webView.url ?? URL(string: pageUrl) else { return }
        UIApplication.shared.open(url, options: [:], completionHandler: nil)
    }

    /// Copies the current page's URL.
    private func copyLink() {
        UIPasteboard.general.string = webView.url?.absoluteString ?? pageUrl
    }

    /// Goes back in the page history, or closes the browser when there is nothing to go back to.
    @objc private func backOrCloseTapped() {
        if showNavigationButtons, webView.canGoBack {
            webView.goBack()
        } else {
            finish(reason: "user_closed")
        }
    }

    /// Shares the current page's URL.
    @objc private func shareTapped() {
        let shareUrl = webView.url?.absoluteString ?? pageUrl
        guard let url = URL(string: shareUrl) else { return }
        let activityController = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        present(activityController, animated: true)
    }

    /// Updates the title and reports the browser as opened after the first page loads.
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        titleLabel?.text = webView.title?.isEmpty == false ? webView.title : (URL(string: pageUrl)?.host ?? pageUrl)

        if !openedFired {
            openedFired = true
            onOpened?()
        }
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        NSLog("%@", "[BrowserPlugin] WebView load error: \(error.localizedDescription)")
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        NSLog("%@", "[BrowserPlugin] WebView load error: \(error.localizedDescription)")
    }

    /// Dismisses the browser once and reports why it closed.
    func finish(reason: String) {
        guard !finished else { return }
        finished = true

        progressObservation?.invalidate()
        webView.stopLoading()

        dismiss(animated: true)

        onClosed?(reason)
    }
}
