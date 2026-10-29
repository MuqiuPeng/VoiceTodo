package app.voicetodo

import android.app.Application
import app.voicetodo.reminder.Reminders
import app.voicetodo.speech.SpeechEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class VoiceTodoApp : Application() {
    sealed interface EngineState {
        data object Loading : EngineState
        data object Ready : EngineState
        data class Failed(val message: String) : EngineState
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var speech: SpeechEngine
        private set

    private val _engineState = MutableStateFlow<EngineState>(EngineState.Loading)
    val engineState: StateFlow<EngineState> = _engineState

    override fun onCreate() {
        super.onCreate()
        Reminders.createChannel(this)
        speech = SpeechEngine(assets)
        scope.launch {
            _engineState.value = try {
                speech.load()
                EngineState.Ready
            } catch (e: Throwable) {
                EngineState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
        // Covers alarms lost to a force-stop, which sends no broadcast.
        scope.launch { Reminders.rescheduleAll(this@VoiceTodoApp) }
    }
}
