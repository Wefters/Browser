package dev.wefter.bridge

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.FragmentActivity
import org.json.JSONObject

private fun JSONObject.optNullableString(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null

class BrowserPlugin(context: Context, dispatcher: BridgeDispatcher) :
        WefterPlugin(context, dispatcher) {

    private val activity: FragmentActivity
        get() = context as FragmentActivity

    @Volatile private var activeOverlay: BrowserOverlay? = null

    init {
        instance = this
    }

    @WefterMethod
    fun open(payload: JSONObject, callback: (Result<Any>) -> Unit) {
        val url = payload.optString("url", "")
        if (url.isBlank()) {
            reject(callback, "INVALID_URL", "A URL must be provided.")
            return
        }

        val mode = payload.optString("mode", "webview")
        if (mode != "webview" && mode != "external") {
            reject(
                    callback,
                    "INVALID_MODE",
                    "Invalid browser mode: $mode. Valid modes are: $VALID_MODES."
            )
            return
        }

        val id = payload.optNullableString("id")

        if (mode == "external") {
            openExternal(url, id, callback)
            return
        }

        val title = payload.optNullableString("title")
        val showToolbar = payload.optBoolean("showToolbar", true)
        val showNavigationButtons = payload.optBoolean("showNavigationButtons", true)
        val shareButton = payload.optBoolean("shareButton", true)
        val desktopMode = payload.optBoolean("desktopMode", false)

        activity.runOnUiThread {
            activeOverlay?.finish(reason = "replaced")
            BrowserAuthActivity.activeInstance?.cancelFromApp("replaced")
            val overlay =
                    BrowserOverlay(
                            url,
                            title,
                            showToolbar,
                            showNavigationButtons,
                            shareButton,
                            desktopMode,
                            id
                    )
            activeOverlay = overlay
            overlay.show()
        }

        resolve(callback, JSONObject().put("started", true))
    }

    @WefterMethod
    fun close(payload: JSONObject, callback: (Result<Any>) -> Unit) {
        val id = payload.optNullableString("id")

        val authSession = BrowserAuthActivity.activeInstance
        if (authSession != null && (id == null || authSession.sessionId == id)) {
            activity.runOnUiThread { authSession.cancelFromApp("closed_by_app") }
            resolve(callback, JSONObject().put("closed", true))
            return
        }

        val overlay = activeOverlay
        if (overlay == null || (id != null && overlay.id != id)) {
            resolve(callback, JSONObject().put("closed", false))
            return
        }

        activity.runOnUiThread { overlay.finish(reason = "closed_by_app") }
        resolve(callback, JSONObject().put("closed", true))
    }

    @WefterMethod
    fun auth(payload: JSONObject, callback: (Result<Any>) -> Unit) {
        val url = payload.optString("url", "")
        if (url.isBlank()) {
            reject(callback, "INVALID_URL", "An authorize URL must be provided.")
            return
        }

        val redirectUri = payload.optNullableString("redirectUri")
        val scheme = redirectUri?.let { Uri.parse(it).scheme }
        if (redirectUri.isNullOrBlank() || scheme.isNullOrBlank()) {
            reject(
                    callback,
                    "INVALID_REDIRECT_URI",
                    "A valid redirectUri with a scheme must be provided."
            )
            return
        }
        if (scheme != AUTH_REDIRECT_SCHEME) {
            reject(
                    callback,
                    "UNSUPPORTED_REDIRECT_SCHEME",
                    "redirectUri must use the \"$AUTH_REDIRECT_SCHEME://\" scheme so Android can route the OAuth callback back into the app, e.g. $AUTH_REDIRECT_SCHEME://callback. This also requires an <activity>/<intent-filter> entry in AndroidManifest.xml — see this plugin's README."
            )
            return
        }

        val id = payload.optNullableString("id")

        activity.runOnUiThread {
            activeOverlay?.finish(reason = "replaced")
            BrowserAuthActivity.activeInstance?.cancelFromApp("replaced")

            val intent =
                    Intent(activity, BrowserAuthActivity::class.java).apply {
                        putExtra(BrowserAuthActivity.EXTRA_AUTHORIZE_URL, url)
                        putExtra(BrowserAuthActivity.EXTRA_REDIRECT_URI, redirectUri)
                        if (id != null) putExtra(BrowserAuthActivity.EXTRA_ID, id)
                    }
            activity.startActivity(intent)
        }

        resolve(callback, JSONObject().put("started", true))
    }

    private fun openExternal(url: String, id: String?, callback: (Result<Any>) -> Unit) {
        val uri =
                try {
                    Uri.parse(url)
                } catch (e: Exception) {
                    reject(callback, "INVALID_URL", "Could not parse URL: $url")
                    return
                }

        val intent =
                Intent(Intent.ACTION_VIEW, uri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "No application available to open URL: $url", e)
            reject(
                    callback,
                    "NO_BROWSER_AVAILABLE",
                    "No application is available to open this URL."
            )
            return
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch external browser", e)
            reject(callback, "LAUNCH_FAILED", "Failed to open the external browser.")
            return
        }

        dispatchOpened(url, "external", id)
        resolve(callback, JSONObject().put("started", true))
    }

    internal fun dispatchOpened(url: String, mode: String, id: String?) {
        val payload = JSONObject().put("url", url).put("mode", mode)
        if (id != null) payload.put("id", id)
        emit("browser:opened", payload)
    }

    internal fun dispatchClosed(reason: String, id: String?) {
        val payload = JSONObject().put("reason", reason)
        if (id != null) payload.put("id", id)
        emit("browser:closed", payload)
    }

    internal fun dispatchAuthCompleted(
            callbackUrl: String,
            params: Map<String, String>,
            id: String?
    ) {
        val payload = JSONObject().put("callbackUrl", callbackUrl).put("params", JSONObject(params))
        if (id != null) payload.put("id", id)
        emit("browser:authCompleted", payload)
    }

    private class BrowserTheme(isDark: Boolean) {
        val headerBackground = if (isDark) Color.parseColor("#1C1C1E") else Color.WHITE
        val contentBackground = if (isDark) Color.parseColor("#000000") else Color.WHITE
        val textPrimary = if (isDark) Color.parseColor("#FFFFFF") else Color.parseColor("#0D0D0D")
        val textSecondary = Color.parseColor("#8E8E93")
        val divider = if (isDark) Color.parseColor("#38383A") else Color.parseColor("#E5E5EA")
        val accent = Color.parseColor("#0A84FF")
        val lightStatusBarIcons = isDark
    }

    private class IconButtonView(
            context: Context,
            var tint: Int,
            initialGlyph: (Canvas, Float, Float, Float, Int) -> Unit,
    ) : View(context) {
        var glyph: (Canvas, Float, Float, Float, Int) -> Unit = initialGlyph
            set(value) {
                field = value
                invalidate()
            }

        init {
            isClickable = true
            isFocusable = true
            val outValue = android.util.TypedValue()
            context.theme.resolveAttribute(
                    android.R.attr.selectableItemBackgroundBorderless,
                    outValue,
                    true
            )
            if (outValue.resourceId != 0) setBackgroundResource(outValue.resourceId)
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val radius = minOf(width, height) / 2f
            glyph(canvas, cx, cy, radius * 0.55f, tint)
        }
    }

    private inner class BrowserOverlay(
            private val url: String,
            private val titleOverride: String?,
            private val showToolbar: Boolean,
            private val showNavigationButtons: Boolean,
            private val shareButtonEnabled: Boolean,
            private val desktopMode: Boolean,
            val id: String?,
    ) {
        private val root = activity.findViewById<ViewGroup>(android.R.id.content)
        private val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        private val openedFired = java.util.concurrent.atomic.AtomicBoolean(false)

        private var overlayView: FrameLayout? = null
        private var webView: WebView? = null
        private var mainTitleLabel: TextView? = null
        private var progressBar: ProgressBar? = null
        private var previousLightStatusBars: Boolean? = null

        private val backPressedCallback =
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        val wv = webView
                        if (wv != null && wv.canGoBack()) {
                            wv.goBack()
                        } else {
                            finish(reason = "user_closed")
                        }
                    }
                }

        private fun dp(value: Int): Int =
                (value * activity.resources.displayMetrics.density).toInt()

        private fun handleBackButtonTap() {
            val wv = webView
            if (showNavigationButtons && wv != null && wv.canGoBack()) {
                wv.goBack()
            } else {
                finish(reason = "user_closed")
            }
        }

        fun show() {
            val isDark =
                    (activity.resources.configuration.uiMode and
                            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val theme = BrowserTheme(isDark)

            val insetsController =
                    WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            previousLightStatusBars = insetsController.isAppearanceLightStatusBars
            insetsController.isAppearanceLightStatusBars = !theme.lightStatusBarIcons

            val toolbarHeight = if (showToolbar) dp(56) else 0

            val webView =
                    WebView(activity).apply {
                        layoutParams =
                                FrameLayout.LayoutParams(
                                                FrameLayout.LayoutParams.MATCH_PARENT,
                                                FrameLayout.LayoutParams.MATCH_PARENT
                                        )
                                        .apply { topMargin = toolbarHeight }
                        setBackgroundColor(theme.contentBackground)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        if (desktopMode) {
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.userAgentString =
                                    settings.userAgentString
                                            ?.replace("Mobile", "")
                                            ?.plus(" Desktop")
                        }
                        webViewClient =
                                object : WebViewClient() {
                                    override fun onPageFinished(
                                            view: WebView,
                                            finishedUrl: String
                                    ) {
                                        super.onPageFinished(view, finishedUrl)
                                        progressBar?.visibility = View.GONE
                                        mainTitleLabel?.text =
                                                view.title?.takeIf { it.isNotBlank() }
                                                        ?: (Uri.parse(finishedUrl).host
                                                                ?: finishedUrl)
                                        if (openedFired.compareAndSet(false, true)) {
                                            dispatchOpened(
                                                    this@BrowserOverlay.url,
                                                    "webview",
                                                    this@BrowserOverlay.id
                                            )
                                        }
                                    }

                                    override fun onReceivedError(
                                            view: WebView,
                                            request: WebResourceRequest,
                                            error: WebResourceError
                                    ) {
                                        super.onReceivedError(view, request, error)
                                        if (request.isForMainFrame) {
                                            Log.w(
                                                    TAG,
                                                    "WebView load error ${error.errorCode}: ${error.description}"
                                            )
                                        }
                                    }
                                }
                        webChromeClient =
                                object : WebChromeClient() {
                                    override fun onProgressChanged(
                                            view: WebView,
                                            newProgress: Int
                                    ) {
                                        super.onProgressChanged(view, newProgress)
                                        progressBar?.apply {
                                            visibility =
                                                    if (newProgress >= 100) View.GONE
                                                    else View.VISIBLE
                                            progress = newProgress
                                        }
                                    }
                                }
                    }
            this.webView = webView

            val overlay =
                    FrameLayout(activity).apply {
                        setBackgroundColor(theme.contentBackground)
                        addView(webView)
                        layoutParams =
                                FrameLayout.LayoutParams(
                                        FrameLayout.LayoutParams.MATCH_PARENT,
                                        FrameLayout.LayoutParams.MATCH_PARENT
                                )
                    }

            val headerView = if (showToolbar) buildHeader(toolbarHeight, theme) else null
            headerView?.let { overlay.addView(it) }

            ViewCompat.setOnApplyWindowInsetsListener(overlay) { _, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

                headerView?.let {
                    it.updatePadding(top = bars.top)
                    (it.layoutParams as FrameLayout.LayoutParams).height = toolbarHeight + bars.top
                    it.requestLayout()
                }

                (webView.layoutParams as FrameLayout.LayoutParams).apply {
                    topMargin = toolbarHeight + bars.top
                }
                webView.requestLayout()

                insets
            }

            overlayView = overlay
            root.addView(overlay)

            activity.onBackPressedDispatcher.addCallback(backPressedCallback)

            webView.loadUrl(url)
        }

        private fun buildHeader(height: Int, theme: BrowserTheme): View {
            val bar =
                    FrameLayout(activity).apply {
                        setBackgroundColor(theme.headerBackground)
                        layoutParams =
                                FrameLayout.LayoutParams(
                                                FrameLayout.LayoutParams.MATCH_PARENT,
                                                height
                                        )
                                        .apply { gravity = Gravity.TOP }
                    }

            val divider =
                    View(activity).apply {
                        setBackgroundColor(theme.divider)
                        layoutParams =
                                FrameLayout.LayoutParams(
                                                FrameLayout.LayoutParams.MATCH_PARENT,
                                                dp(1)
                                        )
                                        .apply { gravity = Gravity.BOTTOM }
                    }
            bar.addView(divider)

            val backButton =
                    IconButtonView(activity, theme.textPrimary) { canvas, cx, cy, r, color ->
                        drawBackIcon(canvas, cx, cy, r, color)
                    }
                            .apply {
                                layoutParams =
                                        FrameLayout.LayoutParams(
                                                        dp(44),
                                                        dp(44),
                                                        Gravity.CENTER_VERTICAL or Gravity.START
                                                )
                                                .apply { leftMargin = dp(4) }
                                setOnClickListener { handleBackButtonTap() }
                            }
            bar.addView(backButton)

            val titleContainer =
                    LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        layoutParams =
                                FrameLayout.LayoutParams(
                                                FrameLayout.LayoutParams.MATCH_PARENT,
                                                FrameLayout.LayoutParams.WRAP_CONTENT,
                                                Gravity.CENTER
                                        )
                                        .apply {
                                            leftMargin = dp(52)
                                            rightMargin = dp(52)
                                        }
                    }

            val mainTitle =
                    TextView(activity).apply {
                        text = titleOverride ?: (Uri.parse(url).host ?: url)
                        setTextColor(theme.textPrimary)
                        textSize = 14f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                        gravity = Gravity.CENTER
                    }
            mainTitleLabel = mainTitle
            titleContainer.addView(mainTitle)

            if (!titleOverride.isNullOrBlank()) {
                val subtitle =
                        TextView(activity).apply {
                            text = titleOverride
                            setTextColor(theme.textSecondary)
                            textSize = 12f
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                            gravity = Gravity.CENTER
                        }
                titleContainer.addView(subtitle)
            }

            bar.addView(titleContainer)

            val moreButton =
                    IconButtonView(activity, theme.textPrimary) { canvas, cx, cy, r, color ->
                        drawMoreIcon(canvas, cx, cy, r, color)
                    }
                            .apply {
                                layoutParams =
                                        FrameLayout.LayoutParams(
                                                        dp(44),
                                                        dp(44),
                                                        Gravity.CENTER_VERTICAL or Gravity.END
                                                )
                                                .apply { rightMargin = dp(4) }
                                setOnClickListener { showOverflowMenu(this) }
                            }
            bar.addView(moreButton)

            val progress =
                    ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                        max = 100
                        progressTintList = android.content.res.ColorStateList.valueOf(theme.accent)
                        layoutParams =
                                FrameLayout.LayoutParams(
                                        FrameLayout.LayoutParams.MATCH_PARENT,
                                        dp(2),
                                        Gravity.BOTTOM
                                )
                    }
            progressBar = progress
            bar.addView(progress)

            return bar
        }

        private fun showOverflowMenu(anchor: View) {
            val popup = PopupMenu(activity, anchor)
            popup.menu.add(0, 1, 0, "Open in Chrome")
            popup.menu.add(0, 2, 1, "Refresh")
            popup.menu.add(0, 3, 2, "Copy Link")
            if (shareButtonEnabled) popup.menu.add(0, 4, 3, "Share via…")
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> openInExternalBrowser()
                    2 -> webView?.reload()
                    3 -> copyLinkToClipboard()
                    4 -> shareCurrentUrl()
                }
                true
            }
            popup.show()
        }

        private fun openInExternalBrowser() {
            val link = webView?.url ?: url
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to open external browser", e)
            }
        }

        private fun copyLinkToClipboard() {
            val link = webView?.url ?: url
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Link", link))
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(activity, "Link copied", Toast.LENGTH_SHORT).show()
            }
        }

        private fun shareCurrentUrl() {
            val shareUrl = webView?.url ?: url
            val intent =
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareUrl)
                    }
            try {
                activity.startActivity(Intent.createChooser(intent, null))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to open share sheet", e)
            }
        }

        fun finish(reason: String) {
            if (!finished.compareAndSet(false, true)) {
                return
            }

            if (activeOverlay === this) {
                activeOverlay = null
            }

            activity.runOnUiThread {
                backPressedCallback.remove()
                webView?.stopLoading()
                webView?.destroy()
                overlayView?.let { root.removeView(it) }

                previousLightStatusBars?.let {
                    WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                            .isAppearanceLightStatusBars = it
                }

                dispatchClosed(reason, id)
            }
        }
    }

    companion object {
        private const val TAG = "BrowserPlugin"
        private const val VALID_MODES = "webview, external"
        const val AUTH_REDIRECT_SCHEME = "wefter"

        @Volatile private var instance: BrowserPlugin? = null

        internal fun current(): BrowserPlugin? = instance
    }
}

private fun drawBackIcon(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
    val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.FILL
            }
    val scale = (r * 2f) / 24f
    val ox = cx - 12f * scale
    val oy = cy - 12f * scale
    val points =
            floatArrayOf(
                    20f,
                    11f,
                    7.83f,
                    11f,
                    13.42f,
                    5.41f,
                    12f,
                    4f,
                    4f,
                    12f,
                    12f,
                    20f,
                    13.41f,
                    18.59f,
                    7.83f,
                    13f,
                    20f,
                    13f
            )
    val path = Path()
    for (i in points.indices step 2) {
        val px = ox + points[i] * scale
        val py = oy + points[i + 1] * scale
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    path.close()
    canvas.drawPath(path, paint)
}

private fun drawMoreIcon(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
    val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.FILL
            }
    val dotRadius = r * 0.13f
    val spacing = r * 0.55f
    canvas.drawCircle(cx, cy - spacing, dotRadius, paint)
    canvas.drawCircle(cx, cy, dotRadius, paint)
    canvas.drawCircle(cx, cy + spacing, dotRadius, paint)
}

class BrowserAuthActivity : FragmentActivity() {

    companion object {
        private const val TAG = "BrowserAuthActivity"
        const val EXTRA_AUTHORIZE_URL = "authorize_url"
        const val EXTRA_REDIRECT_URI = "redirect_uri"
        const val EXTRA_ID = "id"

        @Volatile var activeInstance: BrowserAuthActivity? = null
    }

    private var redirectUri: String = ""
    var sessionId: String? = null
        private set

    private var authorizationStarted = false
    private var redirectReceived = false
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val authorizeUrl = intent?.getStringExtra(EXTRA_AUTHORIZE_URL)
        redirectUri = intent?.getStringExtra(EXTRA_REDIRECT_URI) ?: ""
        sessionId = intent?.getStringExtra(EXTRA_ID)

        if (authorizeUrl.isNullOrBlank() || redirectUri.isBlank()) {
            finish()
            return
        }

        activeInstance = this

        val customTabsIntent = CustomTabsIntent.Builder().build()
        try {
            customTabsIntent.launchUrl(this, Uri.parse(authorizeUrl))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Custom Tabs for authorization", e)
            completeWithClosed("no_browser_available")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val uri = intent.data ?: return
        val callbackUrl = uri.toString()
        if (!callbackUrl.startsWith(redirectUri)) return

        redirectReceived = true
        completeWithSuccess(callbackUrl, parseCallbackParams(uri))
    }

    override fun onResume() {
        super.onResume()

        if (!authorizationStarted) {
            authorizationStarted = true
            return
        }

        if (!redirectReceived && !finished) {
            completeWithClosed("user_cancelled")
        }
    }

    fun cancelFromApp(reason: String) {
        completeWithClosed(reason)
    }

    private fun completeWithSuccess(callbackUrl: String, params: Map<String, String>) {
        if (!markFinished()) return
        BrowserPlugin.current()?.dispatchAuthCompleted(callbackUrl, params, sessionId)
        finish()
    }

    private fun completeWithClosed(reason: String) {
        if (!markFinished()) return
        BrowserPlugin.current()?.dispatchClosed(reason, sessionId)
        finish()
    }

    private fun markFinished(): Boolean {
        if (finished) return false
        finished = true
        if (activeInstance === this) activeInstance = null
        return true
    }

    private fun parseCallbackParams(uri: Uri): Map<String, String> {
        val result = mutableMapOf<String, String>()

        uri.queryParameterNames.forEach { name ->
            uri.getQueryParameter(name)?.let { result[name] = it }
        }

        uri.fragment?.let { fragment ->
            fragment.split("&").forEach { pair ->
                val parts = pair.split("=", limit = 2)
                if (parts.size == 2) result[Uri.decode(parts[0])] = Uri.decode(parts[1])
            }
        }

        return result
    }

    override fun onDestroy() {
        super.onDestroy()
        if (activeInstance === this) activeInstance = null
    }
}
