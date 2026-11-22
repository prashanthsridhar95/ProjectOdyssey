package com.odyssey.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Teal = Color(0xFF0E6F8F)

@Composable
fun OdysseyTheme(primary: Color = Teal, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(primary = primary, secondary = primary, tertiary = primary, background = Color(0xFFF6F7F9), surface = Color.White, surfaceVariant = Color(0xFFEDEFF3)),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Screen(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = { if (onBack != null) TextButton(onClick = onBack) { Text("‹ Back") } },
                actions = actions,
            )
        },
    ) { padding -> Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
}

@Composable
fun <T> Remote(state: Load<T>, onRetry: () -> Unit, content: @Composable (T) -> Unit) {
    when (state) {
        Load.Loading -> Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) { CircularProgressIndicator() }
        is Load.Failed -> ErrorBox(state.message, onRetry)
        is Load.Ready -> content(state.value)
    }
}

@Composable
fun ErrorBox(message: String, onRetry: (() -> Unit)? = null) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFDE8E6))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, color = Color(0xFFB4261F))
            if (onRetry != null) OutlinedButton(onClick = onRetry) { Text("Try again") }
        }
    }
}

@Composable
fun EmptyNote(text: String) = Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { Text(text, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f)) }

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp); content() }
}

@Composable
fun SurfaceCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val m = if (onClick != null) modifier.fillMaxWidth().clickable(onClick = onClick) else modifier.fillMaxWidth()
    Card(m, shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, Color(0xFFE3E6EB))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    Button(onClick, modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = enabled, shape = RoundedCornerShape(12.dp)) { Text(text, fontWeight = FontWeight.SemiBold) }

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false) =
    OutlinedButton(onClick, modifier, enabled = enabled, shape = RoundedCornerShape(12.dp), colors = if (danger) ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB4261F)) else ButtonDefaults.outlinedButtonColors()) { Text(text) }

@Composable
fun Chip(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) =
    FilterChip(selected = selected, onClick = onClick, enabled = enabled, label = { Text(text) })

@Composable
fun StatusBadge(label: String, status: String) {
    val (bg, fg) = when (status) {
        "LIVE", "COMPLETED", "APPROVED", "CONFIRMED" -> Color(0xFFE3F5EA) to Color(0xFF1A7F4B)
        "SUBMITTED", "UNDER_REVIEW", "PAID", "IN_PROGRESS", "PENDING_PAYMENT", "CHANGES_REQUESTED" -> Color(0xFFFDF1D8) to Color(0xFF9A6200)
        "SUSPENDED", "REJECTED", "CANCELLED", "NO_SHOW", "EXPIRED" -> Color(0xFFFDE8E6) to Color(0xFFB4261F)
        else -> Color(0xFFEDEFF3) to Color(0xFF5B6573)
    }
    Text(label, Modifier.background(bg, RoundedCornerShape(999.dp)).padding(horizontal = 10.dp, vertical = 3.dp), color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
fun Labeled(label: String, value: String) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f)); Text(value, fontWeight = FontWeight.Medium)
}

@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, keyboard: KeyboardType = KeyboardType.Text, secret: Boolean = false, enabled: Boolean = true, supporting: String? = null) =
    OutlinedTextField(
        value, onChange, modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard), visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        supportingText = supporting?.let { { Text(it) } }, shape = RoundedCornerShape(12.dp),
    )

@Composable
fun PinDialog(title: String = "Owner PIN", error: String?, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This action is protected. Enter the 6-digit Owner PIN.")
                Field("Owner PIN", pin, { pin = it.filter(Char::isDigit).take(6) }, keyboard = KeyboardType.NumberPassword, secret = true)
                if (error != null) Text(error, color = Color(0xFFB4261F))
            }
        },
        confirmButton = { Button(onClick = { onConfirm(pin) }, enabled = pin.length == 6) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ReasonDialog(title: String, hint: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = { Field(hint, text, { text = it }) },
        confirmButton = { Button(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Back") } },
    )
}
