package app.voicetodo.net

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/** Where speech packs and updates download from. GitHub is slow or blocked in mainland China. */
enum class DownloadSource { GITHUB, CHINA }

/**
 * The user's download source, shared by the speech packs and the updater.
 * Whichever is chosen, the other is the fallback; size and SHA-256 decide
 * whether a file is accepted, so a mirror can be slow but not wrong.
 */
class DownloadSources(context: Context) {
    private val prefs = context.getSharedPreferences("net", Context.MODE_PRIVATE)

    private val _source = MutableStateFlow(
        // 0.12.0 kept it with the speech packs.
        (prefs.getString(KEY, null) ?: context.getSharedPreferences("speech", Context.MODE_PRIVATE).getString(KEY, null))
            ?.let { runCatching { DownloadSource.valueOf(it) }.getOrNull() }
            ?: if (Locale.getDefault().country == "CN") DownloadSource.CHINA else DownloadSource.GITHUB,
    )
    val source: StateFlow<DownloadSource> = _source

    fun set(s: DownloadSource) {
        prefs.edit().putString(KEY, s.name).apply()
        _source.value = s
    }

    /** [github] (a github.com URL) and its China [mirrors] plus proxied copies, the chosen source first. */
    fun urls(github: String, mirrors: List<String> = emptyList()): List<String> {
        val china = mirrors + GH_PROXIES.map { it + github }
        return if (_source.value == DownloadSource.CHINA) china + github else listOf(github) + china
    }

    companion object {
        private const val KEY = "source"

        /** Public github.com proxies reachable from China, checked to pass ranges and redirects through. */
        val GH_PROXIES = listOf("https://ghfast.top/", "https://gh-proxy.com/", "https://ghproxy.net/")
    }
}
