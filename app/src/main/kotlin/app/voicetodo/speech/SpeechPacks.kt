package app.voicetodo.speech

import android.content.Context
import androidx.annotation.StringRes
import app.voicetodo.R
import app.voicetodo.net.Download
import app.voicetodo.net.describe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

data class PackFile(val name: String, val url: String, val size: Long, val sha256: String)

/** A speech model the app can download: the recognizer is not in the APK, so updates stay small. */
data class SpeechPack(val id: String, val name: String, @StringRes val languages: Int, val files: List<PackFile>) {
    val size: Long get() = files.sumOf { it.size }
}

sealed interface PackState {
    data object NotInstalled : PackState
    data class Downloading(val done: Long, val total: Long) : PackState
    data class Paused(val done: Long, val total: Long) : PackState
    data object Installed : PackState
    data class Failed(val message: String) : PackState
}

/**
 * Downloads, verifies and removes speech packs. Packs are published as
 * assets of a GitHub pre-release in this app's repo (pre-release so the
 * updater, which reads releases/latest, never mistakes one for an app
 * version). Sizes and digests are pinned here, so a pack is only installed
 * if it is byte-for-byte the one this version was built against.
 */
class SpeechPacks(private val context: Context, private val scope: CoroutineScope, private val onInstalled: (SpeechPack) -> Unit) {
    private val root = File(context.filesDir, "speech")
    val pack: SpeechPack = SENSEVOICE

    private val _state = MutableStateFlow(if (isInstalled(pack)) PackState.Installed else PackState.NotInstalled)
    val state: StateFlow<PackState> = _state
    private var job: Job? = null

    fun dir(p: SpeechPack) = File(root, p.id)

    private fun isInstalled(p: SpeechPack) = p.files.all { File(dir(p), it.name).length() == it.size }

    fun download() {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                var before = 0L
                for (f in pack.files) {
                    Download.fetch(f.url, File(dir(pack), f.name), f.size, f.sha256) { done ->
                        _state.value = PackState.Downloading(before + done, pack.size)
                    }
                    before += f.size
                }
                _state.value = PackState.Installed
                onInstalled(pack)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = PackState.Failed(e.describe(context))
            }
        }
    }

    /** Stops the download; the partial file stays and the next download resumes it. */
    fun pause() {
        scope.launch {
            job?.cancelAndJoin()
            (_state.value as? PackState.Downloading)?.let { _state.value = PackState.Paused(it.done, it.total) }
        }
    }

    /** `beforeDelete` lets the recognizer let go of the model file first. */
    fun delete(beforeDelete: () -> Unit) {
        scope.launch {
            job?.cancelAndJoin()
            beforeDelete()
            dir(pack).deleteRecursively()
            _state.value = PackState.NotInstalled
        }
    }

    companion object {
        private const val BASE = "https://github.com/MuqiuPeng/VoiceTodo/releases/download/speech-sensevoice-2025-09-09"

        val SENSEVOICE = SpeechPack(
            id = "sensevoice-int8-2025-09-09",
            name = "SenseVoice",
            languages = R.string.pack_languages,
            files = listOf(
                PackFile(
                    "model.int8.onnx", "$BASE/sensevoice-int8-2025-09-09.onnx", 237_115_547,
                    "12ca1a2ae7ecf3e0019ef2822307ee0b5cadc9196569e379b4c4026f8205276d",
                ),
                PackFile(
                    "tokens.txt", "$BASE/sensevoice-tokens.txt", 315_894,
                    "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc",
                ),
            ),
        )
    }
}
