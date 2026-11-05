package com.titanvps.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

sealed interface UpdateState {
    data object Idle : UpdateState
    data object ConnectingVpn : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Updater.Release) : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    data object Installing : UpdateState
    data class Error(val message: String) : UpdateState
}

sealed interface VpnState {
    data object Off : VpnState
    data object Connecting : VpnState
    data class Connected(val since: Long) : VpnState
    data object Disconnecting : VpnState
    data class Error(val message: String) : VpnState
}

/** Everything the UI shows; work runs on IO, state is changed on the UI thread. */
class AppState {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private val core = XrayProcess("core")
    private val tun = SingBoxProcess()
    /** Naive / Mieru client of the connected server, if it is one. */
    @Volatile private var plugin: PluginProcess? = null
    /**
     * Who owns the cores, the tunnel and the system proxy: each connect, off and quit takes a
     * new number, so a connect or a restart under way that was overtaken gives up quietly,
     * and an exit of a process we stopped ourselves is never taken for a crash.
     */
    private val conn = ConnectionGen()
    /** SOCKS port of the running sing-box (0 when TUN is off): the next server's core takes it, the tunnel stays. */
    private var tunSocks = 0
    /** The current exit watch ([watchExits]): an exit seen by an older one isn't handled twice. */
    private var watchId = 0
    /** Restarts of exited processes in a row, and since when the connection has been up again. */
    private var revives = 0
    private var upSince = 0L

    var subscription by mutableStateOf<Subscription?>(null); private set
    var selectedId by mutableStateOf<String?>(null); private set
    var theme by mutableStateOf("system"); private set
    /** "proxy" (system proxy) or "tun" (whole PC, needs administrator rights). */
    var mode by mutableStateOf("proxy"); private set
    var excludedApps by mutableStateOf<Set<String>>(emptySet()); private set
    /** "exclude" (the picked programs bypass the VPN) or "only" (just they go through it). */
    var appsMode by mutableStateOf("exclude"); private set
    var vpnApps by mutableStateOf<Set<String>>(emptySet()); private set
    /** The programs picked for the current [appsMode]. */
    val pickedApps: Set<String> get() = if (appsMode == "only") vpnApps else excludedApps
    var autoConnect by mutableStateOf(false); private set
    var autostart by mutableStateOf(false); private set
    /** Hidden diagnostics screen unlocked by its code (TechMode.kt). */
    var techMode by mutableStateOf(false); private set
    /** Account status and devices from the bot (null until loaded). */
    var account by mutableStateOf<Account.Info?>(null); private set
    var accountLoading by mutableStateOf(false); private set
    /** Asks to restart as administrator (TUN). */
    var needAdmin by mutableStateOf(false)
    var vpn by mutableStateOf<VpnState>(VpnState.Off); private set
    /** What connecting is doing right now (shown under the button). */
    var connectStep by mutableStateOf(""); private set
    var busy by mutableStateOf(false); private set
    var pinging by mutableStateOf(false); private set
    /** The note at the top of the window (Подписка обновлена, errors…). */
    var toast by mutableStateOf<Toast?>(null); private set
    val pings = mutableStateMapOf<String, Long>()
    /** Starred servers (by name): first in the list. */
    var favorites by mutableStateOf<Set<String>>(emptySet()); private set
    /** Напоминания (tray notifications): the subscription ends soon, bypass traffic runs out. */
    var reminders by mutableStateOf(true); private set
    private var reminded: Set<String> = emptySet()
    /** Shows a notification by the tray icon (set by the window; reminders due are shown then). */
    var notifier: ((title: String, text: String) -> Unit)? = null
        set(value) {
            field = value
            remind()
        }
    /** Failed connects so far: a new one puts a just-moved Выкл | Вкл knob back with a shake. */
    var failures by mutableIntStateOf(0); private set
    /** Проверить соединение: a request through the running VPN. */
    var check by mutableStateOf<ConnectionCheck.Status>(ConnectionCheck.Status.Idle); private set
    private var checkJob: kotlinx.coroutines.Job? = null
    /** Speed and traffic of the running core (read while Главная is on screen). */
    val traffic = TrafficMonitor(scope)
    /** The running core's metrics page (speed counters). */
    private var metricsPort = 0
    /** Whitelist mode right now: true / false (normal internet) / null (not known, offline). */
    var whitelist by mutableStateOf<Boolean?>(null); private set
    var whitelistChecking by mutableStateOf(false); private set
    /** Bypass servers spend GB and are only needed in whitelist mode: locked on normal internet (not in Технический режим). */
    val bypassLocked: Boolean get() = whitelist == false && !techMode
    var update by mutableStateOf<UpdateState>(UpdateState.Idle); private set
    /** Shown as a dialog: set by a manual check or when a new version is found. */
    var updateDialog by mutableStateOf(false)
    /** Local HTTP inbound of the running core (updates download through it). */
    private var proxyPort = 0
    private var updateJob: kotlinx.coroutines.Job? = null
    private var notifiedBuild = 0
    /** Bumped by each check or install and by closing the dialog: one closed meanwhile does nothing more. */
    private var updateGen = 0
    /** Saved as the app quits for an update with the VPN on: the next start connects again. */
    @Volatile private var reconnectAfterUpdate = false

    val selected: Server? get() = subscription?.let { s -> s.servers.firstOrNull { it.id == selectedId } ?: s.servers.firstOrNull() }

    init {
        // Crash recovery: a proxy we set earlier must not stay on without a running core.
        runCatching { SystemProxy.restore() }
        val st = Repository.load()
        subscription = st.subscription
        selectedId = st.selectedId
        theme = st.theme
        mode = st.mode
        excludedApps = st.excludedApps
        appsMode = st.appsMode
        vpnApps = st.vpnApps
        autoConnect = st.autoConnect
        techMode = st.techMode
        favorites = st.favorites
        reminders = st.reminders
        reminded = st.reminded
        syncAutostart()
        Runtime.getRuntime().addShutdownHook(Thread { shutdown() })
        scope.launch {
            while (true) {
                checkWhitelist()
                kotlinx.coroutines.delay(WHITELIST_EVERY_MS)
            }
        }
        if (subscription != null) {
            refresh(silent = true)
            loadAccount()
            // Also once after an in-app update that quit with the VPN on (as Android does).
            if (autoConnect || st.reconnectAfterUpdate) connect()
        }
        if (st.reconnectAfterUpdate) scope.launch(Dispatchers.IO) { persist() }
        // Every half an hour: the subscription once it is as old as its update interval (the app
        // lives in the tray from logon for days), then Напоминания (also after each update).
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(REMIND_EVERY_MS)
                subscription?.let { refreshIfStale(it.info.updateIntervalHours * HOUR_MS) }
                remind()
            }
        }
    }

    /** One save at a time, of the state as it is then: the last one to run writes the newest. */
    private val saving = Any()

    private fun persist() = synchronized(saving) {
        Repository.save(
            Repository.State(
                subscription, selectedId, theme, mode, excludedApps, autoConnect, appsMode, vpnApps, techMode,
                favorites, reminders, reminded, reconnectAfterUpdate,
            )
        )
    }

    fun showToast(text: String, kind: Toast.Kind = Toast.Kind.INFO) {
        toast = Toast(text, kind)
    }

    fun dismissToast(id: Long) {
        if (toast?.id == id) toast = null
    }

    /**
     * Bumped by a new key and by Выйти: a subscription or account fetch begun before it saves
     * nothing and says nothing (as Android's and iPhone's generation).
     */
    private var keyGen = 0

    /** Adds the key and connects right away (same as Android and iPhone); on another key while on, reconnects. */
    fun activate(text: String) {
        val url = Links.find(text.trim()) ?: run { showToast("Это не ключ Titan VPS", Toast.Kind.WARNING); return }
        val previous = subscription?.url
        // The old key's Пинг ends: its results wouldn't land any more ([ping]), nor clear its spinners.
        stopPing()
        // Also while a refresh of the old key is under way: that one gives way.
        keyGen++
        load(url, silent = false, supersede = true) {
            showToast("Подписка подключена", Toast.Kind.SUCCESS)
            loadAccount()
            when {
                vpnOn -> if (previous != url) connect()
                else -> connect()
            }
        }
    }

    /**
     * Обновить подписку: says how it went in a note at the top. [silent] (at start-up, in the
     * background): no note, and after a failure one more try a bit later (as on iPhone): at logon
     * the network is often not up yet.
     */
    fun refresh(silent: Boolean = false) {
        val url = subscription?.url ?: return
        val done = {
            if (!silent) showToast("Подписка обновлена", Toast.Kind.SUCCESS)
            loadAccount()
        }
        val gen = keyGen
        load(url, silent, onFail = if (!silent) null else ({
            scope.launch {
                kotlinx.coroutines.delay(RETRY_MS)
                // Signed out or another key meanwhile: not the old one again.
                if (gen == keyGen && subscription?.url == url) load(url, silent = true, onDone = done)
            }
        }), onDone = done)
    }

    /**
     * The subscription again, quietly, if it was fetched longer than [maxAgeMs] ago: the window
     * opened from the tray (30 min, as Android and iPhone on coming back), and every half an hour
     * past its update interval. From any thread.
     */
    fun refreshIfStale(maxAgeMs: Long) {
        scope.launch {
            val s = subscription ?: return@launch
            if (!busy && System.currentTimeMillis() - s.fetchedAt > maxAgeMs) refresh(silent = true)
        }
    }

    /**
     * Fetches [url]'s subscription. [supersede]: a new key, also while another fetch runs (that
     * one gives way through [keyGen]); else nothing while one runs. [onFail]: after a failure.
     */
    private fun load(url: String, silent: Boolean, supersede: Boolean = false, onFail: (() -> Unit)? = null, onDone: () -> Unit = {}) {
        if (busy && !supersede) return
        val gen = keyGen
        busy = true
        scope.launch {
            try {
                val sub = withContext(Dispatchers.IO) { Repository.fetch(url) }
                // Выйти or another key while it downloaded: the old key isn't brought back.
                if (gen != keyGen) return@launch
                subscription = sub
                if (sub.servers.none { it.id == selectedId }) selectedId = sub.servers.first().id
                withContext(Dispatchers.IO) { persist() }
                if (gen != keyGen) return@launch
                onDone()
                remind()
            } catch (e: Exception) {
                if (gen != keyGen) return@launch
                if (!silent) {
                    val why = e.message ?: "сервер не ответил"
                    showToast(if (subscription == null) why else "Не удалось обновить подписку: $why", Toast.Kind.WARNING)
                }
                onFail?.invoke()
            } finally {
                // An overtaken fetch leaves the spinner to the newer one.
                if (gen == keyGen) busy = false
            }
        }
    }

    fun isFavorite(server: Server): Boolean = server.name in favorites

    fun toggleFavorite(server: Server) {
        favorites = if (server.name in favorites) favorites - server.name else favorites + server.name
        scope.launch(Dispatchers.IO) { persist() }
    }

    fun changeReminders(value: Boolean) {
        reminders = value
        scope.launch(Dispatchers.IO) { persist() }
        if (value) remind()
    }

    /**
     * Напоминания (as on iPhone): the subscription ends in 3 days or less, then in a day or
     * less; less than 10 % of the bypass traffic is left (once per paid amount).
     */
    private fun remind() {
        val info = subscription?.info ?: return
        if (!reminders) return
        val notify = notifier ?: return
        val now = System.currentTimeMillis() / 1000
        val trafficKey = "traffic:${info.totalBytes}"
        // Only what is about this subscription period and this paid amount is kept.
        val shown = reminded.filterTo(mutableSetOf()) { it.startsWith("${info.expireAt}:") || it == trafficKey }
        val left = info.expireAt - now
        if (info.expireAt > 0 && left > 0) {
            val days = if (left <= DAY) 1 else if (left <= 3 * DAY) 3 else 0
            if (days > 0 && shown.add("${info.expireAt}:$days")) {
                notify(
                    "Подписка Titan VPS",
                    if (days == 1) "Подписка закончится меньше чем через сутки. Продлите её в личном кабинете, чтобы VPN не отключился."
                    else "Подписка закончится через ${Fmt.plural((left + DAY - 1) / DAY, "день", "дня", "дней")}. Продлите её заранее в личном кабинете.",
                )
            }
        }
        if (info.totalBytes > 0 && info.remainingBytes * 10 < info.totalBytes) {
            if (shown.add(trafficKey)) {
                notify(
                    "Трафик на обходах заканчивается",
                    "Осталось ${formatBytes(info.remainingBytes)} из ${formatBytes(info.totalBytes)}. Докупить ГБ можно в личном кабинете.",
                )
            }
        } else {
            shown.remove(trafficKey)
        }
        if (shown != reminded) {
            reminded = shown
            scope.launch(Dispatchers.IO) { persist() }
        }
    }

    /** Theme, favourites, Автоподключение and the picked server back to the start; the key stays. */
    fun resetSettings() {
        theme = "system"
        favorites = emptySet()
        autoConnect = false
        subscription?.servers?.firstOrNull()?.let { select(it.id) }
        scope.launch(Dispatchers.IO) { persist() }
        showToast("Настройки сброшены", Toast.Kind.SUCCESS)
    }

    /** Checks now whether the internet is in whitelist mode (bypass servers unlock then). */
    fun checkWhitelist() {
        if (whitelistChecking) return
        whitelistChecking = true
        scope.launch {
            try {
                whitelist = withContext(Dispatchers.IO) { Whitelist.detect() }
            } finally {
                whitelistChecking = false
            }
            leaveBypass()
        }
    }

    /**
     * Normal internet while on (or connecting to) a bypass server, e.g. home Wi-Fi after a phone's
     * hotspot: over to a regular server with a note, as Android and iPhone do on Wi-Fi, or every
     * site would spend the bypass GB. Never in Технический режим ([bypassLocked]), nor during an
     * update's download. Once there, it stays (no way back to the bypass by itself).
     */
    private fun leaveBypass() {
        val sub = subscription ?: return
        val from = selected ?: return
        if (!bypassLocked || !vpnOn || !isBypass(from.id)) return
        if (update is UpdateState.Downloading || update == UpdateState.Installing) return
        val to = regularFallback(sub) ?: return
        selectedId = to.id
        scope.launch(Dispatchers.IO) { persist() }
        connect()
        val name = ServerGroups.splitFlag(to.name).second
        showToast("Интернет работает без белых списков — переключили на «$name», чтобы не тратить ГБ обходов")
        // The window is often hidden in the tray: the note there too.
        notifier?.invoke("Обходы сейчас не нужны", "Интернет работает без белых списков. Переключили на «$name», чтобы не тратить ГБ обходов.")
    }

    /** A regular server in place of a bypass one ([ServerGroups.regularFallback]). */
    private fun regularFallback(sub: Subscription): Server? = ServerGroups.regularFallback(sub.servers) { pings[it] }

    private fun isBypass(id: String): Boolean =
        subscription?.servers?.firstOrNull { it.id == id }?.let { ServerGroups.groupOf(it, subscription!!.servers) == ServerGroups.Group.BYPASS } == true

    fun select(id: String) {
        if (id == selectedId) return
        if (bypassLocked && isBypass(id)) {
            showToast(BYPASS_LOCKED_MESSAGE, Toast.Kind.WARNING)
            checkWhitelist()
            return
        }
        selectedId = id
        scope.launch(Dispatchers.IO) { persist() }
        // Also while connecting (as on iPhone): the connect under way gives way to this one.
        if (vpnOn) connect()
    }

    /** On, or on its way (connecting, or bringing back an exited core): a new pick reconnects. */
    private val vpnOn: Boolean get() = vpn is VpnState.Connected || vpn == VpnState.Connecting

    /** System proxy ↔ TUN; reconnects if on. TUN needs administrator rights. */
    fun changeMode(value: String) {
        if (value == mode) return
        if (value == "tun" && !Admin.isAdmin) {
            mode = value
            scope.launch(Dispatchers.IO) { persist() }
            needAdmin = true
            return
        }
        mode = value
        scope.launch(Dispatchers.IO) { persist() }
        if (value == "tun") syncAutostart()
        if (vpnOn) connect()
    }

    /** Picks or unpicks a program for the current [appsMode]. */
    fun setAppPicked(exe: String, picked: Boolean) {
        fun Set<String>.with() = if (picked) this + exe else filterNot { it.equals(exe, true) }.toSet()
        if (appsMode == "only") vpnApps = vpnApps.with() else excludedApps = excludedApps.with()
        scope.launch(Dispatchers.IO) { persist() }
    }

    fun changeAppsMode(value: String) {
        appsMode = value
        scope.launch(Dispatchers.IO) { persist() }
    }

    /** Applies the exclusion list (TUN restarts with it). */
    fun applyExclusions() {
        if (vpnOn && mode == "tun") connect()
    }

    /** Restart as administrator for TUN; on refusal fall back to the system proxy. */
    fun restartAsAdmin() {
        needAdmin = false
        // A standard account (the dialog says so): the elevated copy would be another account's.
        if (!Admin.canElevate) { cancelAdmin(); return }
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                persist()
                Admin.relaunchElevated()
            }
            if (ok) {
                withContext(Dispatchers.IO) { shutdown() }
                kotlin.system.exitProcess(0)
            } else {
                showToast("Без прав администратора режим TUN недоступен", Toast.Kind.WARNING)
            }
        }
    }

    fun cancelAdmin() {
        needAdmin = false
        mode = "proxy"
        scope.launch(Dispatchers.IO) { persist() }
    }

    fun changeAutoConnect(value: Boolean) {
        autoConnect = value
        scope.launch(Dispatchers.IO) { persist() }
    }

    /**
     * Автозапуск turned on without administrator rights is a Run-key entry, which starts us
     * without them at logon, so TUN couldn't come up: in TUN with them it becomes the elevated
     * logon task. An elevated start also gives a task made by an earlier version this one's
     * settings (on battery too, never stopped, normal priority).
     */
    private fun syncAutostart() {
        val tun = mode == "tun"
        scope.launch {
            autostart = withContext(Dispatchers.IO) {
                if (Admin.isAdmin) {
                    if (tun && Autostart.viaRunKey) runCatching { Autostart.set(true, elevated = true) }
                    Autostart.repair()
                }
                Autostart.isEnabled
            }
        }
    }

    /** Start with Windows (a logon task with admin rights when we have them, for TUN). */
    fun changeAutostart(value: Boolean) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { Autostart.set(value, Admin.isAdmin) }.isSuccess }
            autostart = withContext(Dispatchers.IO) { Autostart.isEnabled }
            if (!ok) showToast("Не удалось изменить автозапуск", Toast.Kind.WARNING)
        }
    }

    fun changeTechMode(value: Boolean) {
        techMode = value
        scope.launch(Dispatchers.IO) { persist() }
    }

    /** Local HTTP inbound of the running core, 0 when off (Технический режим checks through it). */
    val localProxyPort: Int get() = if (vpn is VpnState.Connected) proxyPort else 0

    /** The key [accountLoading] is for: a new key's account doesn't wait for the old one's. */
    private var accountGen = -1

    fun loadAccount() {
        val url = subscription?.url ?: return
        if (accountLoading && accountGen == keyGen) return
        val gen = keyGen
        accountGen = gen
        accountLoading = true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { Account.fetch(url, proxyPort) } }
                // Выйти or another key meanwhile: not the old key's account.
                .onSuccess { if (gen == keyGen) account = it }
            if (accountGen == gen) accountLoading = false
        }
    }

    fun changeTheme(value: String) {
        theme = value
        scope.launch(Dispatchers.IO) { persist() }
    }

    fun connect() {
        val sub = subscription ?: return
        var server = selected ?: return
        // A bypass server chosen earlier, while the internet is normal: a regular one instead.
        if (bypassLocked && isBypass(server.id)) {
            val regular = regularFallback(sub) ?: return
            server = regular
            selectedId = regular.id
            scope.launch(Dispatchers.IO) { persist() }
            showToast("Интернет работает без белых списков — подключаем к обычному серверу ${ServerGroups.splitFlag(regular.name).second}")
        }
        if (mode == "tun" && !Admin.isAdmin) { needAdmin = true; return }
        // Fixed for this connect: another mode picked meanwhile starts a new one.
        val useTun = mode == "tun"
        val gen = conn.next()
        revives = 0
        resetCheck()
        vpn = VpnState.Connecting
        connectStep = "Готовим настройки маршрутов…"
        fun step(text: String) { scope.launch { if (conn.isCurrent(gen)) connectStep = text } }
        scope.launch {
            try {
                // Null: overtaken (Выкл, another server or mode); the newer one stops or
                // replaces what this one started.
                val started = withContext(Dispatchers.IO) {
                    conn.ifCurrent(gen) {
                        val geo = GeoFiles.prepare(sub.servers.map { it.xrayJson })
                        if (!conn.isCurrent(gen)) return@ifCurrent false
                        step("Запускаем протокол сервера…")
                        // TUN already up (a server switch): the new core takes the tunnel's SOCKS
                        // port, so sing-box goes on and nothing goes around the VPN meanwhile.
                        val keepTun = useTun && tun.isRunning && tunSocks > 0
                        val (fresh, http, metrics) = freePorts(4).filter { it != tunSocks }
                        val logDir = AppPaths.logDir.absolutePath.replace('\\', '/')
                        startLogsOver()
                        // Naive / Mieru: their own client as a local SOCKS, Xray's proxy outbound
                        // points at it; MASQUE: Xray's own client.
                        plugin?.close()
                        plugin = null
                        val (prepared, p) = Plugins.prepare(server)
                        plugin = p
                        if (!conn.isCurrent(gen)) return@ifCurrent false
                        if ("\"tcpFastOpen\":true" in prepared) Admin.enableTcpFastOpen()
                        step("Подключаемся к серверу…")
                        fun startCore(socks: Int) =
                            core.start(
                                XrayConfigs.buildProxyConfig(
                                    prepared, socks, http, logDir, server.proxyTag, metrics,
                                    sniffingEnable = sub.info.sniffing, accessLog = techMode,
                                ),
                                geo,
                            )
                        var socks = if (keepTun) tunSocks else fresh
                        try {
                            startCore(socks)
                        } catch (e: Exception) {
                            if (!keepTun) throw e
                            // The tunnel's port taken meanwhile: a new one, the tunnel restarts with it.
                            socks = fresh
                            startCore(socks)
                        }
                        if (!conn.isCurrent(gen)) return@ifCurrent false
                        if (useTun) {
                            // The whole PC through the tunnel; no system proxy then.
                            runCatching { SystemProxy.restore() }
                            step("Включаем VPN для всего компьютера…")
                            tun.start(TunConfig.build(socks, pickedApps, appsMode == "only", logDir))
                            tunSocks = socks
                        } else {
                            tun.stop()
                            tunSocks = 0
                            step("Включаем системный прокси…")
                            SystemProxy.enable(http)
                        }
                        proxyPort = http
                        metricsPort = metrics
                        true
                    }
                }
                if (started != true || !conn.isCurrent(gen)) return@launch
                vpn = VpnState.Connected(System.currentTimeMillis())
                upSince = System.currentTimeMillis()
                watchExits(gen, useTun)
                watchUpdates()
                checkConnection(auto = true)
            } catch (e: Exception) {
                // Overtaken meanwhile: not this connect's error, the newer one cleans up.
                if (!conn.isCurrent(gen)) return@launch
                System.err.println("connect failed: $e")
                withContext(Dispatchers.IO) { conn.ifCurrent(gen) { stopProcesses() } }
                if (!conn.isCurrent(gen)) return@launch
                // No technical details for clients.
                vpn = VpnState.Error("Не удалось подключиться. Попробуйте другой сервер или ещё раз")
                failures += 1
            }
        }
    }

    /**
     * The cores' logs start over with each connect (as on Android; a restart of an exited core
     * keeps them): they grew for good and kept the sites visited. A running core or sing-box
     * writes on at the new end. Under [conn].
     */
    private fun startLogsOver() {
        for (name in listOf(XrayConfigs.ACCESS_LOG, XrayConfigs.ERROR_LOG, "sing-box.log")) {
            runCatching { java.io.File(AppPaths.logDir, name).takeIf { it.exists() }?.writeText("") }
        }
    }

    /** Выкл: also while connecting, which then gives up (as on iPhone and Android) and ends off. */
    fun disconnect() {
        if (vpn !is VpnState.Connected && vpn != VpnState.Connecting) return
        val gen = conn.next()
        vpn = VpnState.Disconnecting
        resetCheck()
        scope.launch {
            // After the step a connect under way is in (a core starting can't be cut short);
            // not if a newer connect came meanwhile: that one replaces everything.
            withContext(Dispatchers.IO) { conn.ifCurrent(gen) { stopAll() } }
            if (conn.isCurrent(gen)) vpn = VpnState.Off
        }
    }

    /**
     * After connecting, xray, the protocol's client and sing-box are watched: one that exits by
     * itself (Defender, a crash, Task Manager) would leave «Подключено» over a proxy with
     * nothing behind it, or in TUN a PC offline or going around the VPN. Our own stops come
     * with a new [conn] number, so they never count.
     */
    private fun watchExits(gen: Int, useTun: Boolean) {
        val id = ++watchId
        listOfNotNull(core.exits(), plugin?.exits(), if (useTun) tun.exits() else null).forEach { exit ->
            exit.thenRun { scope.launch { if (id == watchId) onExit(gen, useTun) } }
        }
    }

    /**
     * Brings back what exited, in place: the same ports, while the system proxy or the tunnel
     * holds the traffic, at once and then after a growing pause ([Revive]). If it stays down,
     * everything goes off (the system proxy back, no tunnel) with an error; never «Подключено»
     * over a dead core.
     */
    private fun onExit(gen: Int, useTun: Boolean) {
        val was = vpn as? VpnState.Connected ?: return
        if (!conn.isCurrent(gen)) return
        // The rest of this watch: each try below brings back whatever is down.
        watchId++
        revives = Revive.triesAfterExit(revives, System.currentTimeMillis() - upSince)
        System.err.println("a core exited; restarting (try ${revives + 1})")
        resetCheck()
        vpn = VpnState.Connecting
        connectStep = "Соединение прервалось — восстанавливаем…"
        scope.launch {
            while (true) {
                val wait = Revive.waitSec(revives++) ?: break
                kotlinx.coroutines.delay(wait * 1000L)
                val ok = withContext(Dispatchers.IO) {
                    conn.ifCurrent(gen) {
                        runCatching { reviveExited(useTun) }.onFailure { System.err.println("restart failed: $it") }.isSuccess
                    }
                } ?: return@launch
                if (!conn.isCurrent(gen)) return@launch
                if (ok) {
                    vpn = VpnState.Connected(was.since)
                    upSince = System.currentTimeMillis()
                    watchExits(gen, useTun)
                    checkConnection(auto = true)
                    return@launch
                }
            }
            if (!conn.isCurrent(gen)) return@launch
            val off = conn.next()
            withContext(Dispatchers.IO) { conn.ifCurrent(off) { stopAll() } }
            if (!conn.isCurrent(off)) return@launch
            vpn = VpnState.Error("Соединение прервалось. Включите VPN ещё раз или выберите другой сервер")
            failures += 1
        }
    }

    /** Starts again whatever of the connection has exited: the tunnel first (without it traffic goes around the VPN). Under [conn]. */
    private fun reviveExited(useTun: Boolean) {
        if (useTun && !tun.isRunning) tun.restart()
        plugin?.let { if (!it.isRunning) it.start() }
        if (!core.isRunning) core.restart()
    }

    /** Speed counters of the running core, 0 when off. */
    val metricsPortNow: Int get() = if (vpn is VpnState.Connected) metricsPort else 0

    /**
     * Проверить соединение: a request through the VPN that is on now. [auto]: right after
     * connecting, a few tries (the core may need a moment); by hand: one more try on a failure.
     */
    fun checkConnection(auto: Boolean = false) {
        if (vpn !is VpnState.Connected || proxyPort == 0) return
        checkJob?.cancel()
        check = ConnectionCheck.Status.Checking
        checkJob = scope.launch {
            if (auto) kotlinx.coroutines.delay(1200)
            var result: ConnectionCheck.Status = ConnectionCheck.Status.Failed("")
            for (attempt in 0 until if (auto) 3 else 2) {
                if (attempt > 0) kotlinx.coroutines.delay(1500)
                val port = proxyPort.takeIf { it > 0 } ?: return@launch
                result = ConnectionCheck.run(port)
                if (result is ConnectionCheck.Status.Ok) break
            }
            check = result
        }
    }

    /** The VPN went off or to another server: the old result says nothing any more. */
    private fun resetCheck() {
        checkJob?.cancel()
        checkJob = null
        check = ConnectionCheck.Status.Idle
    }

    // ------------------------------------------------------------ updates

    /** While connected: check 15 s after connecting, then every 12 h; tell once per version. */
    private fun watchUpdates() {
        updateJob?.cancel()
        updateJob = scope.launch {
            kotlinx.coroutines.delay(15_000)
            while (true) {
                if (vpn is VpnState.Connected && update !is UpdateState.Downloading && update != UpdateState.Installing) {
                    val found = runCatching { withContext(Dispatchers.IO) { Updater.findNewer(proxyPort) } }.getOrNull()
                    if (found != null) {
                        update = UpdateState.Available(found)
                        if (found.build != notifiedBuild) { notifiedBuild = found.build; updateDialog = true }
                    }
                }
                kotlinx.coroutines.delay(12 * 60 * 60 * 1000L)
            }
        }
    }

    /**
     * "Обновление приложения": turns the VPN on if needed (GitHub only through it), then checks.
     * The dialog can be closed meanwhile (as on Android): the check then shows nothing more.
     */
    fun checkUpdate() {
        if (update is UpdateState.Downloading || update == UpdateState.Installing) return
        updateDialog = true
        val gen = ++updateGen
        scope.launch {
            if (!ensureVpn(gen)) {
                if (gen == updateGen) update = UpdateState.Error("Не удалось включить VPN. Попробуйте ещё раз")
                return@launch
            }
            update = UpdateState.Checking
            val result = runCatching { viaVpn(gen) { withContext(Dispatchers.IO) { Updater.findNewer(proxyPort) } } }
                .fold({ it?.let(UpdateState::Available) ?: UpdateState.UpToDate }, { UpdateState.Error("Не удалось проверить обновление") })
            if (gen == updateGen) update = result
        }
    }

    fun installUpdate() {
        val release = (update as? UpdateState.Available)?.release ?: return
        val gen = ++updateGen
        scope.launch {
            if (!ensureVpn(gen)) {
                if (gen == updateGen) update = UpdateState.Error("Не удалось включить VPN. Попробуйте ещё раз")
                return@launch
            }
            update = UpdateState.Downloading(0f)
            try {
                val msi = viaVpn(gen) {
                    withContext(Dispatchers.IO) {
                        Updater.download(release, proxyPort) { p -> scope.launch { update = UpdateState.Downloading(p) } }
                    }
                }
                update = UpdateState.Installing
                // The download went through the VPN: it was on (also if turned on for it, as on Android).
                val wasOn = vpnOn
                withContext(Dispatchers.IO) {
                    Updater.installAndRestart(msi)
                    // Once the script runs (it waits for this app to quit): the version that opens
                    // next, the new one or this one if the install fails, connects again.
                    reconnectAfterUpdate = wasOn
                    runCatching { persist() }
                    shutdown()
                }
                kotlin.system.exitProcess(0)
            } catch (e: Exception) {
                if (gen == updateGen) update = UpdateState.Error("Не удалось скачать обновление. Попробуйте ещё раз")
            }
        }
    }

    fun dismissUpdate() {
        updateDialog = false
        updateGen++
        if (update !is UpdateState.Available && update !is UpdateState.Downloading && update != UpdateState.Installing) update = UpdateState.Idle
    }

    /** АВТО (the balancer over every server): updates go through it, it always has a working one. */
    private val autoServer: Server?
        get() = subscription?.servers?.firstOrNull { it.name.contains("авто", ignoreCase = true) }

    /**
     * [block] over the VPN; if GitHub can't be reached through the picked server, once more
     * through АВТО (a dead server picked must not keep the app from updating). Not when GitHub
     * answered (an error status: its limit per address, a failure on its side), the server
     * works then; nor from a bypass server (whitelist mode: АВТО wouldn't open anything); nor
     * once the dialog was closed ([gen]).
     */
    private suspend fun <T> viaVpn(gen: Int, block: suspend () -> T): T = try {
        block()
    } catch (e: java.io.IOException) {
        val auto = autoServer
        val from = selected?.id
        if (auto == null || from == null || from == auto.id || isBypass(from) || vpn !is VpnState.Connected || gen != updateGen) throw e
        select(auto.id) // reconnects
        if (!ensureVpn(gen)) throw e
        block()
    }

    /** The VPN on for an update; false when it couldn't be, or the dialog was closed ([gen]). */
    private suspend fun ensureVpn(gen: Int): Boolean {
        if (gen != updateGen) return false
        if (vpn is VpnState.Connected) return true
        if (subscription == null) return false
        update = UpdateState.ConnectingVpn
        // Off: the update goes through АВТО rather than whatever server was picked last.
        autoServer?.let { auto ->
            if (vpn != VpnState.Connecting && selectedId != auto.id) {
                selectedId = auto.id
                scope.launch(Dispatchers.IO) { persist() }
            }
        }
        if (vpn != VpnState.Connecting) {
            connect()
            // Not started (TUN asks for administrator rights first): no 45 s wait for nothing.
            if (vpn != VpnState.Connecting && vpn !is VpnState.Connected) return false
        }
        repeat(90) {
            // Closed meanwhile: nothing goes on behind it (no download, no install).
            if (gen != updateGen || vpn is VpnState.Error) return false
            if (vpn is VpnState.Connected) return true
            kotlinx.coroutines.delay(500)
        }
        return gen == updateGen && vpn is VpnState.Connected
    }

    /** Servers being measured right now (spinner, or the old value dimmed). */
    var measuring by mutableStateOf<Set<String>>(emptySet()); private set

    /** Which Пинг (or one server's ping) may still change the rows: a stopped or overtaken one doesn't. */
    private val pingRuns = PingRuns()
    /** Servers in the running Пинг: how much of it is done (the ring on Стоп). */
    private var pingTotal by mutableIntStateOf(0)
    val pingProgress: Float
        get() = if (pingTotal <= 0) 0f else 1f - measuring.size.coerceAtMost(pingTotal).toFloat() / pingTotal

    /**
     * Every server, [firstIds] (the tab on screen) first; old values stay until new ones arrive.
     * Pressed again while measuring: stops.
     */
    fun pingAll(firstIds: List<String> = emptyList()) {
        val servers = subscription?.servers ?: return
        if (pinging) { stopPing(); return }
        val ids = servers.map { it.id }
        // A new run measures everything again, also servers stopped by hand before.
        stoppedIds.clear()
        // Spinners right away: getting the ping core ready can take a few seconds.
        measuring = measuring + ids
        pingTotal = ids.size
        pinging = true
        val run = pingRuns.startList()
        // A stopped run finishing late mustn't end a newer one.
        ping(servers, firstIds.filter { it in ids } + ids.filter { it !in firstIds }, run) { if (pingRuns.isLatest(run)) pinging = false }
    }

    /** Stops measuring: servers not measured yet keep their old value (or none). */
    fun stopPing() {
        pingRuns.stop()
        Pinger.cancel()
        pinging = false
        measuring = emptySet()
    }

    /** Servers whose measuring was stopped by hand: a late result for them is dropped. */
    private val stoppedIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * One server; works any time, also while the whole list is being measured. Pressed again
     * while it is being measured: stops it (the old value stays).
     */
    fun pingOne(id: String) {
        val servers = subscription?.servers ?: return
        if (servers.none { it.id == id }) return
        if (id in measuring) {
            stoppedIds += id
            measuring = measuring - id
            return
        }
        stoppedIds -= id
        measuring = measuring + id
        ping(servers, listOf(id), pingRuns.startOne()) {}
    }

    /**
     * Measures [targets] as [ticket] ([PingRuns]): once a list run is stopped or overtaken, its
     * late results, «таймаут» and cleared spinners don't land on the newer run; nothing begun
     * under another key (Выйти, a new key: [keyGen]) lands at all.
     */
    private fun ping(servers: List<Server>, targets: List<String>, ticket: PingRuns.Ticket, onDone: () -> Unit) {
        val gen = keyGen
        val owns = { gen == keyGen && pingRuns.owns(ticket) }
        val spins = { gen == keyGen && pingRuns.spins(ticket) }
        scope.launch {
            val answered = HashSet<String>()
            try {
                withContext(Dispatchers.IO) {
                    Pinger.ping(
                        servers, targets,
                        onStart = { ids -> scope.launch { if (spins()) measuring = measuring + (ids - stoppedIds) } },
                        onResult = { id, ms ->
                            scope.launch {
                                answered += id
                                if (!owns()) return@launch
                                if (id !in stoppedIds) pings[id] = ms
                                if (spins()) measuring = measuring - id
                            }
                        },
                    )
                }
            } catch (e: Exception) {
                if (targets.size > 1 && owns()) showToast("Не удалось проверить пинг. Попробуйте ещё раз", Toast.Kind.WARNING)
            } finally {
                // Let queued results land first.
                kotlinx.coroutines.yield()
                if (spins()) {
                    targets.filter { it !in answered && it !in pings && it !in stoppedIds }.forEach { pings[it] = -1 }
                    measuring = measuring - targets.toSet()
                }
                onDone()
            }
        }
    }

    /** Signed in by the link the subscription brings (profile-web-page-url); else the bot. */
    fun openCabinetUrl(): String = subscription?.info?.webPageUrl ?: Config.TELEGRAM_URL

    fun logout() {
        // A running Пинг ends here (as on Android and iPhone): no «Стоп» on the next key's
        // Главная, no old results on its list.
        stopPing()
        // A subscription or account fetch under way doesn't sign the old key back in.
        keyGen++
        busy = false
        accountLoading = false
        disconnect()
        traffic.stop()
        subscription = null
        selectedId = null
        account = null
        pings.clear()
        measuring = emptySet()
        scope.launch(Dispatchers.IO) { persist() }
    }

    /**
     * Everything off (Выйти, restart as administrator, an update, Windows ending the app): a
     * connect or restart under way gives up, its step finished first.
     */
    fun shutdown() {
        conn.next()
        conn.locked { stopAll() }
    }

    /** Off: the update checks, the ping core, then [stopProcesses]. Under [conn]. */
    private fun stopAll() {
        updateJob?.cancel()
        proxyPort = 0
        runCatching { Pinger.stop() }
        stopProcesses()
    }

    /** Cores, tunnel and our system proxy off. Under [conn]. */
    private fun stopProcesses() {
        tun.stop()
        tunSocks = 0
        core.stop()
        plugin?.close()
        plugin = null
        runCatching { SystemProxy.restore() }
    }

    private companion object {
        const val WHITELIST_EVERY_MS = 2 * 60_000L
        const val REMIND_EVERY_MS = 30 * 60_000L
        const val HOUR_MS = 60 * 60_000L
        /** A failed quiet fetch (start-up, background) is tried once more after this. */
        const val RETRY_MS = 10_000L
        const val DAY = 86_400L
        const val BYPASS_LOCKED_MESSAGE =
            "Обходы нужны только когда интернет работает по белым спискам (открываются ya.ru и vk.com, а google.com — нет). " +
                "Сейчас интернет обычный — выберите сервер во вкладке «Страны»"
    }
}
