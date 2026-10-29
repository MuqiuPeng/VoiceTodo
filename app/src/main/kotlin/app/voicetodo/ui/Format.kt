package app.voicetodo.ui

import app.voicetodo.parser.Warning
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val WEEKDAYS = mapOf(
    DayOfWeek.MONDAY to "周一", DayOfWeek.TUESDAY to "周二", DayOfWeek.WEDNESDAY to "周三",
    DayOfWeek.THURSDAY to "周四", DayOfWeek.FRIDAY to "周五", DayOfWeek.SATURDAY to "周六",
    DayOfWeek.SUNDAY to "周日",
)

private val HM = DateTimeFormatter.ofPattern("HH:mm")

/** "今天", "明天 周四", "11月6日 周五", "2027年2月5日 周五". */
fun describeDate(date: LocalDate, today: LocalDate): String {
    val week = WEEKDAYS.getValue(date.dayOfWeek)
    return when (ChronoUnit.DAYS.between(today, date)) {
        0L -> "今天"
        1L -> "明天 $week"
        2L -> "后天 $week"
        -1L -> "昨天"
        else -> if (date.year == today.year) "${date.monthValue}月${date.dayOfMonth}日 $week"
        else "${date.year}年${date.monthValue}月${date.dayOfMonth}日 $week"
    }
}

fun describeTime(time: LocalTime): String = time.format(HM)

/** "明天 周四 15:00 前" — the due line under a task. */
fun describeDue(date: LocalDate?, time: LocalTime?, kind: String?, today: LocalDate): String? {
    date ?: return null
    val base = describeDate(date, today) + (time?.let { " " + describeTime(it) } ?: "")
    return when (kind) {
        "DEADLINE" -> "$base 前"
        "START" -> "$base 起"
        else -> base
    }
}

fun describeLead(d: Duration?): String = when {
    d == null -> "默认"
    d.isZero -> "准时"
    d.toDays() >= 1 && d.toHours() % 24 == 0L -> "提前${d.toDays()}天"
    d.toHours() >= 1 && d.toMinutes() % 60 == 0L -> "提前${d.toHours()}小时"
    else -> "提前${d.toMinutes()}分钟"
}

fun describeWarning(w: Warning): String = when (w) {
    Warning.IN_PAST -> "这个时间已经过去了"
    Warning.AMBIGUOUS_NEXT_WEEK -> "“下周”可能指本周，请确认是哪一天"
    Warning.AMBIGUOUS_AM_PM -> "没说上午还是晚上，请确认"
    Warning.ROLLED_TO_TOMORROW -> "今天这个时间已过，已改为明天"
    Warning.WEEKDAY_MISMATCH -> "日期和星期对不上，请确认"
    Warning.INVALID_DATE -> "没有这一天，请重新选择日期"
    Warning.RECURRING -> "暂不支持重复任务，只记录了第一次"
}
