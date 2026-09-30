package com.titanvps.app.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanvps.app.core.XrayConfigs
import java.io.File

/**
 * Xray's own logs from the current connection: every connection with the route it
 * took (direct / proxy / block) and the errors. Lets users send us what really happens.
 */
@Composable
internal fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf(readLogs(context.cacheDir)) }

    BackHandler(onBack = onBack)

    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 900.dp).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                Text("Диагностика", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "Откройте проблемный сайт или приложение с включённым VPN, вернитесь сюда и нажмите «Обновить». " +
                    "В журнале видно, куда ушло каждое соединение: [… -> direct] напрямую, [… -> bal…] через сервер.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { text = readLogs(context.cacheDir) }, modifier = Modifier.weight(1f)) { Text("Обновить") }
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(text)) }, modifier = Modifier.weight(1f)) { Text("Копировать") }
                Button(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                        context.startActivity(Intent.createChooser(send, "Отправить журнал"))
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Отправить") }
            }
            SelectionContainer(
                Modifier.fillMaxSize().padding(horizontal = 12.dp)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
            ) {
                Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
            }
        }
    }
}

private fun readLogs(dir: File): String {
    fun tail(name: String, lines: Int): String {
        val f = File(dir, name)
        if (!f.exists()) return "(нет данных — подключитесь к VPN)"
        // Only the end of the file: the access log grows during a long session.
        val bytes = java.io.RandomAccessFile(f, "r").use { raf ->
            val start = (raf.length() - 256 * 1024).coerceAtLeast(0)
            raf.seek(start)
            ByteArray((raf.length() - start).toInt()).also { raf.readFully(it) }
        }
        return String(bytes, Charsets.UTF_8).lines().takeLast(lines).joinToString("\n").ifBlank { "(пусто)" }
    }
    return "=== ОШИБКИ ===\n" + tail(XrayConfigs.ERROR_LOG, 150) +
        "\n\n=== СОЕДИНЕНИЯ (последние) ===\n" + tail(XrayConfigs.ACCESS_LOG, 300)
}
