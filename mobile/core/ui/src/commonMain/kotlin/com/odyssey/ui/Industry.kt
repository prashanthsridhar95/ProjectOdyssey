package com.odyssey.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
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
 * The "Industry" design system from the Rinse design: a blueprint look, softened. Rounded corners on one scale
 * ([Ind.rSm] controls, [Ind.rMd] rows and cards, [Ind.rLg] sheets, [Ind.pill] chips and tags), hairline borders,
 * Barlow Condensed headings over Barlow, one steel-blue accent, and quiet motion (see Motion.kt).
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

    val rXs = RoundedCornerShape(6.dp)
    val rSm = RoundedCornerShape(10.dp)
    val rMd = RoundedCornerShape(14.dp)
    val rLg = RoundedCornerShape(20.dp)
    val pill = RoundedCornerShape(percent = 50)
}

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
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = Shapes(Ind.rXs, Ind.rSm, Ind.rMd, Ind.rLg, Ind.rLg), content = content)
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

/**
 * The card frame: a rounded hairline with an optional fill, clipped so ripples and children stay inside the corners.
 * [marks] adds the blueprint registration marks; off by default now that corners are rounded.
 */
fun Modifier.blueprint(marks: Boolean = false, fill: Color = Color.Transparent, line: Color = Ind.divider, shape: Shape = Ind.rMd): Modifier = this
    .then(if (fill != Color.Transparent) Modifier.background(fill, shape) else Modifier)
    .border(1.dp, line, shape)
    .clip(shape)
    .drawBehind {
        if (!marks) return@drawBehind
        val arm = 5.5.dp.toPx(); val c = Ind.text.copy(alpha = 0.55f); val w = 1.dp.toPx()
        listOf(Offset(0f, 0f), Offset(size.width, 0f), Offset(0f, size.height), Offset(size.width, size.height)).forEach { o ->
            drawLine(c, Offset(o.x - arm, o.y), Offset(o.x + arm, o.y), w); drawLine(c, Offset(o.x, o.y - arm), Offset(o.x, o.y + arm), w)
        }
    }

/** A rounded hairline frame. It clips what follows (ripples, fills, children) to the same corners. */
fun Modifier.hairline(line: Color = Ind.divider, shape: Shape = Ind.rSm): Modifier = this.border(1.dp, line, shape).clip(shape)

/** The diagonal-hatch stand-in the design uses for photos and map tiles. */
@Composable
fun Hatch(modifier: Modifier = Modifier, label: String? = null, base: Color = Ind.n200) {
    Box(modifier.background(Ind.bg, Ind.rSm).clipToBounds().hairline(), contentAlignment = Alignment.Center) {
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
    Row(modifier.background(bg, Ind.pill).then(if (kind == TagKind.OUTLINE) Modifier.border(1.dp, Ind.accent, Ind.pill) else Modifier).padding(horizontal = 10.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (icon != null) Icon(icon, null, Modifier.size(13.dp), tint = fg)
        Text(text, color = fg, fontSize = 11.sp, letterSpacing = 0.02.sp)
    }
}

/** A selectable pill used for filters, vehicle types and quick answers. Solid when selected, hairline when not. */
@Composable
fun Choice(text: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val fill by animateColorAsState(if (selected) Ind.accent else Color.Transparent, Motion.quick(), label = "choice")
    val line by animateColorAsState(if (selected) Ind.accent else Ind.divider, Motion.quick(), label = "choiceLine")
    Box(
        modifier.clip(Ind.pill).pressable(enabled = enabled, onClick = onClick).background(fill, Ind.pill).hairline(line, Ind.pill).padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Body(text, size = 13, color = if (selected) Ind.bg else if (enabled) Ind.text else Ind.mutedSoft) }
}

/** Segmented control: connected options in one hairline box. */
@Composable
fun Seg(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    // A sliding thumb on a soft track: the selected option's pill glides to where you tapped.
    BoxWithConstraints(modifier.background(Ind.surface, Ind.rSm).hairline().padding(3.dp)) {
        val w = maxWidth / options.size.coerceAtLeast(1)
        val x by animateDpAsState(w * selected, Motion.springy(), label = "seg")
        Box(Modifier.offset(x = x).width(w).height(34.dp).background(Ind.accent, RoundedCornerShape(8.dp)))
        Row(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, label ->
                val c by animateColorAsState(if (i == selected) Ind.bg else Ind.text, Motion.quick(), label = "segText")
                Box(Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(8.dp)).clickable { onSelect(i) }.padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                    Body(label, size = 13, color = c, weight = if (i == selected) FontWeight.Medium else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
fun IconBox(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 36.dp, tint: Color = Ind.text, onClick: (() -> Unit)? = null) {
    Box(modifier.size(size).background(Ind.bg, Ind.rSm).hairline().then(if (onClick != null) Modifier.pressable(onClick = onClick) else Modifier), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(18.dp), tint = tint) }
}

/**
 * Branded artwork in the blueprint style: a steel-blue panel with a fine grid and a line-drawn car with dimension marks.
 * Used wherever a photo would go until vendors upload their own. [seed] varies the tone and framing so a list of places
 * does not look like copies; [monogram] puts initials in the corner (an outlet's, for example).
 */
@Composable
fun BlueprintArt(modifier: Modifier = Modifier, seed: String = "", monogram: String? = null, showCar: Boolean = true, animated: Boolean = false) {
    // Looped detail for hero art only (never in lists): spray keeps falling and a soft light sweeps the drawing sheet.
    val loop = if (animated) rememberInfiniteTransition(label = "art") else null
    val drip = loop?.animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "drip")
    val sweep = loop?.animateFloat(0f, 1f, infiniteRepeatable(tween(6500, easing = FastOutSlowInEasing)), label = "sweep")
    val h = seed.fold(7) { a, c -> (a * 31 + c.code) and 0x7fffffff }
    val tones = listOf(Ind.a800 to Ind.a700, Ind.a900 to Ind.a800, Ind.a700 to Ind.a600)
    val (bgTop, bgBottom) = tones[h % tones.size]
    val shift = ((h / 7) % 5 - 2) * 0.03f
    Box(modifier.clipToBounds().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(bgTop, bgBottom)))) {
        Canvas(Modifier.matchParentSize()) {
            val gridGap = 16.dp.toPx(); val thin = 1.dp.toPx()
            val grid = Ind.a300.copy(alpha = 0.10f)
            var gx = 0f; while (gx < size.width) { drawLine(grid, Offset(gx, 0f), Offset(gx, size.height), thin); gx += gridGap }
            var gy = 0f; while (gy < size.height) { drawLine(grid, Offset(0f, gy), Offset(size.width, gy), thin); gy += gridGap }
            sweep?.value?.let { v ->
                val band = 90.dp.toPx(); val x = -band + v * (size.width + band * 2)
                drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Transparent, Ind.a300.copy(alpha = 0.10f), Color.Transparent), x - band / 2, x + band / 2), Offset(x - band / 2, 0f), androidx.compose.ui.geometry.Size(band, size.height))
            }
            if (!showCar) return@Canvas
            // Car box: about 62% of the width, kept inside the height.
            val cw = minOf(size.width * 0.62f, size.height * 1.9f)
            val ch = cw * 0.38f
            val left = size.width * (0.5f + shift) - cw / 2
            val top = size.height * 0.56f - ch / 2
            fun p(x: Float, y: Float) = Offset(left + x * cw, top + y * ch)
            val line = Ind.a200.copy(alpha = 0.9f); val stroke = 1.6.dp.toPx()
            val body = androidx.compose.ui.graphics.Path().apply {
                val pts = listOf(0.03f to 0.74f, 0.03f to 0.54f, 0.10f to 0.47f, 0.30f to 0.42f, 0.41f to 0.18f, 0.66f to 0.16f, 0.80f to 0.40f, 0.94f to 0.45f, 0.97f to 0.58f, 0.97f to 0.74f)
                moveTo(p(pts[0].first, pts[0].second).x, p(pts[0].first, pts[0].second).y)
                pts.drop(1).forEach { (x, y) -> val o = p(x, y); lineTo(o.x, o.y) }
                close()
            }
            drawPath(body, line, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            val window = androidx.compose.ui.graphics.Path().apply {
                val a = p(0.34f, 0.42f); moveTo(a.x, a.y)
                listOf(0.43f to 0.25f, 0.64f to 0.23f, 0.75f to 0.42f).forEach { (x, y) -> val o = p(x, y); lineTo(o.x, o.y) }
                close()
            }
            drawPath(window, line.copy(alpha = 0.6f), style = androidx.compose.ui.graphics.drawscope.Stroke(thin))
            drawLine(line.copy(alpha = 0.6f), p(0.55f, 0.24f), p(0.55f, 0.74f), thin)
            val r = ch * 0.17f
            listOf(0.24f, 0.78f).forEach { wx ->
                val c = p(wx, 0.74f)
                drawCircle(bgBottom, r * 1.08f, c)
                drawCircle(line, r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                drawCircle(line.copy(alpha = 0.5f), r * 0.45f, c, style = androidx.compose.ui.graphics.drawscope.Stroke(thin))
            }
            // Ground line and a dimension line over the roof, the way a drawing sheet marks size.
            val ground = top + 0.74f * ch + r
            drawLine(line.copy(alpha = 0.35f), Offset(left - cw * 0.1f, ground), Offset(left + cw * 1.1f, ground), thin)
            val dim = top - ch * 0.22f; val tick = 4.dp.toPx(); val dimC = Ind.a300.copy(alpha = 0.55f)
            drawLine(dimC, Offset(left + 0.03f * cw, dim), Offset(left + 0.97f * cw, dim), thin)
            listOf(0.03f, 0.97f).forEach { x -> drawLine(dimC, Offset(left + x * cw, dim - tick), Offset(left + x * cw, dim + tick), thin) }
            // Spray: three short arcs falling onto the roof.
            val spray = Ind.a100.copy(alpha = 0.7f)
            listOf(0.46f, 0.55f, 0.64f).forEachIndexed { i, x ->
                val base = Offset(left + x * cw, top + ch * (0.02f - 0.05f * (i % 2)))
                val d = drip?.value
                if (d == null) drawLine(spray, base, Offset(base.x - ch * 0.04f, base.y + ch * 0.1f), stroke)
                else {
                    val ph = (d + i / 3f) % 1f
                    val s = Offset(base.x + ch * 0.04f * (1f - ph), base.y - ch * 0.12f + ch * 0.18f * ph)
                    val a = (if (ph < 0.2f) ph / 0.2f else if (ph > 0.75f) (1f - ph) / 0.25f else 1f).coerceIn(0f, 1f)
                    drawLine(spray.copy(alpha = 0.7f * a), s, Offset(s.x - ch * 0.04f, s.y + ch * 0.1f), stroke)
                }
            }
        }
        if (monogram != null) {
            Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).border(1.dp, Ind.a300.copy(alpha = 0.6f), Ind.pill).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text(monogram, color = Ind.a100, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.08.sp)
            }
        }
    }
}

/** Up to two initials from a name: "Sparkle Auto Spa" -> "SA". */
fun initialsOf(name: String): String =
    name.split(' ', '-', '&').filter { it.isNotBlank() && it.first().isLetterOrDigit() }.take(2).joinToString("") { it.first().uppercase() }
