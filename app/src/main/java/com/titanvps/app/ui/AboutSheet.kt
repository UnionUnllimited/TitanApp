package com.titanvps.app.ui

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.HeadsetMic
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanvps.app.BuildConfig
import com.titanvps.app.R
import com.titanvps.app.core.XrayCore
import com.titanvps.app.ui.kit.PrimaryButton
import com.titanvps.app.ui.kit.RowDivider
import com.titanvps.app.ui.kit.SecondaryButton
import com.titanvps.app.ui.kit.TitanCard
import com.titanvps.app.ui.kit.pressable
import com.titanvps.app.ui.theme.ProvideTitanTextScale
import com.titanvps.app.ui.theme.Titan
import com.titanvps.app.ui.theme.fixedSp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Политика конфиденциальности: docs/PRIVACY-ANDROID.md of the repository's default branch. */
internal const val PRIVACY_URL = "https://github.com/UnionUnllimited/TitanApp/blob/HEAD/docs/PRIVACY-ANDROID.md"

/** О приложении: the logo, the version, where to write and what the app is built from (iOS AboutSheet). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutSheet(supportUrl: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val palette = Titan.palette
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Half open first, as iOS's 440 pt detent: Открытый исходный код is a drag up.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = palette.background,
    ) {
        // The sheet is a window of its own: the app's text size inside it too.
        ProvideTitanTextScale {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LogoWithShadow(Modifier.padding(top = 28.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Titan VPS", fontSize = fixedSp(26f), lineHeight = fixedSp(32f), fontWeight = FontWeight.Bold)
                    Text(
                        "Версия ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        color = palette.textSecondary,
                        fontSize = 15.sp,
                        style = TextStyle(fontFeatureSettings = "tnum"),
                    )
                }
                Row(
                    Modifier.background(palette.primary.copy(alpha = 0.12f), CircleShape).padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Filled.Memory, null, Modifier.size(15.dp), tint = palette.primary)
                    Text("Ядро Xray", color = palette.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Поддержка", { context.openUrl(supportUrl) }, icon = Icons.Filled.HeadsetMic)
                    SecondaryButton("Telegram-бот", { context.openUrl(BuildConfig.TELEGRAM_URL) }, icon = Icons.AutoMirrored.Filled.Send)
                    Box(
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .pressable(onClick = { context.openUrl(PRIVACY_URL) })
                            .heightIn(min = 44.dp)
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Политика конфиденциальности", color = palette.primary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                OpenSourceSection(Modifier.padding(top = 8.dp))
            }
        }
    }
}

/** A part of the app with its repositories on GitHub ("owner/name"). */
private class OpenSourcePart(val name: String, val detail: String, val repos: List<String>)

/**
 * What the Android app is built from (scripts/build-libxray.sh, download-plugins.sh,
 * download-flags-android.sh); [xray]: the core's version, as libXray tells it.
 */
private fun openSourceParts(xray: String?) = listOf(
    OpenSourcePart("Titan VPS", "Исходный код приложения Titan VPS", listOf("UnionUnllimited/TitanApp")),
    OpenSourcePart(
        "Ядро VPN",
        "Xray-core" + (xray?.let { " v" + it.removePrefix("v") } ?: "") + " (через libXray)",
        listOf("XTLS/Xray-core"),
    ),
    OpenSourcePart("libXray", "Xray-core для мобильных приложений", listOf("XTLS/libXray")),
    OpenSourcePart("sing-box", "Клиенты протоколов TUIC, AnyTLS, ShadowTLS и SSH", listOf("SagerNet/sing-box")),
    OpenSourcePart("NaiveProxy", "Клиент протокола Naive (на сетевом стеке Chromium)", listOf("klzgrad/naiveproxy")),
    OpenSourcePart("mieru", "Клиент протокола Mieru", listOf("enfein/mieru")),
    OpenSourcePart("flag-icons", "Флаги стран в списке серверов", listOf("lipis/flag-icons")),
)

/** Открытый исходный код: the app and what it runs, each with its repository (iOS OpenSource). */
@Composable
private fun OpenSourceSection(modifier: Modifier) {
    // Off the main thread: the first call into libXray loads it.
    val xray by produceState<String?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { XrayCore.version() }.getOrNull()?.takeIf { it.isNotBlank() } }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Открытый исходный код", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(
            "Код приложения и всего, на чём оно работает, открыт: что Titan VPS делает с трафиком, можно проверить.",
            color = Titan.palette.textSecondary,
            fontSize = 13.sp,
        )
        TitanCard(Modifier.padding(top = 4.dp)) {
            openSourceParts(xray).forEachIndexed { i, part ->
                if (i > 0) RowDivider(inset = 16.dp)
                OpenSourceRow(part)
            }
        }
    }
}

@Composable
private fun OpenSourceRow(part: OpenSourcePart) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(part.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(part.detail, color = Titan.palette.textSecondary, fontSize = 12.sp)
        // Several links of one part side by side, on the next line when they don't fit.
        FlowRow(
            Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            part.repos.forEach { repo -> LinkChip(repo) { context.openUrl("https://github.com/$repo") } }
        }
    }
}

/** A repository's link: its name and ↗ on a primary capsule. */
@Composable
private fun LinkChip(title: String, onClick: () -> Unit) {
    val palette = Titan.palette
    Row(
        Modifier
            .pressable(onClick = onClick, onClickLabel = "открыть на GitHub")
            .clip(CircleShape)
            .background(palette.primary.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, color = palette.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(11.dp), tint = palette.primary)
    }
}

/** The 96 dp logo over a soft blue copy of itself (its shadow; the blur needs Android 12). */
@Composable
private fun LogoWithShadow(modifier: Modifier) {
    val logo = painterResource(R.drawable.logo)
    val shadow = Titan.palette.primary.copy(alpha = 0.35f)
    Box(modifier, contentAlignment = Alignment.Center) {
        if (Build.VERSION.SDK_INT >= 31) {
            Image(
                logo,
                null,
                Modifier
                    .size(96.dp)
                    .graphicsLayer {
                        translationY = 8.dp.toPx()
                        val blur = 14.dp.toPx()
                        renderEffect = BlurEffect(blur, blur, TileMode.Decal)
                    },
                colorFilter = ColorFilter.tint(shadow),
            )
        }
        Image(logo, null, Modifier.size(96.dp))
    }
}
