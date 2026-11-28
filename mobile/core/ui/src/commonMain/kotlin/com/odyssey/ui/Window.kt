package com.odyssey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How much room the app has. Phones get the bottom tab bar and one column; desktop browsers and tablets get a side rail and wider layouts. */
enum class WindowClass { COMPACT, WIDE }

val LocalWindow = staticCompositionLocalOf { WindowClass.COMPACT }

val WindowClass.isWide get() = this == WindowClass.WIDE

/** Tells every screen below which size class it is in. 720dp is where two columns start to make sense. */
@Composable
fun ProvideWindowClass(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalWindow provides if (maxWidth >= 720.dp) WindowClass.WIDE else WindowClass.COMPACT) { content() }
    }
}

/** Keeps a screen at a readable width, centred, with the rest of the window left empty. */
@Composable
fun CenteredContent(maxWidth: Dp, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Ind.bg), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = maxWidth).fillMaxHeight().fillMaxWidth()) { content() }
    }
}

/** The side navigation used instead of the bottom tabs on wide windows: brand on top, one row per tab. */
@Composable
fun SideRail(brand: String, items: List<TabItem>, selected: Int, onSelect: (Int) -> Unit, footer: @Composable ColumnScope.() -> Unit = {}) {
    Column(Modifier.width(232.dp).fillMaxHeight().background(Ind.surface).padding(vertical = 20.dp)) {
        Heading(brand.uppercase(), 28, color = Ind.a700, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        Spacer(Modifier.height(16.dp))
        items.forEachIndexed { i, t ->
            val on = i == selected
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp).background(if (on) Ind.a100 else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onSelect(i) }.padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(t.icon, null, Modifier.size(20.dp), tint = if (on) Ind.a800 else Ind.text)
                Body(t.label, size = 15, color = if (on) Ind.a800 else Ind.text, weight = if (on) FontWeight.Medium else FontWeight.Normal)
            }
        }
        Spacer(Modifier.weight(1f))
        footer()
    }
}
