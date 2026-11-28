import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.odyssey.customerapp.CustomerEntry
import com.odyssey.data.AppContainer
import com.odyssey.data.WebKeyValueStore
import com.odyssey.data.WebSessionStore
import com.odyssey.ui.WebLocationProvider
import com.odyssey.ui.WebPlatform

/** Where the API lives is decided at deploy time by config.js (window.ODYSSEY), not at build time. */
@JsFun("() => (window.ODYSSEY && window.ODYSSEY.apiUrl) || 'https://carwashapis.prashanthsridhar.com'")
private external fun apiUrl(): String

@JsFun("() => (window.ODYSSEY && window.ODYSSEY.tenant) || 'carwash'")
private external fun tenant(): String

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val container = AppContainer(apiUrl(), tenant(), WebSessionStore("customer"), kv = WebKeyValueStore("customer"))
    ComposeViewport(viewportContainerId = "app") {
        CustomerEntry(container, WebPlatform(), WebLocationProvider(), allowSimulation = false)
    }
}
