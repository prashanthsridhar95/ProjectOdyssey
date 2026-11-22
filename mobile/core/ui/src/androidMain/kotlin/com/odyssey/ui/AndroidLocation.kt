package com.odyssey.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager

/** Last known GPS or network fix, or the default centre when permission is missing or there is no fix. */
class AndroidLocationProvider(private val context: Context) : LocationProvider {
    @SuppressLint("MissingPermission")
    override suspend fun current(): Pair<Double, Double> {
        val granted = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) return FixedLocation.current()
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val fix = lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        return if (fix != null) fix.latitude to fix.longitude else FixedLocation.current()
    }
}
