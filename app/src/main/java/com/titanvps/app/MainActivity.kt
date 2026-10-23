package com.titanvps.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import android.graphics.Color
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.titanvps.app.ui.theme.isDark
import com.titanvps.app.vpn.VpnState
import com.titanvps.app.vpn.VpnStatus
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import com.titanvps.app.reminders.Reminders
import com.titanvps.app.ui.MainViewModel
import com.titanvps.app.ui.TitanScreen
import com.titanvps.app.ui.kit.AppNav
import com.titanvps.app.ui.theme.TitanTheme
import com.titanvps.app.vpn.KeepAlive
import com.titanvps.app.vpn.TitanVpnService

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) TitanVpnService.start(this)
    }

    // Allowed now: the VPN notification that was hidden appears, a due traffic reminder too.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                TitanVpnService.reshow(this)
                Reminders.reschedule(this)
            }
        }

    /** Back from the background (or the first start): the subscription is refreshed quietly. */
    private var wasStopped = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleLink(intent)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // A newly added key connects immediately; starting our VPN makes Android
                // disconnect any other VPN app (only one VPN can be active).
                launch { viewModel.connectRequests.collect { connect() } }
                // Notifications are asked for once, after the first connect (not next to the
                // VPN prompt when the key is added).
                launch { VpnStatus.state.collect { if (it is VpnState.Connected) askNotificationsOnce() } }
            }
        }

        setContent {
            val mode by viewModel.theme.collectAsState()
            val dark = isDark(mode)
            // Status/navigation bar icons follow the app theme, not only the system one.
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            TitanTheme(mode) {
                TitanScreen(viewModel = viewModel, onConnect = ::connect)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    private fun handleLink(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_UPDATE, false) == true) {
            intent.removeExtra(EXTRA_OPEN_UPDATE)
            viewModel.checkUpdate()
            return
        }
        // A reminder was tapped: Подписка.
        if (intent?.getBooleanExtra(EXTRA_OPEN_SUBSCRIPTION, false) == true) {
            intent.removeExtra(EXTRA_OPEN_SUBSCRIPTION)
            AppNav.open(AppNav.SUBSCRIPTION)
            return
        }
        val data = intent?.dataString ?: return
        if (intent.action == Intent.ACTION_VIEW) viewModel.activate(data)
    }

    private fun connect() {
        if (!viewModel.canConnect()) return
        val prepare = VpnService.prepare(this)
        if (prepare != null) vpnPermission.launch(prepare) else TitanVpnService.start(this)
    }

    // Автоподключение on opening the app lives in TitanApp (it knows when the whole app
    // comes back, not only this screen).
    override fun onStart() {
        super.onStart()
        if (wasStopped) {
            wasStopped = false
            quietRefresh()
        }
    }

    override fun onStop() {
        super.onStop()
        wasStopped = !isChangingConfigurations
    }

    /** A subscription older than 30 minutes is fetched again, without messages. */
    private fun quietRefresh() {
        val sub = TitanApp.get(this).repository.subscription.value ?: return
        // Already fetching (the view model refreshes a stale one when it starts).
        if (viewModel.busy.value) return
        if (System.currentTimeMillis() - sub.fetchedAt > QUIET_REFRESH_MS) viewModel.refresh(silent = true)
    }

    /** Android 13+: the notification permission, asked once after the first connect. */
    private fun askNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = KeepAlive.prefs(this)
        if (prefs.getBoolean(KEY_NOTIFICATIONS_ASKED, false)) return
        prefs.edit().putBoolean(KEY_NOTIFICATIONS_ASKED, true).apply()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        const val EXTRA_OPEN_UPDATE = "open_update"
        /** Opens Подписка (the reminders' notifications). */
        const val EXTRA_OPEN_SUBSCRIPTION = "open_subscription"
        private const val KEY_NOTIFICATIONS_ASKED = "notif_asked"
        private const val QUIET_REFRESH_MS = 30 * 60_000L
    }
}
