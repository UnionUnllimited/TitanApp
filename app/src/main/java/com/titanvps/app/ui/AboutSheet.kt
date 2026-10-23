package com.titanvps.app.ui

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.HeadsetMic
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.titanvps.app.ui.kit.PrimaryButton
import com.titanvps.app.ui.kit.SecondaryButton
import com.titanvps.app.ui.theme.ProvideTitanTextScale
import com.titanvps.app.ui.theme.Titan
import com.titanvps.app.ui.theme.fixedSp

/** О приложении: the logo, the version and where to write (iOS AboutSheet). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutSheet(supportUrl: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val palette = Titan.palette
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
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
                    PrimaryButton("Telegram-бот", { context.openUrl(BuildConfig.TELEGRAM_URL) }, icon = Icons.AutoMirrored.Filled.Send)
                    SecondaryButton("Поддержка", { context.openUrl(supportUrl) }, icon = Icons.Filled.HeadsetMic)
                }
            }
        }
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
