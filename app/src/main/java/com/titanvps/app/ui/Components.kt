package com.titanvps.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Rounded card used by all screens (surface on the background, as in the mockups). */
@Composable
internal fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            if (title != null) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.size(8.dp))
            }
            content()
        }
    }
}

/** Row with optional icon, value on the right and a chevron. */
@Composable
internal fun NavRow(
    title: String,
    icon: ImageVector? = null,
    value: String? = null,
    titleColor: Color = Color.Unspecified,
    divider: Boolean = true,
    onClick: () -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(14.dp))
            }
            Text(title, Modifier.weight(1f), color = titleColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (value != null) {
                Text(
                    value,
                    Modifier.padding(start = 8.dp).weight(1f, fill = false),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (divider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
internal fun ToggleRow(
    title: String,
    checked: Boolean,
    subtitle: String? = null,
    divider: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { onChange(!checked) }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title)
                if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = checked, onCheckedChange = onChange)
        }
        if (divider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** Small tinted banner with an info icon. */
@Composable
internal fun InfoBanner(text: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** Screen title row as in the mockups. */
@Composable
internal fun ScreenTitle(title: String, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        trailing()
    }
}
