package com.titanvps.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.titanvps.app.R
import com.titanvps.app.ui.theme.BrandGradient
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanvps.app.BuildConfig
import com.titanvps.app.data.Subscription
import com.titanvps.app.ui.theme.Brand
import com.titanvps.app.ui.theme.Connected
import com.titanvps.app.vpn.VpnState
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun TitanScreen(viewModel: MainViewModel, onConnect: () -> Unit) {
    val subscription by viewModel.subscription.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    val sub = subscription
    var tab by rememberSaveable { mutableStateOf(MainTab.HOME) }
    var overlay by rememberSaveable { mutableStateOf<Overlay?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (sub != null && overlay == null) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    MainTab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(if (tab == t) t.selectedIcon else t.icon, null) },
                            label = { Text(t.title, maxLines = 1) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        // Scaffold's padding covers the bars; consume it so safeDrawing (e.g. IME) isn't added twice.
        Box(Modifier.padding(padding).consumeWindowInsets(padding).safeDrawingPadding().fillMaxSize()) {
            when {
                sub == null -> WelcomeScreen(viewModel, busy)
                overlay == Overlay.APPS -> AppsScreen(viewModel) { overlay = null; viewModel.reconnectIfConnected() }
                overlay == Overlay.DIAGNOSTICS -> DiagnosticsScreen { overlay = null }
                tab == MainTab.HOME -> HomeScreen(viewModel, sub, busy, onConnect)
                tab == MainTab.SUBSCRIPTION -> SubscriptionScreen(viewModel, sub)
                else -> ProfileScreen(
                    viewModel, sub,
                    onOpenApps = { overlay = Overlay.APPS },
                    onOpenDiagnostics = { overlay = Overlay.DIAGNOSTICS },
                    onOpenHome = { tab = MainTab.HOME },
                )
            }
        }
    }
}

private enum class MainTab(val title: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    HOME("Главная", Icons.Outlined.Home, Icons.Filled.Home),
    SUBSCRIPTION("Подписка", Icons.Outlined.CreditCard, Icons.Filled.CreditCard),
    PROFILE("Профиль", Icons.Outlined.Person, Icons.Filled.Person),
}

private enum class Overlay { APPS, DIAGNOSTICS }

@Composable
private fun WelcomeScreen(viewModel: MainViewModel, busy: Boolean) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var key by remember { mutableStateOf("") }

    // Centered, width-capped and scrollable: fits small phones, landscape and tablets.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(
        Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(painterResource(R.drawable.logo), null, Modifier.size(120.dp))
        Spacer(Modifier.height(20.dp))
        Text("TitanVPS", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Вставьте ключ подписки из бота или личного кабинета",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = key,
            onValueChange = { key = it.trim() },
            label = { Text("Ключ") },
            placeholder = { Text("https://api1.titanvps.su/…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { clipboard.getText()?.text?.trim()?.let { key = it } },
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) {
            Icon(Icons.Default.ContentPaste, null)
            Spacer(Modifier.width(8.dp))
            Text("Вставить из буфера")
        }
        Spacer(Modifier.height(12.dp))
        if (busy) {
            CircularProgressIndicator()
        } else {
            GradientButton("Подключить", Icons.AutoMirrored.Filled.Login) {
                if (key.isNotBlank()) viewModel.activate(key)
            }
        }
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = { context.openUrl(BuildConfig.TELEGRAM_URL) }) {
            Text("Нет ключа? Получить в боте")
        }
    }
    }
}

@Composable
internal fun GradientButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(27.dp))
            .background(BrandGradient)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color.White)
            Spacer(Modifier.width(8.dp))
            Text(text, color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}

internal fun android.content.Context.openUrl(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

internal fun formatDuration(ms: Long): String {
    val s = TimeUnit.MILLISECONDS.toSeconds(ms)
    return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
}

internal fun formatBytes(bytes: Long): String {
    val gb = bytes / 1024.0 / 1024.0 / 1024.0
    return if (gb >= 1) "%.1f ГБ".format(gb) else "%.0f МБ".format(bytes / 1024.0 / 1024.0)
}

internal fun formatExpiry(expireAt: Long): String {
    if (expireAt <= 0) return "бессрочно"
    val date = SimpleDateFormat("d MMM yyyy", Locale("ru")).format(Date(expireAt * 1000))
    val days = TimeUnit.SECONDS.toDays(expireAt - System.currentTimeMillis() / 1000)
    return if (days >= 0) "$date ($days дн.)" else "$date (истекла)"
}
