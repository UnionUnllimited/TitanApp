package com.titanvps.app.ui

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.titanvps.app.BuildConfig
import com.titanvps.app.data.SubscriptionLinkFinder
import org.json.JSONArray

/**
 * The website's own login page (e-mail code / Telegram) inside the app. After login we
 * watch the cabinet for a subscription link on our hosts and hand it to the app.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(finder: SubscriptionLinkFinder, onSubscription: (String) -> Unit, onClose: () -> Unit) {
    var progress by remember { mutableIntStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var found by remember { mutableStateOf(false) }

    fun deliver(url: String) {
        if (found) return
        found = true
        onSubscription(url)
    }

    BackHandler {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else onClose()
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Закрыть") }
            Text("Вход в TitanVPS", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        if (progress in 1..99) {
            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        }
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        setBackgroundColor(android.graphics.Color.parseColor("#0B0F1A"))
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.setSupportMultipleWindows(true)
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        val client = LoginWebViewClient(finder, ::deliver)
                        webViewClient = client
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress
                            }

                            // Telegram login opens oauth.telegram.org in a popup window.
                            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                                val popup = WebView(view.context)
                                popup.settings.javaScriptEnabled = true
                                popup.settings.domStorageEnabled = true
                                CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true)
                                val dialog = Dialog(view.context, android.R.style.Theme_DeviceDefault_NoActionBar)
                                popup.webViewClient = client
                                popup.webChromeClient = object : WebChromeClient() {
                                    override fun onCloseWindow(window: WebView) {
                                        dialog.dismiss()
                                        window.destroy()
                                    }
                                }
                                dialog.setContentView(popup)
                                dialog.setOnDismissListener { view.reload() }
                                dialog.show()
                                (resultMsg.obj as WebView.WebViewTransport).webView = popup
                                resultMsg.sendToTarget()
                                return true
                            }
                        }
                        loadUrl(BuildConfig.LOGIN_URL)
                        webView = this
                    }
                },
            )
        }
    }

    // Poll the page: the cabinet may render the link with JS after login.
    DisposableEffect(webView) {
        val wv = webView
        val scan = object : Runnable {
            override fun run() {
                if (wv == null || found) return
                wv.evaluateJavascript(SCAN_JS) { result ->
                    val text = runCatching { JSONArray("[$result]").getString(0) }.getOrNull().orEmpty()
                    finder.find(text)?.let(::deliver)
                }
                wv.postDelayed(this, 1500)
            }
        }
        wv?.postDelayed(scan, 1500)
        onDispose {
            wv?.removeCallbacks(scan)
        }
    }
}

private class LoginWebViewClient(
    private val finder: SubscriptionLinkFinder,
    private val onFound: (String) -> Unit,
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        // Any app link carrying our subscription (titanvps://, happ://, v2raytun://…).
        if (!url.startsWith("http")) {
            finder.find(url)?.let { onFound(it); return true }
        }
        val scheme = request.url.scheme?.lowercase()
        val host = request.url.host?.lowercase()
        // Telegram app, mail apps etc. open outside.
        if (scheme != "http" && scheme != "https" || host == "t.me" || host == "telegram.me") {
            runCatching {
                view.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return true
        }
        return false
    }
}

/** Returns page HTML plus every href/value, so links hidden in inputs or JS state are seen. */
private const val SCAN_JS = """
(function(){
  try {
    var parts=[document.documentElement.outerHTML];
    document.querySelectorAll('[href],[value],[data-url],[data-link],[data-clipboard-text]').forEach(function(e){
      ['href','value','data-url','data-link','data-clipboard-text'].forEach(function(a){var v=e.getAttribute(a); if(v) parts.push(v);});
      if (e.value) parts.push(String(e.value));
    });
    return parts.join('\n');
  } catch(e) { return ''; }
})()
"""
