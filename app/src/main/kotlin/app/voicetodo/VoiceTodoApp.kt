package app.voicetodo

import android.app.Application
import app.voicetodo.data.Recordings
import app.voicetodo.data.TaskStore
import app.voicetodo.reminder.Reminders
import app.voicetodo.speech.PackState
import app.voicetodo.speech.SpeechEngine
import app.voicetodo.speech.SpeechPacks
import app.voicetodo.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
        packs = SpeechPacks(this, scope, onInstalled = { loadSpeech() })
        if (packs.state.value == PackState.Installed) loadSpeech() else _engineState.value = EngineState.Missing
        // A delete whose undo never expired (app killed) leaves its recording behind.
        scope.launch { recordings.deleteOrphans(TaskStore.get(this@VoiceTodoApp).tasks.value) }
        // Covers alarms lost to a force-stop, which sends no broadcast.
        scope.launch { Reminders.rescheduleAll(this@VoiceTodoApp) }
    }

    private fun loadSpeech() {
        _engineState.value = EngineState.Loading
        scope.launch {
            _engineState.value = try {
                speech.load(packs.dir(packs.pack))
                EngineState.Ready
            } catch (e: Throwable) {
                EngineState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun deleteSpeechPack() {
        packs.delete(beforeDelete = { speech.unload() })
        _engineState.value = EngineState.Missing
    }
}
