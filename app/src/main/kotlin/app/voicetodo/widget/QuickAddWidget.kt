package app.voicetodo.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import app.voicetodo.R
import app.voicetodo.localized
import app.voicetodo.ui.MainActivity

/**
 * A home-screen bar that opens the app ready to take a task: the bar to type,
 * the mic to talk. Widgets cannot be held down, so the mic starts a
 * hands-free recording that ends when the speaker stops.
 */
class QuickAddWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, views(context)) }
    }

    companion object {
        const val ACTION_TYPE = "app.voicetodo.QUICK_TYPE"
        const val ACTION_VOICE = "app.voicetodo.QUICK_VOICE"

        private fun open(context: Context, action: String, code: Int): PendingIntent =
            PendingIntent.getActivity(
                context, code,
                Intent(context, MainActivity::class.java)
                    .setAction(action)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun views(context: Context) = RemoteViews(context.packageName, R.layout.widget_quick_add).apply {
            // Below Android 13 the launcher would show the system language; use the app's.
            setTextViewText(R.id.widget_hint, context.localized().getString(R.string.widget_hint))
            setOnClickPendingIntent(R.id.widget_bar, open(context, ACTION_TYPE, 1))
            setOnClickPendingIntent(R.id.widget_mic, open(context, ACTION_VOICE, 2))
        }

        /** Re-drawn on app start, so a language change reaches the widget too. */
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, QuickAddWidget::class.java))
            if (ids.isNotEmpty()) ids.forEach { manager.updateAppWidget(it, views(context)) }
        }
    }
}
