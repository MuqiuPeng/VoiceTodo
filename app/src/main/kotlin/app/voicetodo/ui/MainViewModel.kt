package app.voicetodo.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.voicetodo.VoiceTodoApp
import app.voicetodo.data.Task
import app.voicetodo.data.TaskStore
import app.voicetodo.parser.Due
import app.voicetodo.parser.MentionKind
import app.voicetodo.parser.TaskParser
import app.voicetodo.parser.Warning
import app.voicetodo.reminder.Reminders
import app.voicetodo.speech.Recorder
import app.voicetodo.speech.SpeechEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** One task on the confirmation screen, editable before it is saved. */
data class Draft(
    val key: Int,
    val include: Boolean = true,
    val title: String,
    val date: LocalDate?,
    val time: LocalTime?,
    val kind: MentionKind?,
    val remindAt: LocalDateTime?,
    val remindBefore: Duration?,
    val detail: String?,
    val source: String,
    val warnings: Set<Warning>,
    val alternatives: List<Due>,
)

data class Confirmation(val transcript: String, val drafts: List<Draft>)

sealed interface Capture {
    data object Idle : Capture
    data object Recording : Capture
    data object Recognizing : Capture
    data class Failed(val message: String) : Capture
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val voiceApp = app as VoiceTodoApp
    private val store = TaskStore.get(app)
    private val parser = TaskParser()
    private val recorder = Recorder()

    val tasks: StateFlow<List<Task>> = store.tasks
    val engineState = voiceApp.engineState
    val updater = voiceApp.updater

    private val _capture = MutableStateFlow<Capture>(Capture.Idle)
    val capture: StateFlow<Capture> = _capture

    private val _confirmation = MutableStateFlow<Confirmation?>(null)
    val confirmation: StateFlow<Confirmation?> = _confirmation

    fun startRecording() {
        if (!voiceApp.speech.isReady) return
        recorder.start()
        _capture.value = Capture.Recording
    }

    fun stopRecording() {
        if (_capture.value != Capture.Recording) return
        val samples = recorder.stop()
        // Under half a second is a tap, not speech.
        if (samples.size < SpeechEngine.SAMPLE_RATE / 2) {
            _capture.value = Capture.Failed("按住按钮说话，说完再松开")
            return
        }
        recognize(samples)
    }

    /** Debug builds only: feed a WAV instead of the microphone (the emulator has no voice). */
    fun recognizeFile(path: String) = viewModelScope.launch {
        voiceApp.engineState.first { it != VoiceTodoApp.EngineState.Loading }
        recognize(SpeechEngine.readWav(File(path)))
    }

    private fun recognize(samples: FloatArray) {
        _capture.value = Capture.Recognizing
        viewModelScope.launch {
            try {
                val text = voiceApp.speech.transcribe(samples)
                if (text.isBlank()) {
                    _capture.value = Capture.Failed("没听清，请再说一次")
                } else {
                    _capture.value = Capture.Idle
                    parseText(text)
                }
            } catch (e: Throwable) {
                _capture.value = Capture.Failed("识别失败：${e.message}")
            }
        }
    }

    fun parseText(text: String) {
        val result = parser.parse(text, LocalDateTime.now())
        val drafts = result.tasks.mapIndexed { i, t ->
            Draft(
                key = i,
                title = t.title,
                date = t.due?.date,
                time = t.due?.time,
                kind = t.dueKind,
                remindAt = t.remindAt?.let { it.date.atTime(it.time ?: LocalTime.of(9, 0)) },
                remindBefore = t.remindBefore,
                detail = t.detail,
                source = t.source,
                warnings = t.warnings,
                alternatives = t.alternatives,
            )
        }.ifEmpty { listOf(blankDraft(text)) }
        _confirmation.value = Confirmation(text, drafts)
    }

    private fun blankDraft(text: String) = Draft(
        key = 0, title = text, date = null, time = null, kind = null, remindAt = null,
        remindBefore = null, detail = null, source = text, warnings = emptySet(), alternatives = emptyList(),
    )

    fun editDraft(key: Int, change: (Draft) -> Draft) {
        val c = _confirmation.value ?: return
        _confirmation.value = c.copy(drafts = c.drafts.map { if (it.key == key) change(it) else it })
    }

    fun dismissConfirmation() {
        _confirmation.value = null
    }

    fun dismissError() {
        if (_capture.value is Capture.Failed) _capture.value = Capture.Idle
    }

    fun saveConfirmation() {
        val c = _confirmation.value ?: return
        val ctx = getApplication<Application>()
        for (d in c.drafts.filter { it.include && it.title.isNotBlank() }) {
            val task = Task(
                title = d.title.trim(),
                detail = d.detail,
                dueDate = d.date,
                dueTime = d.time,
                dueKind = d.kind?.name,
                remindAt = d.remindAt,
                remindBefore = d.remindBefore,
                source = c.transcript,
            )
            val id = store.insert(task)
            Reminders.schedule(ctx, task.copy(id = id))
        }
        _confirmation.value = null
    }

    fun setDone(task: Task, done: Boolean) {
        val updated = task.copy(done = done)
        store.update(updated)
        val ctx = getApplication<Application>()
        if (done) Reminders.cancel(ctx, task.id) else Reminders.schedule(ctx, updated)
    }

    fun delete(task: Task) {
        Reminders.cancel(getApplication(), task.id)
        store.delete(task.id)
    }
}
