package app.voicetodo.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
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
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.voicetodo.R
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
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 28.dp, top = 28.dp, bottom = 16.dp),
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.vocabulary)) },
                    selected = false,
                    onClick = { go(Page.VOCABULARY) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.settings)) },
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
        Modifier.size(48.dp).clip(CircleShape).clickable(onClickLabel = stringResource(R.string.menu), onClick = onClick),
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
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
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
        PageHeader(stringResource(R.string.settings), onBack)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SectionTitle(stringResource(R.string.section_speech)) }
            item { PackCard(vm, packState) }

            item { SectionTitle(stringResource(R.string.section_permissions)) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        PermissionRow(stringResource(R.string.perm_notifications), setup.notifications) {
                            if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else openSetting(SetupItem.APP_DETAILS)
                        }
                        PermissionRow(stringResource(R.string.perm_exact_alarms), setup.exactAlarms) { openSetting(SetupItem.EXACT_ALARMS) }
                        PermissionRow(stringResource(R.string.perm_battery), setup.batteryUnrestricted) { openSetting(SetupItem.BATTERY) }
                        PermissionRow(stringResource(R.string.perm_autostart), null) { openSetting(SetupItem.APP_DETAILS) }
                    }
                }
            }

            item { SectionTitle(stringResource(R.string.section_recordings)) }
            item { RecordingsCard(vm) }

            item { SectionTitle(stringResource(R.string.section_language)) }
            item { LanguageCard() }

            item { SectionTitle(stringResource(R.string.section_update)) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.current_version, "v${vm.updater.currentVersion}"), Modifier.weight(1f))
                        TextButton(onClick = { vm.updater.check() }) { Text(stringResource(R.string.check_update)) }
                    }
                }
            }
            if (update != UpdateState.Idle) item { UpdateCard(update, vm.updater) }

            item { SectionTitle(stringResource(R.string.section_about)) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column {
                        LinkRow(stringResource(R.string.help), onClick = openHelp)
                        HorizontalDivider()
                        LinkRow(stringResource(R.string.homepage)) {
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
                        stringResource(pack.languages) + " · " + mb(pack.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (state) {
                    PackState.NotInstalled -> Button(onClick = vm.packs::download) { Text(stringResource(R.string.download)) }
                    is PackState.Downloading -> OutlinedButton(onClick = vm.packs::pause) { Text(stringResource(R.string.pause)) }
                    is PackState.Paused -> Button(onClick = vm.packs::download) { Text(stringResource(R.string.resume)) }
                    is PackState.Failed -> Button(onClick = vm.packs::download) { Text(stringResource(R.string.retry)) }
                    PackState.Installed -> TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.delete)) }
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
                        Text(stringResource(R.string.using_mobile_data), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                is PackState.Failed -> Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                PackState.Installed -> Text(stringResource(R.string.installed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                PackState.NotInstalled -> Unit
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_pack_title)) },
            text = { Text(stringResource(R.string.delete_pack_text, mb(pack.size))) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteSpeechPack() }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/**
 * 跟随系统 / 中文 / English. The names of the two languages are written in
 * themselves, so either is recognisable whichever language is showing.
 */
@Composable
private fun LanguageCard() {
    val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    val options = listOf(
        "" to stringResource(R.string.lang_system),
        "zh" to "中文",
        "en" to "English",
    )
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (tag, label) ->
                FilterChip(
                    selected = current.substringBefore('-') == tag,
                    onClick = { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag)) },
                    label = { Text(label) },
                )
            }
        }
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
                Text(stringResource(R.string.done_recordings))
                Text(
                    stringResource(R.string.recordings_size, names.size, "%.1f MB".format(bytes / 1_048_576.0)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(enabled = names.isNotEmpty(), onClick = { confirm = true }) { Text(stringResource(R.string.delete_all)) }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.delete_recordings_title)) },
            text = { Text(stringResource(R.string.delete_recordings_text, names.size)) },
            confirmButton = { TextButton(onClick = { confirm = false; vm.deleteDoneRecordings() }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** `granted` null: a setting the app cannot read (自启动), so it only offers the way there. */
@Composable
private fun PermissionRow(label: String, granted: Boolean?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
        if (granted == true) {
            Text(stringResource(R.string.perm_on), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 12.dp))
        } else {
            TextButton(onClick = onClick) { Text(stringResource(if (granted == null) R.string.perm_open else R.string.perm_turn_on)) }
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
    R.string.help_input to R.array.help_input_lines,
    R.string.help_tasks to R.array.help_tasks_lines,
    R.string.help_time to R.array.help_time_lines,
    R.string.help_confirm to R.array.help_confirm_lines,
    R.string.help_vocab to R.array.help_vocab_lines,
    R.string.help_reminders to R.array.help_reminders_lines,
    R.string.help_updates to R.array.help_updates_lines,
)

@Composable
private fun HelpScreen(onBack: () -> Unit) {
    val res = LocalContext.current.resources
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        PageHeader(stringResource(R.string.help), onBack)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        ) {
            HELP.forEach { (title, lines) ->
                item { SectionTitle(res.getString(title)) }
                res.getStringArray(lines).forEach { line ->
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
