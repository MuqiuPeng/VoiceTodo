package app.voicetodo.parser

/**
 * Rewrites speech-recognizer output into one spelling so the rules only have
 * to know one: full-width to half-width, and Chinese numerals to digits —
 * but only where a time unit follows ("三点半" → "3点半", "两天后" → "2天后"),
 * so "一起", "统一" and "三个人" are left alone.
 */
internal object Normalizer {
    private val DIGITS = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '俩' to 2, '三' to 3,
        '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )

    private const val UNIT =
        "(?:年(?!级|纪|代|轻)|月|日|号|点(?!儿|点)|分钟|分(?!之)|秒|刻|天|周(?!年|岁|边|围)|星期|礼拜" +
            "|小时|钟头|个半?(?:小时|月|星期|礼拜|钟头|工作日)|工作日|半(?:小时|个))"

    private val NUMERAL = Regex("[零〇一二两俩三四五六七八九十百千廿卅]+(?=$UNIT)")

    // "快一点", "早一点": 一点 meaning "a little", not one o'clock.
    private val A_LITTLE_BEFORE = setOf('快', '早', '晚', '多', '少', '慢', '好', '大', '小', '有', '再', '差', '稍', '提')

    private val WEEKDAY_PREFIXES = listOf("周", "星期", "礼拜")

    fun normalize(input: String): String {
        val sb = StringBuilder(input.length)
        for (c in input) {
            sb.append(
                when (c) {
                    in '０'..'９' -> '0' + (c - '０')
                    '：' -> ':'
                    '　' -> ' '
                    else -> c
                },
            )
        }
        return NUMERAL.replace(sb.toString()) { m -> convertRun(m, sb) }
    }

    private fun convertRun(m: MatchResult, text: CharSequence): String {
        var run = m.value
        val start = m.range.first
        val before = text.substring(0, start)
        // "周三三点": the first 三 is a weekday, only the rest is a number.
        var keep = ""
        if (WEEKDAY_PREFIXES.any { before.endsWith(it) }) {
            keep = run.take(1)
            run = run.drop(1)
            if (run.isEmpty()) return keep
        }
        if (run == "一" && text.startsWith("点", m.range.last + 1) &&
            start > 0 && text[start - 1] in A_LITTLE_BEFORE
        ) {
            return m.value
        }
        val n = toInt(run) ?: return m.value
        return keep + n
    }

    /** "二十三" → 23, "两" → 2, "二〇二六" → 2026, "两三" → 3 (the later of a range). */
    fun toInt(s: String): Int? {
        if (s.isEmpty()) return null
        if (s.none { it in "十百千廿卅" }) {
            val digits = s.map { DIGITS[it] ?: return null }
            if (digits.size == 1) return digits[0]
            // "二〇二六": read digit by digit; "两三天": a rough range, keep its top.
            return if (digits.size == 4 || digits.contains(0)) digits.joinToString("").toInt() else digits.last()
        }
        var total = 0
        var cur = 0
        for (c in s) {
            when (c) {
                '十' -> { total += (if (cur == 0) 1 else cur) * 10; cur = 0 }
                '百' -> { total += cur * 100; cur = 0 }
                '千' -> { total += cur * 1000; cur = 0 }
                '廿' -> { total += 20; cur = 0 }
                '卅' -> { total += 30; cur = 0 }
                else -> cur = DIGITS[c] ?: return null
            }
        }
        return total + cur
    }
}
