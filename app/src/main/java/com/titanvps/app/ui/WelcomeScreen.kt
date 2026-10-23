package com.titanvps.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanvps.app.BuildConfig
import com.titanvps.app.TitanApp
import com.titanvps.app.ui.kit.AuroraBackground
import com.titanvps.app.ui.kit.GlowingLogo
import com.titanvps.app.ui.kit.PrimaryButton
import com.titanvps.app.ui.kit.SecondaryButton
import com.titanvps.app.ui.kit.pressable
import com.titanvps.app.ui.kit.rememberHaptics
import com.titanvps.app.ui.theme.AccentGradient
import com.titanvps.app.ui.theme.LogoGradient
import com.titanvps.app.ui.theme.Titan
import com.titanvps.app.ui.theme.fixedSp
import com.titanvps.app.ui.theme.iosSpring
import com.titanvps.app.ui.theme.rememberReduceMotion
import kotlinx.coroutines.delay

/** First start: the key from the bot, pasted or typed; the VPN connects right after (iOS WelcomeView). */
@Composable
internal fun WelcomeScreen(viewModel: MainViewModel, busy: Boolean) {
    val context = LocalContext.current
    val repository = TitanApp.get(context).repository
    val clipboard = LocalClipboardManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val haptics = rememberHaptics()
    val scroll = rememberScrollState()
    var key by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    // Our subscription link somewhere in what's typed (a bot message pasted whole is fine too).
    val link = remember(key) { repository.deepLinks.extractSubscriptionUrl(key) ?: repository.linkFinder.find(key) }
    val valid = link != null

    val connect: () -> Unit = {
        if (key.isNotBlank() && !busy) {
            keyboard?.hide()
            focusManager.clearFocus()
            // The view model connects once the key is in.
            viewModel.activate(link ?: key.trim())
        }
    }

    // A drag on the page puts the keyboard away (iOS: scrollDismissesKeyboard).
    LaunchedEffect(scroll) {
        scroll.interactionSource.interactions.collect {
            if (it is DragInteraction.Start) {
                keyboard?.hide()
                focusManager.clearFocus()
            }
        }
    }

    // Header, card and steps rise into place, one after another.
    val reduceMotion = rememberReduceMotion()
    val appear = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        delay(50)
        appear.animateTo(1f, iosSpring(0.7f, 0.8f))
    }
    val progress = { appear.value }

    Box(Modifier.fillMaxSize()) {
        AuroraBackground()
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(scroll)
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Header(Modifier.padding(top = 36.dp).rise(progress, 16.dp))
                KeyCard(
                    key = key,
                    onKeyChange = { key = it },
                    valid = valid,
                    editing = editing,
                    onEditingChange = { editing = it },
                    busy = busy,
                    onPaste = {
                        haptics.tap()
                        clipboard.getText()?.text?.trim()?.let { key = it }
                    },
                    onConnect = connect,
                    modifier = Modifier.rise(progress, 24.dp),
                )
                Steps(onOpenBot = { context.openUrl(BuildConfig.TELEGRAM_URL) }, modifier = Modifier.rise(progress, 32.dp))
            }
        }
    }
}

/** Starts [distance] lower and transparent; in place at [progress] 1. */
private fun Modifier.rise(progress: () -> Float, distance: Dp): Modifier = graphicsLayer {
    val p = progress()
    alpha = p.coerceIn(0f, 1f)
    translationY = (1f - p) * distance.toPx()
}

@Composable
private fun Header(modifier: Modifier) {
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlowingLogo(110.dp)
        Text(
            "Titan VPS",
            style = TextStyle(brush = LogoGradient, fontSize = fixedSp(34f), fontWeight = FontWeight.ExtraBold),
        )
        Text(
            "Вставьте ключ подписки из бота или личного кабинета — и VPN включится сразу",
            color = Titan.palette.textSecondary,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/** The see-through card the key goes in: field, paste, connect. */
@Composable
private fun KeyCard(
    key: String,
    onKeyChange: (String) -> Unit,
    valid: Boolean,
    editing: Boolean,
    onEditingChange: (Boolean) -> Unit,
    busy: Boolean,
    onPaste: () -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier,
) {
    val palette = Titan.palette
    val shape = RoundedCornerShape(26.dp)
    val shadow = Color.Black.copy(alpha = 0.18f)
    val empty = key.isEmpty()
    val border by animateColorAsState(
        when {
            empty -> if (editing) palette.primary else palette.outline
            valid -> palette.green
            else -> palette.orange
        },
        tween(200),
        label = "keyBorder",
    )
    val borderWidth by animateDpAsState(if (editing || !empty) 1.5.dp else 1.dp, tween(200), label = "keyBorderWidth")
    val fieldShape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .fillMaxWidth()
            .shadow(24.dp, shape, clip = false, ambientColor = shadow, spotColor = shadow)
            .clip(shape)
            .background(palette.surface.copy(alpha = 0.88f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
            .padding(18.dp)
            .animateContentSize(tween(200)),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "Ключ подписки",
            color = palette.textSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clip(fieldShape)
                .background(palette.background.copy(alpha = 0.7f))
                .border(borderWidth, border, fieldShape)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Filled.Key, null, Modifier.size(22.dp), tint = if (valid) palette.green else palette.primary)
            BasicTextField(
                value = key,
                onValueChange = onKeyChange,
                modifier = Modifier.weight(1f).onFocusChanged { onEditingChange(it.isFocused) },
                singleLine = true,
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp),
                cursorBrush = SolidColor(palette.primary),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { onConnect() }),
                decorationBox = { field ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (key.isEmpty()) {
                            Text("https://api1.titanvps.su/…", color = palette.textTertiary, fontSize = 15.sp, maxLines = 1)
                        }
                        field()
                    }
                },
            )
            AnimatedVisibility(visible = !empty, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        if (valid) Icons.Filled.CheckCircle else Icons.Filled.Error,
                        null,
                        Modifier.size(20.dp),
                        tint = if (valid) palette.green else palette.orange,
                    )
                    Box(
                        Modifier
                            .size(32.dp)
                            .pressable(onClick = { onKeyChange("") }, scale = 0.9f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Cancel, "Очистить", Modifier.size(20.dp), tint = palette.textSecondary)
                    }
                }
            }
        }
        if (!empty && !valid) {
            Text(
                "Это не похоже на ключ Titan VPS. Скопируйте ссылку подписки из бота целиком.",
                color = palette.orange,
                fontSize = 12.sp,
            )
        }
        SecondaryButton("Вставить из буфера", onPaste, icon = Icons.Filled.ContentPaste, minHeight = 50.dp)
        PrimaryButton(
            "Подключить",
            onConnect,
            icon = Icons.Filled.VerifiedUser,
            enabled = key.isNotBlank() && !busy,
            busy = busy,
            busyText = "Проверяем ключ…",
        )
    }
}

/** Где взять ключ: the bot, the subscription, the link. */
@Composable
private fun Steps(onOpenBot: () -> Unit, modifier: Modifier) {
    val palette = Titan.palette
    val shape = RoundedCornerShape(26.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.surface.copy(alpha = 0.88f))
            .border(1.dp, Color.White.copy(alpha = 0.1f), shape)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Где взять ключ", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Step(1, "Откройте нашего бота в Telegram")
        Step(2, "Оформите подписку или продлите её")
        Step(3, "Скопируйте ссылку-ключ и вставьте её выше")
        SecondaryButton(
            "Открыть бота",
            onOpenBot,
            Modifier.padding(top = 2.dp),
            icon = Icons.AutoMirrored.Filled.Send,
            minHeight = 48.dp,
        )
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(28.dp).background(AccentGradient, CircleShape), contentAlignment = Alignment.Center) {
            Text("$n", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Text(text, fontSize = 15.sp)
    }
}
