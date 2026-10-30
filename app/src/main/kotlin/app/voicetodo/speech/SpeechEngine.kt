package app.voicetodo.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * On-device speech recognition through sherpa-onnx, from the speech pack the
 * user chose (see [SpeechPacks]). Nothing leaves the phone.
 *
 * Loading the model takes a couple of seconds and holds a few hundred MB, so
 * it happens off the main thread, only while a screen that records is open
 * (see [app.voicetodo.VoiceTodoApp.ensureSpeech]). Loading, unloading and
 * decoding take turns, so the model is never released mid-decode.
 */
class SpeechEngine {
    @Volatile
    private var recognizer: OfflineRecognizer? = null
    private val lock = Mutex()

    val isReady: Boolean get() = recognizer != null

    suspend fun load(pack: SpeechPack, dir: File) = withContext(Dispatchers.Default) {
        lock.withLock { loadLocked(pack, dir) }
    }

    private fun loadLocked(pack: SpeechPack, dir: File) {
        releaseLocked()
        fun f(name: String) = File(dir, name).absolutePath
        val model = when (pack.kind) {
            PackKind.SENSE_VOICE -> OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = f("model.int8.onnx"),
                    // Detected per utterance, so Chinese, English and Chinese
                    // with English words in it all come through.
                    language = "auto",
                    // Writes "三点半" as "3点半" and adds punctuation, which the
                    // task parser's clause splitting relies on.
                    useInverseTextNormalization = true,
                ),
            )
            PackKind.TRANSDUCER -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = f("encoder.int8.onnx"),
                    decoder = f("decoder.onnx"),
                    joiner = f("joiner.int8.onnx"),
                ),
                modelType = "transducer",
            )
        }.apply {
            tokens = f("tokens.txt")
            numThreads = 2
        }
        recognizer = OfflineRecognizer(
            null,
            OfflineRecognizerConfig(featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80), modelConfig = model),
        )
    }

    suspend fun unload() = lock.withLock { releaseLocked() }

    private fun releaseLocked() {
        recognizer?.release()
        recognizer = null
    }

    suspend fun transcribe(samples: FloatArray): String = withContext(Dispatchers.Default) {
        lock.withLock { transcribeLocked(samples) }
    }

    private fun transcribeLocked(samples: FloatArray): String {
        val r = recognizer ?: error("speech model not loaded")
        val stream = r.createStream()
        try {
            // Fed as recorded. Padding silence in front was tried (100–600 ms)
            // and made SenseVoice worse, not better: it shifts the frames and
            // garbles words ("submit" → "uubmit").
            stream.acceptWaveform(samples, SAMPLE_RATE)
            r.decode(stream)
            return r.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    companion object {
        const val SAMPLE_RATE = 16000

        /** 16-bit PCM mono WAV → floats; used by the debug path that feeds a file instead of the mic. */
        fun readWav(file: File): FloatArray {
            val bytes = file.readBytes()
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var pos = 12
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4)
                val size = buf.getInt(pos + 4)
                if (id == "data") {
                    val n = minOf(size, bytes.size - pos - 8) / 2
                    return FloatArray(n) { buf.getShort(pos + 8 + it * 2) / 32768f }
                }
                pos += 8 + size + (size and 1)
            }
            error("no data chunk in ${file.name}")
        }
    }
}

/** Push-to-talk capture: start on press, stop on release, hand back the samples. */
class Recorder {
    @Volatile
    private var recording = false
    private var thread: Thread? = null
    private val chunks = mutableListOf<ShortArray>()

    private val _level = MutableStateFlow(0f)

    /** Loudness of the latest chunk, 0..1, so the UI can show it is hearing something. */
    val level: StateFlow<Float> = _level

    @SuppressLint("MissingPermission") // The UI requests RECORD_AUDIO before calling start().
    fun start() {
        if (recording) return
        val minBuf = AudioRecord.getMinBufferSize(
            SpeechEngine.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        fun open(source: Int) = AudioRecord(
            source, SpeechEngine.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, SpeechEngine.SAMPLE_RATE),
        )
        // VOICE_RECOGNITION skips the call-oriented processing that hurts
        // recognition, but not every phone provides it; plain MIC always works.
        var record = open(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            record = open(MediaRecorder.AudioSource.MIC)
        }
        synchronized(chunks) { chunks.clear() }
        recording = true
        record.startRecording()
        thread = Thread {
            val buf = ShortArray(1600)
            while (recording) {
                val n = record.read(buf, 0, buf.size)
                if (n > 0) {
                    synchronized(chunks) { chunks += buf.copyOf(n) }
                    var sum = 0.0
                    for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                    // Speech sits around RMS 1000–5000; scale so normal talking fills most of the bar.
                    _level.value = (kotlin.math.sqrt(sum / n) / 4000).toFloat().coerceIn(0f, 1f)
                }
            }
            record.stop()
            record.release()
        }.also { it.start() }
    }

    /** Loudest sample of the last recording, 0..1: near zero means the mic gave silence. */
    @Volatile
    var peak = 0f
        private set

    fun stop(): FloatArray {
        recording = false
        thread?.join(1000)
        thread = null
        _level.value = 0f
        val all = synchronized(chunks) { chunks.toList() }
        val out = FloatArray(all.sumOf { it.size })
        var i = 0
        var max = 0
        for (c in all) for (s in c) {
            out[i++] = s / 32768f
            max = maxOf(max, kotlin.math.abs(s.toInt()))
        }
        peak = max / 32768f
        return out
    }
}
