package com.odyssey.carwash

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import com.odyssey.domain.Asset
import com.odyssey.ui.*
import kotlinx.coroutines.launch

/**
 * The car wash pack for the mobile apps: the garage. The generic booking features know nothing about cars;
 * they ask the active [VerticalUi] for an "asset picker" and get this one.
 */
object CarWashUi : VerticalUi {
    @Composable
    override fun AssetPicker(selectedId: String?, variantKey: String, onSelected: (String?, String?) -> Unit) {
        val app = LocalContainer.current
        val tenant = LocalTenant.current.config
        val scope = rememberCoroutineScope()
        var garage by remember { mutableStateOf<Load<List<Asset>>>(Load.Loading) }
        var adding by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        suspend fun reload() { garage = try { Load.Ready(app.customer.assets()) } catch (e: Exception) { Load.Failed(e.friendly()) } }
        LaunchedEffect(Unit) { reload() }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Vehicle type decides the price, so it is always visible, even without a saved vehicle.
                tenant.variants.options.forEach { v -> Chip(v.label, selectedId == null && variantKey == v.key) { onSelected(null, v.key) } }
            }
            (garage as? Load.Ready)?.value?.let { cars ->
                if (cars.isNotEmpty()) Text("Or use a saved vehicle", style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { cars.forEach { c -> Chip(c.label, selectedId == c.id) { onSelected(c.id, c.attributes["vehicleType"]) } } }
            }
            SecondaryButton("＋ Save a vehicle", { adding = true })
            error?.let { ErrorBox(it) }
        }
        if (adding) AddVehicle(
            types = tenant.variants.options.map { it.key to it.label },
            onDismiss = { adding = false },
            onSave = { type, plate ->
                scope.launch {
                    try { val a = app.customer.addAsset(mapOf("vehicleType" to type, "plate" to plate.uppercase())); reload(); onSelected(a.id, type); adding = false; error = null }
                    catch (e: Exception) { error = e.friendly(); adding = false }
                }
            },
        )
    }
}

@Composable
private fun AddVehicle(types: List<Pair<String, String>>, onDismiss: () -> Unit, onSave: (type: String, plate: String) -> Unit) {
    var type by remember { mutableStateOf(types.first().first) }
    var plate by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Save a vehicle") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { types.forEach { (k, l) -> Chip(l, type == k) { type = k } } }
                Field("Number plate", plate, { plate = it })
            }
        },
        confirmButton = { androidx.compose.material3.Button(onClick = { onSave(type, plate) }, enabled = plate.length >= 4) { Text("Save") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
