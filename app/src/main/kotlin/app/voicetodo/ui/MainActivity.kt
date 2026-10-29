package app.voicetodo.ui

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import app.voicetodo.parser.Term
import app.voicetodo.reminder.Reminders
import app.voicetodo.widget.QuickAddWidget

// AppCompatActivity rather than ComponentActivity: it applies the in-app
// language (Settings → Language) on Android versions below 13.
class MainActivity : AppCompatActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val scheme = if (isSystemInDarkTheme()) {
                darkColorScheme(primary = Color(0xFF9CC2F0))
            } else {
                lightColorScheme(primary = Color(0xFF3A6EA5))
            }
            MaterialTheme(colorScheme = scheme) {
                AppScreen(vm, setup = ::setupState, openSetting = ::openSetting)
            }
        }
        handleQuickIntent(intent)
        handleDebugIntent(intent)
        vm.updater.checkIfDue()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleQuickIntent(intent)
        handleDebugIntent(intent)
    }

    /** The home-screen widget: type or talk straight away. */
    private fun handleQuickIntent(intent: Intent?) {
        when (intent?.action) {
            QuickAddWidget.ACTION_TYPE -> vm.requestQuick(MainViewModel.Quick.TYPE)
            QuickAddWidget.ACTION_VOICE -> vm.requestQuick(MainViewModel.Quick.VOICE)
            // From the quick-add panel: finish the job in the full app.
            ACTION_EDIT -> intent.getStringExtra(EXTRA_TEXT)?.let { vm.parseTyped(it) }
            ACTION_SETTINGS -> vm.requestPage(Page.SETTINGS)
            else -> return
        }
        // Handled once: a rotation must not start another recording.
        intent.action = null
    }

    /**
     * Debug builds accept `--es debug_wav <path>`, `--es debug_text <text>`,
     * `--es debug_term <term|alias…>` and `--es debug_update_url <url>` from adb, so the pipeline and the updater
     * can be driven on an emulator with no voice and no published release.
     */
    private fun handleDebugIntent(intent: Intent?) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 || intent == null) return
        intent.getStringExtra("debug_wav")?.let { vm.recognizeFile(it) }
        // "腾讯|疼讯|藤寻": a term and what it is misheard as.
        intent.getStringExtra("debug_term")?.split('|')?.let { vm.addTerm(Term(it[0], it.drop(1))) }
        intent.getStringExtra("debug_text")?.let { vm.parseTyped(it) }
        intent.getStringExtra("debug_update_url")?.let {
            vm.updater.endpoint = it
            vm.updater.check()
        }
    }

    fun setupState(): SetupState {
        val notifications = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val battery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        return SetupState(
            notifications = notifications,
            exactAlarms = Reminders.canScheduleExact(this),
            batteryUnrestricted = battery,
        )
    }

    fun openSetting(which: SetupItem) {
        val intent = when (which) {
            SetupItem.EXACT_ALARMS -> Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))
            SetupItem.BATTERY -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            SetupItem.APP_DETAILS -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
        runCatching { startActivity(intent) }
            .onFailure { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
    }
}

data class SetupState(val notifications: Boolean, val exactAlarms: Boolean, val batteryUnrestricted: Boolean) {
    val complete get() = notifications && exactAlarms && batteryUnrestricted
}

enum class SetupItem { EXACT_ALARMS, BATTERY, APP_DETAILS }

const val ACTION_EDIT = "app.voicetodo.EDIT"
const val ACTION_SETTINGS = "app.voicetodo.SETTINGS"
const val EXTRA_TEXT = "text"
