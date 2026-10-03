package com.titanvps.desktop

import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() {
    val state = AppState()
    application {
        Window(
            onCloseRequest = {
                // Turn the system proxy off before quitting, or Windows loses internet.
                state.shutdown()
                exitApplication()
            },
            title = "Titan VPS",
            icon = painterResource("logo.png"),
            state = rememberWindowState(size = DpSize(1100.dp, 740.dp)),
        ) {
            window.minimumSize = java.awt.Dimension(420, 600)
            TitanTheme(state.theme) { App(state) }
        }
    }
}
