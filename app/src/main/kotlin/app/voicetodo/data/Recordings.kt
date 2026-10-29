package app.voicetodo.data

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * The recordings tasks were spoken in, as 16 kHz mono WAV files (~32 KB a
 * second). One utterance can become several tasks, so a file is shared and
 * only removed once no task points at it.
 */
class Recordings(context: Context) {
    private val dir = File(context.filesDir, "recordings").apply { mkdirs() }

    fun file(name: String) = File(dir, name)

    fun save(samples: FloatArray, sampleRate: Int): String {
        val name = "${UUID.randomUUID()}.wav"
        val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) pcm.putShort((s.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.capacity()); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.capacity())
        }
        file(name).outputStream().use {
            it.write(header.array())
            it.write(pcm.array())
        }
        return name
    }

    fun seconds(name: String): Int = ((file(name).length() - 44).coerceAtLeast(0) / 32_000).toInt()

    /** Removes the files in `names` that none of `remaining` still uses. */
    fun deleteUnreferenced(names: Collection<String>, remaining: List<Task>) {
        val used = remaining.mapNotNull { it.audio }.toSet()
        names.filter { it !in used }.forEach { file(it).delete() }
    }

    fun deleteOrphans(tasks: List<Task>) {
        deleteUnreferenced(dir.list()?.toList() ?: emptyList(), tasks)
    }

    // --- playback --------------------------------------------------------

    private var player: MediaPlayer? = null
    private val _playing = MutableStateFlow<String?>(null)
    val playing: StateFlow<String?> = _playing

    /** Plays `name`, or stops it if it is the one playing. */
    fun toggle(name: String) {
        val wasPlaying = _playing.value
        stop()
        if (wasPlaying == name) return
        val f = file(name)
        if (!f.exists()) return
        val p = MediaPlayer().apply {
            setDataSource(f.path)
            setOnCompletionListener { stop() }
            prepare()
            start()
        }
        player = p
        _playing.value = name
        // Backstop for an audio output that never reports completion, so the
        // button cannot stay on 停止.
        main.postDelayed({ if (player === p) stop() }, seconds(name) * 1000L + 1500)
    }

    private val main = Handler(Looper.getMainLooper())

    fun stop() {
        player?.release()
        player = null
        _playing.value = null
    }
}
