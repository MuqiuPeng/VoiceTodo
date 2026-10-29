package app.voicetodo.parser

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/**
 * Whether an utterance is English: Latin letters with at most a sprinkling
 * of Chinese (a name). "明天跟OpenAI开会" is Chinese with an English word in
 * it and goes to [TaskParser]; "call 腾讯 tomorrow at 3pm" comes here.
 */
fun looksEnglish(text: String): Boolean {
    val cjk = text.count { it in '一'..'鿿' }
    val latin = text.count { it in 'a'..'z' || it in 'A'..'Z' }
    return latin >= 3 && latin >= cjk * 4
}

/**
 * SenseVoice writes English in capitals ("CALL BOB TOMORROW"). Text with no
 * lower-case letters at all is lower-cased, keeping a lone "I". Run before
 * anything that adds lower-case letters (vocabulary corrections), or the
 * text no longer looks all-caps.
 */
fun sentenceCase(s: String): String {
    if (s.none { it in 'a'..'z' } && s.count { it in 'A'..'Z' } >= 3) {
        return s.lowercase().replace(Regex("\\bi\\b"), "I")
    }
    return s
}

/**
 * The English counterpart of [TaskParser], for the phrasings people use when
 * dictating a to-do: "call Bob tomorrow at 3pm", "submit the report by
 * Friday", "dentist next Tuesday afternoon, remind me an hour before".
 * Smaller than the Chinese rules; same output, same warnings.
 */
class EnglishParser(private val config: ParserConfig = ParserConfig()) {

    fun parse(input: String, now: LocalDateTime, protectedTerms: Collection<String> = emptyList()): ParseResult {
        val shield = Shield(protectedTerms)
        val text = shield.hide(sentenceCase(input))
        val clock = now.withSecond(0).withNano(0)
        val tasks = splitTasks(text).mapNotNull { part -> parsePart(part, clock) }.map { t ->
            t.copy(title = shield.restore(t.title), source = shield.restore(t.source))
        }
        return ParseResult(input, input, tasks)
    }

    // --- splitting -------------------------------------------------------

    private fun splitTasks(text: String): List<String> {
        val sentences = text.split(Regex("(?<=[.;!?])\\s+|\\n|;")).map { it.trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        for (s in sentences) {
            var rest = s
            while (true) {
                val m = CONNECTOR.find(rest) ?: break
                val left = rest.substring(0, m.range.first)
                val right = rest.substring(m.range.last + 1)
                // "and" joins nouns ("milk and eggs") far more often than tasks;
                // split only when both sides carry their own time.
                if (left.isNotBlank() && right.isNotBlank() && hasTime(left) && hasTime(right)) {
                    out += left.trim()
                    rest = right.trim()
                } else break
            }
            out += rest
        }
        // A part that is only a time ("tomorrow at 3, call Bob") belongs to its neighbour.
        val merged = mutableListOf<String>()
        for (p in out) {
            if (merged.isNotEmpty() && titleOf(merged.last()).isEmpty()) merged[merged.lastIndex] = merged.last() + ", " + p
            else merged += p
        }
        return merged
    }

    private fun hasTime(s: String) = atoms(s.lowercase(), LocalDateTime.now()).isNotEmpty()

    private fun titleOf(s: String): String {
        val lower = s.lowercase()
        val spans = atoms(lower, LocalDateTime.now()).map { it.range }
        return cleanTitle(s, spans)
    }

    // --- atoms -----------------------------------------------------------

    private class Atom(
        val range: IntRange,
        val date: LocalDate? = null,
        val dateEnd: LocalDate? = null,
        val period: Period? = null,
        val clock: Clock? = null,
        val fixed: LocalTime? = null,
        val exact: LocalDateTime? = null,
        val kind: MentionKind? = null,
        val approximate: Boolean = false,
        val weekdayAmbiguous: LocalDate? = null,
        /** Holiday names stay in the title: "Halloween party" is the task, not "party". */
        val keepInTitle: Boolean = false,
    )

    private class Rule(val regex: Regex, val build: (MatchResult, LocalDateTime) -> Atom?)

    private val rules: List<Rule> = listOf(
        Rule(Regex("\\b(?:the\\s+)?day\\s+after\\s+tomorrow\\b")) { m, now -> Atom(m.range, date = now.toLocalDate().plusDays(2)) },
        Rule(Regex("\\btonight\\b")) { m, now -> Atom(m.range, date = now.toLocalDate(), period = Period.EVENING) },
        Rule(Regex("\\btoday\\b")) { m, now -> Atom(m.range, date = now.toLocalDate()) },
        Rule(Regex("\\b(?:tomorrow|tmrw|tmr)\\b")) { m, now -> Atom(m.range, date = now.toLocalDate().plusDays(1)) },
        Rule(Regex("\\b(?:(this|next|coming)\\s+)?($WEEKDAY)\\b")) { m, now -> weekday(m, now, abbreviated = false) },
        // Abbreviations only after a word that makes them dates: "on sat", not "sat down".
        Rule(Regex("\\b(this|next|coming|on|by|until|till|before|after)\\s+($WEEKDAY_SHORT)\\b\\.?")) { m, now -> weekday(m, now, abbreviated = true) },
        Rule(Regex("\\b(?:(this|next)\\s+)?weekend\\b")) { m, now ->
            val monday = now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(if (m.groupValues[1] == "next") 1 else 0)
            Atom(m.range, date = monday.plusDays(5), dateEnd = monday.plusDays(6))
        },
        Rule(Regex("\\b(this|next)\\s+week\\b")) { m, now ->
            val monday = now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(if (m.groupValues[1] == "next") 1 else 0)
            var day = monday.with(TemporalAdjusters.nextOrSame(config.weekDeadlineDay))
            if (day < now.toLocalDate()) day = monday.plusDays(6)
            Atom(m.range, date = day, approximate = true)
        },
        Rule(Regex("\\b(?:by\\s+)?(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?(day|week|month|year)\\b|\\beod\\b")) { m, now ->
            val today = now.toLocalDate()
            when (m.groupValues[1]) {
                "", "day" -> Atom(m.range, date = today, fixed = config.workEnd, kind = MentionKind.DEADLINE)
                "week" -> Atom(m.range, date = today.with(TemporalAdjusters.nextOrSame(config.weekDeadlineDay)), kind = MentionKind.DEADLINE)
                "month" -> Atom(m.range, date = YearMonth.from(today).atEndOfMonth(), kind = MentionKind.DEADLINE)
                else -> Atom(m.range, date = LocalDate.of(today.year, 12, 31), kind = MentionKind.DEADLINE)
            }
        },
        Rule(Regex("\\b($MONTH)\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b")) { m, now -> monthDay(m.range, month(m.groupValues[1]), m.groupValues[2].toInt(), now) },
        Rule(Regex("\\b(?:the\\s+)?(\\d{1,2})(?:st|nd|rd|th)\\s+(?:of\\s+)?($MONTH)\\b")) { m, now -> monthDay(m.range, month(m.groupValues[2]), m.groupValues[1].toInt(), now) },
        Rule(Regex("\\b(?<![\\d/])(\\d{1,2})/(\\d{1,2})(?![\\d/])\\b")) { m, now -> monthDay(m.range, m.groupValues[1].toInt(), m.groupValues[2].toInt(), now) },
        Rule(Regex("\\bthe\\s+(\\d{1,2})(?:st|nd|rd|th)\\b")) { m, now ->
            val day = m.groupValues[1].toInt()
            var ym = YearMonth.from(now)
            if (day < now.dayOfMonth) ym = ym.plusMonths(1)
            if (!ym.isValidDay(day)) null else Atom(m.range, date = ym.atDay(day))
        },
        Rule(Regex("\\b(?:in|within)\\s+($NUMBER)\\s+(minutes?|mins?|hours?|hrs?|days?|weeks?|months?)\\b|\\b($NUMBER)\\s+(minutes?|mins?|hours?|hrs?|days?|weeks?|months?)\\s+from\\s+now\\b")) { m, now ->
            val amount = m.groupValues[1].ifEmpty { m.groupValues[3] }
            val unit = m.groupValues[2].ifEmpty { m.groupValues[4] }
            offset(m, number(amount) ?: return@Rule null, unit, now)
        },
        Rule(Regex("\\bin\\s+(?:half\\s+an|a\\s+half)\\s+hour\\b|\\bin\\s+an?\\s+hour\\s+and\\s+a\\s+half\\b")) { m, now ->
            Atom(m.range, exact = now.plusMinutes(if (m.value.contains("and a half")) 90 else 30))
        },
        Rule(Regex("\\b(?:in\\s+the\\s+|this\\s+|tomorrow\\s+)?(morning|afternoon|evening|night)\\b")) { m, now ->
            val period = when (m.groupValues[1]) {
                "morning" -> Period.MORNING
                "afternoon" -> Period.AFTERNOON
                "evening" -> Period.EVENING
                else -> Period.NIGHT
            }
            Atom(m.range, date = if (m.value.startsWith("tomorrow")) now.toLocalDate().plusDays(1) else null, period = period)
        },
        // "lunch" alone is usually the task ("lunch with Alice"); only "at lunch" / "lunchtime" are times.
        Rule(Regex("\\b(?:at\\s+)?(?:noon|midday|lunchtime)\\b|\\bat\\s+lunch\\b")) { m, _ -> Atom(m.range, fixed = config.lunch) },
        Rule(Regex("\\b(?:at\\s+)?midnight\\b")) { m, _ -> Atom(m.range, fixed = LocalTime.of(23, 59)) },
        Rule(Regex("\\b(before|after)\\s+work\\b")) { m, _ ->
            if (m.groupValues[1] == "before") Atom(m.range, fixed = config.workStart, kind = MentionKind.DEADLINE)
            else Atom(m.range, fixed = config.workEnd, kind = MentionKind.START)
        },
        Rule(Regex("\\b(half|quarter)\\s+(past|to)\\s+($NUMBER)\\b")) { m, _ ->
            val h = number(m.groupValues[3])?.toInt() ?: return@Rule null
            if (h !in 1..12) return@Rule null
            val t = LocalTime.of(h % 12, 0).let {
                when {
                    m.groupValues[2] == "to" -> it.minusMinutes(15)
                    m.groupValues[1] == "half" -> it.plusMinutes(30)
                    else -> it.plusMinutes(15)
                }
            }
            Atom(m.range, clock = Clock(if (t.hour == 0) 12 else t.hour, t.minute, ambiguous = true))
        },
        Rule(Regex("\\b(\\d{1,2})(?::(\\d{2}))?\\s*([ap])\\.?\\s?m\\.?(?![a-z])")) { m, _ ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toIntOrNull() ?: 0
            if (h !in 1..12 || min > 59) return@Rule null
            Atom(m.range, clock = Clock((h % 12) + if (m.groupValues[3] == "p") 12 else 0, min, ambiguous = false))
        },
        Rule(Regex("\\b($NUMBER_WORD)(?:\\s+($MINUTE_WORD))?\\s+([ap])\\.?\\s?m\\b\\.?")) { m, _ ->
            val h = number(m.groupValues[1])?.toInt()?.takeIf { it in 1..12 } ?: return@Rule null
            val min = m.groupValues[2].takeIf { it.isNotEmpty() }?.let { number(it)?.toInt() } ?: 0
            if (min > 59) return@Rule null
            Atom(m.range, clock = Clock((h % 12) + if (m.groupValues[3] == "p") 12 else 0, min, ambiguous = false))
        },
        Rule(Regex("\\b($NUMBER)\\s+o'?clock\\b")) { m, _ ->
            val h = number(m.groupValues[1])?.toInt()?.takeIf { it in 1..12 } ?: return@Rule null
            Atom(m.range, clock = Clock(h, 0, ambiguous = true))
        },
        Rule(Regex("\\b(\\d{1,2}):(\\d{2})\\b")) { m, _ ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            if (h > 23 || min > 59) return@Rule null
            Atom(m.range, clock = Clock(h, min, ambiguous = h in 1..11 && !m.groupValues[1].startsWith("0")))
        },
        // A bare hour needs "at" to be a time: "at 3", not "3 apples".
        Rule(Regex("\\bat\\s+(\\d{1,2}|$NUMBER_WORD)\\b(?![:.]\\d|\\s*(?:(?:$MINUTE_WORD)\\s+)?[ap]\\.?\\s?m\\b|\\s*(?:%|percent|people|items|things|days?|weeks?|[a-z]+st\\b))")) { m, _ ->
            val h = number(m.groupValues[1])?.toInt()?.takeIf { it in 0..23 } ?: return@Rule null
            Atom(m.range, clock = Clock(h, 0, ambiguous = h in 1..11))
        },
        Rule(Regex("\\b(christmas eve|christmas|new year'?s eve|new year'?s(?: day)?|valentine'?s day|halloween)\\b")) { m, now ->
            val (mo, d) = when {
                m.value.startsWith("christmas eve") -> 12 to 24
                m.value.startsWith("christmas") -> 12 to 25
                m.value.contains("eve") -> 12 to 31
                m.value.startsWith("new year") -> 1 to 1
                m.value.startsWith("valentine") -> 2 to 14
                else -> 10 to 31
            }
            monthDay(m.range, mo, d, now)?.let { Atom(it.range, date = it.date, keepInTitle = true) }
        },
    )

    private fun atoms(lower: String, now: LocalDateTime): List<Atom> {
        val candidates = rules.flatMap { r -> r.regex.findAll(lower).mapNotNull { r.build(it, now) }.toList() }
        val chosen = mutableListOf<Atom>()
        for (a in candidates.sortedWith(compareBy<Atom>({ it.range.first }, { -it.range.count() }))) {
            if (chosen.none { it.range.overlaps(a.range) }) chosen += a
        }
        return chosen.sortedBy { it.range.first }
    }

    private fun weekday(m: MatchResult, now: LocalDateTime, abbreviated: Boolean): Atom {
        val prefix = m.groupValues[1]
        val dow = dayOfWeek(m.groupValues[2])
        val today = now.toLocalDate()
        val thisWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusDays(dow.value - 1L)
        // "on sat" consumed its preposition; leave it to the relation reader instead.
        val range = if (abbreviated && prefix !in setOf("this", "next", "coming")) {
            (m.range.first + prefix.length + 1)..m.range.last
        } else m.range
        return when (prefix) {
            "next" -> Atom(
                range, date = thisWeek.plusWeeks(1),
                // "next Friday" said on Wednesday: some mean the one in two days.
                weekdayAmbiguous = thisWeek.takeIf { it > today },
            )
            "this" -> Atom(range, date = thisWeek)
            else -> Atom(range, date = today.with(TemporalAdjusters.nextOrSame(dow)))
        }
    }

    private fun monthDay(range: IntRange, month: Int, day: Int, now: LocalDateTime): Atom? {
        if (month !in 1..12) return null
        for (y in now.year..now.year + 1) {
            val d = runCatching { LocalDate.of(y, month, day) }.getOrNull() ?: return null
            if (!d.isBefore(now.toLocalDate())) return Atom(range, date = d)
        }
        return null
    }

    private fun offset(m: MatchResult, n: Double, unit: String, now: LocalDateTime): Atom {
        val within = m.value.startsWith("within")
        val kind = if (within) MentionKind.DEADLINE else MentionKind.AT
        return when {
            unit.startsWith("min") -> Atom(m.range, exact = now.plusMinutes(n.toLong()), kind = kind)
            unit.startsWith("h") -> Atom(m.range, exact = now.plusMinutes((n * 60).toLong()), kind = kind)
            unit.startsWith("day") -> Atom(m.range, date = now.toLocalDate().plusDays(n.toLong()), kind = kind)
            unit.startsWith("week") -> Atom(m.range, date = now.toLocalDate().plusDays((n * 7).toLong()), kind = kind)
            else -> Atom(m.range, date = now.toLocalDate().plusMonths(n.toLong()), kind = kind)
        }
    }

    // --- one task ----------------------------------------------------------

    private fun parsePart(part: String, now: LocalDateTime): ParsedTask? {
        val lower = part.lowercase()
        val remind = REMIND_BEFORE.find(lower)
        val remindBefore = remind?.let(::remindDuration)
        val all = atoms(lower, now).filter { a -> remind == null || !a.range.overlaps(remind.range) }

        // Join neighbours into one expression: "tomorrow at 3pm", "friday afternoon".
        val groups = mutableListOf<MutableList<Atom>>()
        for (a in all) {
            val g = groups.lastOrNull()
            val gap = g?.let { lower.substring(it.last().range.last + 1, a.range.first) }
            if (g != null && gap != null && JOIN.matches(gap) && compatible(g, a)) g += a else groups += mutableListOf(a)
        }

        val removed = mutableListOf<IntRange>()
        remind?.let { removed += it.range }
        val mentions = groups.map { g ->
            var start = g.first().range.first
            val end = g.last().range.last
            var kind = g.firstNotNullOfOrNull { it.kind }
            var before = false
            PREPOSITION.find(lower.substring(0, start))?.let { p ->
                val word = p.groupValues[1]
                when {
                    kind != null -> Unit
                    word in setOf("by", "until", "till", "due", "due by", "no later than", "deadline is") -> kind = MentionKind.DEADLINE
                    word == "before" -> { kind = MentionKind.DEADLINE; before = true }
                    word in setOf("after", "from", "starting") -> kind = MentionKind.START
                }
                start -= p.value.length
            }
            if (!g.all { it.keepInTitle }) removed += start..end
            resolve(g, kind ?: MentionKind.AT, before, now, lower.substring(start, end + 1))
        }

        val title = cleanTitle(part, removed)
        if (title.isEmpty() && mentions.isEmpty()) return null
        val main = mentions.firstOrNull { it.kind == MentionKind.DEADLINE }
            ?: mentions.firstOrNull { it.kind == MentionKind.AT }
            ?: mentions.firstOrNull { it.kind == MentionKind.START }
        val start = mentions.firstOrNull { it.kind == MentionKind.START }
        return ParsedTask(
            title = title,
            due = main?.due,
            dueKind = main?.kind,
            remindBefore = remindBefore,
            startAfter = start?.takeIf { it !== main }?.due,
            source = part,
            alternatives = main?.alternatives ?: emptyList(),
            warnings = buildSet {
                main?.let { addAll(it.warnings) }
                if (RECURRING.containsMatchIn(lower)) add(Warning.RECURRING)
            },
            mentions = mentions,
        )
    }

    private fun compatible(g: List<Atom>, a: Atom): Boolean {
        if (a.date != null && g.any { it.date != null || it.exact != null }) return false
        if (a.period != null && g.any { it.period != null }) return false
        val hasTime = g.any { it.clock != null || it.fixed != null || it.exact != null }
        return !((a.clock != null || a.fixed != null || a.exact != null) && hasTime)
    }

    private fun resolve(g: List<Atom>, kind: MentionKind, before: Boolean, now: LocalDateTime, text: String): TimeMention {
        val warnings = mutableSetOf<Warning>()
        val alternatives = mutableListOf<Due>()
        g.firstNotNullOfOrNull { it.exact }?.let { t ->
            if (t < now) warnings += Warning.IN_PAST
            return TimeMention(kind, Due(t.toLocalDate(), t.toLocalTime()), 0..0, text, warnings = warnings)
        }
        val dateAtom = g.firstOrNull { it.date != null }
        var date = dateAtom?.let { if (kind == MentionKind.DEADLINE && it.dateEnd != null) it.dateEnd else it.date }
        val period = g.firstNotNullOfOrNull { it.period }
        val clock = g.firstNotNullOfOrNull { it.clock }
        val fixed = g.firstNotNullOfOrNull { it.fixed }
        var approximate = g.any { it.approximate }

        var time: LocalTime? = null
        var alt: LocalTime? = null
        when {
            fixed != null -> time = fixed
            clock != null -> {
                val h = clock.hour
                time = when {
                    !clock.ambiguous -> LocalTime.of(h, clock.minute)
                    period == Period.MORNING -> LocalTime.of(h % 12, clock.minute)
                    period != null -> LocalTime.of((h % 12) + 12, clock.minute)
                    else -> {
                        val pm = h < config.bareHourPmBelow
                        alt = LocalTime.of(if (pm) h else (h % 12) + 12, clock.minute)
                        if (h in config.bareHourPmBelow..8) warnings += Warning.AMBIGUOUS_AM_PM
                        LocalTime.of(if (pm) h + 12 else h, clock.minute)
                    }
                }
            }
            period != null -> {
                time = (if (kind == MentionKind.DEADLINE) config.periodEnd else config.periodStart).getValue(period)
                approximate = true
            }
        }

        val today = now.toLocalDate()
        if (date == null) {
            // "at 7" at 10:00 is probably tonight; "at 9" at 10:00 probably tomorrow morning.
            val t = time ?: config.defaultRemindTime
            val a = alt
            when {
                today.atTime(t) >= now -> date = today
                a != null && Warning.AMBIGUOUS_AM_PM in warnings && today.atTime(a) >= now -> {
                    date = today
                    alt = t
                    time = a
                }
                else -> {
                    date = today.plusDays(1)
                    warnings += Warning.ROLLED_TO_TOMORROW
                }
            }
        } else if (before && time == null) {
            date = date.minusDays(1)
        }
        dateAtom?.weekdayAmbiguous?.let {
            warnings += Warning.AMBIGUOUS_NEXT_WEEK
            alternatives += Due(it, time, approximate)
        }
        alt?.let { alternatives.add(0, Due(date, it, approximate)) }
        if (date.atTime(time ?: LocalTime.MAX) < now) warnings += Warning.IN_PAST
        return TimeMention(kind, Due(date, time, approximate), 0..0, text, alternatives, warnings)
    }

    // --- title -------------------------------------------------------------

    private fun cleanTitle(original: String, removed: List<IntRange>): String {
        var s = buildString {
            original.forEachIndexed { i, c -> append(if (removed.any { i in it }) ' ' else c) }
        }
        s = s.replace(Regex("\\s+"), " ").trim()
        var changed = true
        while (changed) {
            val before = s
            s = LEADING.replace(s, "").trim()
            s = TRAILING.replace(s, "").trim()
            s = s.trim { it.isWhitespace() || it in ",.;:!?-" }
            changed = s != before
        }
        s = s.replace(Regex("\\s+([,.!?])"), "$1").replace(Regex("\\s{2,}"), " ")
        return s.replaceFirstChar { it.uppercase() }
    }

    private fun remindDuration(m: MatchResult): Duration {
        val g = m.groupValues
        if (g[3].isNotEmpty()) return Duration.ofDays(1) // "the day before", "the night before"
        val n = number(g[1]) ?: 1.0
        val unit = g[2]
        return when {
            unit.startsWith("min") -> Duration.ofMinutes(n.toLong())
            unit.startsWith("h") -> Duration.ofMinutes((n * 60).toLong())
            unit.startsWith("day") -> Duration.ofDays(n.toLong())
            else -> Duration.ofDays((n * 7).toLong())
        }
    }

    private companion object {
        const val WEEKDAY = "monday|tuesday|wednesday|thursday|friday|saturday|sunday"
        const val WEEKDAY_SHORT = "mon|tues|tue|wed|thurs|thur|thu|fri|sat|sun"
        const val MONTH = "january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sept|sep|oct|nov|dec"
        const val NUMBER_WORD = "one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve"
        const val MINUTE_WORD = "o'?\\s?five|o'?\\s?clock|fifteen|thirty|forty[- ]five|forty|fifty|twenty|ten|forty five"
        val SMALL = listOf(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
        )
        val TENS = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "ninety" to 90)
        const val NUMBER = "\\d+(?:\\.\\d+)?|an?|a couple(?: of)?|couple of|a few|few|(?:twenty|thirty|forty|fifty|sixty|ninety)(?:[- ](?:one|two|three|four|five|six|seven|eight|nine))?|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|$NUMBER_WORD|half an?"

        val CONNECTOR = Regex("\\s*,?\\s*\\b(?:and also|also|and then|then|plus|and)\\b\\s*", RegexOption.IGNORE_CASE)
        val JOIN = Regex("[\\s,]*(?:at|on|in the|the|around|about)?[\\s,]*")
        val PREPOSITION = Regex("\\b(no later than|deadline is|due by|due|by|before|until|till|after|from|starting|on|at|in|around|about|for|every)\\s+$")
        val REMIND_BEFORE = Regex(
            "\\b(?:and\\s+)?remind\\s+me\\s+(?:(?:($NUMBER)\\s+(minutes?|mins?|hours?|hrs?|days?|weeks?)\\s+(?:before|early|earlier|ahead|in advance))|(the\\s+(?:day|night)\\s+before))\\b",
        )
        val RECURRING = Regex("\\bevery\\s+(?:day|morning|evening|night|week|month|year|$WEEKDAY)\\b")

        val LEADING = Regex(
            "^(?:(?:ok(?:ay)?|so|well|hey|please|um+|uh+)[,]?\\s+)*(?:remind me to|remember to|don'?t forget to|i need to|i have to|i'?ve got to|i gotta|i must|i should|i want to|i'?d like to|need to|have to|make sure to|can you|could you|let'?s|to)\\b\\s*",
            RegexOption.IGNORE_CASE,
        )
        // A trailing "…and remind me …" is about the reminder, not the task, even
        // when the rest of it could not be read.
        val TRAILING = Regex("\\s*\\b(?:please|thanks|thank you|ok(?:ay)?)\\s*$|\\s*,?\\s*\\b(?:and\\s+)?remind\\b(?!\\s+me\\s+to\\b).*$", RegexOption.IGNORE_CASE)

        fun dayOfWeek(s: String): DayOfWeek = when (s.take(3)) {
            "mon" -> DayOfWeek.MONDAY
            "tue" -> DayOfWeek.TUESDAY
            "wed" -> DayOfWeek.WEDNESDAY
            "thu" -> DayOfWeek.THURSDAY
            "fri" -> DayOfWeek.FRIDAY
            "sat" -> DayOfWeek.SATURDAY
            else -> DayOfWeek.SUNDAY
        }

        fun month(s: String): Int = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
            .indexOf(s.take(3)) + 1

        fun number(s: String): Double? {
            val t = s.trim().lowercase()
            t.toDoubleOrNull()?.let { return it }
            return when {
                t == "a" || t == "an" -> 1.0
                t.startsWith("half") -> 0.5
                t.contains("couple") -> 2.0
                t.contains("few") -> 3.0
                t.startsWith("o") && t.contains("five") -> 5.0 // "three o five"
                t.contains("clock") -> 0.0
                else -> {
                    // "forty five", "forty-five", "thirty", "seven"
                    val parts = t.split(' ', '-').filter { it.isNotEmpty() }
                    var total = 0
                    for (p in parts) total += TENS[p] ?: SMALL.indexOf(p).takeIf { it >= 0 } ?: return null
                    total.toDouble()
                }
            }
        }
    }
}

/**
 * The English side of [Disfluency]: filler sounds (um, uh), discourse
 * fillers set off by commas ("you know,"), and self-corrections between two
 * times ("Friday, no wait, Saturday").
 */
object EnglishDisfluency {
    private val sound = Regex("\\b(?:u+m+|u+h+|uhm|erm|er|hmm+|ah+)\\b[,.]?\\s*", RegexOption.IGNORE_CASE)
    private val phrase = Regex("(?:^|(?<=,))\\s*(?:you know|i mean|like|basically|so|well|okay|ok)\\s*,\\s*", RegexOption.IGNORE_CASE)
    private const val DAY = "(?:today|tonight|tomorrow|monday|tuesday|wednesday|thursday|friday|saturday|sunday)"
    private const val TIME = "(?:\\d{1,2}(?::\\d{2})?\\s*(?:[ap]\\.?m\\.?)?)"
    private val repair = Regex(
        "\\b($DAY|$TIME)\\s*,?\\s*(?:no wait|no no|no|sorry|i mean|actually|make that|scratch that)\\s*,?\\s*(?=($DAY|$TIME)\\b)",
        RegexOption.IGNORE_CASE,
    )
    private val stutter = Regex("\\b(\\w+)(?:\\s+\\1\\b)+", RegexOption.IGNORE_CASE)

    fun clean(text: String): Cleaned {
        val fillers = mutableListOf<String>()
        val repairs = mutableListOf<Pair<String, String>>()
        var s = sound.replace(sentenceCase(text)) { fillers += it.value.trim(' ', ',', '.'); "" }
        // Repeated: removing one leading phrase exposes the next ("So, you know, …").
        while (true) {
            val next = phrase.replace(s) { fillers += it.value.trim(' ', ','); if (it.range.first == 0) "" else " " }
            if (next == s) break
            s = next
        }
        s = repair.replace(s) { m ->
            val sameKind = m.groupValues[1].first().isDigit() == m.groupValues[2].first().isDigit()
            if (sameKind) { repairs += m.groupValues[1] to m.groupValues[2]; "" } else m.value
        }
        s = stutter.replace(s) { it.groupValues[1] }
        s = s.replace(Regex("\\s{2,}"), " ").replace(Regex("\\s+,"), ",").trim().trim(',').trim()
        return Cleaned(s, fillers.filter { it.isNotEmpty() }, repairs)
    }
}
