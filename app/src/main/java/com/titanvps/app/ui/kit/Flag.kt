package com.titanvps.app.ui.kit

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Dns
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.titanvps.app.data.Flags
import com.titanvps.app.data.Fmt
import com.titanvps.app.data.ServerGroups
import com.titanvps.app.ui.theme.Titan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A server's flag filled into a circle, as on iOS and Windows: the square flag PNG from the
 * assets (flag-icons, drawn in CI: scripts/download-flags-android.sh), else the flag emoji as
 * large as the circle needs. No flag: a bolt for АВТО and Безлимитные · все серверы, else a server.
 * Show the name next to it without its flag: Flags.nameWithoutFlag.
 */
@Composable
fun FlagCircle(
    serverName: String,
    size: Dp,
    modifier: Modifier = Modifier,
    disc: Color = Color.Unspecified,
    stroke: Boolean = true,
) {
    val parts = remember(serverName) { Flags.split(serverName) }
    val code = remember(parts.first) { parts.first?.let(Flags::code) }
    FlagDisc(code, parts.first, parts.second, size, modifier, disc, stroke)
}

/** The same circle for an ISO country code ("FI", "de"): the exit country of a check. */
@Composable
fun CountryFlagCircle(countryCode: String, size: Dp, modifier: Modifier = Modifier) {
    val code = remember(countryCode) { countryCode.trim().lowercase() }
    val emoji = remember(code) { Flags.emojiOf(code).ifEmpty { null } }
    FlagDisc(code.ifEmpty { null }, emoji, "", size, modifier, Color.Unspecified, true)
}

@Composable
private fun FlagDisc(
    code: String?,
    emoji: String?,
    name: String,
    size: Dp,
    modifier: Modifier,
    disc: Color,
    stroke: Boolean,
) {
    val palette = Titan.palette
    val context = LocalContext.current
    // Decoded once per flag and cached; the first time off the main thread.
    var bitmap by remember(code) { mutableStateOf(code?.let(Flags::cached)) }
    LaunchedEffect(code) {
        if (code != null && bitmap == null) {
            bitmap = withContext(Dispatchers.IO) { Flags.bitmap(context.applicationContext, code) }
        }
    }
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    // Безлимитные · все серверы: АВТО's bolt, read out in words.
    val unlimited = code == null && emoji == null && ServerGroups.isUnlimitedName(name)
    val label = remember(code, unlimited) { code?.let(Fmt::countryName) ?: ServerGroups.UNLIMITED_LABEL.takeIf { unlimited } }
    Box(
        modifier
            .size(size)
            .clearAndSetSemantics { if (label != null) contentDescription = label }
            .clip(CircleShape)
            .background(disc.takeOrElse { palette.track })
            .then(
                if (stroke) Modifier.border(if (size < 24.dp) 0.5.dp else 1.dp, palette.outline, CircleShape) else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        when {
            image != null -> Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            emoji != null -> Text(
                emoji,
                // Wider than tall, with a margin: large enough that its height fills the circle.
                Modifier.wrapContentSize(unbounded = true),
                fontSize = with(LocalDensity.current) { (size * 1.6f).toSp() },
                maxLines = 1,
                softWrap = false,
            )
            unlimited || "авто" in name.lowercase() -> Icon(Icons.Filled.Bolt, null, Modifier.size(size * 0.5f), tint = palette.primary)
            else -> Icon(Icons.Filled.Dns, null, Modifier.size(size * 0.5f), tint = palette.textSecondary)
        }
    }
}
