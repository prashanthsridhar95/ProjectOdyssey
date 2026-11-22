package com.odyssey.auth

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import com.odyssey.data.AppContainer
import com.odyssey.domain.isValidPhone
import com.odyssey.ui.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class LoginMode { OTP, STAFF_PIN }

data class LoginState(
    val mode: LoginMode = LoginMode.OTP, val phone: String = "", val code: String = "", val name: String = "",
    val codeSent: Boolean = false, val busy: Boolean = false, val error: String? = null,
)

class LoginViewModel(private val app: AppContainer, private val askName: Boolean) : BaseViewModel() {
    private val _state = MutableStateFlow(LoginState())
    val state: StateFlow<LoginState> = _state

    fun setMode(m: LoginMode) { _state.value = LoginState(mode = m) }
    fun phone(v: String) { _state.value = _state.value.copy(phone = v, error = null) }
    fun code(v: String) { _state.value = _state.value.copy(code = v, error = null) }
    fun name(v: String) { _state.value = _state.value.copy(name = v) }

    fun submit(onSignedIn: () -> Unit) {
        val s = _state.value
        if (s.busy) return
        _state.value = s.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                when {
                    s.mode == LoginMode.STAFF_PIN -> app.signInWithPin(s.phone, s.code)
                    !s.codeSent -> { app.signInWithOtp.request(s.phone); _state.value = _state.value.copy(codeSent = true, code = "") }
                    else -> app.signInWithOtp.verify(s.phone, s.code, s.name.takeIf { askName })
                }
                if (s.mode == LoginMode.STAFF_PIN || s.codeSent) onSignedIn()
                _state.value = _state.value.copy(busy = false)
            } catch (e: Exception) { _state.value = _state.value.copy(busy = false, error = e.friendly()) }
        }
    }
}

@Composable
fun LoginScreen(title: String, subtitle: String, modes: Set<LoginMode> = setOf(LoginMode.OTP), askName: Boolean = false, onSignedIn: () -> Unit) {
    val app = LocalContainer.current
    val vm = screenModel("login") { LoginViewModel(app, askName) }
    val s = vm.state.collectAsStateValue()
    val ready = isValidPhone(s.phone) && when {
        s.mode == LoginMode.STAFF_PIN -> s.code.length == 4
        !s.codeSent -> true
        else -> s.code.length >= 4
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.Start) {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
        Spacer(Modifier.height(24.dp))
        if (modes.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Owner", s.mode == LoginMode.OTP) { vm.setMode(LoginMode.OTP) }
                Chip("Counter staff", s.mode == LoginMode.STAFF_PIN) { vm.setMode(LoginMode.STAFF_PIN) }
            }
            Spacer(Modifier.height(12.dp))
        }
        Field("Phone number", s.phone, vm::phone, keyboard = KeyboardType.Phone, enabled = !s.codeSent)
        Spacer(Modifier.height(12.dp))
        if (s.mode == LoginMode.STAFF_PIN) {
            Field("4-digit PIN", s.code, { vm.code(it.filter(Char::isDigit).take(4)) }, keyboard = KeyboardType.NumberPassword, secret = true)
        } else if (s.codeSent) {
            Field("One-time code", s.code, { vm.code(it.filter(Char::isDigit).take(6)) }, keyboard = KeyboardType.Number, supporting = "Local dev code: 123456")
            if (askName) { Spacer(Modifier.height(12.dp)); Field("Your name (first time only)", s.name, vm::name) }
        }
        s.error?.let { Spacer(Modifier.height(12.dp)); ErrorBox(it) }
        Spacer(Modifier.height(20.dp))
        PrimaryButton(if (s.mode == LoginMode.OTP && !s.codeSent) "Send code" else "Sign in", { vm.submit(onSignedIn) }, enabled = ready && !s.busy)
    }
}
