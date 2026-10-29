package app.voicetodo.ui

import android.content.res.Resources
import app.voicetodo.R
import app.voicetodo.parser.Warning
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val HM = DateTimeFormatter.ofPattern("HH:mm")

private fun Resources.locale() = configuration.locales[0]

/** "今天", "明天 周四", "11月6日 周五" / "Today", "Tomorrow, Thu", "Fri, Nov 6". */
fun describeDate(date: LocalDate, today: LocalDate, res: Resources): String {
    val locale = res.locale()
    val week = DateTimeFormatter.ofPattern("EEE", locale).format(date)
    return when (ChronoUnit.DAYS.between(today, date)) {
        0L -> res.getString(R.string.date_today)
        1L -> res.getString(R.string.date_tomorrow, week)
        -1L -> res.getString(R.string.date_yesterday)
        // English has no everyday word for 后天.
        2L -> if (locale.language == "zh") res.getString(R.string.date_day_after, week) else pattern(date, today, res)
        else -> pattern(date, today, res)
    }
}

private fun pattern(date: LocalDate, today: LocalDate, res: Resources): String =
    DateTimeFormatter.ofPattern(
        res.getString(if (date.year == today.year) R.string.date_pattern else R.string.date_pattern_year),
        res.locale(),
    ).format(date)

fun describeTime(time: LocalTime): String = time.format(HM)

/** "明天 周四 15:00 前" / "by Tomorrow, Thu 15:00" — the due line under a task. */
fun describeDue(date: LocalDate?, time: LocalTime?, kind: String?, today: LocalDate, res: Resources): String? {
    date ?: return null
    val base = describeDate(date, today, res) + (time?.let { " " + describeTime(it) } ?: "")
    // Mid-sentence in English: "by today", not "by Today".
    val inner = if (res.locale().language == "en") base.replaceFirstChar { it.lowercase() } else base
    return when (kind) {
        "DEADLINE" -> res.getString(R.string.due_deadline, inner)
        "START" -> res.getString(R.string.due_start, inner)
        else -> base
    }
}

fun describeLead(d: Duration?, res: Resources): String = when {
    d == null -> res.getString(R.string.lead_default)
    d.isZero -> res.getString(R.string.lead_on_time)
    d.toDays() >= 1 && d.toHours() % 24 == 0L -> res.getString(R.string.lead_days, d.toDays().toInt())
    d.toHours() >= 1 && d.toMinutes() % 60 == 0L -> res.getString(R.string.lead_hours, d.toHours().toInt())
    else -> res.getString(R.string.lead_minutes, d.toMinutes().toInt())
}

fun describeWarning(w: Warning, res: Resources): String = res.getString(
    when (w) {
        Warning.IN_PAST -> R.string.warn_in_past
        Warning.AMBIGUOUS_NEXT_WEEK -> R.string.warn_next_week
        Warning.AMBIGUOUS_AM_PM -> R.string.warn_am_pm
        Warning.ROLLED_TO_TOMORROW -> R.string.warn_rolled
        Warning.WEEKDAY_MISMATCH -> R.string.warn_weekday
        Warning.INVALID_DATE -> R.string.warn_invalid
        Warning.RECURRING -> R.string.warn_recurring
    },
)

fun mb(bytes: Long) = "%.0f MB".format(bytes / 1_048_576.0)
