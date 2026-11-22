package com.odyssey.ui

import androidx.compose.runtime.*
import com.odyssey.domain.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Owner-PIN gate for sensitive actions. `gate.ask { pin -> ... }` shows the PIN dialog, runs the action with the PIN,
 * and keeps the dialog open with an error when the server says the PIN is wrong. Other failures go to [onFailure].
 */
@Stable
class PinGate {
    internal var visible by mutableStateOf(false)
    internal var error by mutableStateOf<String?>(null)
    internal var title by mutableStateOf("Owner PIN")
    internal var action: (suspend (String) -> Unit)? = null

    fun ask(title: String = "Owner PIN", action: suspend (pin: String) -> Unit) {
        this.title = title; this.action = action; error = null; visible = true
    }
    internal fun dismiss() { visible = false; error = null; action = null }
}

@Composable
fun rememberPinGate() = remember { PinGate() }

@Composable
fun PinGateHost(gate: PinGate, onFailure: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    if (!gate.visible) return
    PinDialog(
        title = gate.title, error = gate.error, onDismiss = gate::dismiss,
        onConfirm = { pin ->
            val run = gate.action ?: return@PinDialog
            scope.launch {
                try { run(pin); gate.dismiss() }
                catch (e: CancellationException) { throw e }
                catch (e: ApiException) {
                    if (e.isPinProblem) { gate.error = if (e.code == "pin_locked") e.message else "That PIN is not right." ; if (e.code == "pin_locked") gate.dismiss() }
                    else { gate.dismiss(); onFailure(e.message) }
                }
                catch (e: Exception) { gate.dismiss(); onFailure(e.friendly()) }
            }
        },
    )
}

/** Where the phone is. Android supplies a real implementation; the default is the middle of Bengaluru. */
interface LocationProvider {
    suspend fun current(): Pair<Double, Double>
}

object FixedLocation : LocationProvider {
    override suspend fun current() = 12.9716 to 77.5946
}

val LocalLocation = staticCompositionLocalOf<LocationProvider> { FixedLocation }
