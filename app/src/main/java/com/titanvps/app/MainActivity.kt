package com.titanvps.app

import android.Manifest
import android.content.Intent
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
import com.titanvps.app.ui.MainViewModel
import com.titanvps.app.ui.TitanScreen
import com.titanvps.app.ui.theme.TitanTheme
import com.titanvps.app.vpn.TitanVpnService

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) TitanVpnService.start(this)
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (savedInstanceState == null) handleLink(intent)

        // A newly added key connects immediately; starting our VPN makes Android
        // disconnect any other VPN app (only one VPN can be active).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.connectRequests.collect { connect() }
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
                // Text a notch smaller than the system default everywhere.
                val density = androidx.compose.ui.platform.LocalDensity.current
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides
                        androidx.compose.ui.unit.Density(density.density, density.fontScale * 0.88f),
                ) {
                    TitanScreen(viewModel = viewModel, onConnect = ::connect)
                }
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
        val data = intent?.dataString ?: return
        if (intent.action == Intent.ACTION_VIEW) viewModel.activate(data)
    }

    private fun connect() {
        if (!viewModel.canConnect()) return
        val prepare = VpnService.prepare(this)
        if (prepare != null) vpnPermission.launch(prepare) else TitanVpnService.start(this)
    }

    companion object {
        const val EXTRA_OPEN_UPDATE = "open_update"
    }
}
