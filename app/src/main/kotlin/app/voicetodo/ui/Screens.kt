package app.voicetodo.ui

import android.Manifest
import android.content.res.Resources
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.voicetodo.R
import app.voicetodo.VoiceTodoApp.EngineState
import app.voicetodo.data.Task
import app.voicetodo.parser.MentionKind
import app.voicetodo.parser.Term
import app.voicetodo.update.UpdateState
import app.voicetodo.update.Updater
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

private val Amber = Color(0xFFB26A00)

// --- home -------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(vm: MainViewModel, menu: @Composable () -> Unit, openSettings: () -> Unit) {
    val tasks by vm.tasks.collectAsState()
    val suggestions by vm.suggestions.collectAsState()
    val today = LocalDate.now()
    val snackbar = remember { SnackbarHostState() }
    val res = LocalContext.current.resources
    val deleted by vm.deleted.collectAsState()
    LaunchedEffect(deleted) {
        val t = deleted ?: return@LaunchedEffect
        val r = snackbar.showSnackbar(res.getString(R.string.deleted, t.title), actionLabel = res.getString(R.string.undo), duration = SnackbarDuration.Long)
        if (r == SnackbarResult.ActionPerformed) vm.undoDelete(t) else vm.finishDelete(t)
    }

    val focusManager = LocalFocusManager.current
    // The input field only takes a long press (talk) while it is not focused,
    // so it must not stay focused once the user is done with it: closing the
    // keyboard with Back drops focus too.
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) { if (!imeVisible) focusManager.clearFocus() }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Column(
            Modifier
                .weight(1f)
                // Any touch above the input bar ends typing. Watched in the
                // Initial pass and never consumed, so the list, swipes and
                // buttons underneath still get the touch as usual.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        focusManager.clearFocus()
                    }
                },
        ) {
            Box(Modifier.padding(start = 8.dp, top = 8.dp)) { menu() }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(suggestions, key = { "suggest-" + it.text }) { SuggestionCard(it, vm) }
                // One list: open tasks first (by due time), completed ones after, dimmed rather than set apart.
                items(tasks, key = { it.id }) { TaskRow(it, today, vm) }
            }
        }
        SnackbarHost(snackbar)
        InputBar(vm, openSettings)
    }
}

@Composable
fun UpdateCard(state: UpdateState, updater: Updater) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                UpdateState.Idle -> Unit
                UpdateState.Checking -> Text(stringResource(R.string.update_checking))
                UpdateState.UpToDate -> {
                    Text(stringResource(R.string.update_latest, "v${updater.currentVersion}"))
                    TextButton(onClick = updater::dismiss) { Text(stringResource(R.string.ok)) }
                }
                is UpdateState.Available -> {
                    val r = state.release
                    Text(stringResource(R.string.update_available, "v${r.version}", mb(r.size)), fontWeight = FontWeight.SemiBold)
                    if (r.notes.isNotBlank()) Text(r.notes, style = MaterialTheme.typography.bodySmall)
                    if (updater.isMetered) {
                        Text(stringResource(R.string.update_metered), color = Amber, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { updater.downloadAndInstall(r) }) { Text(stringResource(R.string.update_install)) }
                        TextButton(onClick = { updater.skip(r) }) { Text(stringResource(R.string.update_skip)) }
                    }
                }
                is UpdateState.Downloading -> {
                    val r = state.release
                    Text(stringResource(R.string.update_downloading, "v${r.version}", mb(state.done), mb(r.size)))
                    LinearProgressIndicator(
                        progress = { if (r.size > 0) state.done.toFloat() / r.size else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = updater::cancelDownload) { Text(stringResource(R.string.pause)) }
                }
                is UpdateState.Installing -> Text(stringResource(R.string.update_installing, "v${state.release.version}"))
                is UpdateState.Failed -> {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.release?.let { r -> Button(onClick = { updater.downloadAndInstall(r) }) { Text(stringResource(R.string.retry)) } }
                        TextButton(onClick = updater::dismiss) { Text(stringResource(R.string.close)) }
                    }
                }
            }
        }
    }
}

/** "You fixed 疼讯 to 腾讯 — remember it?" Both fields stay editable: the guess at the word's edges can be off. */
@Composable
private fun SuggestionCard(suggestion: Term, vm: MainViewModel) {
    var text by remember(suggestion) { mutableStateOf(suggestion.text) }
    var heard by remember(suggestion) { mutableStateOf(suggestion.aliases.joinToString("，")) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.suggest_title), fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.spelling)) }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(heard, { heard = it }, label = { Text(stringResource(R.string.heard_as)) }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = text.isNotBlank(), onClick = {
                    vm.addTerm(Term(text.trim(), splitAliases(heard)))
                    vm.dismissSuggestion(suggestion)
                }) { Icon(Icons.Filled.Check, stringResource(R.string.add)) }
                OutlinedButton(onClick = { vm.dismissSuggestion(suggestion) }) { Icon(Icons.Filled.Close, stringResource(R.string.no_thanks)) }
            }
        }
    }
}

// Not on spaces: "open ai" is one alias.
private fun splitAliases(s: String) = s.split(',', '，', '、').map(String::trim).filter(String::isNotEmpty)

@Composable
fun VocabularyScreen(vm: MainViewModel, onBack: () -> Unit) {
    val terms by vm.terms.collectAsState()
    var text by remember { mutableStateOf("") }
    var heard by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        PageHeader(stringResource(R.string.vocabulary), onBack)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.spelling_example)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(heard, { heard = it }, label = { Text(stringResource(R.string.heard_as_optional)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Button(enabled = text.isNotBlank(), onClick = {
                            vm.addTerm(Term(text.trim(), splitAliases(heard)))
                            text = ""
                            heard = ""
                        }) { Icon(Icons.Filled.Add, stringResource(R.string.add_term)) }
                    }
                }
            }
            items(terms, key = { it.text }) { t ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.text, style = MaterialTheme.typography.bodyLarge)
                            if (t.aliases.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.heard_as_list, t.aliases.joinToString(" · ")),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        IconButton(onClick = { vm.deleteTerm(t.text) }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
                    }
                }
            }
        }
    }
}

/**
 * Swipe left to complete (or, in 已完成, to bring back), swipe right to
 * delete; the deletion can be undone from the snackbar. A task that was
 * spoken has its recording on a play button.
 */
@Composable
private fun TaskRow(task: Task, today: LocalDate, vm: MainViewModel) {
    val due = describeDue(task.dueDate, task.dueTime, task.dueKind, today, LocalContext.current.resources)
    val overdue = !task.done && task.dueAt?.let { it < LocalDateTime.now() } == true
    val playing by vm.recordings.playing.collectAsState()
    val shape = RoundedCornerShape(12.dp)
    val restores by vm.restores.collectAsState()
    // Keyed so a row that moved between sections, or came back from an undo, starts unswiped.
    key(task.done, restores) {
        val state = rememberSwipeToDismissBoxState()
        SwipeToDismissBox(
            state = state,
            backgroundContent = {
                val deleting = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
                val completing = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(shape)
                        .background(
                            when {
                                deleting -> MaterialTheme.colorScheme.error
                                completing -> MaterialTheme.colorScheme.primary
                                else -> Color.Transparent
                            },
                        )
                        .padding(horizontal = 24.dp),
                    contentAlignment = if (deleting) Alignment.CenterStart else Alignment.CenterEnd,
                ) {
                    if (deleting || completing) {
                        Icon(
                            when {
                                deleting -> Icons.Filled.Delete
                                task.done -> Icons.Filled.Refresh
                                else -> Icons.Filled.Check
                            },
                            stringResource(
                                when {
                                    deleting -> R.string.swipe_delete
                                    task.done -> R.string.swipe_restore
                                    else -> R.string.swipe_done
                                },
                            ),
                            tint = if (deleting) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            },
            onDismiss = { direction ->
                when (direction) {
                    SwipeToDismissBoxValue.StartToEnd -> vm.delete(task)
                    SwipeToDismissBoxValue.EndToStart -> vm.setDone(task, !task.done)
                    SwipeToDismissBoxValue.Settled -> Unit
                }
            },
        ) {
            // Open tasks stand out (raised, bold, a coloured edge when they have a
            // time); completed ones recede (flat, faded, struck through).
            val scheduled = !task.done && task.dueDate != null
            Card(
                Modifier.fillMaxWidth().alpha(if (task.done) 0.45f else 1f),
                shape = shape,
                colors = CardDefaults.cardColors(
                    containerColor = if (task.done) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = if (task.done) 0.dp else 2.dp),
            ) {
                Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .width(5.dp)
                            .fillMaxHeight()
                            .background(
                                when {
                                    overdue -> MaterialTheme.colorScheme.error
                                    scheduled -> MaterialTheme.colorScheme.primary
                                    else -> Color.Transparent
                                },
                            ),
                    )
                Row(
                    Modifier.weight(1f).padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            task.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (task.done) FontWeight.Normal else FontWeight.SemiBold,
                            textDecoration = if (task.done) TextDecoration.LineThrough else null,
                            color = if (task.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                        if (due != null) {
                            Text(
                                if (overdue) stringResource(R.string.overdue, due) else due,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (scheduled) FontWeight.Medium else FontWeight.Normal,
                                color = when {
                                    overdue -> MaterialTheme.colorScheme.error
                                    scheduled -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        task.detail?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    task.audio?.let { audio ->
                        TextButton(onClick = { vm.recordings.toggle(audio) }) {
                            if (playing == audio) {
                                Icon(Glyphs.Stop, null, Modifier.size(20.dp))
                            } else {
                                Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp))
                                Text(vm.recordings.seconds(audio).let { "%d:%02d".format(it / 60, it % 60) })
                            }
                        }
                    }
                }
                }
            }
        }
    }
}

/**
 * One field for both ways in: a tap puts the cursor in it to type, a long
 * press records until release (slide up to throw the recording away).
 * While the field has focus it is an ordinary text field, so long-press
 * selection still works when editing.
 */
@Composable
private fun InputBar(vm: MainViewModel, openSettings: () -> Unit) {
    val context = LocalContext.current
    val engine by vm.engineState.collectAsState()
    val capture by vm.capture.collectAsState()
    val level by vm.level.collectAsState()
    var text by rememberSaveable { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    var cancelArmed by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val haptics = LocalHapticFeedback.current
    var micGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micGranted = it }
    val ready = engine == EngineState.Ready
    val recording = capture == Capture.Recording
    val loadingHint = stringResource(R.string.engine_loading)

    fun submit() {
        if (text.isBlank()) return
        vm.parseTyped(text.trim())
        text = ""
        focusManager.clearFocus()
    }

    val status = when {
        recording && cancelArmed -> stringResource(R.string.release_to_cancel)
        recording -> stringResource(R.string.listening)
        capture == Capture.Recognizing -> stringResource(R.string.recognizing)
        capture is Capture.Failed -> (capture as Capture.Failed).message
        engine is EngineState.Failed -> stringResource(R.string.engine_failed, (engine as EngineState.Failed).message)
        else -> hint
    }

    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (status != null) {
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (capture is Capture.Failed || cancelArmed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val fieldColor by animateColorAsState(
                    when {
                        recording && cancelArmed -> MaterialTheme.colorScheme.errorContainer
                        recording -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    label = "field",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(fieldColor),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (recording) {
                        // Bars that follow the voice: proof the phone is hearing something.
                        Row(
                            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val shape = listOf(0.5f, 0.8f, 1f, 0.7f, 0.9f, 0.6f, 1f, 0.75f, 0.55f)
                            shape.forEach { k ->
                                Box(
                                    Modifier
                                        .width(4.dp)
                                        .height((6 + 30 * level * k).dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(if (cancelArmed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary),
                                )
                            }
                        }
                    } else {
                        BasicTextField(
                            value = text,
                            onValueChange = { text = it },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { submit() }),
                            maxLines = 4,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 14.dp)
                                .focusRequester(focusRequester)
                                .onFocusChanged { focused = it.isFocused },
                            decorationBox = { inner ->
                                if (text.isEmpty()) {
                                    Text(
                                        stringResource(if (capture == Capture.Recognizing) R.string.recognizing else R.string.input_hint),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                inner()
                            },
                        )
                    }
                    if (!focused) {
                        // Over the idle field: decide between tap (type) and hold (talk).
                        Box(
                            Modifier
                                .matchParentSize()
                                .pointerInput(ready, micGranted) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown()
                                        val tapped = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                            waitForUpOrCancellation()
                                            true
                                        }
                                        if (tapped != null) {
                                            hint = null
                                            vm.dismissError()
                                            focusRequester.requestFocus()
                                            keyboard?.show()
                                            return@awaitEachGesture
                                        }
                                        when {
                                            engine == EngineState.Missing -> openSettings()
                                            !ready -> hint = loadingHint
                                            !micGranted -> micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            else -> {
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                hint = null
                                                vm.dismissError()
                                                vm.startRecording()
                                                val cancelDistance = 80.dp.toPx()
                                                do {
                                                    val change = awaitPointerEvent().changes.first()
                                                    cancelArmed = down.position.y - change.position.y > cancelDistance
                                                    change.consume()
                                                } while (change.pressed)
                                                if (cancelArmed) vm.cancelRecording() else vm.stopRecording()
                                                cancelArmed = false
                                                return@awaitEachGesture
                                            }
                                        }
                                        // Not recording: still swallow the rest of the press.
                                        do {
                                            val change = awaitPointerEvent().changes.first()
                                        } while (change.pressed)
                                    }
                                },
                        )
                    }
                }
                if (capture == Capture.Recognizing || (engine == EngineState.Loading && !focused && text.isEmpty())) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(8.dp))
                }
                if (text.isNotBlank() && !recording) {
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(onClick = ::submit, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.send))
                    }
                }
            }
        }
    }
}

@Composable
private fun TextInputDialog(initial: String, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.type_task)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.type_example)) },
                minLines = 2,
            )
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onSubmit(text) }) { Text(stringResource(R.string.parse)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

// --- confirmation ---------------------------------------------------------

@Composable
fun ConfirmScreen(c: Confirmation, vm: MainViewModel) {
    var editingTranscript by remember { mutableStateOf(false) }
    val count = c.drafts.count { it.include && it.title.isNotBlank() }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "“${c.transcript}”",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { editingTranscript = true }) { Icon(Icons.Filled.Edit, stringResource(R.string.edit_text)) }
        }
        if (c.tidied) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tidySummary(c, LocalContext.current.resources),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                // No undo glyph in the core icon set; ↺ reads as one.
                IconButton(onClick = vm::undoCorrections) { Text("↺", fontSize = 22.sp, color = MaterialTheme.colorScheme.primary) }
            }
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(c.drafts, key = { it.key }) { d -> DraftCard(d, multiple = c.drafts.size > 1, vm) }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = vm::dismissConfirmation, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Close, stringResource(R.string.cancel))
            }
            Button(onClick = vm::saveConfirmation, enabled = count > 0, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Check, stringResource(R.string.save))
                // Several tasks: the count says how many will be saved.
                if (count > 1) Text("  $count")
            }
        }
    }
    if (editingTranscript) {
        TextInputDialog(
            initial = c.transcript,
            onDismiss = { editingTranscript = false },
            onSubmit = { editingTranscript = false; vm.parseText(it) },
        )
    }
}

/** "去掉 嗯、那个 · 改口 明天→后天 · 纠正 疼讯→腾讯" — what the undo next to it would bring back. */
private fun tidySummary(c: Confirmation, res: Resources): String = res.getString(
    R.string.tidy_prefix,
    buildList {
        c.cleaned?.fillers?.distinct()?.takeIf { it.isNotEmpty() }?.let { add(res.getString(R.string.tidy_fillers, it.joinToString(" "))) }
        c.cleaned?.repairs?.takeIf { it.isNotEmpty() }?.let { r -> add(res.getString(R.string.tidy_repairs, r.joinToString(" ") { "${it.first}→${it.second}" })) }
        if (c.corrections.isNotEmpty()) add(res.getString(R.string.tidy_corrections, c.corrections.joinToString(" ") { "${it.from}→${it.to}" }))
    }.joinToString(" · "),
)

private val LEADS: List<Duration?> = listOf(
    null, Duration.ZERO, Duration.ofMinutes(10), Duration.ofMinutes(30),
    Duration.ofHours(1), Duration.ofHours(2), Duration.ofDays(1),
)

@Composable
private fun DraftCard(d: Draft, multiple: Boolean, vm: MainViewModel) {
    val context = LocalContext.current
    val res = context.resources
    val today = LocalDate.now()
    fun edit(change: (Draft) -> Draft) = vm.editDraft(d.key, change)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (multiple) Checkbox(checked = d.include, onCheckedChange = { v -> edit { it.copy(include = v) } })
                OutlinedTextField(
                    value = d.title,
                    onValueChange = { v -> edit { it.copy(title = v) } },
                    label = { Text(stringResource(R.string.task_label)) },
                    modifier = Modifier.weight(1f),
                )
            }

            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = {
                        val base = d.date ?: today
                        DatePickerDialog(context, { _, y, m, day ->
                            edit { it.copy(date = LocalDate.of(y, m + 1, day), warnings = it.warnings - app.voicetodo.parser.Warning.INVALID_DATE) }
                        }, base.year, base.monthValue - 1, base.dayOfMonth).show()
                    },
                    label = { Text(d.date?.let { describeDate(it, today, res) } ?: stringResource(R.string.pick_date)) },
                )
                AssistChip(
                    onClick = {
                        val base = d.time ?: LocalTime.of(9, 0)
                        TimePickerDialog(context, { _, h, m ->
                            edit { it.copy(time = LocalTime.of(h, m), date = it.date ?: today) }
                        }, base.hour, base.minute, true).show()
                    },
                    label = { Text(d.time?.let(::describeTime) ?: stringResource(R.string.any_time)) },
                    trailingIcon = if (d.time != null) {
                        {
                            Icon(
                                Icons.Filled.Close,
                                stringResource(R.string.clear_time),
                                Modifier.size(18.dp).clickable { edit { it.copy(time = null) } },
                            )
                        }
                    } else null,
                )
                if (d.date != null) {
                    val deadline = d.kind == MentionKind.DEADLINE
                    FilterChip(
                        selected = deadline,
                        onClick = { edit { it.copy(kind = if (deadline) MentionKind.AT else MentionKind.DEADLINE) } },
                        label = { Text(stringResource(if (deadline) R.string.kind_deadline else R.string.kind_at)) },
                    )
                }
            }

            d.remindAt?.let {
                Text(stringResource(R.string.remind_at, describeDate(it.toLocalDate(), today, res) + " " + describeTime(it.toLocalTime())), style = MaterialTheme.typography.bodySmall)
            }

            if (d.date != null) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LEADS.forEach { lead ->
                        FilterChip(
                            selected = d.remindBefore == lead,
                            onClick = { edit { it.copy(remindBefore = lead) } },
                            label = { Text(describeLead(lead, res)) },
                        )
                    }
                }
            }

            d.detail?.let { Text(stringResource(R.string.detail, it), style = MaterialTheme.typography.bodySmall) }

            d.warnings.forEach { w ->
                Text("⚠ " + describeWarning(w, res), color = Amber, style = MaterialTheme.typography.bodySmall)
            }
            if (d.alternatives.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    d.alternatives.forEach { alt ->
                        AssistChip(
                            onClick = {
                                edit {
                                    it.copy(
                                        date = alt.date,
                                        time = alt.time,
                                        alternatives = emptyList(),
                                        warnings = it.warnings - setOf(
                                            app.voicetodo.parser.Warning.AMBIGUOUS_AM_PM,
                                            app.voicetodo.parser.Warning.AMBIGUOUS_NEXT_WEEK,
                                        ),
                                    )
                                }
                            },
                            label = { Text(stringResource(R.string.change_to, describeDate(alt.date, today, res) + (alt.time?.let { " " + describeTime(it) } ?: ""))) },
                        )
                    }
                }
            }
        }
    }
}
