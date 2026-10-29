package app.voicetodo.parser

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.time.LocalDate
import java.time.Month
import kotlin.test.Test
import kotlin.test.assertEquals

class NormalizerTest {
    @TestFactory
    fun numerals(): List<DynamicTest> = listOf(
        "三点半" to "3点半",
        "两天后" to "2天后",
        "二十三号" to "23号",
        "二〇二七年" to "2027年",
        "十二点" to "12点",
        "一个半小时" to "1个半小时",
        "两三天" to "3天",
        "周三三点" to "周三3点",
        "１０点" to "10点",
        "十点：三十" to "10点:三十",
        // Left alone: not followed by a time unit, or not a number at all.
        "一起" to "一起",
        "统一" to "统一",
        "三个人" to "三个人",
        "快一点" to "快一点",
        "一周年" to "一周年",
        "三年级" to "三年级",
        "周三" to "周三",
    ).map { (input, expected) ->
        DynamicTest.dynamicTest(input) { assertEquals(expected, Normalizer.normalize(input)) }
    }
}

class HolidaysTest {
    @Test
    fun `lunar table matches known dates`() {
        assertEquals(LocalDate.of(2026, Month.FEBRUARY, 17), Holidays.lunarDate(Holidays.Fest.CHUNJIE, 2026))
        // A new moon minutes before midnight: the case ICU gets wrong.
        assertEquals(LocalDate.of(2027, Month.FEBRUARY, 6), Holidays.lunarDate(Holidays.Fest.CHUNJIE, 2027))
        assertEquals(LocalDate.of(2026, Month.SEPTEMBER, 25), Holidays.lunarDate(Holidays.Fest.ZHONGQIU, 2026))
        assertEquals(LocalDate.of(2026, Month.APRIL, 5), Holidays.lunarDate(Holidays.Fest.QINGMING, 2026))
    }

    @Test
    fun `next occurrence rolls over to next year`() {
        val today = LocalDate.of(2026, 10, 28)
        assertEquals(LocalDate.of(2027, 2, 6), Holidays.resolve("春节", today)?.date)
        assertEquals(LocalDate.of(2026, 12, 25), Holidays.resolve("圣诞", today)?.date)
        assertEquals(LocalDate.of(2027, 10, 7), Holidays.resolve("国庆", today)?.end)
    }

    @Test
    fun `table covers the years the app will see`() {
        assert(LocalDate.now().year + 10 in Holidays.years)
    }
}
