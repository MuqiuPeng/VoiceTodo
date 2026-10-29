package app.voicetodo.parser

import app.voicetodo.parser.MentionKind.AT
import app.voicetodo.parser.MentionKind.DEADLINE
import app.voicetodo.parser.MentionKind.START
import app.voicetodo.parser.Warning.AMBIGUOUS_AM_PM
import app.voicetodo.parser.Warning.AMBIGUOUS_NEXT_WEEK
import app.voicetodo.parser.Warning.RECURRING
import app.voicetodo.parser.Warning.ROLLED_TO_TOMORROW
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.time.Duration
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/** "Now" is Wednesday 2026-10-28 10:00, as in the Chinese corpus. */
class EnglishParserTest {
    private val now = LocalDateTime.of(2026, 10, 28, 10, 0)
    private val parser = EnglishParser()

    private class E(
        val title: String,
        val due: String?,
        val kind: MentionKind? = null,
        val warnings: Set<Warning>? = null,
        val alt: String? = null,
        val remindBefore: Duration? = null,
    )

    private val cases = listOf(
        "Call Bob tomorrow at 3pm" to listOf(E("Call Bob", "2026-10-29 15:00", AT)),
        "Submit the report by Friday" to listOf(E("Submit the report", "2026-10-30", DEADLINE)),
        "Dentist next Tuesday afternoon" to listOf(E("Dentist", "2026-11-03 15:00", warnings = emptySet())),
        "Meeting next Friday at 10" to listOf(E("Meeting", "2026-11-06 10:00", warnings = setOf(AMBIGUOUS_NEXT_WEEK), alt = "2026-10-30 10:00")),
        "Pay rent before Nov 1" to listOf(E("Pay rent", "2026-10-31", DEADLINE)),
        "Buy milk and eggs" to listOf(E("Buy milk and eggs", null)),
        "Call mom tonight" to listOf(E("Call mom", "2026-10-28 20:00")),
        "Pick up the package in 2 hours" to listOf(E("Pick up the package", "2026-10-28 12:00")),
        "Finish the slides within 3 days" to listOf(E("Finish the slides", "2026-10-31", DEADLINE)),
        "Lunch with Alice at noon on Saturday" to listOf(E("Lunch with Alice", "2026-10-31 12:00")),
        "Remind me to call the bank at 9am" to listOf(E("Call the bank", "2026-10-29 09:00", warnings = setOf(ROLLED_TO_TOMORROW))),
        "Team meeting on Dec 3rd at 2:30pm, remind me 30 minutes before" to
            listOf(E("Team meeting", "2026-12-03 14:30", remindBefore = Duration.ofMinutes(30))),
        "Send the invoice by end of month" to listOf(E("Send the invoice", "2026-10-31", DEADLINE)),
        "Call Bob tomorrow at 3pm and email Alice on Friday" to listOf(E("Call Bob", "2026-10-29 15:00"), E("Email Alice", "2026-10-30")),
        "Water the plants every morning" to listOf(E("Water the plants", "2026-10-29 09:00", warnings = setOf(RECURRING, ROLLED_TO_TOMORROW))),
        "Doctor appointment on the 5th at 4:15pm" to listOf(E("Doctor appointment", "2026-11-05 16:15")),
        "Renew passport by 12/15" to listOf(E("Renew passport", "2026-12-15", DEADLINE)),
        "Book flights this weekend" to listOf(E("Book flights", "2026-10-31")),
        "Call the bank on Mon" to listOf(E("Call the bank", "2026-11-02")),
        "I sat down with the team" to listOf(E("I sat down with the team", null)),
        "Order 3 pizzas for the party at 7" to listOf(E("Order 3 pizzas for the party", "2026-10-28 19:00", warnings = setOf(AMBIGUOUS_AM_PM))),
        "Call 腾讯 tomorrow at 3pm" to listOf(E("Call 腾讯", "2026-10-29 15:00")),
        "Pay the electricity bill in a couple of days" to listOf(E("Pay the electricity bill", "2026-10-30")),
        "Gym after work" to listOf(E("Gym", "2026-10-28 18:00", START)),
        "Finish report by EOD" to listOf(E("Finish report", "2026-10-28 18:00", DEADLINE)),
        "Halloween party" to listOf(E("Halloween party", "2026-10-31")),
        "Tomorrow, call Bob" to listOf(E("Call Bob", "2026-10-29")),
        "I need to renew my license by the end of the year" to listOf(E("Renew my license", "2026-12-31", DEADLINE)),
        "Flight on November 20th at 6:45 am" to listOf(E("Flight", "2026-11-20 06:45")),
        "Dinner with Sam the day after tomorrow at 7pm" to listOf(E("Dinner with Sam", "2026-10-30 19:00")),
        "Quarterly review next week" to listOf(E("Quarterly review", "2026-11-06")),
        "Call the plumber at half past four" to listOf(E("Call the plumber", "2026-10-28 16:30")),
        "Dentist on Thursday, remind me the day before" to listOf(E("Dentist", "2026-10-29", remindBefore = Duration.ofDays(1))),
        // As SenseVoice actually writes English: capitals, numbers as words, no punctuation.
        "CALL BOB TOMORROW AT THREE PM AND REMIND ME THIRTY MINUTES BEFORE" to
            // Capitals carry no hint that Bob is a name; the vocabulary is how to fix that.
            listOf(E("Call bob", "2026-10-29 15:00", remindBefore = Duration.ofMinutes(30))),
        "Call Bob tomorrow and remind me sometime earlier" to listOf(E("Call Bob", "2026-10-29")),
        "SUBMIT THE QUARTERLY REPORT BY FRIDAY" to listOf(E("Submit the quarterly report", "2026-10-30", DEADLINE)),
        "MEETING AT THREE THIRTY PM" to listOf(E("Meeting", "2026-10-28 15:30")),
        "I NEED TO CALL THE BANK IN FORTY FIVE MINUTES" to listOf(E("Call the bank", "2026-10-28 10:45")),
    )

    @TestFactory
    fun corpus(): List<DynamicTest> = cases.map { (input, expected) ->
        DynamicTest.dynamicTest(input) {
            val got = parser.parse(input, now).tasks
            if (got.size != expected.size) fail("expected ${expected.size} task(s), got ${got.map { it.title to it.due }}")
            expected.zip(got).forEach { (e, t) ->
                val ctx = "task=$t"
                assertEquals(e.title, t.title, "title; $ctx")
                assertEquals(e.due, t.due?.toString(), "due; $ctx")
                e.kind?.let { assertEquals(it, t.dueKind, "kind; $ctx") }
                e.warnings?.let { assertEquals(it, t.warnings, "warnings; $ctx") }
                e.alt?.let { a -> if (t.alternatives.none { it.toString() == a }) fail("missing alternative $a; $ctx") }
                e.remindBefore?.let { assertEquals(it, t.remindBefore, "remindBefore; $ctx") }
            }
        }
    }

    @Test
    fun `language routing`() {
        assertTrue(looksEnglish("call Bob tomorrow"))
        assertTrue(looksEnglish("call 腾讯 tomorrow at 3pm"))
        assertFalse(looksEnglish("明天跟OpenAI开会"))
        assertFalse(looksEnglish("明天下午三点开会"))
    }

    @TestFactory
    fun disfluency(): List<DynamicTest> = listOf(
        "Um, call the plumber, uh, tomorrow morning" to "call the plumber, tomorrow morning",
        "Dinner with Sam on Friday, no wait, Saturday" to "Dinner with Sam on Saturday",
        "Meeting at 3, sorry, 4pm" to "Meeting at 4pm",
        "So, you know, call call Bob tomorrow" to "call Bob tomorrow",
        "Buy milk" to "Buy milk",
    ).map { (input, expected) ->
        DynamicTest.dynamicTest(input) { assertEquals(expected, EnglishDisfluency.clean(input).text) }
    }

    @Test
    fun `cleaned English parses into the intended task`() {
        val text = EnglishDisfluency.clean("Um, dinner with Sam on Friday, no wait, Saturday at 7pm").text
        val t = parser.parse(text, now).tasks.single()
        assertEquals("Dinner with Sam", t.title)
        assertEquals("2026-10-31 19:00", t.due.toString())
    }
}
