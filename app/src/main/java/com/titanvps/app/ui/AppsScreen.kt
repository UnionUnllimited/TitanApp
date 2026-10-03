package com.titanvps.app.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.titanvps.app.data.RuApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class AppEntry(val packageName: String, val label: String, val icon: Drawable)

/** Apps the user can exclude from the VPN (they go to the internet directly). */
@Composable
internal fun AppsScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val excluded by viewModel.excludedApps.collectAsState()
    val ruApps by viewModel.ruAppsBypass.collectAsState()
    val ruOff by viewModel.ruAppsOff.collectAsState()
    fun isChecked(pkg: String) = pkg in excluded || (ruApps && pkg in RuApps.PACKAGES && pkg !in ruOff)
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var query by remember { mutableStateOf("") }

    BackHandler(onBack = onBack)

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                .map { it.activityInfo.applicationInfo }
                .filter { it.packageName != context.packageName }
                .distinctBy { it.packageName }
                .map { AppEntry(it.packageName, pm.getApplicationLabel(it).toString(), pm.getApplicationIcon(it)) }
                .sortedBy { it.label.lowercase() }
        }?.let { list ->
            // Order fixed once on open (bypassed apps first), so rows don't jump when toggled.
            list.sortedByDescending { isChecked(it.packageName) }
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            Text("Исключения приложений", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(
            "Включённые приложения ходят в интернет напрямую, мимо VPN, и не видят его. " +
                "Это нужно для банков, маркетплейсов и Госуслуг: с VPN они часто не открываются, " +
                "просят его выключить или блокируют оплату. Остальные приложения работают через VPN.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Row(
            Modifier.fillMaxWidth().clickable { viewModel.setRuAppsBypass(!ruApps) }.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Российские приложения без VPN", fontWeight = FontWeight.SemiBold)
                Text(
                    "Включает все сразу: банки, маркетплейсы, Госуслуги, Яндекс, VK, операторы. " +
                        "Любое из них можно выключить ниже.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = ruApps, onCheckedChange = { viewModel.setRuAppsBypass(it) })
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            placeholder = { Text("Поиск") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        val list = apps
        if (list == null) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(24.dp))
        } else {
            val shown = list.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
            LazyColumn {
                items(shown, key = { it.packageName }) { app ->
                    val ru = app.packageName in RuApps.PACKAGES
                    val checked = isChecked(app.packageName)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setExcluded(app.packageName, !checked) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val bitmap = remember(app.packageName) { app.icon.toBitmap(96, 96).asImageBitmap() }
                        Image(bitmap, null, Modifier.size(36.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (ru) Text("российское приложение", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = checked, onCheckedChange = { viewModel.setExcluded(app.packageName, it) })
                    }
                }
            }
        }
    }
}
}
