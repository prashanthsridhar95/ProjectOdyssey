package com.odyssey.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/** Pure formatting helpers shared by every app. No platform APIs. */

val IST: TimeZone = TimeZone.of("Asia/Kolkata")

/** 29900 -> "₹299", 29950 -> "₹299.50". Amounts are minor units. */
fun formatMoney(minor: Long?, currency: String = "INR"): String {
    if (minor == null) return "-"
    val symbol = if (currency == "INR") "₹" else "$currency "
    val major = minor / 100
    val cents = minor % 100
    val grouped = groupIndian(major)
    return if (cents == 0L) "$symbol$grouped" else "$symbol$grouped.${cents.toString().padStart(2, '0')}"
}

private fun groupIndian(n: Long): String {
    val s = n.toString()
    if (s.length <= 3) return s
    val head = s.dropLast(3)
    val grouped = head.reversed().chunked(2).joinToString(",").reversed()
    return "$grouped,${s.takeLast(3)}"
}

fun formatPercent(bps: Int?): String = if (bps == null) "-" else if (bps % 100 == 0) "${bps / 100}%" else "${bps / 100.0}%"

fun String.toInstantOrNull(): Instant? = runCatching { Instant.parse(this) }.getOrNull()

fun formatTime(iso: String): String {
    val t = iso.toInstantOrNull()?.toLocalDateTime(IST) ?: return iso
    val h = if (t.hour % 12 == 0) 12 else t.hour % 12
    return "$h:${t.minute.toString().padStart(2, '0')} ${if (t.hour < 12) "am" else "pm"}"
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

fun formatDay(iso: String): String {
    val t = iso.toInstantOrNull()?.toLocalDateTime(IST) ?: return iso
    return "${DAYS[t.date.dayOfWeek.ordinal]}, ${t.date.day} ${MONTHS[t.date.month.ordinal]}"
}

fun formatDateTime(iso: String) = "${formatDay(iso)} · ${formatTime(iso)}"

/** Today in India, plus [offset] days, as YYYY-MM-DD. */
fun istDate(offset: Int = 0, now: Instant = Clock.System.now()): String =
    now.toLocalDateTime(IST).date.plus(offset, DateTimeUnit.DAY).toString()

fun dayChipLabel(date: String, today: String = istDate()): String {
    if (date == today) return "Today"
    val d = LocalDate.parse(date)
    return if (date == LocalDate.parse(today).plus(1, DateTimeUnit.DAY).toString()) "Tomorrow" else "${DAYS[d.dayOfWeek.ordinal]} ${d.day}"
}

fun String.titleCase(): String = lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
