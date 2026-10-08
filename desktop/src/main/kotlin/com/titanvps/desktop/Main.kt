package com.titanvps.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.FilterNone
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() {
    val state = AppState()
    application {
        val windowState = rememberWindowState(size = DpSize(1100.dp, 740.dp))
        val quit = {
            // Turn the system proxy off before quitting, or Windows loses internet.
            state.shutdown()
            exitApplication()
        }
        Window(
            onCloseRequest = quit,
            title = "Titan VPS",
            icon = painterResource("logo.png"),
            state = windowState,
            // Our own title bar in the app's colours instead of the white Windows frame.
            undecorated = true,
        ) {
            window.minimumSize = java.awt.Dimension(420, 600)
            TitanTheme(state.theme) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        TitleBar(windowState, onClose = quit)
                        Box(Modifier.weight(1f)) { App(state) }
                    }
                }
            }
        }
    }
}

/** Drag to move, double-click to maximize; minimize / maximize / close on the right. */
@Composable
private fun FrameWindowScope.TitleBar(windowState: WindowState, onClose: () -> Unit) {
    val maximized = windowState.placement == WindowPlacement.Maximized
    fun toggleMaximize() {
        windowState.placement = if (maximized) WindowPlacement.Floating else WindowPlacement.Maximized
    }
    var lastClick by remember { mutableStateOf(0L) }
    Row(Modifier.fillMaxWidth().height(36.dp).background(MaterialTheme.colorScheme.surface), verticalAlignment = Alignment.CenterVertically) {
        WindowDraggableArea(
            Modifier.weight(1f).fillMaxHeight().onPointerEvent(PointerEventType.Press) {
                val now = System.currentTimeMillis()
                if (now - lastClick < 400) toggleMaximize()
                lastClick = now
            },
        ) {
            Row(Modifier.fillMaxSize().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource("logo.png"), null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Titan VPS", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
        TitleButton(Icons.Default.Remove, "Свернуть") { window.isMinimized = true }
        TitleButton(if (maximized) Icons.Default.FilterNone else Icons.Default.CropSquare, "Развернуть") { toggleMaximize() }
        TitleButton(Icons.Default.Close, "Закрыть", hover = Color(0xFFE81123), onClick = onClose)
    }
}

@Composable
private fun TitleButton(icon: ImageVector, description: String, hover: Color? = null, onClick: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    val bg = when {
        !hovered -> Color.Transparent
        hover != null -> hover
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    }
    Box(
        Modifier.width(46.dp).fillMaxHeight().background(bg)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size(16.dp), tint = if (hovered && hover != null) Color.White else LocalContentColor.current)
    }
}
