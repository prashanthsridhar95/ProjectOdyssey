package com.odyssey.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide

/** Kept for existing call sites; every app now wears the Industry look. */
@Composable
fun OdysseyTheme(content: @Composable () -> Unit) = IndustryTheme(content)

/**
 * A screen with a back arrow, a heading, a scrolling body and an optional pinned footer (for the main action).
 * The body scrolls; the footer and [bottomBar] stay put.
 */
@Composable
fun Screen(
    title: String, onBack: (() -> Unit)? = null, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {},
    footer: (@Composable ColumnScope.() -> Unit)? = null, bottomBar: (@Composable () -> Unit)? = null, scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Ind.bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (onBack != null) Icon(Lucide.ArrowLeft, "Back", Modifier.size(24.dp).clickable(onClick = onBack))
            Column(Modifier.weight(1f)) {
                Heading(title, 22)
                if (subtitle != null) Body(subtitle, size = 12, color = Ind.muted)
            }
            actions()
        }
        val body = Modifier.weight(1f).fillMaxWidth().then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier).padding(horizontal = 20.dp)
        Column(body, verticalArrangement = Arrangement.spacedBy(16.dp)) { content(); Spacer(Modifier.height(8.dp)) }
        if (footer != null) Column(Modifier.fillMaxWidth().background(Ind.bg).topHairline().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = footer)
        if (bottomBar != null) bottomBar() else Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
fun <T> Remote(state: Load<T>, onRetry: () -> Unit, content: @Composable (T) -> Unit) {
    when (state) {
        Load.Loading -> Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) { CircularProgressIndicator(color = Ind.accent, strokeWidth = 2.dp) }
        is Load.Failed -> ErrorBox(state.message, onRetry)
        is Load.Ready -> content(state.value)
    }
}

@Composable
fun ErrorBox(message: String, onRetry: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().background(Color(0xFFFDE8E6)).border(1.dp, Ind.danger).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Body(message, color = Ind.danger)
        if (onRetry != null) GhostButton("Try again", onRetry)
    }
}

@Composable
fun EmptyNote(text: String) = Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { Body(text, color = Ind.muted) }

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { Caps(title); content() }
}

/** A hairline card with registration marks. Pass [onClick] to make it tappable. */
@Composable
fun SurfaceCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, padding: Int = 12, content: @Composable ColumnScope.() -> Unit) {
    val base = modifier.fillMaxWidth().padding(6.dp).blueprint().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    Column(base.padding(padding.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, height: Int = 52, trailing: String? = null) {
    Box(Modifier.padding(6.dp).then(modifier)) {
        Row(
            Modifier.fillMaxWidth().height(height.dp).blueprint(fill = if (enabled) Ind.accent else Ind.n300, line = if (enabled) Ind.accent else Ind.n300, marks = enabled).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (trailing != null) Arrangement.SpaceBetween else Arrangement.Center,
        ) {
            Heading(text, 17, color = if (enabled) Ind.bg else Ind.mutedSoft)
            if (trailing != null) Heading(trailing, 17, color = Ind.bg)
        }
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false, icon: ImageVector? = null) {
    Row(
        modifier.height(44.dp).hairline(if (danger) Ind.danger else Ind.divider).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = if (danger) Ind.danger else if (enabled) Ind.text else Ind.mutedSoft)
        Heading(text, 15, color = if (danger) Ind.danger else if (enabled) Ind.text else Ind.mutedSoft)
    }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) =
    Box(modifier.height(44.dp).clickable(onClick = onClick).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) { Heading(text, 15, color = Ind.accent) }

@Composable
fun Chip(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) = Choice(text, selected, enabled = enabled, onClick = onClick)

@Composable
fun StatusBadge(label: String, status: String) {
    val kind = when (status) {
        "LIVE", "COMPLETED", "APPROVED", "CONFIRMED", "CHECKED_IN", "IN_PROGRESS" -> TagKind.ACCENT
        "SUSPENDED", "REJECTED", "CANCELLED", "NO_SHOW", "EXPIRED" -> TagKind.DANGER
        else -> TagKind.NEUTRAL
    }
    Tag(label, kind)
}

@Composable
fun Labeled(label: String, value: String, strong: Boolean = false) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    if (strong) { Heading(label, 20); Heading(value, 20) } else { Body(label, color = Ind.muted); Body(value) }
}

/** A labelled text input: caption above, hairline box on the surface tone. */
@Composable
fun Field(
    label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false, enabled: Boolean = true, supporting: String? = null, height: Int = 44, multiline: Boolean = false, placeholder: String = "", bare: Boolean = false,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label.isNotEmpty()) Body(label, size = 12, color = Ind.muted)
        BasicTextField(
            value, onChange, Modifier.fillMaxWidth().then(if (multiline) Modifier.heightIn(min = 100.dp) else Modifier.height(height.dp)).then(if (bare) Modifier else Modifier.background(Ind.surface).hairline()).padding(horizontal = if (bare) 0.dp else 10.dp, vertical = if (multiline) 10.dp else 0.dp),
            enabled = enabled, singleLine = !multiline, textStyle = TextStyle(fontFamily = LocalFonts.current.body, fontSize = 16.sp, color = Ind.text),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard), visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            cursorBrush = SolidColor(Ind.accent),
            decorationBox = { inner -> Box(Modifier.fillMaxHeight(), contentAlignment = if (multiline) Alignment.TopStart else Alignment.CenterStart) { if (value.isEmpty() && placeholder.isNotEmpty()) Body(placeholder, size = 16, color = Ind.mutedSoft); inner() } },
        )
        if (supporting != null) Body(supporting, size = 12, color = Ind.muted)
    }
}

/** Six boxes for a one-time code, one digit each; the box being typed into is outlined in the accent. */
@Composable
fun CodeBoxes(value: String, length: Int = 6, onChange: (String) -> Unit) {
    BasicTextField(
        value, { onChange(it.filter(Char::isDigit).take(length)) }, Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), cursorBrush = SolidColor(Color.Transparent), textStyle = TextStyle(color = Color.Transparent),
        decorationBox = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(length) { i ->
                    Box(Modifier.weight(1f).height(48.dp).background(Ind.surface).border(1.dp, if (i == value.length) Ind.accent else Ind.divider), contentAlignment = Alignment.Center) {
                        Heading(value.getOrNull(i)?.toString() ?: "", 22)
                    }
                }
            }
        },
    )
}

@Composable
fun RadioRow(label: String, selected: Boolean, trailing: String? = null, height: Int = 52, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(height.dp).background(if (selected) Ind.a100 else Color.Transparent).border(1.dp, if (selected) Ind.accent else Ind.divider).clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(18.dp).border(if (selected) 5.dp else 1.5.dp, if (selected) Ind.accent else Ind.mutedSoft, CircleShape))
        Body(label, size = 15, modifier = Modifier.weight(1f))
        if (trailing != null) Body(trailing, size = 13, color = Ind.muted)
    }
}

@Composable
fun CheckRow(label: String, checked: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(20.dp).then(if (checked) Modifier.background(Ind.accent) else Modifier.border(1.5.dp, Ind.mutedSoft)), contentAlignment = Alignment.Center) {
            if (checked) Icon(Lucide.Check, null, Modifier.size(14.dp), tint = Ind.bg)
        }
        Body(label, size = 15)
    }
}

/** Square on/off switch from the design. */
@Composable
fun SquareToggle(on: Boolean, onChange: (Boolean) -> Unit) {
    Box(Modifier.width(44.dp).height(26.dp).background(if (on) Ind.accent else Ind.n300).clickable { onChange(!on) }) {
        Box(Modifier.align(if (on) Alignment.CenterEnd else Alignment.CenterStart).padding(3.dp).size(20.dp).background(Ind.bg))
    }
}

data class TabItem(val label: String, val icon: ImageVector)

/** Bottom tab bar: icon over label, accent when selected, hairline on top. */
@Composable
fun BottomTabs(items: List<TabItem>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(72.dp).background(Ind.bg).topHairline().navigationBarsPadding()) {
        items.forEachIndexed { i, t ->
            val c = if (i == selected) Ind.a700 else Ind.muted
            Column(Modifier.weight(1f).fillMaxHeight().clickable { onSelect(i) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
                Icon(t.icon, null, Modifier.size(22.dp), tint = c); Body(t.label, size = 11, color = c, weight = if (i == selected) FontWeight.Medium else FontWeight.Normal)
            }
        }
    }
}

fun Modifier.topHairline(): Modifier = this.drawBehind {
    drawLine(Ind.divider, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(this.size.width, 0f), 1.dp.toPx())
}

@Composable
fun PinDialog(title: String = "Owner PIN", error: String?, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Ind.bg, title = { Heading(title, 22) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Body("This action is protected. Enter the 6-digit Owner PIN.")
                CodeBoxes(pin, 6) { pin = it }
                if (error != null) Body(error, color = Ind.danger)
            }
        },
        confirmButton = { PrimaryButton("Confirm", { onConfirm(pin) }, enabled = pin.length == 6, height = 44) },
        dismissButton = { GhostButton("Cancel", onDismiss) },
    )
}

@Composable
fun ReasonDialog(title: String, hint: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Ind.bg, title = { Heading(title, 22) },
        text = { Field(hint, text, { text = it }, multiline = true) },
        confirmButton = { PrimaryButton("Confirm", { onConfirm(text.trim()) }, enabled = text.isNotBlank(), height = 44) },
        dismissButton = { GhostButton("Back", onDismiss) },
    )
}

fun Modifier.bottomHairline(): Modifier = this.drawBehind {
    drawLine(Ind.divider, androidx.compose.ui.geometry.Offset(0f, this.size.height), androidx.compose.ui.geometry.Offset(this.size.width, this.size.height), 1.dp.toPx())
}
