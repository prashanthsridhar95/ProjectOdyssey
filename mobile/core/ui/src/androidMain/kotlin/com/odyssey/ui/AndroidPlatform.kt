package com.odyssey.ui

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.CalendarContract
import android.util.Base64
import java.io.ByteArrayOutputStream

/** The phone-only things the customer app needs: maps, dialer, calendar, notifications and the photo picker. */
class AndroidPlatform(
    private val activity: Activity, private val launchPicker: () -> Unit = {}, private val launchScanner: () -> Unit = {},
) : PlatformActions {
    var photoCallback: ((String?) -> Unit)? = null
    var scanCallback: ((String?) -> Unit)? = null
    private var ringtone: android.media.Ringtone? = null

    override fun directions(lat: Double, lng: Double, label: String) =
        start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(label)})")))

    override fun call(phone: String) = start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))

    override fun addToCalendar(title: String, startEpochMs: Long, endEpochMs: Long, location: String) =
        start(Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.Events.EVENT_LOCATION, location).putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startEpochMs).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endEpochMs))

    override fun notify(title: String, body: String) {
        val nm = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("bookings", "Booking updates", NotificationManager.IMPORTANCE_DEFAULT))
        val tap = android.app.PendingIntent.getActivity(activity, 0, (activity.packageManager.getLaunchIntentForPackage(activity.packageName) ?: Intent()), android.app.PendingIntent.FLAG_IMMUTABLE)
        val n = android.app.Notification.Builder(activity, "bookings").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(body)
            .setStyle(android.app.Notification.BigTextStyle().bigText(body)).setContentIntent(tap).setAutoCancel(true).build()
        runCatching { nm.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), n) }
    }

    override fun pickPhoto(onResult: (String?) -> Unit) { photoCallback = onResult; launchPicker() }
    override fun scanCode(onResult: (String?) -> Unit) { scanCallback = onResult; launchScanner() }

    /** A looping alarm tone for a booking that must be accepted; stops when silenced or answered. */
    override fun alertSound(on: Boolean) {
        runCatching {
            if (on && ringtone?.isPlaying != true) {
                ringtone = android.media.RingtoneManager.getRingtone(activity, android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)).apply { isLooping = true; play() }
            } else if (!on) { ringtone?.stop(); ringtone = null }
        }
    }

    override fun shareText(title: String, text: String) =
        start(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text), title))

    /** Shrinks the picked image to a small JPEG so a problem report stays light on a poor network. */
    fun encode(uri: Uri?): String? {
        if (uri == null) return null
        return runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            activity.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            var sample = 1
            while (opts.outWidth / sample > 1280) sample *= 2
            val bmp = activity.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
            val out = ByteArrayOutputStream()
            var quality = 70
            do { out.reset(); bmp.compress(Bitmap.CompressFormat.JPEG, quality, out); quality -= 10 } while (out.size() > 380_000 && quality > 20)
            "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }.getOrNull()
    }

    private fun start(i: Intent) { runCatching { activity.startActivity(i) } }
}
