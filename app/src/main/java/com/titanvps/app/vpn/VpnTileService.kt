package com.titanvps.app.vpn

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.titanvps.app.MainActivity
import com.titanvps.app.TitanApp
import com.titanvps.app.data.Flags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Quick Settings tile (the Control Center toggle of iOS): one tap to connect / disconnect,
 * the server under the name. TitanApp asks Android to redraw it on every VPN change.
 */
class VpnTileService : TileService() {

    private var job: Job? = null

    override fun onTileAdded() {
        super.onTileAdded()
        // qsTile may not be bound yet here: a listening pass draws it.
        runCatching { TileService.requestListeningState(this, ComponentName(this, VpnTileService::class.java)) }
    }

    override fun onStartListening() {
        super.onStartListening()
        job?.cancel()
        job = CoroutineScope(Dispatchers.Main).launch {
            VpnStatus.state.collect { render(it) }
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        job?.cancel()
    }

    override fun onClick() {
        when (VpnStatus.state.value) {
            is VpnState.Connected, VpnState.Connecting -> TitanVpnService.stop(this)
            VpnState.Disconnecting -> Unit
            else -> {
                if (TitanApp.get(this).repository.subscription.value == null || VpnService.prepare(this) != null) {
                    // No key or no VPN permission yet — open the app to add / ask for it.
                    val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (Build.VERSION.SDK_INT >= 34) {
                        startActivityAndCollapse(
                            PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        startActivityAndCollapse(intent)
                    }
                } else {
                    TitanVpnService.start(this)
                }
            }
        }
    }

    private fun render(state: VpnState) {
        val tile = qsTile ?: return
        val hasKey = TitanApp.get(this).repository.subscription.value != null
        // No key: still clickable (an unavailable tile gets no taps), it opens the app.
        tile.state = when (state) {
            is VpnState.Connected, VpnState.Connecting -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when {
                !hasKey -> "Добавьте ключ"
                state is VpnState.Connected -> Flags.nameWithoutFlag(state.serverName)
                state == VpnState.Connecting -> "Подключаемся…"
                else -> null
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            tile.stateDescription = when (state) {
                is VpnState.Connected -> "Подключено"
                VpnState.Connecting -> "Подключаемся…"
                else -> "Не подключено"
            }
        }
        tile.updateTile()
    }
}
