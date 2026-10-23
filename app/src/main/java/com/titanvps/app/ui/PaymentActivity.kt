package com.titanvps.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import com.titanvps.app.TitanApp
import com.titanvps.app.ui.kit.CircleIconButton
import com.titanvps.app.ui.kit.Haptics
import com.titanvps.app.ui.kit.PrimaryButton
import com.titanvps.app.ui.kit.ToastKind
import com.titanvps.app.ui.kit.Toasts
import com.titanvps.app.ui.kit.pressable
import com.titanvps.app.ui.theme.AccentGradient
import com.titanvps.app.ui.theme.ProvideTitanTextScale
import com.titanvps.app.ui.theme.Titan
import com.titanvps.app.ui.theme.TitanTheme
import com.titanvps.app.ui.theme.isDark

/**
 * The payment page of a purchase made in the cabinet screen, inside the app under its own bar;
 * bank apps (SBP) and Telegram open outside. Once the provider sends the payment back to the
 * site, the screen closes by itself (the cabinet then checks the payment).
 */
class PaymentActivity : ComponentActivity() {
    private lateinit var web: WebView
    private var startUrl = ""
    /** The site's host without "www." (the cabinet's); coming back to it ends the payment. */
    private var home: String? = null
    /** A page off the site has opened: only after that does coming back end the payment. */
    private var left = false
    /** The site's page after the provider's: the screen closes once it shows. */
    private var homeward = false
    /** The next page is one back in the history (Назад): a look, not the return. */
    private var backTrip = false
    private var returned = false
    /** A page has been shown: a later failure leaves it on screen and only says so. */
    private var committed = false
    /** The main page that failed; its error page doesn't count as shown. */
    private var failedUrl: String? = null
    private var retryUrl: String? = null
    private var lastUrl: String? = null
    /** The page's process died: this WebView can't be used again, the screen starts over. */
    private var renderGone = false

    private val progress = mutableFloatStateOf(0f)
    private val loading = mutableStateOf(true)
    /** The first page didn't load. */
    private val failure = mutableStateOf(false)
    /** Why, in technical words (shown in Технический режим only); kept while the failure fades out. */
    private val failureDetail = mutableStateOf("")
    /** A dialog of the page, waiting for an answer (the page waits too). */
    private val dialog = mutableStateOf<PageDialog?>(null)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        val subtitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        startUrl = url
        home = intent.getStringExtra(EXTRA_HOME)?.lowercase()?.let(::bare)?.takeIf { it.isNotEmpty() }
        left = savedInstanceState?.getBoolean(KEY_LEFT) ?: false
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            // target=_blank and window.open load here, in place.
            settings.setSupportMultipleWindows(false)
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            // See-through until the first page: the app's background, not a white flash in the dark.
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            webChromeClient = chrome
            webViewClient = client
        }
        val restored = savedInstanceState != null && !savedInstanceState.getBoolean(KEY_RENDER_GONE) &&
            web.restoreState(savedInstanceState) != null
        if (!restored) web.loadUrl(savedInstanceState?.getString(KEY_LAST_URL) ?: url)

        // Back goes back inside the payment page first (also with Android 16's predictive back).
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (web.canGoBack()) {
                        backTrip = true
                        web.goBack()
                    } else {
                        finish()
                    }
                }
            },
        )

        setContent {
            val mode by TitanApp.get(this).settings.theme.collectAsState()
            val dark = isDark(mode)
            // Status/navigation bar icons follow the app theme, as on the main screen.
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            TitanTheme(mode) {
                PaymentScreen(subtitle)
            }
        }
    }

    private val chrome = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress.floatValue = newProgress / 100f
            loading.value = newProgress < 100
        }

        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean =
            ask(DialogKind.ALERT, url, message, null, result)

        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean =
            ask(DialogKind.CONFIRM, url, message, null, result)

        override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean =
            ask(DialogKind.PROMPT, url, message, defaultValue, result)
    }

    private val client = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (openOutside(request.url, asked = request.isForMainFrame || request.hasGesture())) return true
            // A link of the page or a redirect (not called for POSTs: onPageStarted sees those).
            if (request.isForMainFrame) {
                backTrip = false
                track(request.url.toString())
            }
            return false
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            loading.value = true
            lastUrl = url
            val trip = backTrip
            backTrip = false
            if (trip && isHome(url)) {
                // Back on the site's page by Назад: a look, not the return. Back on the provider's,
                // the return counts again.
                left = false
                homeward = false
            } else {
                track(url)
            }
        }

        override fun onPageCommitVisible(view: WebView, url: String?) {
            track(url)
            shown(url)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            shown(url)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            val detail = "${error.description} (${error.errorCode})"
            failedUrl = request.url.toString()
            if (!committed) {
                retryUrl = failedUrl
                failureDetail.value = detail
                failure.value = true
                return
            }
            // A page is on screen (the payment form): only the next one didn't open.
            val tech = TitanApp.get(this@PaymentActivity).settings.techMode.value
            Toasts.show(if (tech) "Страница не открылась: $detail" else "Страница не открылась. Попробуйте ещё раз", ToastKind.WARNING)
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // The page's process died (memory, a crash): a new screen, a new WebView, the same page.
            renderGone = true
            recreate()
            return true
        }
    }

    /**
     * A page of the main frame on its way or shown: off the site, the payment has left it;
     * the site's page after that is the return (it gets the request, the screen closes once it shows).
     */
    private fun track(url: String?) {
        if (!isHome(url)) {
            left = true
            homeward = false
        } else {
            homeward = left
        }
    }

    /** A page is on screen; the site's page after the provider's ends the payment. */
    private fun shown(url: String?) {
        if (url != null && url == failedUrl) return
        if (!committed) {
            committed = true
            // From the first page on, the page's own background (most pages leave it to the default white).
            web.setBackgroundColor(android.graphics.Color.WHITE)
        }
        if (homeward && isHome(url) && !returned) {
            returned = true
            Haptics(window.decorView).success()
            // CabinetActivity checks the payment when it is back on screen.
            finish()
        }
    }

    /** The site itself: its host with or without "www.", and its subdomains. */
    private fun isHome(url: String?): Boolean {
        val site = home ?: return false
        val host = url?.let { Uri.parse(it).host }?.lowercase()?.let(::bare) ?: return false
        return host == site || host.endsWith(".$site")
    }

    private fun retry() {
        failure.value = false
        failedUrl = null
        web.loadUrl(retryUrl ?: web.url ?: startUrl)
    }

    private fun reload() {
        if (failure.value || web.url == null) {
            retry()
        } else {
            failedUrl = null
            web.reload()
        }
    }

    /** http(s) stays here; tg:, bank apps (SBP), intent: links open their app. */
    private fun openOutside(uri: Uri, asked: Boolean): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme == "http" || scheme == "https") {
            // Telegram links go to Telegram.
            if (uri.host == "t.me" || uri.host == "telegram.me") return launch(Intent(Intent.ACTION_VIEW, uri))
            return false
        }
        val intent = if (scheme == "intent") {
            runCatching { Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME) }.getOrNull()?.apply {
                addCategory(Intent.CATEGORY_BROWSABLE); component = null; selector = null
            }
        } else Intent(Intent.ACTION_VIEW, uri)
        if (intent != null && launch(intent)) return true
        // No app for it: an intent: link may name a web fallback. Only a web page, and only for
        // the page itself or a tap: a javascript: or content: one would run in, or replace, the
        // payment page for any frame of it (a third-party iframe too).
        val fallback = runCatching { Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url") }
            .getOrNull()
            ?.takeIf { asked && isWeb(it) }
        if (fallback != null) {
            web.loadUrl(fallback)
        } else if (asked && scheme !in IN_PAGE_SCHEMES) {
            // A tapped link (or the whole page) that nothing could open: say so.
            Toasts.show("Не удалось открыть приложение", ToastKind.WARNING)
        }
        return true
    }

    private fun launch(intent: Intent): Boolean =
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

    /** One dialog at a time: one still open counts as Отмена. */
    private fun ask(kind: DialogKind, url: String?, message: String?, text: String?, result: JsResult): Boolean {
        if (isFinishing || isDestroyed) {
            result.cancel()
            return true
        }
        dialog.value?.result?.cancel()
        val host = url?.let { Uri.parse(it).host }.orEmpty()
        dialog.value = PageDialog(kind, host.ifEmpty { "Оплата" }, message.orEmpty(), text.orEmpty(), result)
        return true
    }

    private fun answer(d: PageDialog, ok: Boolean, text: String) {
        if (dialog.value !== d) return
        dialog.value = null
        when {
            !ok -> d.result.cancel()
            d.kind == DialogKind.PROMPT -> (d.result as? JsPromptResult)?.confirm(text) ?: d.result.confirm()
            else -> d.result.confirm()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // A dead WebView may only be destroyed: the new screen loads the last page instead.
        if (::web.isInitialized && !renderGone) web.saveState(outState)
        outState.putBoolean(KEY_RENDER_GONE, renderGone)
        outState.putString(KEY_LAST_URL, lastUrl)
        outState.putBoolean(KEY_LEFT, left)
    }

    override fun onDestroy() {
        dialog.value?.result?.cancel()
        dialog.value = null
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    @Composable
    private fun PaymentScreen(subtitle: String) {
        Column(Modifier.fillMaxSize().background(Titan.palette.background).safeDrawingPadding()) {
            Bar(subtitle)
            Page()
        }
        dialog.value?.let { PageDialogView(it) }
    }

    /** The web view, and over it the failure of the first page. */
    @Composable
    private fun Page() {
        val techMode by TitanApp.get(this).settings.techMode.collectAsState()
        Box(Modifier.fillMaxSize()) {
            AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
            AnimatedVisibility(failure.value, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
                PageFailure(if (techMode) failureDetail.value else "Проверьте интернет и попробуйте ещё раз") { retry() }
            }
        }
    }

    /** The title in the middle, close on the left, reload on the right; the load line under it. */
    @Composable
    private fun Bar(subtitle: String) {
        val palette = Titan.palette
        val target = progress.floatValue
        val line = remember { Animatable(0f) }
        // Forward smoothly; a new page starting over jumps back.
        LaunchedEffect(target) {
            if (target > line.value) line.animateTo(target, tween(250)) else line.snapTo(target)
        }
        val busy = loading.value
        val lineAlpha by animateFloatAsState(if (busy) 1f else 0f, tween(if (busy) 0 else 350), label = "loadLine")
        Box(Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
            Column(
                Modifier.align(Alignment.Center).padding(horizontal = 68.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text("Оплата", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        color = palette.textSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            CloseCircle(::finish, Modifier.align(Alignment.CenterStart).padding(start = 16.dp))
            CircleIconButton(
                Icons.Filled.Refresh,
                "Обновить страницу",
                onClick = { reload() },
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp),
                size = 32.dp,
            )
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(palette.outline.copy(alpha = 0.7f)),
            )
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .clearAndSetSemantics { }
                    .fillMaxWidth(line.value.coerceIn(0f, 1f))
                    .height(2.5.dp)
                    .graphicsLayer { alpha = lineAlpha }
                    .background(AccentGradient, CircleShape),
            )
        }
    }

    /** The page's alert(), confirm() or prompt(), titled with the page's host. */
    @Composable
    private fun PageDialogView(d: PageDialog) {
        var text by remember(d) { mutableStateOf(d.text) }
        AlertDialog(
            onDismissRequest = { answer(d, false, text) },
            properties = DialogProperties(dismissOnClickOutside = false),
            title = { ProvideTitanTextScale { Text(d.title, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
            text = {
                ProvideTitanTextScale {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (d.message.isNotEmpty()) Text(d.message)
                        if (d.kind == DialogKind.PROMPT) {
                            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), singleLine = true)
                        }
                    }
                }
            },
            confirmButton = {
                ProvideTitanTextScale { TextButton(onClick = { answer(d, true, text) }) { Text("OK") } }
            },
            dismissButton = if (d.kind == DialogKind.ALERT) {
                null
            } else {
                { ProvideTitanTextScale { TextButton(onClick = { answer(d, false, text) }) { Text("Отмена") } } }
            },
        )
    }

    private enum class DialogKind { ALERT, CONFIRM, PROMPT }

    private class PageDialog(
        val kind: DialogKind,
        val title: String,
        val message: String,
        val text: String,
        val result: JsResult,
    )

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_HOME = "home"
        private const val KEY_LAST_URL = "payment_last_url"
        private const val KEY_LEFT = "payment_left"
        private const val KEY_RENDER_GONE = "payment_render_gone"

        /** Loaded in the page itself (or nothing to open): no word when they don't open outside. */
        private val IN_PAGE_SCHEMES = setOf("about", "data", "blob", "javascript")

        private fun bare(host: String): String = host.removePrefix("www.")

        private fun isWeb(url: String): Boolean = Uri.parse(url).scheme?.lowercase().let { it == "http" || it == "https" }

        /**
         * [title]: the tariff and its price, under «Оплата». [homeHost]: the site (the cabinet
         * link's host); its page after the provider's ends the payment.
         */
        fun open(context: Context, url: String, title: String? = null, homeHost: String? = null) {
            context.startActivity(
                Intent(context, PaymentActivity::class.java).putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_TITLE, title).putExtra(EXTRA_HOME, homeHost)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

/** The round close button of the bar: a cross on the track colour. */
@Composable
private fun CloseCircle(onClick: () -> Unit, modifier: Modifier) {
    val palette = Titan.palette
    Box(
        modifier
            .pressable(onClick = onClick, scale = 0.9f)
            .size(32.dp)
            .background(palette.track, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Close, "Закрыть", Modifier.size(13.dp), tint = palette.textSecondary)
    }
}

/** The first page didn't open: over the empty web view. */
@Composable
private fun PageFailure(message: String, onRetry: () -> Unit) {
    val palette = Titan.palette
    Column(
        Modifier
            .fillMaxSize()
            .background(palette.background)
            .padding(32.dp)
            .padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(88.dp).background(palette.primary.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.WifiOff, null, Modifier.size(38.dp), tint = palette.primary)
        }
        Text("Страница оплаты не открылась", fontSize = 19.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(message, color = palette.textSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
        PrimaryButton(
            "Повторить",
            onRetry,
            Modifier.widthIn(max = 240.dp).padding(top = 4.dp),
            icon = Icons.Filled.Refresh,
            minHeight = 50.dp,
        )
    }
}
