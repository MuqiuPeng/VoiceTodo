package app.voicetodo.parser

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * A point in time as precise as the speaker made it.
 *
 * `time` is null when only a day was said ("明天交报告"); what hour that means
 * is the app's decision (a configurable end of day), not the parser's.
 */
data class Due(
    val date: LocalDate,
    val time: LocalTime? = null,
    /** True for "月初", "下周", "几天后": a reasonable guess rather than a date that was said. */
    val approximate: Boolean = false,
) {
    fun at(defaultTime: LocalTime): LocalDateTime = date.atTime(time ?: defaultTime)

    override fun toString(): String = if (time == null) "$date" else "$date $time"
}

/** What a time expression is for, read from the words around it. */
enum class MentionKind {
    /** "明天下午开会" — when it happens. */
    AT,

    /** "周五前", "三天内", "截止到月底" — when it must be done by. */
    DEADLINE,

    /** "周五之后", "下班后" — not before then. */
    START,

    /** "明早八点提醒我" — when to be told, not when the task is due. */
    REMIND,
}

/** Things the confirmation card should make the user look at twice. */
enum class Warning {
    /** The resolved time is already behind `now`. */
    IN_PAST,

    /** "下周三" said early in the week: some people mean the one two days away. */
    AMBIGUOUS_NEXT_WEEK,

    /** A bare "7点" with nothing saying morning or evening. */
    AMBIGUOUS_AM_PM,

    /** A time with no day had already passed today, so it was moved to tomorrow. */
    ROLLED_TO_TOMORROW,

    /** "11月4号周三" where 11月4号 is not a Wednesday. */
    WEEKDAY_MISMATCH,

    /** "2月30号". */
    INVALID_DATE,

    /** "每天", "每周一": repeating tasks are not modelled; only the first occurrence is kept. */
    RECURRING,
}

/** One time expression found in the text, for highlighting on the confirmation card. */
data class TimeMention(
    val kind: MentionKind,
    val due: Due,
    /** Range in [ParseResult.normalized], including relation words such as "之前". */
    val span: IntRange,
    val text: String,
    val alternatives: List<Due> = emptyList(),
    val warnings: Set<Warning> = emptySet(),
    /** Set for ranges ("周三到周五"): `due` is the end, this is the start. */
    val rangeStart: Due? = null,
)

data class ParsedTask(
    val title: String,
    /** The deadline if one was given, otherwise when the task happens. */
    val due: Due?,
    val dueKind: MentionKind?,
    /** An explicit "提醒我" time, separate from the due time. */
    val remindAt: Due? = null,
    /** "提前一小时提醒我". */
    val remindBefore: Duration? = null,
    val startAfter: Due? = null,
    /** Follow-on clauses with no time of their own ("记得带身份证"). */
    val detail: String? = null,
    /** The part of the utterance this task came from. */
    val source: String,
    /** Other readings of `due` worth offering, most likely first. */
    val alternatives: List<Due> = emptyList(),
    val warnings: Set<Warning> = emptySet(),
    val mentions: List<TimeMention> = emptyList(),
)

data class ParseResult(
    val input: String,
    val normalized: String,
    val tasks: List<ParsedTask>,
)
