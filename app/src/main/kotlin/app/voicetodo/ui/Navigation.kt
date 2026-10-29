package app.voicetodo.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.voicetodo.VoiceTodoApp.EngineState
import app.voicetodo.speech.PackState
import app.voicetodo.update.UpdateState
import app.voicetodo.update.Updater
import kotlinx.coroutines.launch

enum class Page { HOME, VOCABULARY, SETTINGS, HELP }

@Composable
fun AppScreen(vm: MainViewModel, setup: () -> SetupState, openSetting: (SetupItem) -> Unit) {
    val confirmation by vm.confirmation.collectAsState()
    val update by vm.updater.state.collectAsState()
    val engine by vm.engineState.collectAsState()
    var page by rememberSaveable { mutableStateOf(Page.HOME) }
    var setupState by remember { mutableStateOf(setup()) }
    LifecycleResumeEffect(Unit) {
        setupState = setup()
        onPauseOrDispose { }
    }
    // A dot on the menu and on 设置 when something there wants the user.
    val attention = !setupState.complete || update is UpdateState.Available || engine == EngineState.Missing

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val c = confirmation
        when {
            c != null -> {
                BackHandler { vm.dismissConfirmation() }
                ConfirmScreen(c, vm)
            }
            page == Page.VOCABULARY -> {
                BackHandler { page = Page.HOME }
                VocabularyScreen(vm, onBack = { page = Page.HOME })
            }
            page == Page.SETTINGS -> {
                BackHandler { page = Page.HOME }
                SettingsScreen(
                    vm, setupState,
                    refreshSetup = { setupState = setup() },
                    openSetting = openSetting,
                    openHelp = { page = Page.HELP },
                    onBack = { page = Page.HOME },
                )
            }
            page == Page.HELP -> {
                BackHandler { page = Page.SETTINGS }
                HelpScreen(onBack = { page = Page.SETTINGS })
            }
            else -> HomeWithDrawer(vm, attention, open = { page = it })
        }
    }
}

@Composable
private fun HomeWithDrawer(vm: MainViewModel, attention: Boolean, open: (Page) -> Unit) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    fun go(p: Page) {
        scope.launch { drawer.close() }
        open(p)
    }
    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    "语音待办",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 28.dp, top = 28.dp, bottom = 16.dp),
                )
                NavigationDrawerItem(
                    label = { Text("专有名词") },
                    selected = false,
                    onClick = { go(Page.VOCABULARY) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                NavigationDrawerItem(
                    label = { Text("设置") },
                    selected = false,
                    badge = { if (attention) Dot() },
                    onClick = { go(Page.SETTINGS) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            HomeScreen(
                vm,
                menu = { MenuButton(attention) { scope.launch { drawer.open() } } },
                openSettings = { open(Page.SETTINGS) },
            )
            // With gesture navigation a swipe from the left edge is Back. Claim
            // a band mid-screen (Android allows 200dp per edge) so a swipe that
            // starts at the edge there opens the menu; above and below it the
            // edge still means Back.
            Box(Modifier.align(Alignment.CenterStart).width(24.dp).height(200.dp).systemGestureExclusion())
        }
    }
}

@Composable
private fun Dot() {
    Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
}

/** Three bars, drawn rather than taken from an icon library for one glyph. */
@Composable
private fun MenuButton(attention: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(onClickLabel = "菜单", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) {
                Box(Modifier.width(20.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(MaterialTheme.colorScheme.onSurface))
            }
        }
        if (attention) Box(Modifier.offset(x = 12.dp, y = (-10).dp)) { Dot() }
    }
}

@Composable
fun PageHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("‹ 返回") }
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp),
    )
}

private fun mb(bytes: Long) = "%.0f MB".format(bytes / 1_048_576.0)

// --- settings -----------------------------------------------------------------

@Composable
private fun SettingsScreen(
    vm: MainViewModel,
    setup: SetupState,
    refreshSetup: () -> Unit,
    openSetting: (SetupItem) -> Unit,
    openHelp: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val packState by vm.packs.state.collectAsState()
    val update by vm.updater.state.collectAsState()
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refreshSetup() }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        PageHeader("设置", onBack)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SectionTitle("语音包") }
            item { PackCard(vm, packState) }

            item { SectionTitle("提醒权限") }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        PermissionRow("通知", setup.notifications) {
                            if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else openSetting(SetupItem.APP_DETAILS)
                        }
                        PermissionRow("精确闹钟", setup.exactAlarms) { openSetting(SetupItem.EXACT_ALARMS) }
                        PermissionRow("后台运行（不受电池优化限制）", setup.batteryUnrestricted) { openSetting(SetupItem.BATTERY) }
                        PermissionRow("自启动（小米、华为、OPPO、vivo 等）", null) { openSetting(SetupItem.APP_DETAILS) }
                    }
                }
            }

            item { SectionTitle("录音") }
            item { RecordingsCard(vm) }

            item { SectionTitle("更新") }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("当前版本 v${vm.updater.currentVersion}", Modifier.weight(1f))
                        TextButton(onClick = { vm.updater.check() }) { Text("检查更新") }
                    }
                }
            }
            if (update != UpdateState.Idle) item { UpdateCard(update, vm.updater) }

            item { SectionTitle("关于") }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column {
                        LinkRow("软件说明", onClick = openHelp)
                        HorizontalDivider()
                        LinkRow("项目主页") {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${Updater.REPO}")))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PackCard(vm: MainViewModel, state: PackState) {
    val pack = vm.packs.pack
    var confirmDelete by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(pack.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${pack.languages} · ${mb(pack.size)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (state) {
                    PackState.NotInstalled -> Button(onClick = vm.packs::download) { Text("下载") }
                    is PackState.Downloading -> OutlinedButton(onClick = vm.packs::pause) { Text("暂停") }
                    is PackState.Paused -> Button(onClick = vm.packs::download) { Text("继续") }
                    is PackState.Failed -> Button(onClick = vm.packs::download) { Text("重试") }
                    PackState.Installed -> TextButton(onClick = { confirmDelete = true }) { Text("删除") }
                }
            }
            when (state) {
                is PackState.Downloading, is PackState.Paused -> {
                    val (done, total) = when (state) {
                        is PackState.Downloading -> state.done to state.total
                        is PackState.Paused -> state.done to state.total
                        else -> 0L to 1L
                    }
                    LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                    Text("${mb(done)} / ${mb(total)}", style = MaterialTheme.typography.bodySmall)
                    if (state is PackState.Downloading && vm.updater.isMetered) {
                        Text("正在使用移动数据", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                is PackState.Failed -> Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                PackState.Installed -> Text("已安装", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                PackState.NotInstalled -> Unit
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除语音包？") },
            text = { Text("删除后不能语音输入，需要重新下载 ${mb(pack.size)}。") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteSpeechPack() }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun RecordingsCard(vm: MainViewModel) {
    val tasks by vm.tasks.collectAsState()
    val names = vm.doneRecordings(tasks)
    val bytes = names.sumOf { vm.recordings.file(it).length() }
    var confirm by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("已完成任务的录音")
                Text(
                    "${names.size} 条 · ${"%.1f".format(bytes / 1_048_576.0)} MB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(enabled = names.isNotEmpty(), onClick = { confirm = true }) { Text("全部删除") }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("删除已完成任务的录音？") },
            text = { Text("共 ${names.size} 条，任务本身保留。") },
            confirmButton = { TextButton(onClick = { confirm = false; vm.deleteDoneRecordings() }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
        )
    }
}

/** `granted` null: a setting the app cannot read (自启动), so it only offers the way there. */
@Composable
private fun PermissionRow(label: String, granted: Boolean?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
        if (granted == true) {
            Text("已开启", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 12.dp))
        } else {
            TextButton(onClick = onClick) { Text(if (granted == null) "去设置" else "去开启") }
        }
    }
}

@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// --- help ---------------------------------------------------------------------

private val HELP = listOf(
    "输入" to listOf(
        "点一下底部输入框：打字，点「发送」。",
        "长按输入框：说话，松开识别；按住时上滑取消。",
        "语音需要先在「设置 → 语音包」下载，识别全程在手机上完成，不联网。",
    ),
    "任务" to listOf(
        "向左滑：完成；在「已完成」里向左滑：恢复。",
        "向右滑：删除，底部会出现「撤销」。",
        "说出来的任务会保留录音，点 ▶ 播放；一句话拆成几条任务时共用一段录音。",
        "「设置 → 录音」可以一次删除所有已完成任务的录音，任务本身保留。",
    ),
    "时间怎么说" to listOf(
        "日期：明天、后天、周五、下周三、11月5号、月底、国庆前、三天后、两周内。",
        "时刻：下午三点、三点半、15:30、晚上八点、下班前、睡前。",
        "“之前 / 前 / 截止”是截止时间，“之后 / 以后”是开始时间。",
        "提醒：“提前半小时提醒我”“前一天提醒我”。",
        "一句话可以说几件事，用“还有”“然后”隔开。",
    ),
    "确认" to listOf(
        "保存前会显示识别结果，标题、日期、时间都能改。",
        "有拿不准的地方会标黄，并给出备选，点一下就能改。",
        "语气词、改口、专有名词的纠正会列在顶部，点「撤回」恢复原话。",
    ),
    "专有名词" to listOf(
        "在菜单「专有名词」里添加公司名、人名、地名，发音相同的字会自动改成你的写法。",
        "英文名或读音对不上的词，可以填它“常被听成”什么。",
        "在确认卡片上手动改了名字，保存后会询问是否加入。",
    ),
    "提醒" to listOf(
        "带时刻的任务：提前 30 分钟（可改）和到点各提醒一次。",
        "只有日期的任务：前一天 20:00 和当天 9:00 各提醒一次。",
        "通知上可以直接「完成」或「1小时后再提醒」。",
        "提醒要准时，需要在「设置 → 提醒权限」里全部开启；国产手机还要允许自启动。",
    ),
    "更新" to listOf(
        "每天打开时自动检查一次，也可以在「设置 → 更新」手动检查。",
        "联网只用于检查更新和下载语音包。",
    ),
)

@Composable
private fun HelpScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        PageHeader("软件说明", onBack)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        ) {
            HELP.forEach { (title, lines) ->
                item { SectionTitle(title) }
                lines.forEach { line ->
                    item {
                        Text(
                            "· $line",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 3.dp),
                        )
                    }
                }
                item { Spacer(Modifier.height(4.dp)) }
            }
        }
    }
}
