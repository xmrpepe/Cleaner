package dev.pixelforge.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pixelforge.core.data.Tier
import dev.pixelforge.core.privacy.Posture
import dev.pixelforge.ui.theme.Pf
import dev.pixelforge.ui.theme.readout

/** Primary container: flat graphite panel with a hairline border. */
@Composable
fun PfCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    highlight: Boolean = false,
    contentPadding: androidx.compose.ui.unit.Dp = Pf.dims.pad,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Pf.colors
    val shape = RoundedCornerShape(16.dp)
    Surface(
        modifier = modifier,
        shape = shape,
        color = c.panel,
        border = BorderStroke(1.dp, if (highlight) c.accent.copy(alpha = 0.55f) else c.hairline),
    ) {
        Column(
            Modifier
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .then(if (highlight) Modifier.background(Brush.verticalGradient(listOf(c.accent.copy(alpha = 0.08f), Color.Transparent))) else Modifier)
                .padding(contentPadding),
            content = content,
        )
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 3.dp, height = 12.dp).background(Pf.colors.accent, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(8.dp))
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted, modifier = Modifier.weight(1f))
        trailing?.invoke(this)
    }
}

@Composable
fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    color: Color = MaterialTheme.colorScheme.onSurface,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    PfCard(modifier, onClick = onClick, contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(14.dp), tint = Pf.colors.muted)
                Spacer(Modifier.width(6.dp))
            }
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(6.dp))
        Text(value, style = MaterialTheme.typography.readout, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sub != null) {
            Text(sub, style = MaterialTheme.typography.bodySmall, color = Pf.colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun KeyValue(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Pf.colors.muted, modifier = Modifier.weight(0.45f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor, modifier = Modifier.weight(0.55f), fontWeight = FontWeight.Medium)
    }
}

@Composable
fun TierBadge(tier: Tier, modifier: Modifier = Modifier) {
    val c = Pf.colors
    val color = when (tier) {
        Tier.SAFE -> c.success
        Tier.ADVANCED -> c.warning
        Tier.UNAVAILABLE -> c.muted
    }
    Pill(tier.label, color, modifier)
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

@Composable
fun postureColor(p: Posture): Color = when (p) {
    Posture.GOOD -> Pf.colors.success
    Posture.WARN -> Pf.colors.warning
    Posture.BAD -> Pf.colors.danger
    Posture.INFO -> Pf.colors.cyan
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).clip(CircleShape).background(color))
}

@Composable
fun ScreenHeader(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = if (onBack != null) 4.dp else 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Pf.colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

@Composable
fun NoticeCard(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Pf.colors.cyan,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    PfCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            if (icon != null) {
                Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(text, style = MaterialTheme.typography.bodySmall, color = Pf.colors.muted)
                if (actionLabel != null && onAction != null) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = onAction, border = BorderStroke(1.dp, color.copy(alpha = 0.6f))) { Text(actionLabel, color = color) }
                }
            }
        }
    }
}

@Composable
fun ProgressPanel(title: String, detail: String, fraction: Float?, modifier: Modifier = Modifier, onCancel: (() -> Unit)? = null) {
    PfCard(modifier.fillMaxWidth(), highlight = true) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(detail, style = MaterialTheme.typography.bodySmall, color = Pf.colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))
        if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        if (onCancel != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                content?.invoke(this)
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = if (destructive) ButtonDefaults.buttonColors(containerColor = Pf.colors.danger, contentColor = Color.White) else ButtonDefaults.buttonColors(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun BigReadout(value: String, unit: String?, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(value, style = MaterialTheme.typography.readout.copy(fontSize = MaterialTheme.typography.displaySmall.fontSize), color = color)
        if (unit != null) {
            Spacer(Modifier.width(4.dp))
            Text(unit, style = MaterialTheme.typography.titleSmall, color = Pf.colors.muted, modifier = Modifier.padding(bottom = 6.dp))
        }
    }
}
