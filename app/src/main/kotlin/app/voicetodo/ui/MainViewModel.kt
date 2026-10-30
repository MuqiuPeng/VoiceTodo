package app.voicetodo.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.voicetodo.R
import app.voicetodo.VoiceTodoApp
import app.voicetodo.localized
import app.voicetodo.data.Task
import app.voicetodo.data.TaskStore
import app.voicetodo.data.VocabularyStore
import app.voicetodo.parser.Cleaned
import app.voicetodo.parser.Correction
import app.voicetodo.parser.Disfluency
import app.voicetodo.parser.EnglishDisfluency
import app.voicetodo.parser.EnglishParser
import app.voicetodo.parser.looksEnglish
import app.voicetodo.parser.Term
import app.voicetodo.parser.VocabularyCorrector
import app.voicetodo.parser.suggestTerm
import app.voicetodo.parser.Due
import app.voicetodo.parser.MentionKind
import app.voicetodo.parser.TaskParser
import app.voicetodo.parser.Warning
import app.voicetodo.reminder.Reminders
import app.voicetodo.speech.Recorder
import app.voicetodo.topics.Topic
import app.voicetodo.topics.TopicStore
import app.voicetodo.speech.SpeechPack
import app.voicetodo.speech.SpeechEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    /** The title as parsed, to tell a hand-corrected name from no edit at all. */
    val parsedTitle: String = title,
)

data class Confirmation(
    /** What will be saved as the task's source: the text after vocabulary corrections. */
    val transcript: String,
    val drafts: List<Draft>,
    /** What the recognizer actually wrote, for undoing the corrections. */
    val heard: String = transcript,
    val corrections: List<Correction> = emptyList(),
    /** Hesitations and self-corrections taken out before parsing. */
    val cleaned: Cleaned? = null,
) {
    val tidied: Boolean get() = corrections.isNotEmpty() || cleaned?.changed == true
}

sealed interface Capture {
    data object Idle : Capture
    data object Recording : Capture
    data object Recognizing : Capture
    data class Failed(val message: String) : Capture
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val voiceApp = app as VoiceTodoApp
    private val store = TaskStore.get(app)
    private val vocabulary = VocabularyStore.get(app)
    private val parser = TaskParser()
    private val englishParser = EnglishParser()
    private val recorder = Recorder()

    val tasks: StateFlow<List<Task>> = store.tasks
    val engineState = voiceApp.engineState
    val updater = voiceApp.updater
    val packs = voiceApp.packs
    val sources = voiceApp.sources

    fun deleteSpeechPack(pack: SpeechPack) = voiceApp.deleteSpeechPack(pack)

    private fun text(id: Int, vararg args: Any) = getApplication<Application>().localized().getString(id, *args)

    val recordings = voiceApp.recordings

    /** The audio of the utterance on the confirmation card, saved with its tasks on 保存. */
    private var pendingAudio: FloatArray? = null

    private val _capture = MutableStateFlow<Capture>(Capture.Idle)
    val capture: StateFlow<Capture> = _capture

    private val _confirmation = MutableStateFlow<Confirmation?>(null)
    val confirmation: StateFlow<Confirmation?> = _confirmation

    val terms: StateFlow<List<Term>> = vocabulary.terms

    private val topicStore = TopicStore.get(app)
    val topics: StateFlow<List<Topic>> = topicStore.topics

    fun putTopic(t: Topic) = topicStore.put(t)

    fun deleteTopic(t: Topic) {
        topicStore.delete(t.id)
        // Tasks put there by hand go back to automatic.
        store.tasks.value.filter { it.topic == t.id }.forEach { store.update(it.copy(topic = null)) }
    }

    /** A hand-picked topic; picking "none" stores "" so classification stays off for this task. */
    fun setTaskTopic(task: Task, topic: Topic?) = store.update(task.copy(topic = topic?.id ?: ""))

    /** Names the user just fixed by hand, offered for the vocabulary. */
    private val _suggestions = MutableStateFlow<List<Term>>(emptyList())
    val suggestions: StateFlow<List<Term>> = _suggestions

    fun startRecording() {
        if (!voiceApp.speech.isReady) return
        recorder.start()
        _capture.value = Capture.Recording
    }

    // --- hands-free recording, for voice started from the widget ------------

    private val _handsFree = MutableStateFlow(false)

    /** Recording that ends by itself on silence (or on ■), not on releasing a press. */
    val handsFree: StateFlow<Boolean> = _handsFree

    /**
     * A widget cannot be held down, so voice from the widget records until the
     * speaker stops: ~1.6 s of quiet after speech was heard ends it, 7 s with
     * no speech at all gives up, 30 s is the ceiling. Waits for the speech
     * model if the app was cold-started by the widget.
     */
    fun startHandsFree() = viewModelScope.launch {
        val engine = withTimeoutOrNull(20_000) {
            voiceApp.engineState.first { it != VoiceTodoApp.EngineState.Loading }
        }
        if (engine != VoiceTodoApp.EngineState.Ready) return@launch
        startRecording()
        _handsFree.value = true
        val started = System.currentTimeMillis()
        var heard = false
        var quietSince = 0L
        while (_capture.value == Capture.Recording && _handsFree.value) {
            delay(100)
            val now = System.currentTimeMillis()
            val level = recorder.level.value
            when {
                level > SPEECH_LEVEL -> { heard = true; quietSince = 0L }
                heard && level < QUIET_LEVEL -> if (quietSince == 0L) quietSince = now
            }
            when {
                heard && quietSince != 0L && now - quietSince > 1_600 -> stopRecording()
                !heard && now - started > 7_000 -> {
                    cancelRecording()
                    _capture.value = Capture.Failed(text(R.string.not_heard))
                }
                now - started > 30_000 -> stopRecording()
            }
        }
        _handsFree.value = false
    }

    fun stopRecording() {
        if (_capture.value != Capture.Recording) return
        _handsFree.value = false
        val samples = recorder.stop()
        // Under half a second is a tap, not speech.
        if (samples.size < SpeechEngine.SAMPLE_RATE / 2) {
            _capture.value = Capture.Failed(text(R.string.hold_longer))
            return
        }
        // Silence is a microphone problem, not something to recognize: say so,
        // rather than "didn't catch that", which sounds like the model's fault.
        if (recorder.peak < 0.01f) {
            _capture.value = Capture.Failed(text(R.string.no_sound))
            return
        }
        recognize(samples)
    }

    /** Slid away while holding: the recording is thrown away unheard. */
    fun cancelRecording() {
        if (_capture.value != Capture.Recording) return
        _handsFree.value = false
        recorder.stop()
        _capture.value = Capture.Idle
    }

    val level: StateFlow<Float> = recorder.level

    // --- quick actions from the widget -------------------------------------

    enum class Quick { TYPE, VOICE }

    private val _quick = MutableStateFlow<Quick?>(null)

    /**
     * Held until the input bar acts on it: the activity may be showing another
     * page (or not composed yet) when the widget's intent arrives.
     */
    val quick: StateFlow<Quick?> = _quick

    fun requestQuick(q: Quick) {
        _confirmation.value = null
        _quick.value = q
    }

    fun quickHandled() {
        _quick.value = null
    }

    private val _page = MutableStateFlow<Page?>(null)

    /** A page another entry point asked for (the quick-add panel opening Settings). */
    val requestedPage: StateFlow<Page?> = _page

    fun requestPage(p: Page) {
        _page.value = p
    }

    fun pageHandled() {
        _page.value = null
    }

    /** Debug builds only: feed a WAV instead of the microphone (the emulator has no voice). */
    fun recognizeFile(path: String) = viewModelScope.launch {
        voiceApp.engineState.first { it != VoiceTodoApp.EngineState.Loading }
        recognize(SpeechEngine.readWav(File(path)))
    }

    private fun recognize(samples: FloatArray) {
        _capture.value = Capture.Recognizing
        pendingAudio = samples
        viewModelScope.launch {
            try {
                val text = voiceApp.speech.transcribe(samples)
                if (text.isBlank()) {
                    _capture.value = Capture.Failed(text(R.string.not_heard))
                } else {
                    _capture.value = Capture.Idle
                    parseText(text)
                }
            } catch (e: Throwable) {
                _capture.value = Capture.Failed(text(R.string.recognize_failed, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    /**
     * Strips hesitations, applies the user's vocabulary, then parses. With
     * `correct = false` (the undo on the confirmation card) the text stays as
     * heard, but vocabulary terms are still kept from being read as times.
     */
    fun parseText(heard: String, correct: Boolean = true) {
        val terms = vocabulary.terms.value
        val english = looksEnglish(heard)
        val cleaned = when {
            !correct -> null
            english -> EnglishDisfluency.clean(heard)
            else -> Disfluency.clean(heard, terms.map { it.text })
        }
        // All filler ("嗯嗯") leaves nothing; better to show what was heard than an empty card.
        val base = cleaned?.text?.ifBlank { null } ?: heard
        val corrected = if (correct) VocabularyCorrector(terms).correct(base) else null
        val text = corrected?.text ?: base
        val result = if (english) englishParser.parse(text, LocalDateTime.now(), terms.map { it.text })
        else parser.parse(text, LocalDateTime.now(), terms.map { it.text })
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
        _confirmation.value = Confirmation(text, drafts, heard, corrected?.corrections ?: emptyList(), cleaned)
    }

    /** Typed input has no recording to keep. */
    fun parseTyped(text: String) {
        pendingAudio = null
        parseText(text)
    }

    fun undoCorrections() {
        _confirmation.value?.let { parseText(it.heard, correct = false) }
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
        pendingAudio = null
        _confirmation.value = null
    }

    fun dismissError() {
        if (_capture.value is Capture.Failed) _capture.value = Capture.Idle
    }

    fun saveConfirmation() {
        val c = _confirmation.value ?: return
        val ctx = getApplication<Application>()
        val saving = c.drafts.filter { it.include && it.title.isNotBlank() }
        // One file for the whole utterance, shared by every task it became.
        val audio = pendingAudio?.takeIf { saving.isNotEmpty() }?.let { recordings.save(it, SpeechEngine.SAMPLE_RATE) }
        pendingAudio = null
        for (d in saving) {
            val task = Task(
                title = d.title.trim(),
                detail = d.detail,
                dueDate = d.date,
                dueTime = d.time,
                dueKind = d.kind?.name,
                remindAt = d.remindAt,
                remindBefore = d.remindBefore,
                source = c.transcript,
                audio = audio,
            )
            val id = store.insert(task)
            Reminders.schedule(ctx, task.copy(id = id))
        }
        val known = vocabulary.terms.value.map { it.text }.toSet()
        _suggestions.value = c.drafts
            .mapNotNull { suggestTerm(it.parsedTitle, it.title.trim()) }
            .filter { it.text !in known }
            .distinctBy { it.text }
        _confirmation.value = null
    }

    fun addTerm(term: Term) = vocabulary.put(term)

    fun dismissSuggestion(term: Term) {
        _suggestions.value = _suggestions.value - term
    }

    fun deleteTerm(text: String) = vocabulary.delete(text)

    fun setDone(task: Task, done: Boolean) {
        val updated = task.copy(done = done)
        store.update(updated)
        val ctx = getApplication<Application>()
        if (done) Reminders.cancel(ctx, task.id) else Reminders.schedule(ctx, updated)
    }

    // --- deleting, with undo ---------------------------------------------

    private val _deleted = MutableStateFlow<Task?>(null)

    /** The last task swiped away, while its undo is still on offer. */
    val deleted: StateFlow<Task?> = _deleted

    fun delete(task: Task) {
        _deleted.value?.let(::finishDelete)
        recordings.stop()
        Reminders.cancel(getApplication(), task.id)
        store.delete(task.id)
        _deleted.value = task
    }

    private val _restores = MutableStateFlow(0)

    /**
     * Bumped on every undo. A restored task comes back under its old id, and
     * the list would hand it its old swipe state — already swiped away, which
     * deletes it again. Rows key their swipe state on this to start fresh.
     */
    val restores: StateFlow<Int> = _restores

    fun undoDelete(task: Task) {
        if (_deleted.value?.id != task.id) return
        _deleted.value = null
        _restores.value++
        store.restore(task)
        if (!task.done) Reminders.schedule(getApplication(), task)
    }

    /** The undo has expired: now the recording can go, unless another task shares it. */
    fun finishDelete(task: Task) {
        if (_deleted.value?.id == task.id) _deleted.value = null
        task.audio?.let { recordings.deleteUnreferenced(listOf(it), store.tasks.value) }
    }

    // --- recordings ------------------------------------------------------

    /** Recordings only completed tasks still use: what 删除已完成任务的录音 would free. */
    fun doneRecordings(tasks: List<Task>): List<String> {
        val open = tasks.filter { !it.done }.mapNotNull { it.audio }.toSet()
        return tasks.filter { it.done }.mapNotNull { it.audio }.distinct().filter { it !in open }
    }

    fun deleteDoneRecordings() {
        recordings.stop()
        val all = store.tasks.value
        val names = doneRecordings(all).toSet()
        all.filter { it.done && it.audio in names }.forEach { store.update(it.copy(audio = null)) }
        recordings.deleteUnreferenced(names, store.tasks.value)
    }

    private companion object {
        /** Recorder.level units (RMS / 4000): talking sits well above 0.12, a quiet room below 0.06. */
        const val SPEECH_LEVEL = 0.12f
        const val QUIET_LEVEL = 0.06f
    }
}
