package app.voicetodo

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate

/**
 * This context in the language chosen in Settings, for text built outside
 * an Activity: notifications, background errors. Below Android 13 AppCompat
 * localizes activities only, so the application context would otherwise
 * stay in the system language.
 */
fun Context.localized(): Context {
    val chosen = AppCompatDelegate.getApplicationLocales()
    if (chosen.isEmpty) return this
    val config = Configuration(resources.configuration).apply {
        setLocales(LocaleList.forLanguageTags(chosen.toLanguageTags()))
    }
    return createConfigurationContext(config)
}
