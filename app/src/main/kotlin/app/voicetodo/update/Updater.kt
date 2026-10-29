package app.voicetodo.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.Uri
import android.provider.Settings
import app.voicetodo.net.Download
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** A GitHub release that carries an APK. */
data class Release(
    val version: Version,
    val tag: String,
    val notes: String,
    val apkUrl: String,
    val size: Long,
    /** From GitHub's asset digest, when it provides one. */
    val sha256: String?,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val done: Long) : UpdateState
    data class Installing(val release: Release) : UpdateState
    data class Failed(val message: String, val release: Release? = null) : UpdateState
}

/**
 * Self-update from this app's GitHub releases: the only network traffic the
 * app makes. It looks for the latest release, downloads its APK (resuming a
 * partial download), checks size and digest, and hands it to the system
 * installer — which itself refuses an APK not signed with the same key.
 *
 * Lives in the Application so a 280 MB download survives rotation and
 * leaving the screen.
 */
class Updater(private val context: Context, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("updater", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state
    private var job: Job? = null

    val currentVersion: Version =
        Version.parse(context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0") ?: Version(listOf(0))

    /** Debug builds can point at a local server: `--es debug_update_url http://10.0.2.2:8000/latest.json`. */
    var endpoint: String = RELEASES_URL

    init {
        // A finished update leaves its APK behind; once the new version is running it is garbage.
        val downloaded = prefs.getString(KEY_DOWNLOADED, null)?.let(Version::parse)
        if (downloaded == null || downloaded <= currentVersion) updateDir().deleteRecursively()
    }

    val isMetered: Boolean
        get() = context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered

    /** Once a day at most, on launch; quiet unless there is something to show. */
    fun checkIfDue() {
        val last = prefs.getLong(KEY_LAST_CHECK, 0)
        if (System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return
        check(manual = false)
    }

    fun check(manual: Boolean = true) {
        if (job?.isActive == true) return
        job = scope.launch {
            if (manual) _state.value = UpdateState.Checking
            val result = runCatching { fetchLatest() }
            prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            _state.value = result.fold(
                onSuccess = { r ->
                    val skipped = prefs.getString(KEY_SKIPPED, null)
                    when {
                        r == null || r.version <= currentVersion -> if (manual) UpdateState.UpToDate else UpdateState.Idle
                        !manual && r.tag == skipped -> UpdateState.Idle
                        else -> UpdateState.Available(r)
                    }
                },
                onFailure = { if (manual) UpdateState.Failed("检查更新失败：${it.message ?: it.javaClass.simpleName}") else UpdateState.Idle },
            )
        }
    }

    fun skip(release: Release) {
        prefs.edit().putString(KEY_SKIPPED, release.tag).apply()
        _state.value = UpdateState.Idle
    }

    fun dismiss() {
        if (_state.value !is UpdateState.Downloading && _state.value !is UpdateState.Installing) _state.value = UpdateState.Idle
    }

    fun cancelDownload() {
        job?.cancel()
        (_state.value as? UpdateState.Downloading)?.let { _state.value = UpdateState.Available(it.release) }
    }

    fun downloadAndInstall(release: Release) {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                val apk = download(release)
                if (!context.packageManager.canRequestPackageInstalls()) {
                    _state.value = UpdateState.Failed("请允许本应用“安装未知应用”，然后再点一次安装", release)
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    return@launch
                }
                _state.value = UpdateState.Installing(release)
                install(apk)
            } catch (e: Exception) {
                if (isActive) _state.value = UpdateState.Failed("更新失败：${e.message ?: e.javaClass.simpleName}", release)
            }
        }
    }

    /** Called by [InstallResultReceiver] when the installer gives up. */
    internal fun installFailed(message: String) {
        val release = (_state.value as? UpdateState.Installing)?.release
        _state.value = UpdateState.Failed("安装没有完成：$message", release)
    }

    // --- network ---------------------------------------------------------

    private suspend fun fetchLatest(): Release? = withContext(Dispatchers.IO) {
        val conn = Download.open(endpoint)
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode == 404) return@withContext null // No release published yet.
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val tag = json.getString("tag_name")
            val version = Version.parse(tag) ?: return@withContext null
            val assets = json.getJSONArray("assets")
            val apk = (0 until assets.length()).map(assets::getJSONObject)
                .firstOrNull { it.getString("name").endsWith(".apk") } ?: return@withContext null
            Release(
                version = version,
                tag = tag,
                notes = json.optString("body").trim(),
                apkUrl = apk.getString("browser_download_url"),
                size = apk.getLong("size"),
                sha256 = apk.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun updateDir() = File(context.cacheDir, "update")

    /** One file per release, so a partial download is never resumed into another version. */
    private suspend fun download(release: Release): File {
        _state.value = UpdateState.Downloading(release, 0)
        return Download.fetch(
            release.apkUrl,
            File(updateDir(), "VoiceTodo-${release.version}.apk"),
            release.size,
            release.sha256,
        ) { _state.value = UpdateState.Downloading(release, it) }.also {
            prefs.edit().putString(KEY_DOWNLOADED, release.tag).apply()
        }
    }

    // --- install ---------------------------------------------------------

    private suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out, 1 shl 20) }
                session.fsync(out)
            }
            val callback = PendingIntent.getBroadcast(
                context, id,
                Intent(context, InstallResultReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
        }
    }

    companion object {
        const val REPO = "MuqiuPeng/VoiceTodo"
        const val RELEASES_URL = "https://api.github.com/repos/$REPO/releases/latest"
        private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
        private const val KEY_LAST_CHECK = "last_check"
        private const val KEY_SKIPPED = "skipped_tag"
        private const val KEY_DOWNLOADED = "downloaded_tag"
    }
}

/**
 * The installer reports back here. It first asks for the user's confirmation
 * (this app cannot install silently); success kills and replaces the running
 * app, so only the failures need handling.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?: if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "已取消" else "错误 $status"
                (context.applicationContext as app.voicetodo.VoiceTodoApp).updater.installFailed(message)
            }
        }
    }
}
