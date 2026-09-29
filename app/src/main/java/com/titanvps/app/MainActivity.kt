package com.titanvps.app

import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
            TitanTheme {
                TitanScreen(viewModel = viewModel, onConnect = ::connect)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    private fun handleLink(intent: Intent?) {
        val data = intent?.dataString ?: return
        if (intent.action == Intent.ACTION_VIEW) viewModel.activate(data)
    }

    private fun connect() {
        val prepare = VpnService.prepare(this)
        if (prepare != null) vpnPermission.launch(prepare) else TitanVpnService.start(this)
    }
}
