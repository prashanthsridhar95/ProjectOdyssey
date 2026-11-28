package com.odyssey.customer

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.odyssey.customerapp.CustomerEntry
import com.odyssey.data.AndroidSessionStore
import com.odyssey.data.AppContainer
import com.odyssey.ui.AndroidLocationProvider

class MainActivity : ComponentActivity() {
    private val container by lazy { AppContainer(BuildConfig.API_URL, BuildConfig.TENANT, AndroidSessionStore(this, "customer")) }
    private lateinit var platform: com.odyssey.ui.AndroidPlatform
    private val photo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> platform.photoCallback?.invoke(platform.encode(uri)); platform.photoCallback = null }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        platform = com.odyssey.ui.AndroidPlatform(this, launchPicker = { photo.launch("image/*") })
        permissions.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS))
        setContent { CustomerEntry(container, platform, AndroidLocationProvider(this), allowSimulation = BuildConfig.DEBUG) }
    }
}
