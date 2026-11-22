package com.odyssey.carwash

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import com.odyssey.domain.Asset
import com.odyssey.domain.SavedPlace
import com.odyssey.ui.*
import kotlinx.coroutines.launch

/**
 * The car wash pack for the mobile apps. The generic features know nothing about cars; they ask the active
 * [VerticalUi] for the garage form and for which vehicle type (price column) a saved vehicle uses.
 */
object CarWashUi : VerticalUi {
    override val assetNoun = "Vehicle"
    override val serviceNoun = "wash"
    override val reviewTags = listOf("On time", "Spotless exterior", "Friendly staff", "Good value", "Clean interior")
    override val reportCategories = listOf("QUALITY" to "Wash quality", "DAMAGE" to "Damage to my vehicle", "WAIT" to "Long wait or slot not honoured", "CHARGE" to "Charged incorrectly")

    override fun variantOf(asset: Asset): String? = asset.attributes["vehicleType"]

    override fun iconForVariant(key: String): ImageVector = when (key) {
        "HATCHBACK" -> Lucide.Car
        "SEDAN" -> Lucide.CarFront
        "SUV" -> Lucide.Truck
        "BIKE" -> Lucide.Bike
        else -> Lucide.Car
    }

    override fun iconFor(asset: Asset): ImageVector = iconForVariant(variantOf(asset) ?: "")

    @Composable
    override fun AssetForm(stepText: String?, onBack: (() -> Unit)?, onSaved: (Asset) -> Unit) {
        val app = LocalContainer.current
        val tenant = LocalTenant.current.config
        val customer = LocalCustomer.current
        val where = LocalLocation.current
        val scope = rememberCoroutineScope()
        var type by remember { mutableStateOf(tenant.variants.options.first().key) }
        var plate by remember { mutableStateOf("") }
        var nickname by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        var saveAs by remember { mutableStateOf<String?>(null) }

        Screen(
            "Your garage", onBack = onBack, actions = { if (stepText != null) Body(stepText, size = 13, color = Ind.muted) },
            footer = { PrimaryButton(if (busy) "Saving…" else "Save vehicle", {
                busy = true; error = null
                scope.launch {
                    try {
                        val attrs = buildMap { put("vehicleType", type); put("plate", plate.trim().uppercase()); if (nickname.isNotBlank()) put("nickname", nickname.trim()) }
                        val a = app.customer.addAsset(attrs)
                        customer.garage = customer.garage + a
                        if (customer.vehicle == null) customer.vehicle = a
                        onSaved(a)
                    } catch (e: Exception) { error = e.friendly() } finally { busy = false }
                }
            }, enabled = plate.trim().length >= 4 && !busy) },
        ) {
            Body("Prices depend on vehicle type. You can add more vehicles later.", color = Ind.muted)
            Section("Vehicle type") {
                tenant.variants.options.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { v ->
                            val on = type == v.key
                            Column(
                                Modifier.weight(1f).height(84.dp).then(if (on) Modifier.blueprint(fill = Ind.a100, line = Ind.accent) else Modifier.hairline()).clickable { type = v.key },
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                            ) {
                                Icon(iconForVariant(v.key), null, Modifier.size(22.dp), tint = if (on) Ind.a800 else Ind.text)
                                Body(v.label, color = if (on) Ind.a800 else Ind.text, weight = if (on) FontWeight.Medium else FontWeight.Normal)
                            }
                        }
                    }
                }
            }
            Field("Number plate", plate, { plate = it.uppercase() }, height = 44, placeholder = "KA 03 MN 4821")
            Field("Nickname (optional)", nickname, { nickname = it }, placeholder = "White City")
            Section("Where should we look?") {
                Row(
                    Modifier.fillMaxWidth().height(48.dp).hairline(Ind.accent).clickable {
                        scope.launch { val (la, ln) = where.current(); customer.place = PlaceSel("Current location", "Near you", la, ln) }
                    }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) { Icon(Lucide.LocateFixed, null, Modifier.size(18.dp), tint = Ind.a800); Body("Use current location", color = Ind.a800) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.clickable { saveAs = "Home" }) { Tag("Save Home", TagKind.NEUTRAL, Lucide.House) }
                    Box(Modifier.clickable { saveAs = "Work" }) { Tag("Save Work", TagKind.NEUTRAL, Lucide.Briefcase) }
                }
            }
            error?.let { ErrorBox(it) }
        }
        saveAs?.let { label ->
            var address by remember(label) { mutableStateOf("") }
            ReasonDialogLike("Save $label", "Address", address, { address = it }, onConfirm = {
                scope.launch {
                    try {
                        val p = customer.place
                        customer.savedPlaces = app.customer.savePlace(SavedPlace(label, address.trim(), p.lat, p.lng))
                        customer.place = PlaceSel(label, address.trim(), p.lat, p.lng)
                    } catch (e: Exception) { error = e.friendly() }
                    saveAs = null
                }
            }, onDismiss = { saveAs = null })
        }
    }
}

@Composable
private fun ReasonDialogLike(title: String, hint: String, value: String, onChange: (String) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss, containerColor = Ind.bg, title = { Heading(title, 22) },
        text = { Field(hint, value, onChange) },
        confirmButton = { PrimaryButton("Save", onConfirm, enabled = value.isNotBlank(), height = 44) },
        dismissButton = { GhostButton("Cancel", onDismiss) },
    )
}
