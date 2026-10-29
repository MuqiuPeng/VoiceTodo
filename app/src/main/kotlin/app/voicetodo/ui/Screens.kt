package app.voicetodo.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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

@Composable
fun HomeScreen(vm: MainViewModel, menu: @Composable () -> Unit, openSettings: () -> Unit) {
    val tasks by vm.tasks.collectAsState()
    val suggestions by vm.suggestions.collectAsState()
    val today = LocalDate.now()
    val open = tasks.filter { !it.done }
    val done = tasks.filter { it.done }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            menu()
            Text(
                "语音待办",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(suggestions, key = { "suggest-" + it.text }) { SuggestionCard(it, vm) }
            items(open, key = { it.id }) { TaskRow(it, today, vm) }
            if (done.isNotEmpty()) {
                item {
                    Text(
                        "已完成",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp, start = 4.dp),
                    )
                }
                items(done, key = { it.id }) { TaskRow(it, today, vm) }
            }
        }
        InputBar(vm, openSettings)
    }
}

@Composable
fun UpdateCard(state: UpdateState, updater: Updater) {
    fun mb(bytes: Long) = "%.0f MB".format(bytes / 1_048_576.0)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                UpdateState.Idle -> Unit
                UpdateState.Checking -> Text("正在检查更新…")
                UpdateState.UpToDate -> {
                    Text("已经是最新版本 v${updater.currentVersion}")
                    TextButton(onClick = updater::dismiss) { Text("好") }
                }
                is UpdateState.Available -> {
                    val r = state.release
                    Text("发现新版本 v${r.version}（${mb(r.size)}）", fontWeight = FontWeight.SemiBold)
                    if (r.notes.isNotBlank()) Text(r.notes, style = MaterialTheme.typography.bodySmall)
                    if (updater.isMetered) {
                        Text("当前在用移动数据，建议连上 Wi-Fi 再下载", color = Amber, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { updater.downloadAndInstall(r) }) { Text("下载并安装") }
                        TextButton(onClick = { updater.skip(r) }) { Text("跳过此版本") }
                    }
                }
                is UpdateState.Downloading -> {
                    val r = state.release
                    Text("正在下载 v${r.version}：${mb(state.done)} / ${mb(r.size)}")
                    LinearProgressIndicator(
                        progress = { if (r.size > 0) state.done.toFloat() / r.size else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = updater::cancelDownload) { Text("暂停") }
                }
                is UpdateState.Installing -> Text("正在安装 v${state.release.version}，请在系统弹窗中确认…")
                is UpdateState.Failed -> {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.release?.let { r -> Button(onClick = { updater.downloadAndInstall(r) }) { Text("重试") } }
                        TextButton(onClick = updater::dismiss) { Text("关闭") }
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
            Text("加入专有名词？以后会自动纠正", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, label = { Text("正确写法") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(heard, { heard = it }, label = { Text("常被听成") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = text.isNotBlank(), onClick = {
                    vm.addTerm(Term(text.trim(), splitAliases(heard)))
                    vm.dismissSuggestion(suggestion)
                }) { Text("加入") }
                TextButton(onClick = { vm.dismissSuggestion(suggestion) }) { Text("不用") }
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
        PageHeader("专有名词", onBack)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(text, { text = it }, label = { Text("正确写法，如：腾讯") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(heard, { heard = it }, label = { Text("常被听成（可不填，多个用逗号分开）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Button(enabled = text.isNotBlank(), onClick = {
                            vm.addTerm(Term(text.trim(), splitAliases(heard)))
                            text = ""
                            heard = ""
                        }) { Text("添加") }
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
                                    "常被听成：" + t.aliases.joinToString("、"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        TextButton(onClick = { vm.deleteTerm(t.text) }) { Text("删除") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskRow(task: Task, today: LocalDate, vm: MainViewModel) {
    var confirmDelete by remember { mutableStateOf(false) }
    val due = describeDue(task.dueDate, task.dueTime, task.dueKind, today)
    val overdue = !task.done && task.dueAt?.let { it < LocalDateTime.now() } == true
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = task.done, onCheckedChange = { vm.setDone(task, it) })
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (task.done) TextDecoration.LineThrough else null,
                    color = if (task.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                if (due != null) {
                    Text(
                        (if (overdue) "已到期 · " else "") + due,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                task.detail?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(onClick = { confirmDelete = true }) { Text("删除") }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这条任务？") },
            text = { Text(task.title) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(task) }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
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

    fun submit() {
        if (text.isBlank()) return
        vm.parseText(text.trim())
        text = ""
        focusManager.clearFocus()
    }

    val status = when {
        recording && cancelArmed -> "松开手指，取消这次录音"
        recording -> "正在听…松开结束，上滑取消"
        capture == Capture.Recognizing -> "识别中…"
        capture is Capture.Failed -> (capture as Capture.Failed).message
        engine is EngineState.Failed -> "语音模型加载失败：${(engine as EngineState.Failed).message}"
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
                                        if (capture == Capture.Recognizing) "识别中…" else "输入任务，或长按说话",
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
                                            !ready -> hint = "语音模型还在加载，请稍等几秒"
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
                    Button(onClick = ::submit, shape = CircleShape, contentPadding = PaddingValues(horizontal = 18.dp)) {
                        Text("发送")
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
        title = { Text("输入任务") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("例如：明天下午三点开会") },
                minLines = 2,
            )
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onSubmit(text) }) { Text("解析") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// --- confirmation ---------------------------------------------------------

@Composable
fun ConfirmScreen(c: Confirmation, vm: MainViewModel) {
    var editingTranscript by remember { mutableStateOf(false) }
    val count = c.drafts.count { it.include && it.title.isNotBlank() }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Text(
            "确认任务",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 20.dp, top = 16.dp),
        )
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "“${c.transcript}”",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { editingTranscript = true }) { Text("改文字") }
        }
        if (c.tidied) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tidySummary(c),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = vm::undoCorrections) { Text("撤回") }
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
            OutlinedButton(onClick = vm::dismissConfirmation, modifier = Modifier.weight(1f)) { Text("取消") }
            Button(onClick = vm::saveConfirmation, enabled = count > 0, modifier = Modifier.weight(1f)) {
                Text(if (count > 1) "保存 $count 条" else "保存")
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
private fun tidySummary(c: Confirmation): String = buildList {
    c.cleaned?.fillers?.distinct()?.takeIf { it.isNotEmpty() }?.let { add("去掉 " + it.joinToString("、")) }
    c.cleaned?.repairs?.takeIf { it.isNotEmpty() }?.let { r -> add("改口 " + r.joinToString("，") { "${it.first}→${it.second}" }) }
    if (c.corrections.isNotEmpty()) add("纠正 " + c.corrections.joinToString("，") { "${it.from}→${it.to}" })
}.joinToString(" · ", prefix = "已整理：")

private val LEADS: List<Duration?> = listOf(
    null, Duration.ZERO, Duration.ofMinutes(10), Duration.ofMinutes(30),
    Duration.ofHours(1), Duration.ofHours(2), Duration.ofDays(1),
)

@Composable
private fun DraftCard(d: Draft, multiple: Boolean, vm: MainViewModel) {
    val context = LocalContext.current
    val today = LocalDate.now()
    fun edit(change: (Draft) -> Draft) = vm.editDraft(d.key, change)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (multiple) Checkbox(checked = d.include, onCheckedChange = { v -> edit { it.copy(include = v) } })
                OutlinedTextField(
                    value = d.title,
                    onValueChange = { v -> edit { it.copy(title = v) } },
                    label = { Text("任务") },
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
                    label = { Text(d.date?.let { describeDate(it, today) } ?: "选日期") },
                )
                AssistChip(
                    onClick = {
                        val base = d.time ?: LocalTime.of(9, 0)
                        TimePickerDialog(context, { _, h, m ->
                            edit { it.copy(time = LocalTime.of(h, m), date = it.date ?: today) }
                        }, base.hour, base.minute, true).show()
                    },
                    label = { Text(d.time?.let(::describeTime) ?: "不限时间") },
                )
                if (d.time != null) {
                    AssistChip(onClick = { edit { it.copy(time = null) } }, label = { Text("去掉时间") })
                }
                if (d.date != null) {
                    val deadline = d.kind == MentionKind.DEADLINE
                    FilterChip(
                        selected = deadline,
                        onClick = { edit { it.copy(kind = if (deadline) MentionKind.AT else MentionKind.DEADLINE) } },
                        label = { Text(if (deadline) "截止时间" else "发生时间") },
                    )
                }
            }

            d.remindAt?.let {
                Text("提醒：${describeDate(it.toLocalDate(), today)} ${describeTime(it.toLocalTime())}", style = MaterialTheme.typography.bodySmall)
            }

            if (d.date != null) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LEADS.forEach { lead ->
                        FilterChip(
                            selected = d.remindBefore == lead,
                            onClick = { edit { it.copy(remindBefore = lead) } },
                            label = { Text(describeLead(lead)) },
                        )
                    }
                }
            }

            d.detail?.let { Text("备注：$it", style = MaterialTheme.typography.bodySmall) }

            d.warnings.forEach { w ->
                Text("⚠ " + describeWarning(w), color = Amber, style = MaterialTheme.typography.bodySmall)
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
                            label = { Text("改为 " + describeDate(alt.date, today) + (alt.time?.let { " " + describeTime(it) } ?: "")) },
                        )
                    }
                }
            }
        }
    }
}
