package app.voicetodo.parser

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/**
 * The day a date expression lands on, read four ways because the words around
 * it change the answer: "国庆" is Oct 1st, "国庆前" is Sep 30th, "国庆内" is
 * the end of the break and "国庆后" the day after it.
 */
internal class DatePart(
    val date: LocalDate,
    val end: LocalDate = date,
    val before: LocalDate = end,
    val after: LocalDate = date,
    val approximate: Boolean = false,
    val alternatives: List<LocalDate> = emptyList(),
    val warnings: Set<Warning> = emptySet(),
)

internal data class Clock(val hour: Int, val minute: Int, val ambiguous: Boolean)

/** The smallest piece of a time expression: a date, a part of day, a clock time, or one of each. */
internal class Atom(
    val range: IntRange,
    val date: DatePart? = null,
    val period: Period? = null,
    val clock: Clock? = null,
    /** Times that are not on the 12-hour clock: 下班 → 18:00. */
    val fixedTime: LocalTime? = null,
    /** Fully resolved instants: "两小时后". */
    val exact: LocalDateTime? = null,
    /** A relation the atom already contains: "三天内", "下班前". */
    val kind: MentionKind? = null,
    val relationIsBefore: Boolean = false,
    /** Set for a bare "周三", so "11月4号周三" can be checked rather than read as two dates. */
    val weekday: DayOfWeek? = null,
)

internal class Scanner(private val now: LocalDateTime, private val config: ParserConfig) {
    private val today: LocalDate = now.toLocalDate()

    private class Rule(val regex: Regex, val build: (MatchResult) -> Atom?)

    private val rules: List<Rule> = listOf(
        Rule(Regex("(?<![\\d.])(?:(\\d{4})年|(今年|明年|后年))?(\\d{1,2})月份?(?:(\\d{1,2})(?:[日号]|(?![\\d个点:分]))|(初|上旬|中旬|中|下旬|底|末))?")) { m ->
            monthAtom(m, explicitYear = m.groupValues[1].toIntOrNull(), relYear = m.groupValues[2], month = m.groupValues[3].toInt())
        },
        Rule(Regex("(这个?月|本月|当月|下下个?月|下个?月)(?:(\\d{1,2})[日号]?|(初|上旬|中旬|中|下旬|底|末))?")) { m ->
            val shift = when {
                m.groupValues[1].startsWith("下下") -> 2L
                m.groupValues[1].startsWith("下") -> 1L
                else -> 0L
            }
            val ym = YearMonth.from(today).plusMonths(shift)
            val day = m.groupValues[2].toIntOrNull()
            val part = m.groupValues[3]
            Atom(m.range, date = when {
                day != null -> dayInMonth(ym, day) ?: return@Rule null
                part.isNotEmpty() -> monthPart(ym, part)
                else -> wholeMonth(ym)
            })
        },
        Rule(Regex("(?<![\\d月个])月(初|上旬|中旬|中|下旬|底|末)")) { m ->
            var ym = YearMonth.from(today)
            if (monthPart(ym, m.groupValues[1]).end < today) ym = ym.plusMonths(1)
            Atom(m.range, date = monthPart(ym, m.groupValues[1]))
        },
        Rule(Regex("(今年|明年)?(?<![\\d去前])年(初|中|底|末)")) { m ->
            var year = today.year + if (m.groupValues[1] == "明年") 1 else 0
            fun part(y: Int) = when (m.groupValues[2]) {
                "初" -> DatePart(LocalDate.of(y, 1, 1), end = LocalDate.of(y, 1, 31), approximate = true)
                "中" -> DatePart(LocalDate.of(y, 6, 30), approximate = true)
                else -> DatePart(LocalDate.of(y, 12, 31))
            }
            if (m.groupValues[1].isEmpty() && part(year).end < today) year++
            Atom(m.range, date = part(year))
        },
        Rule(Regex("(?<![\\d./:])(\\d{1,2})[./](\\d{1,2})[日号]?(?=[日号]|之?前|以前|截止|$|[\\s，,。])")) { m ->
            val month = m.groupValues[1].toInt()
            val day = m.groupValues[2].toInt()
            if (month !in 1..12) return@Rule null
            Atom(m.range, date = yearlessDate(month, day) ?: return@Rule null)
        },
        Rule(Regex("(?<![\\d月./年:])(\\d{1,2})[日号](?![线楼床门房位窗口机台车厅馆院码子人])")) { m ->
            val day = m.groupValues[1].toInt()
            var ym = YearMonth.from(today)
            if (day < today.dayOfMonth) ym = ym.plusMonths(1)
            Atom(m.range, date = dayInMonth(ym, day) ?: return@Rule null)
        },
        Rule(Regex("大大后天|大后天|后天|明天|明日|明儿个?|今天|今日|今儿个?")) { m ->
            val offset = when (m.value) {
                "大大后天" -> 4L
                "大后天" -> 3L
                "后天" -> 2L
                "今天", "今日", "今儿", "今儿个" -> 0L
                else -> 1L
            }
            Atom(m.range, date = DatePart(today.plusDays(offset)))
        },
        Rule(Regex("(今|明)(早上?|晨|晚上?|夜)")) { m ->
            val offset = if (m.groupValues[1] == "明") 1L else 0L
            val period = when (m.groupValues[2].first()) {
                '早', '晨' -> Period.EARLY_MORNING
                else -> Period.EVENING
            }
            Atom(m.range, date = DatePart(today.plusDays(offset)), period = period)
        },
        Rule(Regex("(?<!\\d)(这个?|本|下下个?|下个?|上个?)?(?:周|星期|礼拜)([1-7一二三四五六日天])(?![年岁])")) { m ->
            weekdayAtom(m)
        },
        Rule(Regex("(这个?|本|下下个?|下个?)?周末")) { m ->
            val monday = weekStart(today).plusWeeks(weekShift(m.groupValues[1]))
            val saturday = monday.plusDays(5)
            val sunday = monday.plusDays(6)
            Atom(m.range, date = DatePart(saturday, end = sunday, before = monday.plusDays(4), after = sunday.plusDays(1)))
        },
        Rule(Regex("(这个?|本|下下个?|下个?)(?:周|星期|礼拜)(?![1-7一二三四五六日天末年岁])")) { m ->
            val monday = weekStart(today).plusWeeks(weekShift(m.groupValues[1]))
            var day = monday.with(TemporalAdjusters.nextOrSame(config.weekDeadlineDay))
            if (day < today) day = monday.plusDays(6)
            Atom(m.range, date = DatePart(day, before = monday.minusDays(1), after = monday.plusWeeks(1), approximate = true))
        },
        Rule(Regex(Holidays.pattern)) { m ->
            val occ = Holidays.resolve(m.value, today) ?: return@Rule null
            Atom(m.range, date = DatePart(occ.date, end = occ.end, before = occ.date.minusDays(1), after = occ.end.plusDays(1)))
        },
        Rule(Regex("(\\d+个半|\\d+|半|几)个?(分钟|分|小时|钟头|天|日|周|星期|礼拜|月|年|工作日)(之后|以后|后|以内|之内|内)(?![天台面续悔])")) { m ->
            offsetAtom(m)
        },
        Rule(Regex("过(\\d+|几)个?(天|周|星期|礼拜|月)")) { m ->
            val n = m.groupValues[1].toLongOrNull() ?: 3L
            val date = when (m.groupValues[2]) {
                "天" -> today.plusDays(n)
                "月" -> today.plusMonths(n)
                else -> today.plusWeeks(n)
            }
            Atom(m.range, date = DatePart(date, approximate = true))
        },
        Rule(Regex("等一会儿?|过一会儿?|待会儿?|等会儿?|一会儿")) { m ->
            Atom(m.range, exact = now.plusMinutes(30))
        },
        Rule(Regex("这(\\d+|几)天")) { m ->
            val n = m.groupValues[1].toLongOrNull() ?: 3L
            Atom(m.range, date = DatePart(today.plusDays(maxOf(n - 1, 0)), approximate = true), kind = MentionKind.DEADLINE)
        },
        Rule(Regex("凌晨|清早|一早|早上|早晨|上午|中午|下午|傍晚|晚上|晚间|夜里|半夜|深夜")) { m ->
            Atom(m.range, period = when (m.value) {
                "凌晨" -> Period.DAWN
                "清早", "一早", "早上", "早晨" -> Period.EARLY_MORNING
                "上午" -> Period.MORNING
                "中午" -> Period.NOON
                "下午" -> Period.AFTERNOON
                "傍晚" -> Period.DUSK
                "晚上", "晚间" -> Period.EVENING
                else -> Period.NIGHT
            })
        },
        Rule(Regex("睡前|(下班|上班|午饭|午休|晚饭|睡觉|天黑)(之前|以前|前|之后|以后|后)")) { m ->
            val time = when (m.groupValues[1]) {
                "下班" -> config.workEnd
                "上班" -> config.workStart
                "午饭", "午休" -> config.lunch
                "晚饭", "天黑" -> config.dinner
                else -> config.bedtime
            }
            val isAfter = m.groupValues[2].endsWith("后")
            Atom(
                m.range,
                fixedTime = time,
                kind = if (isAfter) MentionKind.START else MentionKind.DEADLINE,
                relationIsBefore = !isAfter,
            )
        },
        Rule(Regex("差(\\d{1,2})分?(\\d{1,2})点")) { m ->
            val minutes = m.groupValues[1].toInt()
            val hour = m.groupValues[2].toInt()
            if (minutes !in 1..59 || hour !in 1..24) return@Rule null
            val t = LocalTime.of(hour % 24, 0).minusMinutes(minutes.toLong())
            Atom(m.range, clock = Clock(t.hour, t.minute, ambiguous = t.hour in 1..11))
        },
        Rule(Regex("(?<![\\d:.])(\\d{1,2})(?::(\\d{2}))?\\s*([aApP])\\.?[mM]\\.?")) { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toIntOrNull() ?: 0
            if (h !in 1..12 || min > 59) return@Rule null
            val pm = m.groupValues[3].lowercase() == "p"
            Atom(m.range, clock = Clock((h % 12) + if (pm) 12 else 0, min, ambiguous = false))
        },
        Rule(Regex("(?<![\\d:.])(?:(\\d{1,2}):(\\d{2})|(\\d{1,2})点钟?(?:(\\d)刻|半|整|(\\d{1,2})分?)?)")) { m ->
            clockAtom(m)
        },
    )

    fun scan(text: String, masked: List<IntRange>): List<Atom> {
        this.text = text
        val candidates = rules.flatMap { rule ->
            rule.regex.findAll(text).mapNotNull { rule.build(it) }.toList()
        }.filter { a -> masked.none { it.overlaps(a.range) } }
        // Leftmost wins, then longest: "下周三" beats the "周三" inside it.
        val chosen = mutableListOf<Atom>()
        for (a in candidates.sortedWith(compareBy<Atom>({ it.range.first }, { -it.range.count() }))) {
            if (chosen.none { it.range.overlaps(a.range) }) chosen += a
        }
        return chosen
    }

    // --- dates -------------------------------------------------------------

    private fun monthAtom(m: MatchResult, explicitYear: Int?, relYear: String, month: Int): Atom? {
        if (month !in 1..12) return null
        val day = m.groupValues[4].toIntOrNull()
        val part = m.groupValues[5]
        val baseYear = explicitYear ?: (today.year + when (relYear) { "明年" -> 1; "后年" -> 2; else -> 0 })
        val yearGiven = explicitYear != null || relYear.isNotEmpty()

        fun build(year: Int): DatePart? {
            val ym = YearMonth.of(year, month)
            return when {
                day != null -> dayInMonth(ym, day, rollOver = false)
                part.isNotEmpty() -> monthPart(ym, part)
                else -> wholeMonth(ym)
            }
        }

        var date = build(baseYear) ?: return Atom(m.range, date = invalid())
        if (!yearGiven && date.end < today) date = build(baseYear + 1) ?: return null
        return Atom(m.range, date = date)
    }

    /** A date said without a year: this year unless that has passed. */
    private fun yearlessDate(month: Int, day: Int): DatePart? {
        for (y in today.year..today.year + 1) {
            val d = runCatching { LocalDate.of(y, month, day) }.getOrNull() ?: return invalid()
            if (!d.isBefore(today)) return DatePart(d)
        }
        return null
    }

    private fun dayInMonth(ym: YearMonth, day: Int, rollOver: Boolean = true): DatePart? {
        if (day !in 1..31) return null
        var m = ym
        repeat(if (rollOver) 3 else 1) {
            if (m.isValidDay(day)) return DatePart(m.atDay(day))
            m = m.plusMonths(1)
        }
        return invalid()
    }

    /** An unreal date still marks the spot, so the card can say what was wrong with it. */
    private fun invalid() = DatePart(today, approximate = true, warnings = setOf(Warning.INVALID_DATE))

    private fun wholeMonth(ym: YearMonth) = DatePart(
        ym.atDay(1),
        end = ym.atEndOfMonth(),
        before = ym.atDay(1).minusDays(1),
        approximate = true,
    )

    private fun monthPart(ym: YearMonth, part: String): DatePart = when (part) {
        "初" -> DatePart(ym.atDay(1), end = ym.atDay(5), approximate = true)
        "上旬" -> DatePart(ym.atDay(1), end = ym.atDay(10), approximate = true)
        "中", "中旬" -> DatePart(ym.atDay(15), end = ym.atDay(20), approximate = true)
        "下旬" -> DatePart(ym.atDay(21), end = ym.atEndOfMonth(), approximate = true)
        else -> DatePart(ym.atEndOfMonth())
    }

    private fun weekStart(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    private fun weekShift(prefix: String): Long = when {
        prefix.startsWith("下下") -> 2
        prefix.startsWith("下") -> 1
        prefix.startsWith("上") -> -1
        else -> 0
    }

    private fun weekdayAtom(m: MatchResult): Atom {
        val prefix = m.groupValues[1]
        val dow = when (m.groupValues[2]) {
            "一", "1" -> DayOfWeek.MONDAY
            "二", "2" -> DayOfWeek.TUESDAY
            "三", "3" -> DayOfWeek.WEDNESDAY
            "四", "4" -> DayOfWeek.THURSDAY
            "五", "5" -> DayOfWeek.FRIDAY
            "六", "6" -> DayOfWeek.SATURDAY
            else -> DayOfWeek.SUNDAY
        }
        if (prefix.isEmpty()) {
            return Atom(m.range, date = DatePart(today.with(TemporalAdjusters.nextOrSame(dow))), weekday = dow)
        }
        val thisWeek = weekStart(today).plusDays(dow.value - 1L)
        val date = thisWeek.plusWeeks(weekShift(prefix))
        // "下周三" said on a Monday: next calendar week, but the Wednesday two
        // days away is what some people mean.
        val ambiguous = prefix.startsWith("下") && !prefix.startsWith("下下") && thisWeek > today
        return Atom(
            m.range,
            date = DatePart(
                date,
                alternatives = if (ambiguous) listOf(thisWeek) else emptyList(),
                warnings = if (ambiguous) setOf(Warning.AMBIGUOUS_NEXT_WEEK) else emptySet(),
            ),
        )
    }

    private fun offsetAtom(m: MatchResult): Atom? {
        val amount = m.groupValues[1]
        val unit = m.groupValues[2]
        val within = m.groupValues[3].endsWith("内")
        val approximate = amount == "几"
        // In halves, so "1个半" and "半" need no fractions.
        val halves: Long = when {
            amount == "半" -> 1
            amount == "几" -> 6
            amount.endsWith("个半") -> amount.removeSuffix("个半").toLong() * 2 + 1
            else -> amount.toLong() * 2
        }
        val kind = if (within) MentionKind.DEADLINE else MentionKind.AT
        val minutesPerUnit = when (unit) {
            "分钟", "分" -> 1L
            "小时", "钟头" -> 60L
            else -> 0L
        }
        if (minutesPerUnit > 0 || (unit in setOf("天", "日") && halves % 2 == 1L && halves < 2)) {
            val minutes = if (minutesPerUnit > 0) halves * minutesPerUnit / 2 else halves * 12 * 60
            val t = now.plusMinutes(minutes).withSecond(0).withNano(0)
            return Atom(m.range, exact = t, kind = kind)
        }
        val date = when (unit) {
            "天", "日" -> today.plusDays(halves / 2)
            "周", "星期", "礼拜" -> today.plusDays(halves * 7 / 2)
            "月" -> if (halves % 2 == 1L) today.plusMonths(halves / 2).plusDays(15) else today.plusMonths(halves / 2)
            "年" -> today.plusMonths(halves * 6)
            else -> addWorkdays(today, halves / 2)
        }
        return Atom(m.range, date = DatePart(date, approximate = approximate), kind = kind)
    }

    private fun addWorkdays(from: LocalDate, n: Long): LocalDate {
        var d = from
        var left = n
        while (left > 0) {
            d = d.plusDays(1)
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) left--
        }
        return d
    }

    // --- clock -------------------------------------------------------------

    /** "3点建议": a count of points, not three o'clock. */
    private val notAClock = listOf(
        "建议", "意见", "要求", "内容", "事项", "原因", "想法", "看法", "问题",
        "好处", "注意", "说明", "总结", "要点", "理由", "共识", "需求", "改进",
    )

    private fun clockAtom(m: MatchResult): Atom? {
        val g = m.groupValues
        val hour: Int
        val minute: Int
        var leadingZero = false
        if (g[1].isNotEmpty()) {
            hour = g[1].toInt()
            minute = g[2].toInt()
            leadingZero = g[1].length == 2 && g[1][0] == '0'
        } else {
            hour = g[3].toInt()
            minute = when {
                g[4].isNotEmpty() -> g[4].toInt() * 15
                m.value.endsWith("半") -> 30
                g[5].isNotEmpty() -> g[5].toInt()
                else -> 0
            }
            if (m.value.endsWith("点") && notAClock.any { text.startsWith(it, m.range.last + 1) }) return null
        }
        if (hour > 24 || minute > 59) return null
        return Atom(m.range, clock = Clock(hour % 24, minute, ambiguous = hour in 1..11 && !leadingZero))
    }

    /** The text being scanned, for rules that need to look past their own match. */
    private var text: String = ""
}

internal fun IntRange.overlaps(other: IntRange): Boolean =
    first <= other.last && other.first <= last
