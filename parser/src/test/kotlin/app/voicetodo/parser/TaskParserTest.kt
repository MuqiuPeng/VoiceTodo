package app.voicetodo.parser

import app.voicetodo.parser.MentionKind.AT
import app.voicetodo.parser.MentionKind.DEADLINE
import app.voicetodo.parser.MentionKind.REMIND
import app.voicetodo.parser.MentionKind.START
import app.voicetodo.parser.Warning.AMBIGUOUS_AM_PM
import app.voicetodo.parser.Warning.INVALID_DATE
import app.voicetodo.parser.Warning.IN_PAST
import app.voicetodo.parser.Warning.RECURRING
import app.voicetodo.parser.Warning.ROLLED_TO_TOMORROW
import app.voicetodo.parser.Warning.WEEKDAY_MISMATCH
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.time.Duration
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The colloquial corpus. Unless a case says otherwise "now" is
 * Wednesday 2026-10-28 10:00, so:
 *   this week  Mon 10-26 … Wed 10-28 (today) … Fri 10-30, Sat 10-31, Sun 11-01
 *   next week  Mon 11-02, Tue 11-03, Wed 11-04, Thu 11-05, Fri 11-06
 *
 * When a real utterance is parsed wrong, add it here first.
 */
class TaskParserTest {
    private val wednesday = LocalDateTime.of(2026, 10, 28, 10, 0)
    private val monday = LocalDateTime.of(2026, 10, 26, 9, 0)
    private val friday = LocalDateTime.of(2026, 10, 30, 10, 0)
    private val parser = TaskParser()

    private class Expect(
        val title: String,
        val due: String?,
        val kind: MentionKind? = null,
        val warnings: Set<Warning>? = null,
        val alt: String? = null,
        val remindAt: String? = null,
        val remindBefore: Duration? = null,
        val startAfter: String? = null,
        val detail: String? = null,
    )

    private class Case(val input: String, val now: LocalDateTime, val tasks: List<Expect>)

    private val cases = mutableListOf<Case>()

    private fun case(
        input: String,
        title: String,
        due: String?,
        kind: MentionKind? = null,
        warnings: Set<Warning>? = null,
        alt: String? = null,
        remindAt: String? = null,
        remindBefore: Duration? = null,
        startAfter: String? = null,
        detail: String? = null,
        now: LocalDateTime = wednesday,
    ) {
        cases += Case(input, now, listOf(Expect(title, due, kind, warnings, alt, remindAt, remindBefore, startAfter, detail)))
    }

    private fun multi(input: String, vararg tasks: Expect, now: LocalDateTime = wednesday) {
        cases += Case(input, now, tasks.toList())
    }

    init {
        // --- relative days ---
        case("明天交报告", "交报告", "2026-10-29", AT)
        case("后天去银行办卡", "去银行办卡", "2026-10-30")
        case("大后天体检", "体检", "2026-10-31")
        case("今天下午三点开会", "开会", "2026-10-28 15:00")
        case("明天上午十点半面试", "面试", "2026-10-29 10:30")
        case("今晚八点给妈妈打电话", "给妈妈打电话", "2026-10-28 20:00")
        case("明早九点前把方案发给老王", "把方案发给老王", "2026-10-29 09:00", DEADLINE)
        case("明天早上八点提醒我交报告", "交报告", null, remindAt = "2026-10-29 08:00")
        case("今晚整理行李", "整理行李", "2026-10-28 20:00")

        // --- weekdays ---
        case("周五前交报告", "交报告", "2026-10-30", DEADLINE)
        case("下周三下午三点前提交方案", "提交方案", "2026-11-04 15:00", DEADLINE, warnings = emptySet())
        case("下周一开周会", "开周会", "2026-11-02")
        case("这周六去超市", "去超市", "2026-10-31")
        case("周日之前把房租交了", "把房租交了", "2026-11-01", DEADLINE)
        case("星期四下午两点看牙", "看牙", "2026-10-29 14:00")
        case("礼拜五晚上聚餐", "聚餐", "2026-10-30 20:00")
        case("周三交作业", "交作业", "2026-10-28")
        case("周三三点开会", "开会", "2026-10-28 15:00")
        // 下周X is next calendar week's X, never this week's, however early in the week.
        case("下周三交报告", "交报告", "2026-11-04", warnings = emptySet(), now = monday)
        case("下周六去参加一个lecture", "去参加一个lecture", "2026-11-07", warnings = emptySet(), now = friday)
        case("下个周日爬山", "爬山", "2026-11-08", warnings = emptySet(), now = friday)
        case("这周六去参加一个lecture", "去参加一个lecture", "2026-10-31", now = friday)
        case("下下周五前提交申请", "提交申请", "2026-11-13", DEADLINE)
        case("这周内把合同签了", "把合同签了", "2026-10-30", DEADLINE)
        case("下周把PPT做完", "把PPT做完", "2026-11-06")
        case("这个周末大扫除", "大扫除", "2026-10-31")
        case("周末前把报销单交了", "把报销单交了", "2026-10-30", DEADLINE)
        case("11月4号周三开会", "开会", "2026-11-04", warnings = emptySet())
        case("11月5号周三开会", "开会", "2026-11-05", warnings = setOf(WEEKDAY_MISMATCH))
        case("上周五的会议纪要", "会议纪要", "2026-10-23", warnings = setOf(IN_PAST))

        // --- calendar dates ---
        case("11月5号提交材料", "提交材料", "2026-11-05")
        case("11月5日下午4点开会", "开会", "2026-11-05 16:00")
        case("12月1号之前续费", "续费", "2026-12-01", DEADLINE)
        case("3月15号交税", "交税", "2027-03-15")
        case("2027年1月10日考试", "考试", "2027-01-10")
        case("十一月二十号还信用卡", "还信用卡", "2026-11-20")
        case("30号前交房租", "交房租", "2026-10-30", DEADLINE)
        case("5号交水电费", "交水电费", "2026-11-05")
        case("11.15前提交", "提交", "2026-11-15", DEADLINE)
        case("下个月5号体检", "体检", "2026-11-05")
        case("月底前交月报", "交月报", "2026-10-31", DEADLINE)
        case("下个月底前完成项目", "完成项目", "2026-11-30", DEADLINE)
        case("月初交房租", "交房租", "2026-11-01")
        case("12月中旬出差", "出差", "2026-12-15")
        case("年底前完成年度总结", "完成年度总结", "2026-12-31", DEADLINE)
        case("11月前把签证办好", "把签证办好", "2026-10-31", DEADLINE)
        case("明年三月份考驾照", "考驾照", "2027-03-01")
        // An impossible date still yields a task, flagged, so the card can ask.
        case("2月30号交", "交", "2026-10-28", warnings = setOf(INVALID_DATE))

        // --- offsets ---
        case("三天后复查", "复查", "2026-10-31")
        case("两周内完成测试", "完成测试", "2026-11-11", DEADLINE)
        case("半小时后给客户回电话", "给客户回电话", "2026-10-28 10:30")
        case("两个小时以后开会", "开会", "2026-10-28 12:00")
        case("一个半小时后出发", "出发", "2026-10-28 11:30")
        case("十分钟后提醒我关火", "关火", null, remindAt = "2026-10-28 10:10")
        case("一个月后复诊", "复诊", "2026-11-28")
        case("半个月内把书看完", "把书看完", "2026-11-12", DEADLINE)
        case("过两天去取快递", "去取快递", "2026-10-30")
        case("这两天把发票寄了", "把发票寄了", "2026-10-29", DEADLINE)
        case("三个工作日内回复", "回复", "2026-11-02", DEADLINE)

        // --- clock times ---
        case("下午3点15分开会", "开会", "2026-10-28 15:15")
        case("3点开会", "开会", "2026-10-28 15:00")
        case("9点开会", "开会", "2026-10-29 09:00", warnings = setOf(ROLLED_TO_TOMORROW))
        case("7点吃饭", "吃饭", "2026-10-28 19:00", warnings = setOf(AMBIGUOUS_AM_PM))
        case("明天7点出发", "出发", "2026-10-29 07:00", warnings = setOf(AMBIGUOUS_AM_PM), alt = "2026-10-29 19:00")
        case("今天9点交", "交", "2026-10-28 21:00", alt = "2026-10-28 09:00")
        case("15:30开会", "开会", "2026-10-28 15:30")
        case("明天下午3点到5点开会", "开会", "2026-10-29 15:00")
        case("差五分三点到会议室", "到会议室", "2026-10-28 14:55")
        case("晚上十二点前提交", "提交", "2026-10-28 23:59", DEADLINE)
        case("半夜两点看球赛", "看球赛", "2026-10-29 02:00")
        case("3pm给客户打电话", "给客户打电话", "2026-10-28 15:00")
        case("明天10点之前回复邮件", "回复邮件", "2026-10-29 10:00", DEADLINE)
        case("四点一刻接孩子", "接孩子", "2026-10-28 16:15")

        // --- named moments and parts of day ---
        case("下班前把日报发了", "把日报发了", "2026-10-28 18:00", DEADLINE)
        case("明天下班前交周报", "交周报", "2026-10-29 18:00", DEADLINE)
        case("睡前吃药", "吃药", "2026-10-28 22:30", DEADLINE)
        case("下班后去健身房", "去健身房", "2026-10-28 18:00", START)
        case("明天上午之前把合同发过去", "把合同发过去", "2026-10-29 12:00", DEADLINE)
        case("明天下午去医院", "去医院", "2026-10-29 15:00")

        // --- holidays ---
        case("春节前回老家", "回老家", "2027-02-05", DEADLINE)
        case("元旦前把年终总结写完", "把年终总结写完", "2026-12-31", DEADLINE)
        case("圣诞节去看展", "去看展", "2026-12-25")
        case("双十一前买电脑", "买电脑", "2026-11-10", DEADLINE)
        case("年前把账结了", "把账结了", "2027-02-05", DEADLINE)
        case("中秋节前寄月饼", "寄月饼", "2027-09-14", DEADLINE)
        case("清明回家扫墓", "回家扫墓", "2027-04-05")
        case("国庆后去旅游", "去旅游", "2027-10-08", START)

        // --- reminders ---
        case("明天下午三点开会，提前半小时提醒我", "开会", "2026-10-29 15:00", remindBefore = Duration.ofMinutes(30))
        case("周五交报告前一天提醒我", "交报告", "2026-10-30", remindBefore = Duration.ofDays(1))
        case("提前一天提醒我下周二交房租", "交房租", "2026-11-03", remindBefore = Duration.ofDays(1))
        case("提醒我明天交电费", "交电费", "2026-10-29", AT)

        // --- relation words around the time ---
        case("截止到下周五提交申请", "提交申请", "2026-11-06", DEADLINE)
        case("最晚周四要把稿子给编辑", "把稿子给编辑", "2026-10-29", DEADLINE)
        case("在周五之前完成", "完成", "2026-10-30", DEADLINE)
        case("交报告的截止时间是周五", "交报告", "2026-10-30", DEADLINE)
        case("周五以后再联系客户", "再联系客户", "2026-10-30", START)
        case("从周三到周五出差", "出差", "2026-10-28", AT)
        case("明天起开始早睡", "开始早睡", "2026-10-29", START)

        // --- fillers ---
        case("帮我记一下明天交报告", "交报告", "2026-10-29")
        case("别忘了周五去拿快递", "去拿快递", "2026-10-30")
        case("我明天下午三点要去开会", "去开会", "2026-10-29 15:00")
        case("记得周五之前要把报告交给老板哦", "把报告交给老板", "2026-10-30", DEADLINE)
        case("大概下午四五点去接孩子", "去接孩子", "2026-10-28 17:00")
        case("一会儿去取快递", "去取快递", "2026-10-28 10:30")

        // --- longer, real-sounding utterances ---
        case(
            "下周五下午五点之前把季度报告发给张总，提前一天提醒我", "把季度报告发给张总", "2026-11-06 17:00", DEADLINE,
            warnings = emptySet(), remindBefore = Duration.ofDays(1),
        )
        case("明天上午十点去医院复查，记得带病历本", "去医院复查", "2026-10-29 10:00", detail = "带病历本")
        case("后天晚上七点跟小李吃饭", "跟小李吃饭", "2026-10-30 19:00", warnings = emptySet())
        case("这个月二十号之前交物业费", "交物业费", "2026-10-20", DEADLINE, warnings = setOf(IN_PAST))
        case("下礼拜二上午开项目评审会", "开项目评审会", "2026-11-03 09:00")
        case("明天中午十二点半跟客户吃饭", "跟客户吃饭", "2026-10-29 12:30")
        case("十二月三十一号前续签合同", "续签合同", "2026-12-31", DEADLINE)
        case("下周末带孩子去公园", "带孩子去公园", "2026-11-07")
        multi(
            "周四之前把代码合进去然后周五发版",
            Expect("把代码合进去", "2026-10-29", DEADLINE),
            Expect("发版", "2026-10-30", AT),
        )

        // --- not times at all ---
        case("快一点把报告写完", "快一点把报告写完", null)
        case("去1号楼开会", "去1号楼开会", null)
        case("统一一下格式", "统一一下格式", null)
        case("我还有事", "我还有事", null)
        case("前台拿快递", "前台拿快递", null)
        case("庆祝一周年", "庆祝一周年", null)
        case("三年级家长会明天下午", "三年级家长会", "2026-10-29 15:00")
        case("整理三点建议", "整理3点建议", null)

        // --- repeating ---
        case("每天早上八点跑步", "每天跑步", "2026-10-29 08:00", warnings = setOf(RECURRING, ROLLED_TO_TOMORROW))

        // --- in the past ---
        case("今天早上八点交", "交", "2026-10-28 08:00", warnings = setOf(IN_PAST))

        // --- several tasks in one breath ---
        multi(
            "周五交报告，还有下周一前报销发票",
            Expect("交报告", "2026-10-30", AT),
            Expect("报销发票", "2026-11-02", DEADLINE),
        )
        multi("明天去银行，后天去医院", Expect("去银行", "2026-10-29"), Expect("去医院", "2026-10-30"))
        multi("周五交报告还有下周一报发票", Expect("交报告", "2026-10-30"), Expect("报发票", "2026-11-02"))
        multi("买牛奶。明天交水费", Expect("买牛奶", null), Expect("交水费", "2026-10-29"))
        multi("明天要去银行，记得带身份证", Expect("去银行", "2026-10-29", detail = "带身份证"))
        multi("交报告，周五之前", Expect("交报告", "2026-10-30", DEADLINE))
        multi("明天下午三点，去银行办卡", Expect("去银行办卡", "2026-10-29 15:00"))
    }

    @TestFactory
    fun corpus(): List<DynamicTest> = cases.map { c ->
        DynamicTest.dynamicTest(c.input) {
            val result = parser.parse(c.input, c.now)
            val got = result.tasks
            if (got.size != c.tasks.size) {
                fail("expected ${c.tasks.size} task(s), got ${got.size}: ${got.map { it.title to it.due }} (normalized: ${result.normalized})")
            }
            c.tasks.zip(got).forEach { (e, t) -> check(e, t) }
        }
    }

    private fun check(e: Expect, t: ParsedTask) {
        val context = "task=$t"
        assertEquals(e.title, t.title, "title; $context")
        assertEquals(e.due, t.due?.toString(), "due; $context")
        e.kind?.let { assertEquals(it, t.dueKind, "kind; $context") }
        e.warnings?.let { assertEquals(it, t.warnings, "warnings; $context") }
        e.alt?.let { alt -> if (t.alternatives.none { it.toString() == alt }) fail("missing alternative $alt; $context") }
        e.remindAt?.let { assertEquals(it, t.remindAt?.toString(), "remindAt; $context") }
        e.remindBefore?.let { assertEquals(it, t.remindBefore, "remindBefore; $context") }
        e.startAfter?.let { assertEquals(it, t.startAfter?.toString(), "startAfter; $context") }
        e.detail?.let { assertEquals(it, t.detail, "detail; $context") }
    }
}
