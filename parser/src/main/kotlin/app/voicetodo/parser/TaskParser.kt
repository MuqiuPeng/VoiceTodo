package app.voicetodo.parser

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Turns one utterance into tasks with due times, entirely offline.
 *
 * The pipeline: normalize → split into clauses → find time atoms per clause →
 * join adjacent atoms into mentions → read each mention's relation from the
 * words around it (之前 / 之后 / 提醒我) → resolve against `now` → strip the
 * time words out of the clause to leave a title → group clauses into tasks.
 *
 * It never refuses: anything it is unsure of comes back with [Warning]s and
 * alternatives, because the confirmation card is where doubt gets settled.
 */
class TaskParser(private val config: ParserConfig = ParserConfig()) {

    fun parse(input: String, now: LocalDateTime): ParseResult {
        val normalized = Normalizer.normalize(input)
        val scanner = Scanner(now.withSecond(0).withNano(0), config)
        val clauses = splitClauses(normalized, scanner).map { analyze(it, scanner, now) }
        return ParseResult(input, normalized, assemble(clauses))
    }

    // --- clauses -----------------------------------------------------------

    private class RawClause(val text: String, val offset: Int, val strong: Boolean)

    private class Clause(
        val raw: RawClause,
        val mentions: List<TimeMention>,
        val title: String,
        val remindBefore: Duration?,
        val recurring: Boolean,
    ) {
        val hasDue get() = mentions.any { it.kind != MentionKind.REMIND }
    }

    private fun splitClauses(text: String, scanner: Scanner): List<RawClause> {
        val out = mutableListOf<RawClause>()
        var start = 0
        var strong = true
        fun flush(end: Int) {
            var s = start
            var e = end
            while (s < e && text[s].isWhitespace()) s++
            while (e > s && text[e - 1].isWhitespace()) e--
            if (s >= e) return
            var piece = text.substring(s, e)
            var isStrong = strong
            CONNECTOR_AT_START.find(piece)?.let {
                isStrong = true
                s += it.value.length
                piece = piece.substring(it.value.length)
            }
            if (piece.isNotBlank()) out += splitOnConnector(RawClause(piece, s, isStrong), scanner)
        }
        for ((i, c) in text.withIndex()) {
            if (c in STRONG_BREAK || c in WEAK_BREAK) {
                flush(i)
                // A strong break only makes the next clause a new task if it
                // has a title of its own; that is decided in assemble().
                strong = c in STRONG_BREAK
                start = i + 1
            }
        }
        flush(text.length)
        return out
    }

    /**
     * Speech often arrives without punctuation: "周五交报告还有下周一报发票".
     * A connector in mid-clause splits it only when both sides carry a time,
     * so "我还有事" stays whole.
     */
    private fun splitOnConnector(c: RawClause, scanner: Scanner): List<RawClause> {
        for (m in CONNECTOR.findAll(c.text)) {
            if (m.range.first == 0) continue
            val left = c.text.substring(0, m.range.first)
            val right = c.text.substring(m.range.last + 1)
            if (right.isBlank()) continue
            if (scanner.scan(left, emptyList()).isNotEmpty() && scanner.scan(right, emptyList()).isNotEmpty()) {
                return listOf(RawClause(left, c.offset, c.strong)) +
                    splitOnConnector(RawClause(right, c.offset + m.range.last + 1, true), scanner)
            }
        }
        return listOf(c)
    }

    private fun analyze(raw: RawClause, scanner: Scanner, now: LocalDateTime): Clause {
        val text = raw.text
        val remindMatch = REMIND_OFFSET.find(text)
        val masked = listOfNotNull(remindMatch?.range)
        val atoms = scanner.scan(text, masked)
        val mentions = mentionsOf(text, atoms, masked, now)
        val removed = masked + mentions.map { it.span }
        val title = cleanTitle(text, removed)
        return Clause(
            raw,
            mentions.map { it.copy(span = it.span.first + raw.offset..it.span.last + raw.offset) },
            title,
            remindMatch?.let(::remindDuration),
            RECURRING.containsMatchIn(text),
        )
    }

    // --- mentions ----------------------------------------------------------

    /** Atoms that read as one expression: "下周三" + "下午" + "3点". */
    private class Group(first: Atom) {
        val atoms = mutableListOf(first)
        var date: DatePart? = first.date
        var dateFromWeekday = first.weekday != null
        val warnings = mutableSetOf<Warning>()
        val range get() = atoms.first().range.first..atoms.last().range.last
        val period get() = atoms.firstNotNullOfOrNull { it.period }
        val clock get() = atoms.firstNotNullOfOrNull { it.clock }
        val fixedTime get() = atoms.firstNotNullOfOrNull { it.fixedTime }
        val exact get() = atoms.firstNotNullOfOrNull { it.exact }
        val bakedKind get() = atoms.firstNotNullOfOrNull { it.kind }
        val bakedBefore get() = atoms.any { it.relationIsBefore }
        private val hasTime get() = clock != null || fixedTime != null || exact != null

        fun tryAdd(a: Atom): Boolean {
            if (a.period != null && period != null) return false
            if ((a.clock != null || a.fixedTime != null || a.exact != null) && hasTime) return false
            if (a.exact != null && date != null) return false
            if (a.date != null && exact != null) return false
            val d = date
            if (a.date != null && d != null) {
                when {
                    a.weekday != null -> if (d.date.dayOfWeek != a.weekday) warnings += Warning.WEEKDAY_MISMATCH
                    dateFromWeekday -> {
                        if (a.date.date.dayOfWeek != d.date.dayOfWeek) warnings += Warning.WEEKDAY_MISMATCH
                        date = a.date
                        dateFromWeekday = false
                    }
                    a.date.date != d.date -> return false
                }
            } else if (a.date != null) {
                date = a.date
                dateFromWeekday = a.weekday != null
            }
            atoms += a
            return true
        }
    }

    private fun mentionsOf(text: String, atoms: List<Atom>, masked: List<IntRange>, now: LocalDateTime): List<TimeMention> {
        val groups = mutableListOf<Group>()
        val rangeStartOf = mutableMapOf<Group, Group>()
        for (a in atoms) {
            val last = groups.lastOrNull()
            val gap = if (last == null) "" else text.substring(last.range.last + 1, a.range.first)
            if (last != null && JOIN_GAP.matches(gap) && last.tryAdd(a)) continue
            val g = Group(a)
            if (last != null && RANGE_GAP.matches(gap)) rangeStartOf[g] = last
            groups += g
        }
        val rangeStarts = rangeStartOf.values.toSet()

        return groups.filter { it !in rangeStarts }.map { g ->
            val startGroup = rangeStartOf[g]
            val (kind, before, span) = relation(text, g, startGroup, masked)
            val due = resolve(g, kind, before, now, inherit = startGroup)
            val startDue = startGroup?.let { resolve(it, MentionKind.AT, false, now, inherit = null) }
            TimeMention(
                kind = kind,
                due = due.due,
                span = span,
                text = text.substring(span.first, span.last + 1),
                alternatives = due.alternatives,
                warnings = due.warnings + (startDue?.warnings ?: emptySet()),
                rangeStart = startDue?.due,
            )
        }
    }

    private data class Relation(val kind: MentionKind, val before: Boolean, val span: IntRange)

    private fun relation(text: String, g: Group, startGroup: Group?, masked: List<IntRange>): Relation {
        var first = (startGroup ?: g).range.first
        var last = g.range.last
        var kind = g.bakedKind
        var before = g.bakedBefore

        fun suffix(regex: Regex): Boolean {
            val m = regex.find(text.substring(last + 1)) ?: return false
            val r = last + 1..last + m.value.length
            if (masked.any { it.overlaps(r) }) return false
            last += m.value.length
            return true
        }

        if (kind == null) {
            when {
                suffix(SUFFIX_BEFORE) -> { kind = MentionKind.DEADLINE; before = true }
                suffix(SUFFIX_WITHIN) -> kind = MentionKind.DEADLINE
                suffix(SUFFIX_AFTER) -> kind = MentionKind.START
            }
        }
        if (suffix(SUFFIX_REMIND)) kind = MentionKind.REMIND

        fun prefix(regex: Regex): Boolean {
            val m = regex.find(text.substring(0, first)) ?: return false
            first -= m.value.length
            return true
        }

        if (kind == null && prefix(PREFIX_DEADLINE)) kind = MentionKind.DEADLINE
        // "从周三到周五" is a range, not a start; the 从 is just swallowed.
        if (prefix(PREFIX_START) && kind == null && startGroup == null) kind = MentionKind.START
        // "在周五之前": 在 belongs to the time, not to the title.
        prefix(PREFIX_FILLER)
        return Relation(kind ?: MentionKind.AT, before, first..last)
    }

    private class Resolved(val due: Due, val alternatives: List<Due>, val warnings: Set<Warning>)

    private fun resolve(g: Group, kind: MentionKind, before: Boolean, now: LocalDateTime, inherit: Group?): Resolved {
        val warnings = g.warnings.toMutableSet()
        g.exact?.let { t ->
            if (t < now) warnings += Warning.IN_PAST
            return Resolved(Due(t.toLocalDate(), t.toLocalTime()), emptyList(), warnings)
        }

        val datePart = g.date ?: inherit?.date
        val period = g.period ?: inherit?.period?.takeIf { g.clock != null && g.date == null }
        var approximate = datePart?.approximate ?: false
        datePart?.warnings?.let { warnings += it }

        var date: LocalDate? = datePart?.let {
            when (kind) {
                MentionKind.DEADLINE -> if (before) it.before else it.end
                MentionKind.START -> it.after
                else -> it.date
            }
        }

        var time: LocalTime? = null
        var altTime: LocalTime? = null
        var dayShift = 0L
        var ambiguousClock = false
        val clock = g.clock
        when {
            g.fixedTime != null -> time = g.fixedTime
            clock != null -> {
                val ct = clockTime(clock, period)
                time = ct.time
                altTime = ct.alt
                dayShift = ct.dayShift
                ambiguousClock = ct.ambiguous
                if (ct.ambiguous) warnings += Warning.AMBIGUOUS_AM_PM
            }
            period != null -> {
                time = (if (kind == MentionKind.DEADLINE) config.periodEnd else config.periodStart).getValue(period)
                approximate = true
            }
        }

        val today = now.toLocalDate()
        if (date == null) {
            // A time with no day: the next time it comes round.
            // "7点" at 10:00 is probably tonight; "9点" at 10:00 is probably tomorrow morning.
            val t = time ?: config.defaultRemindTime
            val base = today.plusDays(dayShift)
            val candidates = buildList {
                add(base.atTime(t))
                if (ambiguousClock) altTime?.let { add(base.atTime(it)) }
                add(base.plusDays(1).atTime(t))
            }
            val pick = candidates.first { it >= now }
            if (pick.toLocalTime() != t) {
                altTime = t
                time = pick.toLocalTime()
            }
            date = pick.toLocalDate()
            if (date != base) warnings += Warning.ROLLED_TO_TOMORROW
        } else {
            date = date.plusDays(dayShift)
            val alt = altTime
            if (date == today && time != null && alt != null &&
                date.atTime(time) < now && date.atTime(alt) >= now
            ) {
                altTime = time
                time = alt
            }
        }

        if (date.atTime(time ?: LocalTime.MAX) < now) warnings += Warning.IN_PAST
        val alternatives = buildList {
            altTime?.let { add(Due(date, it, approximate)) }
            datePart?.alternatives?.forEach { add(Due(it, time, approximate)) }
        }
        return Resolved(Due(date, time, approximate), alternatives, warnings)
    }

    private class ClockTime(val time: LocalTime, val alt: LocalTime?, val dayShift: Long, val ambiguous: Boolean)

    private fun clockTime(c: Clock, period: Period?): ClockTime {
        val h = c.hour
        val m = c.minute
        fun at(hour: Int) = if (hour >= 24) LocalTime.of(23, 59) else LocalTime.of(hour, m)
        if (period != null) {
            return when (period) {
                Period.DAWN -> ClockTime(at(if (h == 12) 0 else h), null, 0, false)
                Period.EARLY_MORNING, Period.MORNING -> ClockTime(at(h), null, 0, false)
                Period.NOON -> ClockTime(at(if (h in 1..5) h + 12 else h), null, 0, false)
                Period.AFTERNOON, Period.DUSK -> ClockTime(at(if (h in 1..11) h + 12 else h), null, 0, false)
                Period.EVENING -> ClockTime(if (h == 12) LocalTime.of(23, 59) else at(if (h in 1..11) h + 12 else h), null, 0, false)
                Period.NIGHT -> when (h) {
                    in 1..5 -> ClockTime(at(h), null, 1, false)
                    12, 0 -> ClockTime(LocalTime.of(23, 59), null, 0, false)
                    else -> ClockTime(at(if (h < 12) h + 12 else h), null, 0, false)
                }
            }
        }
        if (!c.ambiguous) return ClockTime(at(h), null, 0, false)
        val pm = h < config.bareHourPmBelow
        val preferred = at(if (pm) h + 12 else h)
        val other = at(if (pm) h else h + 12)
        return ClockTime(preferred, other, 0, ambiguous = h in config.bareHourPmBelow..8)
    }

    // --- titles ------------------------------------------------------------

    private fun cleanTitle(text: String, removed: List<IntRange>): String {
        var s = buildString {
            text.forEachIndexed { i, c -> if (removed.none { i in it }) append(c) }
        }
        s = REMIND_PHRASE.replace(s, "")
        var changed = true
        while (changed) {
            changed = false
            s = s.trim { it.isWhitespace() || it in EDGE_PUNCT }
            LEADING_FILLERS.firstOrNull { s.startsWith(it) && s.length > it.length }?.let {
                s = s.substring(it.length)
                changed = true
            }
            val trimmed = s.trimEnd { it in TRAILING_PARTICLES || it.isWhitespace() || it in EDGE_PUNCT }
            if (trimmed != s && trimmed.isNotEmpty()) {
                s = trimmed
                changed = true
            }
        }
        return CJK_SPACE.replace(s, "")
    }

    private fun remindDuration(m: MatchResult): Duration {
        val g = m.groupValues
        val amount = g[1].ifEmpty { g[3] }
        val unit = g[2].ifEmpty { g[4] }
        val halves: Long = when {
            amount == "半" -> 1
            amount.endsWith("个半") -> amount.removeSuffix("个半").toLong() * 2 + 1
            else -> amount.toLong() * 2
        }
        val minutes = when (unit) {
            "分钟", "分" -> 1L
            "小时", "钟头" -> 60L
            "天", "日", "晚" -> 24 * 60L
            else -> 7 * 24 * 60L
        }
        return Duration.ofMinutes(halves * minutes / 2)
    }

    // --- tasks -------------------------------------------------------------

    private class Draft(first: Clause) {
        val clauses = mutableListOf(first)
        val title get() = clauses.map { it.title }.firstOrNull { it.isNotEmpty() } ?: ""
        val hasDue get() = clauses.any { it.hasDue }
        val details = mutableListOf<String>()
    }

    private fun assemble(clauses: List<Clause>): List<ParsedTask> {
        val drafts = mutableListOf<Draft>()
        for (c in clauses) {
            val cur = drafts.lastOrNull()
            when {
                cur == null -> drafts += Draft(c)
                c.raw.strong && c.title.isNotEmpty() && cur.title.isNotEmpty() -> drafts += Draft(c)
                c.title.isEmpty() -> cur.clauses += c
                cur.title.isEmpty() -> cur.clauses += c
                c.hasDue -> drafts += Draft(c)
                c.mentions.isEmpty() -> { cur.clauses += c; cur.details += c.title }
                else -> drafts += Draft(c)
            }
        }
        return drafts.mapNotNull(::toTask)
    }

    private fun toTask(d: Draft): ParsedTask? {
        val mentions = d.clauses.flatMap { it.mentions }
        if (d.title.isEmpty() && mentions.isEmpty()) return null
        fun first(kind: MentionKind) = mentions.firstOrNull { it.kind == kind }
        val deadline = first(MentionKind.DEADLINE)
        val at = first(MentionKind.AT)
        val start = first(MentionKind.START)
        val remind = first(MentionKind.REMIND)
        val main = deadline ?: at ?: start
        // For an event that spans days ("周三到周五出差") the reminder belongs at its start.
        val due = if (main?.kind == MentionKind.AT && main.rangeStart != null) main.rangeStart else main?.due
        val warnings = buildSet {
            main?.let { addAll(it.warnings) }
            remind?.let { addAll(it.warnings) }
            if (d.clauses.any { it.recurring }) add(Warning.RECURRING)
        }
        return ParsedTask(
            title = d.title,
            due = due,
            dueKind = main?.kind,
            remindAt = remind?.due?.let { if (it.time == null) it.copy(time = config.defaultRemindTime) else it },
            remindBefore = d.clauses.firstNotNullOfOrNull { it.remindBefore },
            startAfter = start?.takeIf { it !== main }?.due,
            detail = d.details.joinToString("，").ifEmpty { null },
            source = d.clauses.joinToString("，") { it.raw.text },
            alternatives = main?.alternatives ?: emptyList(),
            warnings = warnings,
            mentions = mentions,
        )
    }

    private companion object {
        const val STRONG_BREAK = "。！!？?；;\n"
        const val WEAK_BREAK = "，,"
        const val EDGE_PUNCT = "，,。.！!？?、；;:：~～-—"
        const val TRAILING_PARTICLES = "吧啊呀哦呢哈嘛哟噢的"

        val CONNECTOR_AT_START = Regex("^(?:还有就是|还有|另外|顺便|此外|再就是|再有|以及|然后|而且)[，,\\s]*")
        val CONNECTOR = Regex("还有|另外|顺便|然后")

        val JOIN_GAP = Regex("[的\\s]*")
        val RANGE_GAP = Regex("\\s*(?:到|至|~|～|-|—)\\s*")

        val SUFFIX_BEFORE = Regex("^\\s*(?:之前|以前|前(?![台面端辈任排方线天\\d]))")
        val SUFFIX_WITHIN = Regex("^\\s*(?:为止|截止|以内|之内|内(?![容部存外]))")
        val SUFFIX_AFTER = Regex("^\\s*(?:之后|以后|后(?![台面续悔边果天])|起(?![床来])|开始)")
        val SUFFIX_REMIND = Regex("^\\s*(?:的时候)?(?:提醒(?:我|一下)?|叫我|喊我|通知我|告诉我)")
        val PREFIX_DEADLINE = Regex(
            "(?:截止(?:时间|日期)?(?:是|为|到|在)?|截至到?|最晚(?:要)?(?:是|在)?|最迟(?:要)?(?:是|在)?|不晚于|不迟于|务必在|必须在)\\s*$",
        )
        val PREFIX_START = Regex("(?:从|自|等到)\\s*$")
        val PREFIX_FILLER = Regex("(?:在|于)\\s*$")

        val REMIND_OFFSET = Regex(
            "(?:提前|早)(\\d+个半|\\d+|半)个?(分钟|分|小时|钟头|天|日|周|星期|礼拜)(?:提醒(?:我|一下)?|通知我?|叫我|喊我|告诉我)?" +
                "|前(\\d+|半)个?(分钟|小时|钟头|天|晚)(?:提醒(?:我|一下)?|通知我?|叫我|喊我|告诉我)",
        )
        val REMIND_PHRASE = Regex("(?:记得|别忘了|别忘记)?(?:提醒(?:我|一下)|叫我一下)")
        val RECURRING = Regex("每(?:天|日|晚|周|星期|礼拜|个?月|年|逢|隔)")

        val LEADING_FILLERS = listOf(
            "帮我记一下", "帮我记下", "帮我记", "记一下", "提醒我", "提醒一下",
            "别忘了", "别忘记", "不要忘了", "记得要", "记得", "记着",
            "我们要", "我要", "我得", "我需要", "我必须", "一定要", "务必", "必须", "需要",
            "要", "得", "请", "帮我", "麻烦", "的", "大概", "大约", "差不多",
        ).sortedByDescending { it.length }

        val CJK_SPACE = Regex("(?<=[\\u4e00-\\u9fff])\\s+(?=[\\u4e00-\\u9fff])")
    }
}
