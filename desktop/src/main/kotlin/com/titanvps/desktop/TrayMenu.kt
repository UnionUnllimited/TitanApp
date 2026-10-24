@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.titanvps.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.datatransfer.DataFlavor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BaseMultiResolutionImage
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/**
 * The tray icon and its menu, drawn by the app itself (as in Happ) instead of the plain
 * Windows one: the connection with the server's flag, connect / disconnect, servers and the
 * mode as submenus, import from the clipboard, the window, exit. Left click opens the window.
 */
class TitanTray(private val state: AppState, private val showWindow: () -> Unit, private val quit: () -> Unit) {
    private var icon: TrayIcon? = null
    private var menu: ComposeWindow? = null
    private var sub: ComposeWindow? = null
    /** The open submenu and the top of its row in the menu. */
    private var submenu by mutableStateOf<Sub?>(null)
    private var subRowY = 0
    private var anchor = Point()
    /** Closes a menu that never got the focus (then no focus loss closes it) once the mouse leaves it. */
    private var watch: javax.swing.Timer? = null
    private var outsideSince = 0L

    private enum class Sub { SERVERS, MODE }

    fun install() {
        if (!SystemTray.isSupported()) return
        val tray = SystemTray.getSystemTray()
        val ti = TrayIcon(trayImage(tray.trayIconSize.width), "Titan VPS")
        ti.addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger || SwingUtilities.isRightMouseButton(e)) openMenu()
            }

            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) { closeMenu(); showWindow() }
            }
        })
        runCatching { tray.add(ti) }.onSuccess { icon = ti }
    }

    fun dispose() {
        closeMenu()
        menu?.dispose()
        sub?.dispose()
        icon?.let { runCatching { SystemTray.getSystemTray().remove(it) } }
        icon = null
    }

    fun notify(title: String, text: String) {
        icon?.displayMessage(title, text, TrayIcon.MessageType.INFO)
    }

    fun tooltip(text: String) {
        icon?.toolTip = text
    }

    // ------------------------------------------------------------------ windows

    private fun openMenu() {
        anchor = MouseInfo.getPointerInfo()?.location ?: return
        submenu = null
        sub?.isVisible = false
        val w = menu ?: popup(focusable = true) { MenuContent() }.also { m ->
            m.addWindowFocusListener(object : WindowAdapter() {
                override fun windowLostFocus(e: WindowEvent) {
                    // A click in the submenu (it never takes the focus) keeps the menu open.
                    val s = sub
                    val p = MouseInfo.getPointerInfo()?.location
                    if (s != null && s.isVisible && p != null && s.bounds.contains(p)) {
                        m.toFront()
                        m.requestFocus()
                        return
                    }
                    closeMenu()
                }
            })
            menu = m
        }
        place(w, menuHeight(), anchor.x, anchor.y, above = true)
        w.isVisible = true
        w.toFront()
        w.requestFocus()
        foreground(w)
        watchUnfocused(w)
    }

    private fun watchUnfocused(m: ComposeWindow) {
        watch?.stop()
        outsideSince = 0L
        watch = javax.swing.Timer(250) {
            if (!m.isVisible || m.isFocused) { outsideSince = 0L; return@Timer }
            val p = MouseInfo.getPointerInfo()?.location ?: return@Timer
            val inside = m.bounds.contains(p) || sub?.let { it.isVisible && it.bounds.contains(p) } == true
            val now = System.currentTimeMillis()
            when {
                inside -> outsideSince = 0L
                outsideSince == 0L -> outsideSince = now
                now - outsideSince > 1500 -> closeMenu()
            }
        }.apply { start() }
    }

    private fun closeMenu() {
        watch?.stop()
        watch = null
        submenu = null
        sub?.isVisible = false
        menu?.isVisible = false
    }

    private fun openSub(which: Sub, rowY: Int) {
        if (submenu == which && sub?.isVisible == true) return
        submenu = which
        subRowY = rowY
        val m = menu ?: return
        val s = sub ?: popup(focusable = false) { SubContent() }.also { sub = it }
        val height = subHeight(which)
        val screen = screenBounds(Point(m.x, m.y))
        // To the left of the menu when there's no room on the right (the tray is bottom right).
        val x = if (m.x + m.width + SUB_WIDTH <= screen.x + screen.width) m.x + m.width - 4 else m.x - SUB_WIDTH + 4
        place(s, height, x, m.y + rowY - PADDING, above = false)
        s.isVisible = true
    }

    private fun closeSub() {
        submenu = null
        sub?.isVisible = false
    }

    /** An undecorated window with no taskbar button, always on top, rounded on Windows 11. */
    private fun popup(focusable: Boolean, content: @Composable () -> Unit): ComposeWindow =
        ComposeWindow().apply {
            type = java.awt.Window.Type.UTILITY
            isUndecorated = true
            isAlwaysOnTop = true
            isResizable = false
            focusableWindowState = focusable
            isAutoRequestFocus = focusable
            setContent(onKeyEvent = { e -> if (e.key == Key.Escape) { closeMenu(); true } else false }) {
                // this@TitanTray: inside the window, "state" is the Frame's own.
                TitanTheme(this@TitanTray.state.theme) { content() }
            }
            addNotify()
            WindowCorners.round(this)
        }

    /** [x], [y] the anchor; the window goes above it (or below), inside the screen's work area. */
    private fun place(w: ComposeWindow, height: Int, x: Int, y: Int, above: Boolean) {
        val width = if (w === sub) SUB_WIDTH else MENU_WIDTH
        val screen = screenBounds(Point(x, y))
        var left = if (w === menu && x + width > screen.x + screen.width) x - width else x
        var top = if (above) y - height else y
        left = left.coerceIn(screen.x, (screen.x + screen.width - width).coerceAtLeast(screen.x))
        top = top.coerceIn(screen.y, (screen.y + screen.height - height).coerceAtLeast(screen.y))
        w.setBounds(left, top, width, height)
    }

    private fun screenBounds(p: Point): Rectangle {
        val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val gc = ge.screenDevices.map { it.defaultConfiguration }.firstOrNull { it.bounds.contains(p) } ?: ge.defaultScreenDevice.defaultConfiguration
        val b = gc.bounds
        val i = Toolkit.getDefaultToolkit().getScreenInsets(gc)
        return Rectangle(b.x + i.left, b.y + i.top, b.width - i.left - i.right, b.height - i.top - i.bottom)
    }

    /** Windows lets the app the tray icon belongs to come to the front on its click. */
    private fun foreground(w: ComposeWindow) {
        if (!isWindows) return
        runCatching { User32.INSTANCE.SetForegroundWindow(WinDef.HWND(Native.getComponentPointer(w))) }
    }

    // ------------------------------------------------------------------ menu

    private val hasSubscription get() = state.subscription != null

    private fun menuHeight(): Int =
        PADDING * 2 + HEADER + SEPARATOR + ROW * (if (hasSubscription) 4 else 1) + SEPARATOR + ROW * 2

    private fun subHeight(which: Sub): Int = PADDING * 2 + when (which) {
        Sub.MODE -> ROW * 2
        Sub.SERVERS -> {
            val groups = ServerGroups.split(state.subscription?.servers.orEmpty())
            (ROW * groups.values.sumOf { it.size } + LABEL * groups.size).coerceAtMost(MAX_SUB)
        }
    }

    @Composable
    private fun MenuContent() {
        val c = Palette.colors
        Column(Modifier.fillMaxSize().background(c.surface).border(1.dp, c.outline, RoundedCornerShape(8.dp)).padding(vertical = PADDING.dp)) {
            Header()
            Separator()
            var y = PADDING + HEADER + SEPARATOR
            if (hasSubscription) {
                val vpn = state.vpn
                val on = vpn is VpnState.Connected
                val busy = vpn == VpnState.Connecting || vpn == VpnState.Disconnecting
                Item(
                    when (vpn) {
                        VpnState.Connecting -> "Подключаемся…"
                        VpnState.Disconnecting -> "Отключаемся…"
                        else -> if (on) "Отключиться" else "Подключиться"
                    },
                    enabled = !busy,
                    onHover = ::closeSubLater,
                ) {
                    closeMenu()
                    if (on) state.disconnect() else state.connect()
                    // TUN without administrator rights: the window asks.
                    if (state.needAdmin) showWindow()
                }
                y += ROW
                val serversY = y
                Item("Сменить сервер", arrow = true, open = submenu == Sub.SERVERS, onHover = { openSub(Sub.SERVERS, serversY) }) { openSub(Sub.SERVERS, serversY) }
                y += ROW
                val modeY = y
                Item("Режим подключения", arrow = true, open = submenu == Sub.MODE, onHover = { openSub(Sub.MODE, modeY) }) { openSub(Sub.MODE, modeY) }
            }
            Item("Импорт из буфера обмена", onHover = ::closeSubLater) {
                closeMenu()
                val text = runCatching { Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String }.getOrNull()
                if (text != null && Links.find(text) != null) state.activate(text)
                else state.showToast("В буфере обмена нет ключа Titan VPS", Toast.Kind.WARNING)
                showWindow()
            }
            Separator()
            Item("Показать окно", onHover = ::closeSubLater) { closeMenu(); showWindow() }
            Item("Выйти", onHover = ::closeSubLater) { closeMenu(); quit() }
        }
    }

    /** The connection: the server's flag, "Подключено" and its name. */
    @Composable
    private fun Header() {
        val c = Palette.colors
        val server = state.selected
        val vpn = state.vpn
        val (status, color) = when {
            state.subscription == null -> "Нет подписки" to c.secondary
            vpn is VpnState.Connected -> "Подключено" to c.green
            vpn == VpnState.Connecting -> "Подключаемся…" to Brand
            else -> "Не подключено" to c.secondary
        }
        Row(
            Modifier.fillMaxWidth().height(HEADER.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (server != null) FlagCircle(server, 26.dp) else Box(Modifier.size(26.dp).clip(CircleShape).background(c.track))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(status, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color)
                Text(
                    server?.let { ServerGroups.splitFlag(it.name).second } ?: "Titan VPS",
                    fontSize = 14.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    @Composable
    private fun SubContent() {
        val c = Palette.colors
        Column(
            Modifier.fillMaxSize().background(c.surface).border(1.dp, c.outline, RoundedCornerShape(8.dp))
                .verticalScroll(rememberScrollState()).padding(vertical = PADDING.dp),
        ) {
            when (submenu) {
                Sub.SERVERS -> {
                    val servers = state.subscription?.servers.orEmpty()
                    ServerGroups.split(servers).forEach { (group, items) ->
                        Text(
                            group.title.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
                            color = c.secondary, modifier = Modifier.height(LABEL.dp).padding(start = 14.dp, top = 8.dp),
                        )
                        (items.filter(state::isFavorite) + items.filterNot(state::isFavorite)).forEach { s ->
                            val locked = state.bypassLocked && ServerGroups.isBypass(s)
                            ServerItem(s, s.id == state.selected?.id, locked, state.pings[s.id]) {
                                closeMenu()
                                state.select(s.id)
                            }
                        }
                    }
                }
                Sub.MODE -> {
                    Item("Весь компьютер (TUN)", checked = state.mode == "tun") {
                        closeMenu()
                        state.changeMode("tun")
                        if (state.needAdmin) showWindow()
                    }
                    Item("Системный прокси", checked = state.mode == "proxy") {
                        closeMenu()
                        state.changeMode("proxy")
                    }
                }
                null -> {}
            }
        }
    }

    @Composable
    private fun ServerItem(server: Server, selected: Boolean, locked: Boolean, ping: Long?, onClick: () -> Unit) {
        val c = Palette.colors
        Row(itemModifier(!locked, onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
            FlagCircle(server, 20.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                ServerGroups.splitFlag(server.name).second, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (locked) c.tertiary else c.text, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
            )
            ping?.let { Text(if (it >= 0) "$it мс" else "—", fontSize = 12.sp, color = if (it >= 0) pingColor(it) else c.tertiary, style = Tabular) }
            Box(Modifier.width(26.dp), contentAlignment = Alignment.CenterEnd) {
                if (selected) Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = Brand)
            }
        }
    }

    @Composable
    private fun Item(
        text: String,
        enabled: Boolean = true,
        arrow: Boolean = false,
        open: Boolean = false,
        checked: Boolean? = null,
        onHover: (() -> Unit)? = null,
        onClick: () -> Unit,
    ) {
        val c = Palette.colors
        Row(itemModifier(enabled, open, onHover, onClick), verticalAlignment = Alignment.CenterVertically) {
            if (checked != null) {
                Box(Modifier.width(26.dp)) { if (checked) Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = Brand) }
            }
            Text(text, fontSize = 14.sp, color = if (enabled) c.text else c.tertiary, modifier = Modifier.weight(1f))
            if (arrow) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = c.secondary)
        }
    }

    /** Pointing at a plain row closes an open submenu (in the menu itself, not in the submenu). */
    private fun closeSubLater() {
        if (submenu != null) closeSub()
    }

    @Composable
    private fun itemModifier(enabled: Boolean, open: Boolean = false, onHover: (() -> Unit)? = null, onClick: () -> Unit): Modifier {
        val c = Palette.colors
        val interaction = remember { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        if (onHover != null) LaunchedEffect(hovered) { if (hovered && enabled) onHover() }
        return Modifier.fillMaxWidth().height(ROW.dp).padding(horizontal = 4.dp).clip(RoundedCornerShape(6.dp))
            .background(if ((hovered || open) && enabled) c.track else Color.Transparent)
            .hoverable(interaction, enabled)
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp)
    }

    @Composable
    private fun Separator() {
        HorizontalDivider(Modifier.padding(horizontal = 10.dp, vertical = ((SEPARATOR - 1) / 2).dp), color = Palette.colors.outline)
    }

    private companion object {
        const val MENU_WIDTH = 290
        const val SUB_WIDTH = 300
        const val PADDING = 6
        const val HEADER = 52
        const val ROW = 36
        const val SEPARATOR = 9
        const val LABEL = 28
        const val MAX_SUB = 460

        /** The logo at every size the tray may need (the display's scale picks one). */
        fun trayImage(size: Int): Image {
            val logo = TitanTray::class.java.classLoader.getResourceAsStream("logo.png")!!.use { ImageIO.read(it) }
            val variants = listOf(1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0).map { scale ->
                val px = (size * scale).toInt().coerceAtLeast(1)
                BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB).apply {
                    createGraphics().apply {
                        setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                        setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                        drawImage(logo.getScaledInstance(px, px, Image.SCALE_SMOOTH), 0, 0, null)
                        dispose()
                    }
                }
            }
            return BaseMultiResolutionImage(*variants.toTypedArray())
        }
    }
}
