package com.odyssey.vendor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.odyssey.carwash.CarWashUi
import com.odyssey.data.AndroidKeyValueStore
import com.odyssey.data.AndroidSessionStore
import com.odyssey.data.AppContainer
import com.odyssey.domain.PublicConfig
import com.odyssey.ui.*
import com.odyssey.vendorops.VendorApp

class MainActivity : ComponentActivity() {
    private val container by lazy { AppContainer(BuildConfig.API_URL, BuildConfig.TENANT, AndroidSessionStore(this, "vendor"), kv = AndroidKeyValueStore(this, "vendor")) }
    private lateinit var platform: AndroidPlatform
    private var stoppedAt = 0L
    private val scanner = registerForActivityResult(ScanContract()) { r -> platform.scanCallback?.invoke(r.contents); platform.scanCallback = null }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        platform = AndroidPlatform(this, launchScanner = { scanner.launch(ScanOptions().setPrompt("Scan the customer's QR code").setBeepEnabled(false).setOrientationLocked(false)) })
        permissions.launch(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS))
        setContent {
            IndustryTheme {
                var config by remember { mutableStateOf<PublicConfig?>(null) }
                var failure by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) { try { config = container.auth.config() } catch (e: Exception) { failure = e.friendly() } }
                val loaded = config
                if (loaded == null) Box(Modifier.fillMaxSize(), Alignment.Center) { failure?.let { ErrorBox(it) } ?: CircularProgressIndicator(color = Ind.accent) }
                else CompositionLocalProvider(LocalContainer provides container, LocalTenant provides loaded, LocalVertical provides CarWashUi, LocalPlatform provides platform) { VendorApp(loaded.tenant.name) }
            }
        }
    }

    // The counter device locks itself after a minute in the background.
    override fun onStop() { super.onStop(); stoppedAt = System.currentTimeMillis() }
    override fun onStart() { super.onStart(); if (stoppedAt > 0 && System.currentTimeMillis() - stoppedAt > 60_000) container.locked.value = true }
}
