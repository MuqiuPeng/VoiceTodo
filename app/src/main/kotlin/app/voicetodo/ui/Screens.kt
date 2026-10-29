package app.voicetodo.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.voicetodo.VoiceTodoApp.EngineState
import app.voicetodo.data.Task
import app.voicetodo.parser.MentionKind
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

private val Amber = Color(0xFFB26A00)

@Composable
fun AppScreen(vm: MainViewModel, setup: () -> SetupState, openSetting: (SetupItem) -> Unit) {
    val confirmation by vm.confirmation.collectAsState()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val c = confirmation
        if (c != null) {
            BackHandler { vm.dismissConfirmation() }
            ConfirmScreen(c, vm)
        } else {
            HomeScreen(vm, setup, openSetting)
        }
    }
}

// --- home -------------------------------------------------------------------

@Composable
private fun HomeScreen(vm: MainViewModel, setup: () -> SetupState, openSetting: (SetupItem) -> Unit) {
    val tasks by vm.tasks.collectAsState()
    var setupState by remember { mutableStateOf(setup()) }
    LifecycleResumeEffect(Unit) {
        setupState = setup()
        onPauseOrDispose { }
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        setupState = setup()
    }
    var typing by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val open = tasks.filter { !it.done }
    val done = tasks.filter { it.done }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Text(
            "语音待办",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
        )
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!setupState.complete) {
                item {
                    SetupCard(
                        setupState,
                        requestNotifications = {
                            if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                        openSetting = openSetting,
                    )
                }
            }
            if (open.isEmpty() && done.isEmpty()) {
                item {
                    Text(
                        "按住下面的按钮，说出要做的事和时间，比如：\n“周五下午三点前把报告发给老王，提前一小时提醒我”",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 32.dp, horizontal = 8.dp),
                    )
                }
            }
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
        MicBar(vm, onType = { typing = true })
    }

    if (typing) {
        TextInputDialog(
            initial = "",
            onDismiss = { typing = false },
            onSubmit = { typing = false; vm.parseText(it) },
        )
    }
}

@Composable
private fun SetupCard(state: SetupState, requestNotifications: () -> Unit, openSetting: (SetupItem) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("让提醒准时响起", fontWeight = FontWeight.SemiBold)
            if (!state.notifications) {
                SetupLine("允许发送通知", "开启", requestNotifications)
            }
            if (!state.exactAlarms) {
                SetupLine("允许设置精确闹钟，否则提醒可能晚到", "去设置") { openSetting(SetupItem.EXACT_ALARMS) }
            }
            if (!state.batteryUnrestricted) {
                SetupLine("取消电池优化，避免后台被清理", "去设置") { openSetting(SetupItem.BATTERY) }
            }
            Text(
                "小米、华为、OPPO、vivo 等手机还需要在系统设置里允许本应用“自启动”。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            TextButton(onClick = { openSetting(SetupItem.APP_DETAILS) }) { Text("打开应用设置") }
        }
    }
}

@Composable
private fun SetupLine(text: String, action: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onClick) { Text(action) }
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

@Composable
private fun MicBar(vm: MainViewModel, onType: () -> Unit) {
    val context = LocalContext.current
    val engine by vm.engineState.collectAsState()
    val capture by vm.capture.collectAsState()
    var micGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micGranted = it }
    val ready = engine == EngineState.Ready

    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val status = when {
                engine == EngineState.Loading -> "正在加载语音模型…"
                engine is EngineState.Failed -> "语音模型加载失败：${(engine as EngineState.Failed).message}"
                capture == Capture.Recording -> "正在听…松开结束"
                capture == Capture.Recognizing -> "识别中…"
                capture is Capture.Failed -> (capture as Capture.Failed).message
                else -> "按住说话"
            }
            Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(72.dp))
                val recording = capture == Capture.Recording
                Box(
                    Modifier
                        .size(if (recording) 96.dp else 84.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                !ready -> MaterialTheme.colorScheme.surfaceVariant
                                recording -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.primary
                            },
                        )
                        .pointerInput(ready, micGranted) {
                            detectTapGestures(onPress = {
                                if (!ready) return@detectTapGestures
                                if (!micGranted) {
                                    micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    return@detectTapGestures
                                }
                                vm.dismissError()
                                vm.startRecording()
                                tryAwaitRelease()
                                vm.stopRecording()
                            })
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (capture == Capture.Recognizing || engine == EngineState.Loading) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(32.dp))
                    } else {
                        Text("🎙", fontSize = 34.sp)
                    }
                }
                TextButton(onClick = onType, modifier = Modifier.width(72.dp)) { Text("打字") }
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
private fun ConfirmScreen(c: Confirmation, vm: MainViewModel) {
    var editingTranscript by remember { mutableStateOf(false) }
    val count = c.drafts.count { it.include && it.title.isNotBlank() }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
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
