package me.river.remoter.feature.session

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

private val hhmm = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)

/** "today 18:40", "yesterday 18:40", "Monday 18:40" within the week, then "3 Oct", with the year once it isn't this one. */
fun pastWhen(ms: Long, nowMs: Long, zone: ZoneId): String {
    val at = Instant.ofEpochMilli(ms).atZone(zone)
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(at.toLocalDate(), today)
    val time = at.format(hhmm)
    return when {
        days <= 0L -> "today $time"
        days == 1L -> "yesterday $time"
        days <= 6L -> "${at.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.UK)} $time"
        at.year == today.year -> "${at.dayOfMonth} ${shortMonth(at.toLocalDate())}"
        else -> "${at.dayOfMonth} ${shortMonth(at.toLocalDate())} ${at.year}"
    }
}

private fun shortMonth(d: LocalDate) = d.month.getDisplayName(TextStyle.SHORT, Locale.UK).take(3)
