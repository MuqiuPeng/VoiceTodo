package app.voicetodo.parser

/**
 * What was taken out of an utterance, for the confirmation card.
 * `repairs` are self-corrections: "明天，不对，后天" → ("明天", "后天").
 */
data class Cleaned(
    val text: String,
    val fillers: List<String> = emptyList(),
    val repairs: List<Pair<String, String>> = emptyList(),
) {
    val changed: Boolean get() = fillers.isNotEmpty() || repairs.isNotEmpty()
}

/**
 * Removes the hesitations people say while dictating, before the task parser
 * sees the text: filler sounds (嗯, 呃), filler words (那个, 就是说) where they
 * carry no meaning, stutters (我我我, 明天明天), and self-corrections
 * ("周五，哦不，周六" keeps 周六).
 *
 * It errs towards leaving text alone. A word that is sometimes filler and
 * sometimes meaning ("那个方案", "这不是重点", "额度") is only removed where
 * the pause around it shows it was filler.
 */
object Disfluency {
    // A time word, the unit self-corrections and pauses are reasoned about in.
    private const val NUM = "[0-9零一二两三四五六七八九十]{1,3}"
    private const val DAY = "(?:大大后天|大后天|后天|明天|今天|今晚|明早|明晚|(?:这个?|本|下下个?|下个?)?(?:周|星期|礼拜)[一二三四五六日天1-7]|$NUM[号日]|${NUM}月$NUM[号日]?)"
    private const val PERIOD = "(?:凌晨|早上|早晨|上午|中午|下午|傍晚|晚上)"
    private const val CLOCK = "(?:${NUM}点(?:半|${NUM}分?|[一二三1-3]刻)?|\\d{1,2}:\\d{2})"
    private val day = Regex(DAY)
    private val period = Regex(PERIOD)
    private val clock = Regex(CLOCK)
    private const val TIME = "(?:$DAY|$PERIOD|$CLOCK)"

    private enum class Kind { DAY, PERIOD, CLOCK }

    private fun kindAtStart(s: String): Pair<Kind, Int>? {
        for ((kind, r) in listOf(Kind.DAY to day, Kind.PERIOD to period, Kind.CLOCK to clock)) {
            val m = r.matchAt(s, 0) ?: continue
            return kind to m.value.length
        }
        return null
    }

    /** The time words a piece of text ends with, in order: "明天下午三点" → day, period, clock. */
    private fun trailingTokens(s: String): List<Pair<Kind, Int>> {
        val out = mutableListOf<Pair<Kind, Int>>()
        var end = s.length
        outer@ while (end > 0) {
            for (start in maxOf(0, end - 8) until end) {
                val piece = s.substring(start, end)
                val k = kindAtStart(piece)
                if (k != null && k.second == piece.length) {
                    out.add(0, k.first to start)
                    end = start
                    continue@outer
                }
            }
            break
        }
        return out
    }

    private const val PAUSE = "[，,、…\\s]"

    // Sounds that are never words.
    private val pureFiller = Regex("[嗯呃唔呣]+")

    // Sounds that are filler only when a pause sets them apart: "额，" but not "额度".
    private val pausedSound = Regex("(?<=^|$PAUSE)[额啊哦噢诶欸哎呀]+(?=$PAUSE|$)")

    // Words that are filler when followed by a pause, or open the utterance.
    private val pausedWord = Regex(
        "(?<=^|$PAUSE)(?:那个|这个|就是说|就是|那么|怎么说呢|我想想啊?|让我想想|你知道吧?|对吧|对了|反正|嗯哼)(?=$PAUSE|$)",
    )
    private val openingWord = Regex("^(?:那个|这个|就是说|就是|然后呢|然后|那么|对了)(?=$TIME|$PAUSE)")

    // A correction marker. Bare negations need a pause beside them, or "这不是重点" would be cut.
    private val repairMarker = Regex(
        "$PAUSE*(?:" +
            "(?:哦|噢|呃|额|啊)不(?:对|是)?|说错了|不对不对|" +
            "(?<=$PAUSE)(?:不对|不是|不不|不|我是说|应该是|改成)(?=$PAUSE|$TIME)" +
            ")$PAUSE*",
    )

    private val bareMarker = Regex("不对|不是|我是说|应该是")

    private val stutterWord = Regex("([\\u4e00-\\u9fff]{2,4})(?:$PAUSE*\\1)+")
    private val stutterChar = Regex("([\\u4e00-\\u9fff])\\1{2,}")

    fun clean(text: String, protectedTerms: Collection<String> = emptyList()): Cleaned {
        val shield = Shield(protectedTerms)
        var s = shield.hide(text).replace("...", "…")
        val fillers = mutableListOf<String>()
        val repairs = mutableListOf<Pair<String, String>>()

        fun strip(r: Regex) {
            // Dropped outright: a pause around it stays a pause, none is invented.
            s = r.replace(s) { m -> fillers += m.value; "" }
        }

        fun stripFillers() {
            strip(pureFiller)
            strip(pausedSound)
            strip(pausedWord)
            s = tidy(s)
            openingWord.find(s)?.let { fillers += it.value; s = tidy(s.substring(it.value.length)) }
        }

        // Fillers, then stutters, then fillers again: a filler can hide a
        // stutter ("明天，呃，明天下午") and a stutter can hide a filler ("那个那个，").
        stripFillers()
        s = stutterWord.replace(s) { it.groupValues[1] }
        s = stutterChar.replace(s) { it.groupValues[1] }
        stripFillers()

        // Recognizers often drop the pauses: "明天不对后天". A bare negation
        // between two times of the same kind is a correction too; with no
        // time on either side ("这不是重点") it is just a negation.
        s = bareMarker.replace(s) { m ->
            val before = s.substring(0, m.range.first)
            val after = s.substring(m.range.last + 1)
            val tail = trailingTokens(before).lastOrNull()?.first
            val head = kindAtStart(after)?.first
            if (tail != null && tail == head) "，${m.value}，" else m.value
        }

        // Self-corrections, left to right.
        var from = 0
        while (true) {
            val m = repairMarker.find(s, from) ?: break
            val before = s.substring(0, m.range.first)
            val after = s.substring(m.range.last + 1)
            if (before.isBlank() || after.isBlank()) {
                from = m.range.last + 1
                continue
            }
            val cut = reparandumStart(before, after)
            if (cut < before.length) repairs += before.substring(cut).trim() to replacementHead(after)
            s = before.substring(0, cut) + after
            from = cut
        }

        // "周四吧，周四下午去" restarts the time; "明天，下午三点" is one time read with a pause.
        s = joinTimes(s)
        s = tidy(s)
        return Cleaned(shield.restore(s), fillers.map(String::trim).filter { it.isNotEmpty() }.map(shield::restore), repairs)
    }

    /**
     * Where the corrected part begins. A time correction replaces the latest
     * time word of the same kind ("下午三点，不是，四点" keeps 下午); otherwise
     * the replacement is aligned on its first character ("给张三，不对，给李四").
     * Anything less certain removes only the marker.
     */
    private fun reparandumStart(before: String, after: String): Int {
        val head = kindAtStart(after)
        if (head != null) {
            val tokens = trailingTokens(before)
            return tokens.lastOrNull { it.first == head.first }?.second ?: before.length
        }
        val i = before.lastIndexOf(after[0])
        val boundary = before.indexOfLast { it in "。！？!?；;" }
        return if (i > boundary && before.length - i <= after.length + 4) i else before.length
    }

    private fun replacementHead(after: String): String =
        kindAtStart(after)?.let { after.substring(0, it.second) } ?: after.take(4)

    /**
     * A clause that is only a time, followed by a clause starting with one, is
     * either a restart ("周四吧，周四下午去" keeps the second) or one time read
     * with a pause ("明天，下午三点，去银行" becomes "明天下午三点，去银行").
     * Left split, the parser would see two times for one task.
     */
    private fun joinTimes(text: String): String {
        val out = mutableListOf<String>()
        for (part in text.split('，')) {
            val prev = out.lastOrNull()
            val prevCore = prev?.trimEnd('吧', '啊', '呢', '嘛')
            val prevTokens = prevCore?.let(::trailingTokens)
            val head = kindAtStart(part)
            if (prevCore != null && prevTokens != null && prevTokens.firstOrNull()?.second == 0 && head != null) {
                out[out.lastIndex] = if (prevTokens.first().first == head.first) part else prevCore + part
            } else {
                out += part
            }
        }
        return out.joinToString("，")
    }

    /** One comma where a pause was; none at the ends or before a full stop. Spaces between Latin words stay. */
    private fun tidy(text: String): String = text
        .replace("…", "，")
        .replace(Regex("\\s*[，,]+(?:\\s*[，,]+)*\\s*"), "，")
        .replace(Regex("^[，,\\s]+"), "")
        .replace(Regex("[，,\\s]+(?=[。！？!?；;]|$)"), "")
}
