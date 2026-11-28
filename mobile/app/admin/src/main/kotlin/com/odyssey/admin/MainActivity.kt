package com.odyssey.admin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.odyssey.adminops.AdminHome
import com.odyssey.auth.LoginScreen
import com.odyssey.data.AndroidSessionStore
import com.odyssey.data.AppContainer
import com.odyssey.domain.PublicConfig
import com.odyssey.domain.Role
import com.odyssey.ui.*

class MainActivity : ComponentActivity() {
    private val container by lazy { AppContainer(BuildConfig.API_URL, BuildConfig.TENANT, AndroidSessionStore(this, "admin")) }

    private val platform by lazy { AndroidPlatform(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OdysseyTheme {
                var config by remember { mutableStateOf<PublicConfig?>(null) }
                var failure by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) { try { config = container.auth.config() } catch (e: Exception) { failure = e.friendly() } }
                val loaded = config
                var attempt by remember { mutableStateOf(0) }
                LaunchedEffect(attempt) { if (attempt > 0) { failure = null; try { config = container.auth.config() } catch (e: Exception) { failure = e.friendly() } } }
                if (loaded == null) AppSplash("Odyssey", "Admin", failure) { attempt++ }
                else CompositionLocalProvider(LocalContainer provides container, LocalTenant provides loaded, LocalPlatform provides platform) {
                    val user = container.session.collectAsStateValue()?.user
                    if (user == null || user.role !in setOf(Role.ADMIN, Role.SUPPORT, Role.FINANCE)) {
                        LaunchedEffect(user) { if (user != null) container.logout() }
                        LoginScreen("${loaded.tenant.name} Admin", "Verify vendors, handle reports and watch the numbers.", showHero = false, onSignedIn = {})
                    } else AdminHome(user, onSignOut = container::logout)
                }
            }
        }
    }
}
