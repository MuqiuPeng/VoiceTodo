package app.voicetodo.parser

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VocabularyTest {
    private fun fix(text: String, vararg terms: Term) = VocabularyCorrector(terms.toList()).correct(text)

    @Test
    fun `pinyin table reads tones away and keeps v`() {
        assertEquals(listOf("teng"), Pinyin.of('腾'))
        assertEquals("nv", Pinyin.of('女')!!.first())
        assertNull(Pinyin.of('3'))
    }

    @Test
    fun `exact homophones are corrected`() {
        val r = fix("明天去疼讯开会", Term("腾讯"))
        assertEquals("明天去腾讯开会", r.text)
        assertEquals(listOf(Correction("疼讯", "腾讯", 3..4)), r.corrections)
    }

    @Test
    fun `names are corrected`() {
        assertEquals("周五把方案发给王晓明", fix("周五把方案发给王小明", Term("王晓明")).text)
        assertEquals("下午跟陈思远开会", fix("下午跟陈思源开会", Term("陈思远")).text)
    }

    @Test
    fun `one accent-level miss is allowed in a three-character term`() {
        // 诗 shi vs 思 si differ only by the sh/s merger.
        assertEquals("下午跟陈思远开会", fix("下午跟陈诗远开会", Term("陈思远")).text)
    }

    @Test
    fun `near misses alone do not rewrite two-character words`() {
        // 心 xin vs 讯 xun: unrelated sounds.
        assertEquals("去疼心开会", fix("去疼心开会", Term("腾讯")).text)
        // 南 nan vs 兰 lan is only an accent merger; on two characters that is too loose.
        assertEquals("兰京见", fix("兰京见", Term("南京")).text)
    }

    @Test
    fun `aliases cover words that do not sound like their spelling`() {
        val r = fix("周五前在虾皮上架新品", Term("Shopee", listOf("虾皮", "秀匹")))
        assertEquals("周五前在Shopee上架新品", r.text)
        assertEquals("虾皮", r.corrections.single().from)
    }

    @Test
    fun `aliases match regardless of letter case`() {
        assertEquals("明天跟OpenAI开会", fix("明天跟openai开会", Term("OpenAI", listOf("openai"))).text)
    }

    @Test
    fun `text already right is left alone`() {
        val r = fix("明天去腾讯开会", Term("腾讯"))
        assertEquals("明天去腾讯开会", r.text)
        assertEquals(emptyList(), r.corrections)
    }

    @Test
    fun `longer terms win over shorter overlapping ones`() {
        assertEquals("去腾讯音乐开会", fix("去疼讯音乐开会", Term("腾讯"), Term("腾讯音乐")).text)
    }

    // --- protection inside the task parser ---

    private val now = LocalDateTime.of(2026, 10, 28, 10, 0)

    @Test
    fun `a protected name is not read as a time`() {
        val plain = TaskParser().parse("去周一科技谈合作", now).tasks.single()
        assertEquals("2026-11-02", plain.due.toString()) // the problem being fixed

        val t = TaskParser().parse("去周一科技谈合作", now, listOf("周一科技")).tasks.single()
        assertEquals("去周一科技谈合作", t.title)
        assertNull(t.due)
    }

    @Test
    fun `protected terms keep their spelling and real times still work`() {
        val t = TaskParser().parse("明天下午三点去三点水工作室拍照", now, listOf("三点水工作室")).tasks.single()
        assertEquals("去三点水工作室拍照", t.title)
        assertEquals("2026-10-29 15:00", t.due.toString())
    }

    // --- learning from edits ---

    @Test
    fun `a corrected name is offered with what was heard`() {
        assertEquals(Term("腾讯", listOf("疼讯")), suggestTerm("去疼讯开会", "去腾讯开会"))
        // Only the differing part and one neighbour are known; the card lets the user widen it.
        assertEquals(Term("晓明", listOf("小明")), suggestTerm("发给王小明", "发给王晓明"))
        assertEquals(Term("Shopee", listOf("虾皮")), suggestTerm("在虾皮上架", "在Shopee上架"))
    }

    @Test
    fun `edits that are not name fixes are ignored`() {
        assertNull(suggestTerm("开会", "开会"))
        assertNull(suggestTerm("交报告", "交季度报告")) // only added
        assertNull(suggestTerm("交季度报告", "交报告")) // only removed
        assertNull(suggestTerm("买菜", "去银行办理信用卡")) // rewritten
    }
}
