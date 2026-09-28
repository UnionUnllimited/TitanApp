package com.titanvps.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.titanvps.app.TitanApp
import com.titanvps.app.core.TcpPing
import com.titanvps.app.data.AuthApi
import com.titanvps.app.vpn.TitanVpnService
import com.titanvps.app.vpn.VpnState
import com.titanvps.app.vpn.VpnStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = TitanApp.get(app).repository
    private val auth = AuthApi()

    val subscription = repo.subscription
    val selectedId = repo.selectedId
    val vpnState = VpnStatus.state

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    /** serverId → delay ms (-1 = unreachable). */
    private val _pings = MutableStateFlow<Map<String, Long>>(emptyMap())
    val pings = _pings.asStateFlow()

    private val _pinging = MutableStateFlow(false)
    val pinging = _pinging.asStateFlow()

    /** E-mail waiting for a code, or null when on the e-mail step. */
    private val _codeSentTo = MutableStateFlow<String?>(null)
    val codeSentTo = _codeSentTo.asStateFlow()

    /** True while waiting for the user to confirm in Telegram. */
    private val _telegramWaiting = MutableStateFlow(false)
    val telegramWaiting = _telegramWaiting.asStateFlow()

    /** URLs the UI should open in the browser. */
    private val _openUrl = Channel<String>(Channel.BUFFERED)
    val openUrl = _openUrl.receiveAsFlow()

    private var tgToken: String? = null
    private var tgPollJob: Job? = null

    init {
        if (repo.isStale()) refresh(silent = true)
        pingAll()
    }

    // ------------------------------------------------------------ subscription

    fun activate(link: String) = launchBusy {
        repo.activate(link)
        _message.value = "Подписка подключена"
        pingAll()
    }

    fun refresh(silent: Boolean = false) = launchBusy(silent) {
        repo.refresh()
        pingAll()
    }

    fun select(serverId: String?) {
        if (serverId == repo.selectedId.value) return
        repo.select(serverId)
        // Reconnect on the new server if we're online.
        if (vpnState.value is VpnState.Connected) TitanVpnService.start(getApplication<Application>())
    }

    fun disconnect() = TitanVpnService.stop(getApplication<Application>())

    fun logout() {
        disconnect()
        repo.logout()
        _pings.value = emptyMap()
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun pingAll() {
        val servers = subscription.value?.servers ?: return
        if (_pinging.value) return
        viewModelScope.launch {
            _pinging.value = true
            try {
                _pings.value = TcpPing.pingAll(servers)
            } finally {
                _pinging.value = false
            }
        }
    }

    // ------------------------------------------------------------ login

    fun loginWithTelegram() = launchBusy {
        val login = auth.startTelegram()
        tgToken = login.token
        _telegramWaiting.value = true
        _openUrl.send(login.url)
        startTelegramPolling()
    }

    fun cancelTelegram() {
        tgPollJob?.cancel()
        tgToken = null
        _telegramWaiting.value = false
    }

    /** Called when the browser sends the user back (titanvps://auth-done) or the app resumes. */
    fun onReturnFromBrowser() {
        if (tgToken != null) viewModelScope.launch { pollTelegramOnce() }
    }

    private fun startTelegramPolling() {
        tgPollJob?.cancel()
        tgPollJob = viewModelScope.launch {
            val deadline = System.currentTimeMillis() + 10 * 60 * 1000
            while (tgToken != null && System.currentTimeMillis() < deadline) {
                delay(2000)
                pollTelegramOnce()
            }
            if (tgToken != null) {
                cancelTelegram()
                _message.value = "Время на вход истекло, попробуйте ещё раз"
            }
        }
    }

    private suspend fun pollTelegramOnce() {
        val token = tgToken ?: return
        val result = runCatching { auth.pollTelegram(token) }.getOrNull() ?: return
        when (result) {
            is AuthApi.PollResult.Ok -> {
                cancelTelegram()
                activate(result.subscriptionUrl)
            }
            AuthApi.PollResult.NotFound -> {
                cancelTelegram()
                _message.value = "К этому Telegram не привязана подписка"
            }
            AuthApi.PollResult.Expired -> {
                cancelTelegram()
                _message.value = "Время на вход истекло, попробуйте ещё раз"
            }
            AuthApi.PollResult.Pending -> Unit
        }
    }

    fun requestEmailCode(email: String) = launchBusy {
        val e = email.trim()
        auth.requestEmailCode(e)
        _codeSentTo.value = e
        _message.value = "Если почта привязана к подписке, мы отправили на неё код"
    }

    fun verifyEmailCode(code: String) = launchBusy {
        val email = _codeSentTo.value ?: return@launchBusy
        val url = auth.verifyEmailCode(email, code.trim())
        _codeSentTo.value = null
        repo.activate(url)
        _message.value = "Подписка подключена"
        pingAll()
    }

    fun changeEmail() {
        _codeSentTo.value = null
    }

    private fun launchBusy(silent: Boolean = false, block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
            } catch (e: Exception) {
                if (!silent) _message.value = e.message ?: "Ошибка"
            } finally {
                _busy.value = false
            }
        }
    }
}
