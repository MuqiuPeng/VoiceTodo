package app.voicetodo.reminder

import app.voicetodo.data.Task
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReminderPolicyTest {
    private val now = LocalDateTime.of(2026, 10, 28, 10, 0)
    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, d, h, m)

    @Test
    fun `timed task fires lead time before and at due`() {
        val t = Task(title = "开会", dueDate = LocalDate.of(2026, 10, 29), dueTime = LocalTime.of(15, 0))
        assertEquals(listOf(at(29, 14, 30), at(29, 15)), ReminderPolicy.points(t))
        assertEquals(at(29, 14, 30), ReminderPolicy.next(t, now))
        assertEquals(at(29, 15), ReminderPolicy.next(t.copy(lastNotified = at(29, 14, 30)), at(29, 14, 30)))
    }

    @Test
    fun `explicit lead replaces the default`() {
        val t = Task(title = "交报告", dueDate = LocalDate.of(2026, 10, 30), dueTime = LocalTime.of(17, 0), remindBefore = Duration.ofDays(1))
        assertEquals(at(29, 17), ReminderPolicy.next(t, now))
    }

    @Test
    fun `date-only task fires the evening before and the morning of`() {
        val t = Task(title = "交房租", dueDate = LocalDate.of(2026, 10, 30))
        assertEquals(listOf(at(29, 20), at(30, 9)), ReminderPolicy.points(t))
    }

    @Test
    fun `a recently missed point fires now, an old one is skipped`() {
        val t = Task(title = "开会", dueDate = LocalDate.of(2026, 10, 28), dueTime = LocalTime.of(10, 20))
        // 09:50 was missed ten minutes ago and never shown.
        assertEquals(now, ReminderPolicy.next(t, now))
        // Once shown, the next is the due time itself.
        assertEquals(at(28, 10, 20), ReminderPolicy.next(t.copy(lastNotified = now), now))
        val old = Task(title = "旧事", dueDate = LocalDate.of(2026, 10, 20), dueTime = LocalTime.of(9, 0))
        assertNull(ReminderPolicy.next(old, now))
    }

    @Test
    fun `done tasks never fire and snooze adds a point`() {
        val t = Task(title = "x", dueDate = LocalDate.of(2026, 10, 29), dueTime = LocalTime.of(15, 0))
        assertNull(ReminderPolicy.next(t.copy(done = true), now))
        val snoozed = t.copy(snoozeUntil = at(28, 11), lastNotified = now)
        assertEquals(at(28, 11), ReminderPolicy.next(snoozed, now))
    }
}
