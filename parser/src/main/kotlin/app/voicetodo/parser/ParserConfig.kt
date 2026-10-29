package app.voicetodo.parser

import java.time.DayOfWeek
import java.time.LocalTime

/** Parts of the day a speaker can name without a clock time. */
enum class Period {
    DAWN, // 凌晨
    EARLY_MORNING, // 早上 / 一早 / 明早
    MORNING, // 上午
    NOON, // 中午
    AFTERNOON, // 下午
    DUSK, // 傍晚
    EVENING, // 晚上 / 今晚
    NIGHT, // 半夜 / 深夜
}

/**
 * The guesses the parser makes when speech leaves something out. They are
 * habits, not facts, so they are the user's to change.
 */
data class ParserConfig(
    /** The day "下周" or "这周内" lands on when no day is named. */
    val weekDeadlineDay: DayOfWeek = DayOfWeek.FRIDAY,
    val workStart: LocalTime = LocalTime.of(9, 0),
    val workEnd: LocalTime = LocalTime.of(18, 0),
    val lunch: LocalTime = LocalTime.of(12, 0),
    val dinner: LocalTime = LocalTime.of(18, 0),
    val bedtime: LocalTime = LocalTime.of(22, 30),
    /** Representative time of a period: "明天下午开会" → 15:00. */
    val periodStart: Map<Period, LocalTime> = mapOf(
        Period.DAWN to LocalTime.of(6, 0),
        Period.EARLY_MORNING to LocalTime.of(8, 0),
        Period.MORNING to LocalTime.of(9, 0),
        Period.NOON to LocalTime.of(12, 0),
        Period.AFTERNOON to LocalTime.of(15, 0),
        Period.DUSK to LocalTime.of(18, 0),
        Period.EVENING to LocalTime.of(20, 0),
        Period.NIGHT to LocalTime.of(23, 0),
    ),
    /** Latest moment of a period, used for deadlines: "明天上午前" → 12:00. */
    val periodEnd: Map<Period, LocalTime> = mapOf(
        Period.DAWN to LocalTime.of(6, 0),
        Period.EARLY_MORNING to LocalTime.of(9, 0),
        Period.MORNING to LocalTime.of(12, 0),
        Period.NOON to LocalTime.of(12, 0),
        Period.AFTERNOON to LocalTime.of(18, 0),
        Period.DUSK to LocalTime.of(19, 0),
        Period.EVENING to LocalTime.of(22, 0),
        Period.NIGHT to LocalTime.of(23, 59),
    ),
    /** A bare hour below this is read as afternoon: "3点" → 15:00, "9点" → 09:00. */
    val bareHourPmBelow: Int = 6,
    /** Time a date-only reminder fires: "明天提醒我" → 09:00. */
    val defaultRemindTime: LocalTime = LocalTime.of(9, 0),
)
