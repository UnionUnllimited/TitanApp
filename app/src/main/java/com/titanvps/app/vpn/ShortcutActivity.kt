package com.titanvps.app.vpn

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.titanvps.app.MainActivity
import com.titanvps.app.TitanApp
import com.titanvps.app.data.Flags
import com.titanvps.app.data.ServerGroups

/**
 * Launcher shortcuts «Включить» / «Выключить» / «Переключить» (Siri's VPN intents on iOS):
 * does it without showing the app and closes. Not exported, so no other app can turn the
 * VPN off through it; the launcher starts it as our app.
 */
class ShortcutActivity : ComponentActivity() {

    // The VPN consent dialog the first time; then on as asked.
    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) turnOn()
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreated while the consent dialog is up: its result comes to [consent].
        if (savedInstanceState != null) return
        val state = VpnStatus.state.value
        val on = when (intent?.action) {
            ACTION_CONNECT -> true
            ACTION_DISCONNECT -> false
            ACTION_TOGGLE -> state is VpnState.Disconnected || state is VpnState.Error
            else -> { finish(); return }
        }
        if (on) connect() else disconnect()
    }

    private fun connect() {
        val app = TitanApp.get(this)
        if (app.repository.subscription.value == null) {
            toast("Добавьте ключ подписки в приложении Titan VPS")
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        val server = app.repository.selectedServer()
        val message = when {
            server == null -> "В подписке нет серверов. Обновите её в приложении Titan VPS"
            ServerGroups.isBypass(server) && !app.settings.techMode.value && !app.network.current() ->
                "Обходы работают только через мобильный интернет. На Wi-Fi выберите другой сервер в приложении"
            VpnStatus.state.value is VpnState.Connected -> "Titan VPS уже включён"
            else -> null
        }
        if (message != null) {
            toast(message)
            finish()
            return
        }
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            consent.launch(prepare)
            return
        }
        turnOn()
        finish()
    }

    private fun turnOn() {
        val name = TitanApp.get(this).repository.selectedServer()?.name.orEmpty()
        TitanVpnService.start(this)
        toast("Включаем Titan VPS: " + Flags.nameWithoutFlag(name))
    }

    private fun disconnect() {
        val st = VpnStatus.state.value
        if (st is VpnState.Connected || st == VpnState.Connecting) {
            TitanVpnService.stop(this)
            toast("Titan VPS выключен")
        } else {
            toast("Titan VPS уже выключен")
        }
        finish()
    }

    /** No window of the app is shown: the system toast. */
    private fun toast(text: String) {
        Toast.makeText(applicationContext, text, if (text.length > 40) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val ACTION_CONNECT = "com.titanvps.app.action.CONNECT"
        const val ACTION_DISCONNECT = "com.titanvps.app.action.DISCONNECT"
        const val ACTION_TOGGLE = "com.titanvps.app.action.TOGGLE"
    }
}
