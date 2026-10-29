package app.voicetodo.widget

import android.app.PendingIntent
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import androidx.core.graphics.ColorUtils
import app.voicetodo.R
import app.voicetodo.localized
import app.voicetodo.ui.QuickAddActivity

/**
 * Adds a task without opening the app: every target opens a small panel over
 * the home screen ([QuickAddActivity]).
 *
 * One cell wide, the widget is just the icon on the wallpaper — dark on a
 * light wallpaper, white on a dark one — and tapping it starts talking. Wider,
 * it is the app's input bar: tap the bar to type, the icon to talk. A widget
 * cannot be long-pressed (the launcher owns that), so talking from it is
 * hands-free and ends when the speaker stops.
 */
class QuickAddWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, views(context, manager, it)) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        manager.updateAppWidget(id, views(context, manager, id))
    }

    companion object {
        const val ACTION_TYPE = "app.voicetodo.QUICK_TYPE"
        const val ACTION_VOICE = "app.voicetodo.QUICK_VOICE"

        /** Narrower than this (dp) the bar does not fit: one cell is 57–90dp depending on the launcher. */
        private const val BAR_MIN_WIDTH = 110f

        private fun open(context: Context, action: String, code: Int): PendingIntent =
            PendingIntent.getActivity(
                context, code,
                Intent(context, QuickAddActivity::class.java)
                    .setAction(action)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun bar(context: Context) = RemoteViews(context.packageName, R.layout.widget_quick_add).apply {
            // Below Android 13 the launcher would show the system language; use the app's.
            setTextViewText(R.id.widget_hint, context.localized().getString(R.string.widget_hint))
            setOnClickPendingIntent(R.id.widget_bar, open(context, ACTION_TYPE, 1))
            setOnClickPendingIntent(R.id.widget_mic, open(context, ACTION_VOICE, 2))
        }

        private fun icon(context: Context) = RemoteViews(context.packageName, R.layout.widget_icon).apply {
            setImageViewResource(R.id.widget_icon, if (wallpaperIsDark(context)) R.drawable.widget_mark_light else R.drawable.widget_mark_dark)
            // The whole cell is the target, not just the drawing.
            setOnClickPendingIntent(R.id.widget_cell, open(context, ACTION_VOICE, 2))
        }

        private fun views(context: Context, manager: AppWidgetManager, id: Int): RemoteViews =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // The launcher picks the largest layout that fits, also while resizing.
                RemoteViews(mapOf(SizeF(40f, 40f) to icon(context), SizeF(BAR_MIN_WIDTH, 40f) to bar(context)))
            } else {
                val width = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, Int.MAX_VALUE)
                if (width < BAR_MIN_WIDTH) icon(context) else bar(context)
            }

        /** From the wallpaper's own colours where Android reports them, else from dark mode. */
        private fun wallpaperIsDark(context: Context): Boolean {
            val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                runCatching { WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM) }.getOrNull()
            } else {
                null
            }
            if (colors != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    return colors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT == 0
                }
                return ColorUtils.calculateLuminance(colors.primaryColor.toArgb()) < 0.5
            }
            return context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }

        /** Re-drawn on app start and when the wallpaper changes, so language and icon colour stay current. */
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, QuickAddWidget::class.java))
            ids.forEach { manager.updateAppWidget(it, views(context, manager, it)) }
        }

        /** Follows wallpaper changes while the app's process is alive; otherwise the next update catches up. */
        fun watchWallpaper(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return
            runCatching {
                WallpaperManager.getInstance(context).addOnColorsChangedListener(
                    { _, which -> if (which and WallpaperManager.FLAG_SYSTEM != 0) updateAll(context) },
                    android.os.Handler(android.os.Looper.getMainLooper()),
                )
            }
        }
    }
}
