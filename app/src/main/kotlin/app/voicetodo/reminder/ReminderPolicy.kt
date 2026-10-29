package app.voicetodo.reminder

import app.voicetodo.data.Task
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * When a task should speak up. Pure, so it is tested without a device.
 *
 * Every reminder point is computed from the task, and only the next one is
 * ever scheduled; firing it schedules the one after. That keeps one alarm per
 * task and makes rescheduling after a reboot the same code path as normal.
 */
object ReminderPolicy {
    /** Before a timed task when the user did not say: "3点开会" → 14:30. */
    val defaultLead: Duration = Duration.ofMinutes(30)

    /** A date-only task is mentioned the evening before and the morning of. */
    val eveningBefore: LocalTime = LocalTime.of(20, 0)
    val morningOf: LocalTime = LocalTime.of(9, 0)

    /** A point missed while the phone was off is still worth showing if it is this recent. */
    val catchUpWindow: Duration = Duration.ofHours(12)

    fun points(task: Task): List<LocalDateTime> {
        if (task.done) return emptyList()
        val out = mutableListOf<LocalDateTime>()
        task.remindAt?.let(out::add)
        val date = task.dueDate
        if (date != null) {
            val time = task.dueTime
            if (time != null) {
                val due = date.atTime(time)
                out += due.minus(task.remindBefore ?: defaultLead)
                out += due
            } else {
                val lead = task.remindBefore
                out += if (lead != null) date.atTime(morningOf).minus(lead) else date.minusDays(1).atTime(eveningBefore)
                out += date.atTime(morningOf)
            }
        }
        task.snoozeUntil?.let(out::add)
        return out.distinct().sorted()
    }

    /**
     * The next moment to fire, or null if there is none. A point that was
     * missed (phone off, alarm delayed) but is recent and newer than the last
     * reminder shown fires right away instead of being skipped silently.
     */
    fun next(task: Task, now: LocalDateTime): LocalDateTime? {
        val after = task.lastNotified
        val pending = points(task).filter { after == null || it > after }
        val missed = pending.lastOrNull { it <= now && it > now.minus(catchUpWindow) }
        if (missed != null) return now
        return pending.firstOrNull { it > now }
    }
}
