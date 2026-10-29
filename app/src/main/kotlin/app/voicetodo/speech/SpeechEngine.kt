package app.voicetodo.speech

import android.annotation.SuppressLint
import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * On-device speech recognition: SenseVoice (int8) through sherpa-onnx, read
 * straight from the APK's assets. Nothing leaves the phone.
 *
 * Loading the model takes a few seconds, so it happens once, off the main
 * thread, when the app starts.
 */
class SpeechEngine(private val assets: AssetManager) {
    @Volatile
    private var recognizer: OfflineRecognizer? = null

    val isReady: Boolean get() = recognizer != null

    suspend fun load() = withContext(Dispatchers.Default) {
        if (recognizer != null) return@withContext
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = "sense-voice/model.int8.onnx",
                    language = "zh",
                    // Writes "三点半" as "3点半" and adds punctuation, which the
                    // task parser's clause splitting relies on.
                    useInverseTextNormalization = true,
                ),
                tokens = "sense-voice/tokens.txt",
                numThreads = 2,
            ),
        )
        recognizer = OfflineRecognizer(assets, config)
    }

    suspend fun transcribe(samples: FloatArray): String = withContext(Dispatchers.Default) {
        val r = recognizer ?: error("speech model not loaded")
        val stream = r.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            r.decode(stream)
            r.getResult(stream).text.trim()
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
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SpeechEngine.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, SpeechEngine.SAMPLE_RATE),
        )
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

    fun stop(): FloatArray {
        recording = false
        thread?.join(1000)
        thread = null
        _level.value = 0f
        val all = synchronized(chunks) { chunks.toList() }
        val out = FloatArray(all.sumOf { it.size })
        var i = 0
        for (c in all) for (s in c) out[i++] = s / 32768f
        return out
    }
}
