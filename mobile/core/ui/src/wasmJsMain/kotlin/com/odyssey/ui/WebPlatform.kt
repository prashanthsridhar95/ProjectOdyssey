package com.odyssey.ui

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.datetime.Instant
import kotlin.coroutines.resume

@JsFun("(s) => encodeURIComponent(s)")
private external fun enc(s: String): String

@JsFun("(url) => { window.open(url, '_blank'); }")
private external fun openTab(url: String)

@JsFun("(url) => { window.location.href = url; }")
private external fun goTo(url: String)

@JsFun("(t, x) => { if (navigator.share) { navigator.share({ title: t, text: x }); } else if (navigator.clipboard) { navigator.clipboard.writeText(x); } }")
private external fun shareOrCopy(title: String, text: String)

/** Opens a file picker and gives back a downscaled JPEG data URI, or null when cancelled. */
@JsFun(
    "(cb) => { const i = document.createElement('input'); i.type = 'file'; i.accept = 'image/*'; " +
        "i.oncancel = () => cb(null); " +
        "i.onchange = () => { const f = i.files[0]; if (!f) { cb(null); return; } const r = new FileReader(); " +
        "r.onload = () => { const img = new Image(); img.onload = () => { const s = Math.min(1, 1024 / Math.max(img.width, img.height)); " +
        "const c = document.createElement('canvas'); c.width = Math.round(img.width * s); c.height = Math.round(img.height * s); " +
        "c.getContext('2d').drawImage(img, 0, 0, c.width, c.height); cb(c.toDataURL('image/jpeg', 0.8)); }; img.src = r.result; }; r.readAsDataURL(f); }; i.click(); }",
)
private external fun pickImage(cb: (JsString?) -> Unit)

@JsFun(
    "(ok, fail) => { if (!navigator.geolocation) { fail(); return; } " +
        "navigator.geolocation.getCurrentPosition(p => ok(p.coords.latitude, p.coords.longitude), () => fail(), { timeout: 6000, maximumAge: 600000 }); }",
)
private external fun locate(ok: (Double, Double) -> Unit, fail: () -> Unit)

/** What the browser can do in place of the phone's own apps: maps, dialler, calendar, photo picker, share. */
class WebPlatform : PlatformActions {
    override fun directions(lat: Double, lng: Double, label: String) = openTab("https://www.google.com/maps/dir/?api=1&destination=$lat,$lng")
    override fun call(phone: String) = goTo("tel:$phone")
    override fun addToCalendar(title: String, startEpochMs: Long, endEpochMs: Long, location: String) {
        fun stamp(ms: Long) = Instant.fromEpochMilliseconds(ms).toString().substringBefore('.').removeSuffix("Z").replace("-", "").replace(":", "") + "Z"
        val q = "action=TEMPLATE&text=${enc(title)}&dates=${stamp(startEpochMs)}/${stamp(endEpochMs)}&location=${enc(location)}"
        openTab("https://calendar.google.com/calendar/render?$q")
    }
    override fun notify(title: String, body: String) = Unit   // browser notifications need a service worker; the in-app bell covers it
    override fun pickPhoto(onResult: (String?) -> Unit) = pickImage { onResult(it?.toString()) }
    override fun scanCode(onResult: (String?) -> Unit) = onResult(null)
    override fun alertSound(on: Boolean) = Unit
    override fun shareText(title: String, text: String) = shareOrCopy(title, text)
}

/** The browser's position when the customer allows it, otherwise the default centre. */
class WebLocationProvider : LocationProvider {
    override suspend fun current(): Pair<Double, Double> = suspendCancellableCoroutine { k ->
        locate({ la, ln -> if (k.isActive) k.resume(la to ln) }, { if (k.isActive) k.resume((12.9716 to 77.5946)) })
    }
}
