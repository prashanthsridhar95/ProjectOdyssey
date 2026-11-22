package com.odyssey.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.odyssey.data.AppContainer
import com.odyssey.domain.isValidPhone
import com.odyssey.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class LoginMode { OTP, STAFF_PIN }

data class LoginState(
    val mode: LoginMode = LoginMode.OTP, val phone: String = "", val code: String = "", val name: String = "",
    val codeSent: Boolean = false, val busy: Boolean = false, val error: String? = null, val resendIn: Int = 0,
)

class LoginViewModel(private val app: AppContainer, private val askName: Boolean) : BaseViewModel() {
    private val _state = MutableStateFlow(LoginState())
    val state: StateFlow<LoginState> = _state

    fun setMode(m: LoginMode) { _state.value = LoginState(mode = m) }
    fun phone(v: String) { _state.value = _state.value.copy(phone = v.filter { it.isDigit() || it == '+' || it == ' ' }, error = null) }
    fun code(v: String) { _state.value = _state.value.copy(code = v, error = null) }
    fun name(v: String) { _state.value = _state.value.copy(name = v) }
    fun changeNumber() { _state.value = _state.value.copy(codeSent = false, code = "", error = null) }

    private fun startResendTimer() {
        viewModelScope.launch {
            _state.value = _state.value.copy(resendIn = 30)
            while (_state.value.resendIn > 0) { delay(1000); _state.value = _state.value.copy(resendIn = _state.value.resendIn - 1) }
        }
    }

    fun resend() {
        if (_state.value.resendIn > 0) return
        viewModelScope.launch {
            try { app.signInWithOtp.request(_state.value.phone); startResendTimer() } catch (e: Exception) { _state.value = _state.value.copy(error = e.friendly()) }
        }
    }

    fun submit(onSignedIn: () -> Unit) {
        val s = _state.value
        if (s.busy) return
        _state.value = s.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                when {
                    s.mode == LoginMode.STAFF_PIN -> app.signInWithPin(s.phone, s.code)
                    !s.codeSent -> { app.signInWithOtp.request(s.phone); _state.value = _state.value.copy(codeSent = true, code = ""); startResendTimer() }
                    else -> app.signInWithOtp.verify(s.phone, s.code, s.name.takeIf { askName })
                }
                if (s.mode == LoginMode.STAFF_PIN || s.codeSent) onSignedIn()
                _state.value = _state.value.copy(busy = false)
            } catch (e: Exception) { _state.value = _state.value.copy(busy = false, error = e.friendly()) }
        }
    }
}

/**
 * Phone sign-in in the Industry style: wordmark, headline, a hero image slot, the phone number with its country code,
 * then six boxes for the code. Owners and counter staff use the same screen with a switch on the vendor app.
 */
@Composable
fun LoginScreen(
    title: String, subtitle: String, modes: Set<LoginMode> = setOf(LoginMode.OTP), askName: Boolean = false,
    showHero: Boolean = true, onSignedIn: () -> Unit,
) {
    val app = LocalContainer.current
    val brand = LocalTenant.current.tenant.name.uppercase()
    val vm = screenModel("login") { LoginViewModel(app, askName) }
    val s = vm.state.collectAsStateValue()
    val codeLength = if (s.mode == LoginMode.STAFF_PIN) 4 else 6
    val ready = isValidPhone(s.phone) && when {
        s.mode == LoginMode.STAFF_PIN -> s.code.length == 4
        !s.codeSent -> true
        else -> s.code.length == 6
    }
    Column(Modifier.fillMaxSize().background(Ind.bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Heading(brand, 22, color = Ind.a700)
                Heading(title, 36)
                Body(subtitle, color = Ind.muted)
            }
            if (showHero) Hatch(Modifier.fillMaxWidth().height(if (s.codeSent) 120.dp else 200.dp), "hero photo · duotone")
            if (modes.size > 1) {
                Seg(listOf("Owner", "Counter staff"), if (s.mode == LoginMode.OTP) 0 else 1) { vm.setMode(if (it == 0) LoginMode.OTP else LoginMode.STAFF_PIN) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Body("Mobile number", size = 12, color = Ind.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.width(72.dp).height(44.dp).background(Ind.surface).hairline(), contentAlignment = Alignment.Center) { Body("+91", size = 16) }
                    Field("", s.phone, vm::phone, Modifier.weight(1f), keyboard = KeyboardType.Phone, enabled = !s.codeSent, placeholder = "98450 21734")
                }
                if (s.codeSent) GhostButton("Change number", vm::changeNumber)
            }
            if (s.mode == LoginMode.STAFF_PIN) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { Body("4-digit PIN", size = 12, color = Ind.muted); CodeBoxes(s.code, 4, vm::code) }
            } else if (s.codeSent) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Body("Enter the 6-digit code sent by SMS", size = 12, color = Ind.muted)
                    CodeBoxes(s.code, 6, vm::code)
                    if (s.resendIn > 0) Body("Resend code in 0:${s.resendIn.toString().padStart(2, '0')}", size = 12, color = Ind.muted)
                    else GhostButton("Resend code", vm::resend)
                    Body("Local dev code: 123456", size = 12, color = Ind.mutedSoft)
                }
                if (askName) Field("Your name (first time only)", s.name, vm::name)
            }
            s.error?.let { ErrorBox(it) }
        }
        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            PrimaryButton(if (s.mode == LoginMode.OTP && !s.codeSent) "Send code" else "Verify and continue", { vm.submit(onSignedIn) }, enabled = ready && !s.busy)
        }
    }
}
