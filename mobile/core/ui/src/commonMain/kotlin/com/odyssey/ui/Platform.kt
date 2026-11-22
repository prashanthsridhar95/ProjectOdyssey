package com.odyssey.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.odyssey.domain.Asset
import com.odyssey.domain.SavedPlace
import androidx.compose.ui.graphics.vector.ImageVector

/** Things only the phone can do. Each platform supplies an implementation; screens just call these. */
interface PlatformActions {
    fun directions(lat: Double, lng: Double, label: String)
    fun call(phone: String)
    fun addToCalendar(title: String, startEpochMs: Long, endEpochMs: Long, location: String)
    fun notify(title: String, body: String)
    /** Opens the photo picker and returns a small JPEG as a data URI, or null if cancelled. */
    fun pickPhoto(onResult: (String?) -> Unit)
    /** Opens the camera to scan a QR code and returns its text, or null if cancelled. */
    fun scanCode(onResult: (String?) -> Unit)
    /** A repeating alert sound for a new booking that needs accepting. */
    fun alertSound(on: Boolean)
    fun shareText(title: String, text: String)
}

object NoPlatform : PlatformActions {
    override fun directions(lat: Double, lng: Double, label: String) = Unit
    override fun call(phone: String) = Unit
    override fun addToCalendar(title: String, startEpochMs: Long, endEpochMs: Long, location: String) = Unit
    override fun notify(title: String, body: String) = Unit
    override fun pickPhoto(onResult: (String?) -> Unit) = onResult(null)
    override fun scanCode(onResult: (String?) -> Unit) = onResult(null)
    override fun alertSound(on: Boolean) = Unit
    override fun shareText(title: String, text: String) = Unit
}

val LocalPlatform = staticCompositionLocalOf<PlatformActions> { NoPlatform }

/** The dark and light squares of a QR code for [text]. Each platform encodes it with its own library. */
expect fun qrModules(text: String): List<List<Boolean>>

@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val modules = remember(text) { qrModules(text) }
    Canvas(modifier.background(Ind.bg).padding(8.dp)) {
        val n = modules.size
        if (n == 0) return@Canvas
        val cell = size.minDimension / n
        for (y in 0 until n) for (x in 0 until n) if (modules[y][x]) drawRect(Ind.text, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
    }
}

/** A chosen search origin: the phone's position or one of the customer's saved places. */
data class PlaceSel(val label: String, val address: String, val lat: Double, val lng: Double)

/** What the customer app remembers across screens: where they are looking, which vehicle, what they favour. */
@Stable
class CustomerState {
    var place by mutableStateOf(PlaceSel("Current location", "", 12.9716, 77.5946))
    var vehicle by mutableStateOf<Asset?>(null)
    var garage by mutableStateOf<List<Asset>>(emptyList())
    var savedPlaces by mutableStateOf<List<SavedPlace>>(emptyList())
    var favorites by mutableStateOf<Set<String>>(emptySet())
    var unread by mutableStateOf(0)
}

val LocalCustomer = staticCompositionLocalOf { CustomerState() }
