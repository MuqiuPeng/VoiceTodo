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
import java.util.Locale

/**
 * [url] is the GitHub copy; [mirrors] serve the same bytes from inside China
 * (the pinned size and digest make any of them safe to use).
 */
data class PackFile(val name: String, val url: String, val size: Long, val sha256: String, val mirrors: List<String> = emptyList()) {
    /** Where to try, in order: the chosen source first, the other as a fallback. */
    fun urls(source: PackSource): List<String> {
        val china = mirrors + SpeechPacks.GH_PROXIES.map { it + url }
        return if (source == PackSource.CHINA) china + url else listOf(url) + china
    }
}

/** Where speech packs download from. GitHub is slow or blocked in mainland China. */
enum class PackSource { GITHUB, CHINA }

/** How the recognizer has to be configured for a pack's files. */
enum class PackKind {
    /** One model file in SenseVoice's format: SenseVoice itself and FunASR Nano. */
    SENSE_VOICE,

    /** Encoder, decoder and joiner: Zipformer transducers. */
    TRANSDUCER,
}

/** A speech model the app can download: the recognizer is not in the APK, so updates stay small. */
data class SpeechPack(
    val id: String,
    val name: String,
    @StringRes val description: Int,
    val kind: PackKind,
    val files: List<PackFile>,
) {
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
 * Downloads, verifies, removes and chooses between speech packs. Packs are
 * published as assets of GitHub pre-releases in this app's repo (pre-releases
 * so the updater, which reads releases/latest, never mistakes one for an app
 * version). Sizes and digests are pinned here, so a pack is only installed if
 * it is byte-for-byte the one this version was tested with — which is also
 * what lets the China mirrors (ModelScope copies, GitHub proxies) be trusted.
 *
 * Several packs can be installed; one is active. Which one was compared on
 * the same Chinese, English and mixed clips — see README.
 */
class SpeechPacks(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onActiveChanged: (SpeechPack?) -> Unit,
) {
    private val root = File(context.filesDir, "speech")
    private val prefs = context.getSharedPreferences("speech", Context.MODE_PRIVATE)
    val all: List<SpeechPack> = listOf(FUNASR_NANO, SENSEVOICE, ZIPFORMER)

    private val _states = MutableStateFlow(all.associate { it.id to (if (isInstalled(it)) PackState.Installed else PackState.NotInstalled) })
    val states: StateFlow<Map<String, PackState>> = _states

    private val _active = MutableStateFlow(resolveActive(prefs.getString(KEY_ACTIVE, null)))
    val active: StateFlow<SpeechPack?> = _active

    private val jobs = mutableMapOf<String, Job>()

    private val _source = MutableStateFlow(
        prefs.getString(KEY_SOURCE, null)?.let { runCatching { PackSource.valueOf(it) }.getOrNull() }
            ?: if (Locale.getDefault().country == "CN") PackSource.CHINA else PackSource.GITHUB,
    )
    val source: StateFlow<PackSource> = _source

    fun setSource(s: PackSource) {
        prefs.edit().putString(KEY_SOURCE, s.name).apply()
        _source.value = s
    }

    fun dir(p: SpeechPack) = File(root, p.id)

    private fun isInstalled(p: SpeechPack) = p.files.all { File(dir(p), it.name).length() == it.size }

    /** The chosen pack if it is installed, else the first installed one in the list's order. */
    private fun resolveActive(chosen: String?): SpeechPack? =
        all.firstOrNull { it.id == chosen && isInstalled(it) } ?: all.firstOrNull { isInstalled(it) }

    private fun set(p: SpeechPack, s: PackState) {
        _states.value = _states.value + (p.id to s)
    }

    fun select(p: SpeechPack) {
        if (!isInstalled(p) || _active.value == p) return
        prefs.edit().putString(KEY_ACTIVE, p.id).apply()
        _active.value = p
        onActiveChanged(p)
    }

    fun download(p: SpeechPack) {
        if (jobs[p.id]?.isActive == true) return
        jobs[p.id] = scope.launch {
            try {
                var before = 0L
                for (f in p.files) {
                    fetch(f, File(dir(p), f.name)) { done -> set(p, PackState.Downloading(before + done, p.size)) }
                    before += f.size
                }
                set(p, PackState.Installed)
                // The first pack installed becomes the one in use.
                if (_active.value == null) select(p)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                set(p, PackState.Failed(e.describe(context)))
            }
        }
    }

    /** Each URL in turn until one delivers; a partial file carries over, since every source has the same bytes. */
    private suspend fun fetch(f: PackFile, dest: File, onProgress: (Long) -> Unit) {
        var error: Exception? = null
        for (url in f.urls(_source.value)) {
            try {
                Download.fetch(url, dest, f.size, f.sha256, onProgress)
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = error ?: e
            }
        }
        throw error!!
    }

    /** Stops the download; the partial file stays and the next download resumes it. */
    fun pause(p: SpeechPack) {
        scope.launch {
            jobs[p.id]?.cancelAndJoin()
            (_states.value[p.id] as? PackState.Downloading)?.let { set(p, PackState.Paused(it.done, it.total)) }
        }
    }

    /** `release` lets the recognizer let go of the model files first when this pack is in use. */
    fun delete(p: SpeechPack, release: suspend () -> Unit) {
        scope.launch {
            jobs[p.id]?.cancelAndJoin()
            val wasActive = _active.value == p
            if (wasActive) release()
            dir(p).deleteRecursively()
            set(p, PackState.NotInstalled)
            if (wasActive) {
                _active.value = resolveActive(null)
                onActiveChanged(_active.value)
            }
        }
    }

    companion object {
        private const val KEY_ACTIVE = "active_pack"
        private const val KEY_SOURCE = "source"
        private const val GH = "https://github.com/MuqiuPeng/VoiceTodo/releases/download"
        private const val MS = "https://www.modelscope.cn/models"

        /** Public GitHub download proxies reachable from China, checked to pass ranges through. */
        val GH_PROXIES = listOf("https://ghfast.top/", "https://gh-proxy.com/", "https://ghproxy.net/")

        /** Best on Chinese with English mixed in, and on accents. */
        val FUNASR_NANO = SpeechPack(
            id = "funasr-nano-2512-int8",
            name = "FunASR Nano",
            description = R.string.pack_funasr_desc,
            kind = PackKind.SENSE_VOICE,
            files = listOf(
                PackFile(
                    "model.int8.onnx", "$GH/speech-funasr-nano-2512/funasr-nano-2512-int8.onnx", 263_531_902,
                    "9dc6e72aa8bc6f5966cf2857a0ce3a425b1d72e91500e147d66329f407c017a1",
                    listOf("$MS/liaowenbin/sherpa-onnx-sense-voice-funasr-nano-int8/resolve/master/model.int8.onnx"),
                ),
                PackFile(
                    "tokens.txt", "$GH/speech-funasr-nano-2512/funasr-nano-2512-tokens.txt", 939_815,
                    "2db4bb25d046e8849e336c9465e248e1694914d996c58c71bdeca18cfb722992",
                    listOf("$MS/liaowenbin/sherpa-onnx-sense-voice-funasr-nano-int8/resolve/master/tokens.txt"),
                ),
            ),
        )

        /** Two independent ModelScope copies of the upstream repo. Zipformer has none; it goes through [GH_PROXIES]. */
        private val SENSEVOICE_MS = listOf(
            "aistoy/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09",
            "Mr7Cat/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09",
        )

        val SENSEVOICE = SpeechPack(
            id = "sensevoice-int8-2025-09-09",
            name = "SenseVoice",
            description = R.string.pack_languages,
            kind = PackKind.SENSE_VOICE,
            files = listOf(
                PackFile(
                    "model.int8.onnx", "$GH/speech-sensevoice-2025-09-09/sensevoice-int8-2025-09-09.onnx", 237_115_547,
                    "12ca1a2ae7ecf3e0019ef2822307ee0b5cadc9196569e379b4c4026f8205276d",
                    SENSEVOICE_MS.map { "$MS/$it/resolve/master/model.int8.onnx" },
                ),
                PackFile(
                    "tokens.txt", "$GH/speech-sensevoice-2025-09-09/sensevoice-tokens.txt", 315_894,
                    "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc",
                    SENSEVOICE_MS.map { "$MS/$it/resolve/master/tokens.txt" },
                ),
            ),
        )

        /** A quarter of the size of the others; less accurate on mixed speech. */
        val ZIPFORMER = SpeechPack(
            id = "zipformer-zh-en-2023-11-22",
            name = "Zipformer",
            description = R.string.pack_zipformer_desc,
            kind = PackKind.TRANSDUCER,
            files = listOf(
                PackFile(
                    "encoder.int8.onnx", "$GH/speech-zipformer-zh-en-2023-11-22/zipformer-zh-en-encoder.int8.onnx", 69_037_476,
                    "28780a73b0fc5a05ec579ad4dbbc7d8fcd09fed5e6c431f84dcaab8f8170d9bf",
                ),
                PackFile(
                    "decoder.onnx", "$GH/speech-zipformer-zh-en-2023-11-22/zipformer-zh-en-decoder.onnx", 5_165_101,
                    "110dc19143f0731ce0f64513e524d7d8f729e37107e1392da790f66d6c936a0b",
                ),
                PackFile(
                    "joiner.int8.onnx", "$GH/speech-zipformer-zh-en-2023-11-22/zipformer-zh-en-joiner.int8.onnx", 1_033_479,
                    "498861ff0963ecbbff101a940ed533a715e5d68ee96431a3b092d0ca00c3ba38",
                ),
                PackFile(
                    "tokens.txt", "$GH/speech-zipformer-zh-en-2023-11-22/zipformer-zh-en-tokens.txt", 25_645,
                    "2d5d6245197582cea396b23fa29d592705be31bac9a97bb74a8d1bca3ca6a0e0",
                ),
            ),
        )
    }
}
