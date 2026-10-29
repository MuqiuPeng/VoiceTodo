package app.voicetodo.data

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

data class Task(
    val id: Long = 0,
    val title: String,
    val detail: String? = null,
    val dueDate: LocalDate? = null,
    /** Null for "明天交报告": the day matters, the hour was never said. */
    val dueTime: LocalTime? = null,
    /** "DEADLINE", "AT", "START" — how the time was said, for display. */
    val dueKind: String? = null,
    val remindAt: LocalDateTime? = null,
    val remindBefore: Duration? = null,
    /** What was actually said, kept so a reminder days later still makes sense. */
    val source: String = "",
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val done: Boolean = false,
    val snoozeUntil: LocalDateTime? = null,
    /** The last reminder shown, so a reschedule never repeats one. */
    val lastNotified: LocalDateTime? = null,
    /** File name of the recording it was spoken in, under [Recordings]; null if typed. */
    val audio: String? = null,
) {
    val dueAt: LocalDateTime? get() = dueDate?.atTime(dueTime ?: LocalTime.of(23, 59))
}
