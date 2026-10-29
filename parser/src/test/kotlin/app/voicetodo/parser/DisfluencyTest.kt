package app.voicetodo.parser

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class DisfluencyTest {
    @TestFactory
    fun corpus(): List<DynamicTest> = listOf(
        // filler sounds
        "嗯，明天下午三点开会" to "明天下午三点开会",
        "呃呃，明天交报告" to "明天交报告",
        "明天嗯下午去银行" to "明天下午去银行",
        "嗯嗯" to "",
        // filler words, only where a pause marks them
        "那个，周五前交报告" to "周五前交报告",
        "那个那个，周五前交报告" to "周五前交报告",
        "就是说，周五之前要把那个方案发给老王" to "周五之前要把那个方案发给老王",
        "啊，对了，明天交水费" to "明天交水费",
        "我想想啊，周四下午去体检" to "周四下午去体检",
        "然后周五去拿快递" to "周五去拿快递",
        "那个报告周五交" to "那个报告周五交",
        "额度申请周五截止" to "额度申请周五截止",
        "这不是重点" to "这不是重点",
        // stutters
        "我我我明天要交报告" to "我明天要交报告",
        "明天明天下午开会" to "明天下午开会",
        "明天，明天下午开会" to "明天下午开会",
        "看看邮件" to "看看邮件",
        // self-corrections
        "明天，不对，后天去医院" to "后天去医院",
        "下午三点，不是，四点开会" to "下午四点开会",
        "周五哦不周六交报告" to "周六交报告",
        "明天下午，不对，后天下午去银行" to "后天下午去银行",
        "给张三，不对，给李四打电话" to "给李四打电话",
        "明天，不，下午三点开会" to "明天下午三点开会",
        "三号，说错了，五号交房租" to "五号交房租",
        // as the recognizer actually writes it: no pauses at all
        "嗯那个明天不对后天下午三点去医院复查" to "后天下午三点去医院复查",
        "下午三点不是四点开会" to "下午四点开会",
        "明天不是周末" to "明天不是周末",
        // restarts and pauses inside a time
        "周四吧，周四下午去体检" to "周四下午去体检",
        "明天，下午三点，去银行" to "明天下午三点，去银行",
        "明天，嗯，下午三点，呃，去银行" to "明天下午三点，去银行",
        // pauses that mean something stay
        "交报告，嗯，还有买菜" to "交报告，还有买菜",
        "周五交报告…呃…还有周一开会" to "周五交报告，还有周一开会",
        "明天跟open ai开会" to "明天跟open ai开会",
    ).map { (input, expected) ->
        DynamicTest.dynamicTest(input) { assertEquals(expected, Disfluency.clean(input).text) }
    }

    @Test
    fun `reports what it removed`() {
        val c = Disfluency.clean("嗯，那个，明天，不对，后天去医院")
        assertEquals("后天去医院", c.text)
        assertEquals(listOf("嗯", "那个"), c.fillers)
        assertEquals(listOf("明天" to "后天"), c.repairs)
    }

    @Test
    fun `protected terms keep their filler-looking characters`() {
        assertEquals("嗯哼文化明天开会", Disfluency.clean("嗯哼文化明天开会", listOf("嗯哼文化")).text)
        assertEquals("哼文化明天开会", Disfluency.clean("嗯哼文化明天开会").text)
    }

    @Test
    fun `cleaned text parses into the intended task`() {
        val now = LocalDateTime.of(2026, 10, 28, 10, 0)
        val text = Disfluency.clean("呃，那个，明天，嗯，下午三点，不对，四点，去银行办卡").text
        val t = TaskParser().parse(text, now).tasks.single()
        assertEquals("去银行办卡", t.title)
        assertEquals("2026-10-29 16:00", t.due.toString())
    }
}
