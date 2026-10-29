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
import app.voicetodo.ui.QuickAddActivity

/**
 * A home-screen bar that adds a task without opening the app: both targets
 * open a small panel over the home screen ([QuickAddActivity]) — the bar to
 * type, the mic to talk. A widget cannot be long-pressed (the launcher owns
 * that), so the mic starts a hands-free recording that ends when the speaker
 * stops; inside the panel, holding the field records as it does in the app.
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
                Intent(context, QuickAddActivity::class.java)
                    .setAction(action)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
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
