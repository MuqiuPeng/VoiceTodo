package app.voicetodo.parser

import java.time.LocalDate
import java.time.MonthDay

/**
 * Festival dates. Solar ones are fixed; lunar ones and 清明 come from a table
 * extracted from the Hong Kong Observatory's Gregorian–Lunar conversion
 * tables, because the obvious alternative (ICU's ChineseCalendar) gets a new
 * moon that falls minutes before midnight wrong — 2027's 春节 among them.
 */
internal object Holidays {
    enum class Fest { CHUNJIE, YUANXIAO, DUANWU, QIXI, ZHONGQIU, CHONGYANG, QINGMING }

    // year, then MMdd for: 春节 元宵 端午 七夕 中秋 重阳 清明
    private val TABLE = listOf(
        "2024 0210 0224 0610 0810 0917 1011 0404",
        "2025 0129 0212 0531 0829 1006 1029 0404",
        "2026 0217 0303 0619 0819 0925 1018 0405",
        "2027 0206 0220 0609 0808 0915 1008 0405",
        "2028 0126 0209 0528 0826 1003 1026 0404",
        "2029 0213 0227 0616 0816 0922 1016 0404",
        "2030 0203 0217 0605 0805 0912 1005 0405",
        "2031 0123 0206 0624 0824 1001 1024 0405",
        "2032 0211 0225 0612 0812 0919 1012 0404",
        "2033 0131 0214 0601 0801 0908 1001 0404",
        "2034 0219 0305 0620 0820 0927 1020 0405",
        "2035 0208 0222 0610 0810 0916 1009 0405",
        "2036 0128 0211 0530 0828 1004 1027 0404",
        "2037 0215 0301 0618 0817 0924 1017 0404",
        "2038 0204 0218 0607 0807 0913 1007 0405",
        "2039 0124 0207 0527 0826 1002 1026 0405",
        "2040 0212 0226 0614 0814 0920 1014 0404",
        "2041 0201 0215 0603 0803 0910 1003 0404",
        "2042 0122 0205 0622 0822 0928 1022 0404",
        "2043 0210 0224 0611 0811 0917 1011 0405",
        "2044 0130 0213 0531 0731 1005 1029 0404",
        "2045 0217 0303 0619 0819 0925 1018 0404",
    )

    private val lunar: Map<Int, List<MonthDay>> = TABLE.associate { row ->
        val parts = row.split(' ')
        parts[0].toInt() to parts.drop(1).map {
            MonthDay.of(it.substring(0, 2).toInt(), it.substring(2).toInt())
        }
    }

    val years: IntRange = lunar.keys.min()..lunar.keys.max()

    fun lunarDate(fest: Fest, year: Int): LocalDate? = lunar[year]?.get(fest.ordinal)?.atYear(year)

    /**
     * A named holiday resolved to its next occurrence on or after `today`.
     * `end` is the last day off where there is a public break, so "国庆后"
     * means after the break rather than after October 1st.
     */
    class Occurrence(val date: LocalDate, val end: LocalDate)

    private class Def(val names: List<String>, val resolve: (Int) -> Occurrence?)

    private fun fixed(month: Int, day: Int, days: Long = 1) = { y: Int ->
        LocalDate.of(y, month, day).let { Occurrence(it, it.plusDays(days - 1)) }
    }

    private fun lunarDef(fest: Fest, days: Long = 1, shift: Long = 0) = { y: Int ->
        lunarDate(fest, y)?.plusDays(shift)?.let { Occurrence(it, it.plusDays(days - 1)) }
    }

    // Longest names first so "国庆节" wins over "国庆" in the alternation.
    private val defs = listOf(
        Def(listOf("春节", "过年", "大年初一", "新年"), lunarDef(Fest.CHUNJIE, days = 7)),
        Def(listOf("除夕", "大年三十", "年三十"), lunarDef(Fest.CHUNJIE, shift = -1)),
        Def(listOf("元宵节", "元宵"), lunarDef(Fest.YUANXIAO)),
        Def(listOf("清明节", "清明"), lunarDef(Fest.QINGMING)),
        Def(listOf("端午节", "端午"), lunarDef(Fest.DUANWU)),
        Def(listOf("七夕节", "七夕"), lunarDef(Fest.QIXI)),
        Def(listOf("中秋节", "中秋"), lunarDef(Fest.ZHONGQIU)),
        Def(listOf("重阳节", "重阳"), lunarDef(Fest.CHONGYANG)),
        Def(listOf("元旦"), fixed(1, 1)),
        Def(listOf("情人节"), fixed(2, 14)),
        Def(listOf("妇女节"), fixed(3, 8)),
        Def(listOf("劳动节", "五一假期", "五一长假", "五一"), fixed(5, 1, days = 5)),
        Def(listOf("儿童节"), fixed(6, 1)),
        Def(listOf("国庆节", "国庆假期", "国庆长假", "国庆", "十一假期", "十一长假", "十一"), fixed(10, 1, days = 7)),
        Def(listOf("双十一", "双11"), fixed(11, 11)),
        Def(listOf("双十二", "双12"), fixed(12, 12)),
        Def(listOf("平安夜"), fixed(12, 24)),
        Def(listOf("圣诞节", "圣诞"), fixed(12, 25)),
    )

    private val byName: Map<String, Def> = defs.flatMap { d -> d.names.map { it to d } }.toMap()

    /**
     * Names that are also ordinary words or numbers only count with a time
     * word after them: "五一前" is a holiday, "五一" alone may be a count.
     * "年" alone is 春节 only in "年前" / "年后".
     */
    private val needsRelation = setOf("五一", "十一", "新年")

    val pattern: String = run {
        val names = byName.keys.sortedByDescending { it.length }.joinToString("|") { name ->
            if (name in needsRelation) "$name(?=之?前|以前|之?后|以后|期间)" else name
        }
        "(?:$names|(?<![\\d今明后去前几多半两每])年(?=之?前|以前|之?后|以后))"
    }

    fun resolve(name: String, today: LocalDate): Occurrence? {
        val def = if (name == "年") byName.getValue("春节") else byName[name] ?: return null
        for (y in today.year..today.year + 1) {
            val occ = def.resolve(y) ?: continue
            if (!occ.end.isBefore(today)) return occ
        }
        return null
    }
}
