package com.titanvps.app.vpn

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.app.PendingIntent
import com.titanvps.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Quick Settings tile: one tap to connect / disconnect. */
class VpnTileService : TileService() {

    private var job: Job? = null

    override fun onStartListening() {
        job = CoroutineScope(Dispatchers.Main).launch {
            VpnStatus.state.collect { render(it) }
        }
    }

    override fun onStopListening() {
        job?.cancel()
    }

    override fun onClick() {
        when (VpnStatus.state.value) {
            is VpnState.Connected, VpnState.Connecting -> TitanVpnService.stop(this)
            else -> {
                if (VpnService.prepare(this) != null) {
                    // Permission not granted yet — open the app to ask for it.
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
        tile.state = when (state) {
            is VpnState.Connected -> Tile.STATE_ACTIVE
            VpnState.Connecting -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = (state as? VpnState.Connected)?.serverName
        }
        tile.updateTile()
    }
}
