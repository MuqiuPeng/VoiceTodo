package app.voicetodo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.voicetodo.speech.SpeechEngine
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWenetCtcModelConfig
import java.io.File

/**
 * Debug builds only: runs a candidate speech model over a folder of WAVs and
 * logs each transcript, so models can be compared on the device's own
 * runtime before one is published as a speech pack.
 *
 *   adb shell am broadcast -n app.voicetodo/.DebugBenchReceiver \
 *     --es dir <model dir> --es type sensevoice|paraformer|transducer|wenet \
 *     --es wavs <wav dir> [--es lang auto|zh|en] [--es model model.int8.onnx]
 *
 * Output: logcat tag VTBENCH, one line per file: "<file>\t<ms>\t<text>".
 */
class DebugBenchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                run(intent)
            } catch (e: Throwable) {
                Log.e(TAG, "failed", e)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun run(intent: Intent) {
        val dir = File(intent.getStringExtra("dir")!!)
        val type = intent.getStringExtra("type")!!
        val wavs = File(intent.getStringExtra("wavs")!!).listFiles { f -> f.name.endsWith(".wav") }!!.sortedBy { it.name }
        fun f(name: String) = File(dir, name).absolutePath
        val modelFile = intent.getStringExtra("model") ?: "model.int8.onnx"
        val model = when (type) {
            "sensevoice" -> OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(f(modelFile), intent.getStringExtra("lang") ?: "auto", true),
            )
            "paraformer" -> OfflineModelConfig(paraformer = OfflineParaformerModelConfig(f(modelFile)))
            "wenet" -> OfflineModelConfig(wenetCtc = OfflineWenetCtcModelConfig(f(modelFile)))
            "transducer" -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = f(intent.getStringExtra("encoder")!!),
                    decoder = f(intent.getStringExtra("decoder")!!),
                    joiner = f(intent.getStringExtra("joiner")!!),
                ),
                modelType = "transducer",
            )
            else -> error("unknown type $type")
        }.apply {
            tokens = f("tokens.txt")
            numThreads = 2
        }
        val started = System.currentTimeMillis()
        val recognizer = OfflineRecognizer(null, OfflineRecognizerConfig(featConfig = FeatureConfig(16000, 80), modelConfig = model))
        Log.i(TAG, "loaded ${dir.name} in ${System.currentTimeMillis() - started} ms")
        for (wav in wavs) {
            val samples = SpeechEngine.readWav(wav)
            val t0 = System.currentTimeMillis()
            val stream = recognizer.createStream()
            stream.acceptWaveform(samples, 16000)
            recognizer.decode(stream)
            val text = recognizer.getResult(stream).text
            stream.release()
            Log.i(TAG, "${wav.name}\t${System.currentTimeMillis() - t0}\t$text")
        }
        recognizer.release()
        Log.i(TAG, "done ${dir.name}")
    }

    private companion object {
        const val TAG = "VTBENCH"
    }
}
