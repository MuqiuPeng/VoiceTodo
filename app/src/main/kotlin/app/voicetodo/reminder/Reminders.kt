package app.voicetodo.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.voicetodo.R
import app.voicetodo.localized
import app.voicetodo.data.Task
import app.voicetodo.data.TaskStore
import app.voicetodo.ui.MainActivity
import app.voicetodo.ui.describeDue
import java.time.LocalDateTime
import java.time.ZoneId

/** Schedules, shows and acts on reminders. One alarm per task, always the next one. */
object Reminders {
    const val CHANNEL = "reminders"
    private const val ACTION_FIRE = "app.voicetodo.FIRE"
    private const val ACTION_DONE = "app.voicetodo.DONE"
    private const val ACTION_SNOOZE = "app.voicetodo.SNOOZE"
    private const val EXTRA_ID = "task_id"

    /** Re-run on language change too: re-creating a channel updates its name and description. */
    fun createChannel(context: Context) {
        val ctx = context.localized()
        val channel = NotificationChannel(CHANNEL, ctx.getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
            description = ctx.getString(R.string.channel_desc)
            enableVibration(true)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun schedule(context: Context, task: Task, now: LocalDateTime = LocalDateTime.now()) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pi = firePendingIntent(context, task.id)
        val at = ReminderPolicy.next(task, now)
        if (at == null) {
            alarms.cancel(pi)
            return
        }
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // Without the exact-alarm grant Android may hold the alarm for a while;
        // late is better than not at all, and the app asks for the grant up front.
        if (canScheduleExact(context)) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        }
    }

    fun cancel(context: Context, taskId: Long) {
        context.getSystemService(AlarmManager::class.java).cancel(firePendingIntent(context, taskId))
        NotificationManagerCompat.from(context).cancel(taskId.toInt())
    }

    fun rescheduleAll(context: Context) {
        TaskStore.get(context).tasks.value.filter { !it.done }.forEach { schedule(context, it) }
    }

    private fun firePendingIntent(context: Context, taskId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            taskId.toInt(),
            Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_ID, taskId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun actionIntent(context: Context, action: String, taskId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            (taskId * 10 + if (action == ACTION_DONE) 1 else 2).toInt(),
            Intent(context, ReminderReceiver::class.java).setAction(action).putExtra(EXTRA_ID, taskId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    internal fun handle(context: Context, intent: Intent) {
        val store = TaskStore.get(context)
        val id = intent.getLongExtra(EXTRA_ID, -1)
        val task = store.get(id) ?: return
        val now = LocalDateTime.now()
        when (intent.action) {
            ACTION_FIRE -> {
                if (task.done) return
                notify(context, task, now)
                val updated = task.copy(lastNotified = now, snoozeUntil = task.snoozeUntil?.takeIf { it > now })
                store.update(updated)
                schedule(context, updated, now)
            }
            ACTION_DONE -> {
                store.update(task.copy(done = true))
                cancel(context, id)
            }
            ACTION_SNOOZE -> {
                val updated = task.copy(snoozeUntil = now.plusHours(1))
                store.update(updated)
                NotificationManagerCompat.from(context).cancel(id.toInt())
                schedule(context, updated, now)
            }
        }
    }

    private fun notify(context: Context, task: Task, now: LocalDateTime) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val ctx = context.localized()
        val overdue = task.dueAt?.let { it <= now } ?: false
        val title = if (overdue) ctx.getString(R.string.notif_overdue, task.title) else task.title
        val text = buildString {
            append(describeDue(task.dueDate, task.dueTime, task.dueKind, now.toLocalDate(), ctx.resources) ?: ctx.getString(R.string.notif_reminder))
            task.detail?.let { append(" · ").append(it) }
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n“${task.source}”"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, ctx.getString(R.string.swipe_done), actionIntent(context, ACTION_DONE, task.id))
            .addAction(0, ctx.getString(R.string.notif_snooze), actionIntent(context, ACTION_SNOOZE, task.id))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(task.id.toInt(), n)
        } catch (_: SecurityException) {
            // Notification permission revoked between the check and the call.
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Reminders.handle(context, intent)
}

/** Alarms do not survive a reboot or a clock change; put them back. */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Reminders.rescheduleAll(context)
}
