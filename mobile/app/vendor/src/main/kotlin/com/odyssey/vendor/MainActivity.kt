package com.odyssey.vendor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.odyssey.auth.LoginMode
import com.odyssey.auth.LoginScreen
import com.odyssey.data.AndroidSessionStore
import com.odyssey.data.AppContainer
import com.odyssey.domain.PublicConfig
import com.odyssey.ui.*
import com.odyssey.vendorops.VendorHome

class MainActivity : ComponentActivity() {
    private val container by lazy { AppContainer(BuildConfig.API_URL, BuildConfig.TENANT, AndroidSessionStore(this, "vendor")) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OdysseyTheme {
                var config by remember { mutableStateOf<PublicConfig?>(null) }
                var failure by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) { try { config = container.auth.config() } catch (e: Exception) { failure = e.friendly() } }
                val loaded = config
                if (loaded == null) Box(Modifier.fillMaxSize(), Alignment.Center) { failure?.let { ErrorBox(it) } ?: CircularProgressIndicator() }
                else CompositionLocalProvider(LocalContainer provides container, LocalTenant provides loaded) {
                    val session = container.session.collectAsStateValue()
                    val user = session?.user
                    // Vendor apps accept provider roles only; anyone else is signed out.
                    if (user == null || user.role.name.startsWith("PROVIDER_").not()) {
                        LaunchedEffect(user) { if (user != null) container.logout() }
                        LoginScreen("${loaded.tenant.name} Vendor", "Manage your slots, menu and bookings", modes = setOf(LoginMode.OTP, LoginMode.STAFF_PIN), onSignedIn = {})
                    } else VendorHome(user, onSignOut = container::logout)
                }
            }
        }
    }
}
