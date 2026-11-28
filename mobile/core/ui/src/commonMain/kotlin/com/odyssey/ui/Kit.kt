package com.odyssey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*

// Shared building blocks for the operational apps (vendor and admin): confirmations that say what will happen,
// empty states that say what to do next, search, stat tiles and settings rows.

/**
 * Asks before something that cannot be taken back. Says what will happen in [body]. With [reasonLabel] it also asks for
 * a written reason, and will not confirm without one when [reasonRequired].
 */
@Composable
fun ConfirmDialog(
    title: String, body: String, confirmLabel: String, onConfirm: (reason: String) -> Unit, onDismiss: () -> Unit,
    danger: Boolean = false, reasonLabel: String? = null, reasonRequired: Boolean = true, reasonHint: String? = null,
) {
    var text by remember { mutableStateOf("") }
    val blocked = reasonLabel != null && reasonRequired && text.isBlank()
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Ind.bg, shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
        icon = { Box(Modifier.size(40.dp).background(if (danger) Color(0xFFFDE8E6) else Ind.a100), Alignment.Center) { Icon(if (danger) Lucide.TriangleAlert else Lucide.Info, null, Modifier.size(20.dp), tint = if (danger) Ind.danger else Ind.a700) } },
        title = { Heading(title, 22) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Body(body, size = 14, color = Ind.muted)
                if (reasonLabel != null) Field(reasonLabel, text, { text = it }, multiline = true, supporting = reasonHint)
            }
        },
        confirmButton = {
            Box(Modifier.height(44.dp).background(if (blocked) Ind.n300 else if (danger) Ind.danger else Ind.accent).clickable(enabled = !blocked) { onConfirm(text.trim()) }.padding(horizontal = 16.dp), Alignment.Center) {
                Heading(confirmLabel, 15, color = if (blocked) Ind.mutedSoft else Ind.bg)
            }
        },
        dismissButton = { GhostButton("Cancel", onDismiss) },
    )
}

/** An empty list, explained: what would be here, and what to do about it. */
@Composable
fun EmptyState(icon: ImageVector, title: String, body: String? = null, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(52.dp).border(1.dp, Ind.n400), Alignment.Center) { Icon(icon, null, Modifier.size(22.dp), tint = Ind.mutedSoft) }
        Spacer(Modifier.height(4.dp))
        Heading(title, 19)
        if (body != null) Text(body, color = Ind.muted, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 320.dp))
        if (action != null && onAction != null) { Spacer(Modifier.height(4.dp)); SecondaryButton(action, onAction) }
    }
}

@Composable
private fun Text(text: String, color: Color, fontSize: androidx.compose.ui.unit.TextUnit, textAlign: TextAlign, modifier: Modifier) =
    androidx.compose.material3.Text(text, modifier, color = color, fontSize = fontSize, textAlign = textAlign, lineHeight = 20.sp)

/** Search box with a magnifier and a clear button. */
@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(44.dp).background(Ind.surface).hairline().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Lucide.Search, null, Modifier.size(18.dp), tint = Ind.muted)
        BasicTextField(
            value, onChange, Modifier.weight(1f), singleLine = true, textStyle = TextStyle(fontFamily = LocalFonts.current.body, fontSize = 16.sp, color = Ind.text),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), cursorBrush = SolidColor(Ind.accent),
            decorationBox = { inner -> Box(contentAlignment = Alignment.CenterStart) { if (value.isEmpty()) Body(placeholder, size = 16, color = Ind.mutedSoft); inner() } },
        )
        if (value.isNotEmpty()) Icon(Lucide.X, "Clear search", Modifier.size(18.dp).clickable { onChange("") }, tint = Ind.muted)
    }
}

data class Stat(val label: String, val value: String, val foot: String? = null, val alert: Boolean = false)

/** Numbers in a hairline grid, two to a row. */
@Composable
fun StatGrid(stats: List<Stat>) {
    Column(Modifier.fillMaxWidth().hairline()) {
        stats.chunked(2).forEachIndexed { r, row ->
            if (r > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Ind.divider))
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                row.forEachIndexed { i, s ->
                    if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(Ind.divider))
                    Column(Modifier.weight(1f).padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Body(s.label, size = 12, color = Ind.muted)
                        Heading(s.value, 26, color = if (s.alert) Ind.danger else Ind.text)
                        if (s.foot != null) Body(s.foot, size = 11, color = Ind.muted)
                    }
                }
                if (row.size == 1) { Box(Modifier.width(1.dp).fillMaxHeight().background(Ind.divider)); Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

enum class NoticeTone { INFO, WARN, BAD, GOOD }

@Composable
fun Notice(text: String, tone: NoticeTone = NoticeTone.INFO, action: String? = null, onAction: (() -> Unit)? = null) {
    val (bg, fg, icon) = when (tone) {
        NoticeTone.INFO -> Triple(Ind.a100, Ind.a800, Lucide.Info)
        NoticeTone.WARN -> Triple(Color(0xFFFDF1D8), Color(0xFF9A6200), Lucide.Clock)
        NoticeTone.BAD -> Triple(Color(0xFFFDE8E6), Ind.danger, Lucide.TriangleAlert)
        NoticeTone.GOOD -> Triple(Color(0xFFE3F5EA), Color(0xFF1A7F4B), Lucide.CircleCheck)
    }
    Row(Modifier.fillMaxWidth().background(bg).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = fg)
        Body(text, size = 13, color = fg, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) Box(Modifier.clickable(onClick = onAction).padding(horizontal = 4.dp, vertical = 6.dp)) { Heading(action, 14, color = fg) }
    }
}

/** A tappable row in a settings list: icon, title, a line of detail, and a chevron or a trailing value. */
@Composable
fun SettingRow(icon: ImageVector, title: String, detail: String? = null, trailing: String? = null, badge: Int = 0, danger: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(36.dp).background(if (danger) Color(0xFFFDE8E6) else Ind.a100), Alignment.Center) { Icon(icon, null, Modifier.size(18.dp), tint = if (danger) Ind.danger else Ind.a700) }
        Column(Modifier.weight(1f)) {
            Body(title, size = 15, weight = FontWeight.Medium, color = if (danger) Ind.danger else Ind.text)
            if (detail != null) Body(detail, size = 12, color = Ind.muted)
        }
        if (badge > 0) Box(Modifier.background(Ind.danger).padding(horizontal = 7.dp, vertical = 1.dp)) { Body(badge.toString(), size = 12, color = Ind.bg, weight = FontWeight.SemiBold) }
        if (trailing != null) Body(trailing, size = 13, color = Ind.muted)
        if (!danger) Icon(Lucide.ChevronRight, null, Modifier.size(18.dp), tint = Ind.mutedSoft)
    }
}

/** Snackbars in the house style: dark bar, square corners. */
@Composable
fun IndSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data ->
        Snackbar(Modifier.padding(12.dp), containerColor = Ind.text, contentColor = Ind.bg, shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)) { Body(data.visuals.message, color = Ind.bg) }
    }
}

/** Shown while an app loads its configuration: brand art instead of a bare spinner, or the problem and a retry. */
@Composable
fun AppSplash(name: String, subtitle: String, failure: String? = null, onRetry: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize()) {
        BlueprintArt(Modifier.fillMaxSize(), seed = "splash", showCar = false)
        Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(52.dp).border(1.dp, Ind.a300), Alignment.Center) { Icon(Lucide.Droplet, null, Modifier.size(24.dp), tint = Ind.a100) }
            Heading(name.uppercase(), 34, color = Ind.a100)
            Body(subtitle, size = 13, color = Ind.a300)
            Spacer(Modifier.height(12.dp))
            if (failure == null) androidx.compose.material3.LinearProgressIndicator(Modifier.width(140.dp).height(2.dp), color = Ind.a300, trackColor = Ind.a800)
            else {
                Body(failure, size = 14, color = Ind.a100)
                if (onRetry != null) Box(Modifier.border(1.dp, Ind.a300).clickable(onClick = onRetry).padding(horizontal = 18.dp, vertical = 10.dp)) { Heading("Try again", 15, color = Ind.a100) }
            }
        }
    }
}

/** "Just now", "5 min ago", "3 h ago", "Yesterday", or the date. */
fun relativeTime(iso: String, now: kotlin.time.Instant = kotlin.time.Clock.System.now()): String {
    val t = runCatching { kotlin.time.Instant.parse(iso) }.getOrNull() ?: return ""
    val mins = (now - t).inWholeMinutes
    return when {
        mins < 1 -> "Just now"
        mins < 60 -> "$mins min ago"
        mins < 24 * 60 -> "${mins / 60} h ago"
        mins < 48 * 60 -> "Yesterday"
        mins < 7 * 24 * 60 -> "${mins / (24 * 60)} days ago"
        else -> com.odyssey.domain.formatDay(iso)
    }
}
