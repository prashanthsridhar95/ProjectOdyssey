package com.odyssey.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.odyssey.ui.res.*
import org.jetbrains.compose.resources.Font

/**
 * The "Industry" design system from the Rinse design: a blueprint look. Square corners, hairline borders,
 * registration marks on key objects, Barlow Condensed headings over Barlow, one steel-blue accent.
 * Tokens mirror the design's styles.css so the apps and the design stay in step.
 */
object Ind {
    val bg = Color(0xFFF2F2F3)
    val surface = Color(0xFFE9E9EA)
    val text = Color(0xFF1D1F20)
    val accent = Color(0xFF5980A6)
    val divider = Color(0x291D1F20) // 16% of text
    val muted = Color(0xFF5D5D60)   // neutral-700
    val mutedSoft = Color(0xFF98989B) // neutral-500
    val n200 = Color(0xFFE7E7EA)
    val n300 = Color(0xFFD4D4D7)
    val n400 = Color(0xFFB7B7BA)
    val n900 = Color(0xFF2B2B2D)
    val a100 = Color(0xFFEEF6FF)
    val a200 = Color(0xFFD6EBFF)
    val a300 = Color(0xFFB5D9FD)
    val a500 = Color(0xFF749DC4)
    val a600 = Color(0xFF597EA3)
    val a700 = Color(0xFF416180)
    val a800 = Color(0xFF2C455D)
    val a900 = Color(0xFF1D2D3D)
    val danger = Color(0xFFB4261F)
}

private val Square = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)

class IndustryFonts(val heading: FontFamily, val body: FontFamily)

val LocalFonts = staticCompositionLocalOf<IndustryFonts> { error("IndustryTheme missing") }

@Composable
fun IndustryTheme(content: @Composable () -> Unit) {
    val body = FontFamily(
        Font(Res.font.barlow_regular, FontWeight.Normal), Font(Res.font.barlow_medium, FontWeight.Medium),
        Font(Res.font.barlow_bold, FontWeight.Bold), Font(Res.font.barlow_bold, FontWeight.SemiBold),
    )
    val heading = FontFamily(Font(Res.font.barlowcondensed_regular, FontWeight.Normal), Font(Res.font.barlowcondensed_semibold, FontWeight.SemiBold), Font(Res.font.barlowcondensed_semibold, FontWeight.Bold))
    val fonts = remember(body, heading) { IndustryFonts(heading, body) }
    fun h(size: Int) = TextStyle(fontFamily = heading, fontWeight = FontWeight.SemiBold, fontSize = size.sp, lineHeight = (size * 1.12).sp, color = Ind.text)
    val b = TextStyle(fontFamily = body, fontSize = 15.sp, lineHeight = 22.sp, color = Ind.text)
    val typography = Typography(
        displayLarge = h(42), displayMedium = h(36), displaySmall = h(32), headlineLarge = h(32), headlineMedium = h(28), headlineSmall = h(25),
        titleLarge = h(22), titleMedium = h(19), titleSmall = h(17), bodyLarge = b, bodyMedium = b.copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = b.copy(fontSize = 12.sp, lineHeight = 16.sp), labelLarge = b.copy(fontWeight = FontWeight.Medium), labelMedium = b.copy(fontSize = 12.sp), labelSmall = b.copy(fontSize = 11.sp),
    )
    val scheme = lightColorScheme(
        primary = Ind.accent, onPrimary = Ind.bg, primaryContainer = Ind.a100, onPrimaryContainer = Ind.a800, secondary = Ind.accent, tertiary = Ind.accent,
        background = Ind.bg, onBackground = Ind.text, surface = Ind.bg, onSurface = Ind.text, surfaceVariant = Ind.surface, onSurfaceVariant = Ind.muted,
        outline = Ind.divider, outlineVariant = Ind.divider, error = Ind.danger, surfaceContainer = Ind.bg, surfaceContainerHigh = Ind.bg, surfaceContainerHighest = Ind.surface,
    )
    CompositionLocalProvider(LocalFonts provides fonts) {
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = Shapes(Square, Square, Square, Square, Square), content = content)
    }
}

/** Heading text: Barlow Condensed SemiBold, tight leading. */
@Composable
fun Heading(text: String, size: Int = 22, modifier: Modifier = Modifier, color: Color = Ind.text) =
    Text(text, modifier, color = color, style = TextStyle(fontFamily = LocalFonts.current.heading, fontWeight = FontWeight.SemiBold, fontSize = size.sp, lineHeight = (size * 1.1).sp, letterSpacing = (-0.015 * size).sp))

@Composable
fun Body(text: String, modifier: Modifier = Modifier, size: Int = 14, color: Color = Ind.text, weight: FontWeight = FontWeight.Normal, strike: Boolean = false, spacing: TextUnit = TextUnit.Unspecified) =
    Text(text, modifier, color = color, fontSize = size.sp, fontWeight = weight, letterSpacing = spacing, textDecoration = if (strike) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)

/** Small uppercase caption used above groups ("MORNING", "PRICE RANGE"). */
@Composable
fun Caps(text: String, modifier: Modifier = Modifier) = Body(text.uppercase(), modifier, size = 12, color = Ind.muted, spacing = 0.7.sp)

/** Hairline frame with registration marks on the four corners, the signature of the system. */
fun Modifier.blueprint(marks: Boolean = true, fill: Color = Color.Transparent, line: Color = Ind.divider): Modifier = this
    .then(if (fill != Color.Transparent) Modifier.background(fill) else Modifier)
    .border(1.dp, line, RectangleShape)
    .drawBehind {
        if (!marks) return@drawBehind
        val arm = 5.5.dp.toPx(); val c = Ind.text.copy(alpha = 0.55f); val w = 1.dp.toPx()
        listOf(Offset(0f, 0f), Offset(size.width, 0f), Offset(0f, size.height), Offset(size.width, size.height)).forEach { o ->
            drawLine(c, Offset(o.x - arm, o.y), Offset(o.x + arm, o.y), w); drawLine(c, Offset(o.x, o.y - arm), Offset(o.x, o.y + arm), w)
        }
    }

fun Modifier.hairline(line: Color = Ind.divider): Modifier = this.border(1.dp, line, RectangleShape)

/** The diagonal-hatch stand-in the design uses for photos and map tiles. */
@Composable
fun Hatch(modifier: Modifier = Modifier, label: String? = null, base: Color = Ind.n200) {
    Box(modifier.background(Ind.bg).clipToBounds().hairline(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val gap = 12.dp.toPx(); val stroke = 6.dp.toPx() * 0.7f
            var x = -size.height
            while (x < size.width) { drawLine(base, Offset(x, size.height), Offset(x + size.height, 0f), stroke); x += gap }
        }
        if (label != null) Body(label, Modifier.background(Ind.bg).padding(horizontal = 6.dp, vertical = 2.dp), size = 11, color = Ind.muted)
    }
}

enum class TagKind { ACCENT, NEUTRAL, OUTLINE, SOLID, DANGER }

@Composable
fun Tag(text: String, kind: TagKind = TagKind.NEUTRAL, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    val (bg, fg) = when (kind) {
        TagKind.ACCENT -> Ind.a100 to Ind.a800
        TagKind.NEUTRAL -> Color(0xFFF5F5F8) to Color(0xFF424244)
        TagKind.OUTLINE -> Color.Transparent to Ind.accent
        TagKind.SOLID -> Ind.accent to Ind.bg
        TagKind.DANGER -> Color(0xFFFDE8E6) to Ind.danger
    }
    Row(modifier.background(bg).then(if (kind == TagKind.OUTLINE) Modifier.border(1.dp, Ind.accent) else Modifier).padding(horizontal = 10.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (icon != null) Icon(icon, null, Modifier.size(13.dp), tint = fg)
        Text(text, color = fg, fontSize = 11.sp, letterSpacing = 0.02.sp)
    }
}

/** A selectable pill used for filters, vehicle types and quick answers. Solid when selected, hairline when not. */
@Composable
fun Choice(text: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.then(if (selected) Modifier.background(Ind.accent) else Modifier.hairline()).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Body(text, size = 13, color = if (selected) Ind.bg else if (enabled) Ind.text else Ind.mutedSoft) }
}

/** Segmented control: connected options in one hairline box. */
@Composable
fun Seg(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    Row(modifier.hairline()) {
        options.forEachIndexed { i, label ->
            Box(Modifier.weight(1f).background(if (i == selected) Ind.accent else Color.Transparent).clickable { onSelect(i) }.padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                Body(label, size = 13, color = if (i == selected) Ind.bg else Ind.text, weight = if (i == selected) FontWeight.Medium else FontWeight.Normal)
            }
            if (i < options.lastIndex) Box(Modifier.width(1.dp).height(36.dp).background(Ind.divider))
        }
    }
}

@Composable
fun IconBox(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 36.dp, tint: Color = Ind.text, onClick: (() -> Unit)? = null) {
    Box(modifier.size(size).background(Ind.bg).hairline().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(18.dp), tint = tint) }
}
