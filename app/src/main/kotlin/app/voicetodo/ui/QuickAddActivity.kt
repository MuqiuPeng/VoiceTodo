package app.voicetodo.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.voicetodo.R
import app.voicetodo.data.Task
import app.voicetodo.topics.TopicClassifier
import app.voicetodo.widget.QuickAddWidget
import java.time.LocalDate

/**
 * The widget's panel: an input bar (tap to type, hold to talk) and, once
 * something is understood, a compact confirmation — over the home screen,
 * without the app's own screens. ✓ saves and returns to the home screen;
 * ✏ hands the sentence to the full app for detailed editing.
 *
 * Runs in its own task (see the manifest), so closing it never brings the
 * app's main screen forward.
 */
class QuickAddActivity : AppCompatActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val scheme = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF9CC2F0)) else lightColorScheme(primary = Color(0xFF3A6EA5))
            MaterialTheme(colorScheme = scheme) { Panel() }
        }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        when (intent?.action) {
            QuickAddWidget.ACTION_TYPE -> vm.requestQuick(MainViewModel.Quick.TYPE)
            QuickAddWidget.ACTION_VOICE -> vm.requestQuick(MainViewModel.Quick.VOICE)
            else -> return
        }
        intent.action = null
    }

    private fun openApp(action: String, text: String? = null) {
        startActivity(
            Intent(this, MainActivity::class.java).setAction(action).putExtra(EXTRA_TEXT, text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }

    @Composable
    private fun Panel() {
        val confirmation by vm.confirmation.collectAsState()
        val noRipple = remember { MutableInteractionSource() }
        // A dimmed home screen above; tapping it closes the panel.
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))
                .clickable(interactionSource = noRipple, indication = null) { vm.cancelRecording(); finish() },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                Modifier.fillMaxWidth().clickable(interactionSource = noRipple, indication = null) {},
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                tonalElevation = 3.dp,
            ) {
                Column(Modifier.navigationBarsPadding().imePadding().padding(top = 8.dp)) {
                    val c = confirmation
                    if (c == null) {
                        InputBar(vm, openSettings = { openApp(ACTION_SETTINGS) })
                    } else {
                        QuickConfirm(
                            c,
                            onSave = {
                                val titles = c.drafts.filter { it.include && it.title.isNotBlank() }.map { it.title }
                                vm.saveConfirmation()
                                if (titles.isNotEmpty()) {
                                    Toast.makeText(this@QuickAddActivity, "✓ " + titles.joinToString(" · "), Toast.LENGTH_SHORT).show()
                                }
                                finish()
                            },
                            onCancel = { vm.dismissConfirmation() },
                            onEdit = { openApp(ACTION_EDIT, c.transcript) },
                        )
                    }
                }
            }
        }
    }

    /** What was understood, one line per task in its topic's colour; ✓ / ✏ / ✕. */
    @Composable
    private fun QuickConfirm(c: Confirmation, onSave: () -> Unit, onCancel: () -> Unit, onEdit: () -> Unit) {
        val res = LocalContext.current.resources
        val topics by vm.topics.collectAsState()
        val today = LocalDate.now()
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
            Text(
                "“${c.transcript}”",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
            Column(
                Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                c.drafts.filter { it.include && it.title.isNotBlank() }.forEach { d ->
                    val topic = TopicClassifier.topicOf(Task(title = d.title, source = c.transcript), topics)
                    val color = topic?.let { Color(it.color) } ?: MaterialTheme.colorScheme.primary
                    Row(
                        Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                            .background(color.copy(alpha = 0.10f), RoundedCornerShape(12.dp)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(5.dp).fillMaxHeight().background(color, RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)))
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(d.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            describeDue(d.date, d.time, d.kind?.name, today, res)?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium, color = color)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedIconButton(onClick = onCancel) { Icon(Icons.Filled.Close, stringResource(R.string.cancel)) }
                IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, stringResource(R.string.edit_text)) }
                Box(Modifier.weight(1f))
                FilledIconButton(onClick = onSave, modifier = Modifier.width(96.dp)) { Icon(Icons.Filled.Check, stringResource(R.string.save)) }
            }
        }
    }
}
