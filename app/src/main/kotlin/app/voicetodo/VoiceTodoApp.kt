package app.voicetodo

import android.app.Application
import app.voicetodo.data.Recordings
import app.voicetodo.data.TaskStore
import app.voicetodo.reminder.Reminders
import app.voicetodo.speech.SpeechPack
import app.voicetodo.speech.SpeechEngine
import app.voicetodo.speech.SpeechPacks
import app.voicetodo.update.Updater
import app.voicetodo.widget.QuickAddWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class VoiceTodoApp : Application() {
    sealed interface EngineState {
        /** No speech pack downloaded yet: typing works, talking does not. */
        data object Missing : EngineState
        data object Loading : EngineState
        data object Ready : EngineState
        data class Failed(val message: String) : EngineState
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var speech: SpeechEngine
        private set
    lateinit var updater: Updater
        private set
    lateinit var packs: SpeechPacks
        private set
    val recordings by lazy { Recordings(this) }

    private val _engineState = MutableStateFlow<EngineState>(EngineState.Loading)
    val engineState: StateFlow<EngineState> = _engineState

    override fun onCreate() {
        super.onCreate()
        Reminders.createChannel(this)
        speech = SpeechEngine()
        updater = Updater(this, scope)
        packs = SpeechPacks(this, scope, onActiveChanged = ::loadSpeech)
        // Not loaded here: this also runs for every reminder, notification
        // button and widget refresh, where the model would cost seconds of CPU
        // and a few hundred MB for nothing. Screens that record ask for it.
        _engineState.value = if (packs.active.value == null) EngineState.Missing else EngineState.Loading
        QuickAddWidget.updateAll(this)
        QuickAddWidget.watchWallpaper(this)
        // A delete whose undo never expired (app killed) leaves its recording behind.
        scope.launch { recordings.deleteOrphans(TaskStore.get(this@VoiceTodoApp).tasks.value) }
        // Covers alarms lost to a force-stop, which sends no broadcast.
        scope.launch { Reminders.rescheduleAll(this@VoiceTodoApp) }
    }

    /** The pack the engine holds or is loading; null when nothing is loaded. */
    private var loaded: SpeechPack? = null
    private var loadJob: Job? = null

    /** Called when a screen that can record comes up; cheap if the model is already there. */
    @Synchronized
    fun ensureSpeech() {
        val pack = packs.active.value
        if (pack != null && pack == loaded && _engineState.value !is EngineState.Failed) return
        loadSpeech(pack)
    }

    @Synchronized
    private fun loadSpeech(pack: SpeechPack?) {
        loadJob?.cancel()
        loaded = pack
        if (pack == null) {
            _engineState.value = EngineState.Missing
            scope.launch { speech.unload() }
            return
        }
        _engineState.value = EngineState.Loading
        loadJob = scope.launch {
            val state = try {
                speech.load(pack, packs.dir(pack))
                EngineState.Ready
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                EngineState.Failed(e.message ?: e.javaClass.simpleName)
            }
            _engineState.value = state
        }
    }

    /**
     * No screen of ours is showing and Android is ranking background
     * processes: give the model's memory back. The next screen that records
     * loads it again in a couple of seconds.
     */
    @Synchronized
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level < TRIM_MEMORY_BACKGROUND || loaded == null) return
        loadJob?.cancel()
        loaded = null
        _engineState.value = if (packs.active.value == null) EngineState.Missing else EngineState.Loading
        scope.launch { speech.unload() }
    }

    fun deleteSpeechPack(pack: SpeechPack) = packs.delete(pack, release = { speech.unload() })
}
