package app.voicetodo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.voicetodo.R
import app.voicetodo.topics.Topic
import app.voicetodo.topics.TopicStore

/** Topics: tap one to edit it, + to add. Tasks are coloured by them, never split. */
@Composable
fun TopicsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val topics by vm.topics.collectAsState()
    var editing by remember { mutableStateOf<Topic?>(null) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            PageHeader(stringResource(R.string.topics), onBack)
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(topics, key = { it.id }) { t ->
                    Card(Modifier.fillMaxWidth().clickable { editing = t }) {
                        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(20.dp).clip(CircleShape).background(Color(t.color)))
                            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                Text(t.name, style = MaterialTheme.typography.bodyLarge)
                                if (t.keywords.isNotEmpty()) {
                                    Text(
                                        t.keywords.joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                    )
                                }
                            }
                            IconButton(onClick = { vm.deleteTopic(t) }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
                        }
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { editing = Topic(name = "", color = TopicStore.PALETTE[topics.size % TopicStore.PALETTE.size]) },
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(24.dp),
        ) { Icon(Icons.Filled.Add, stringResource(R.string.add_topic)) }
    }
    editing?.let { t -> TopicEditor(t, onSave = { vm.putTopic(it); editing = null }, onDismiss = { editing = null }) }
}

@Composable
private fun TopicEditor(topic: Topic, onSave: (Topic) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(topic.name) }
    var color by remember { mutableStateOf(topic.color) }
    var keywords by remember { mutableStateOf(topic.keywords.joinToString(", ")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding(),
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.topic_name)) }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TopicStore.PALETTE.forEach { c ->
                        Box(
                            Modifier.size(24.dp).clip(CircleShape).background(Color(c))
                                .border(if (c == color) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                .clickable { color = c },
                        )
                    }
                }
                OutlinedTextField(
                    keywords, { keywords = it },
                    label = { Text(stringResource(R.string.topic_keywords)) },
                    minLines = 3,
                )
            }
        },
        confirmButton = {
            IconButton(enabled = name.isNotBlank(), onClick = {
                onSave(
                    topic.copy(
                        name = name.trim(),
                        color = color,
                        keywords = keywords.split(',', '，', '、', '\n').map(String::trim).filter(String::isNotEmpty).distinct(),
                    ),
                )
            }) { Icon(Icons.Filled.Check, stringResource(R.string.save), tint = MaterialTheme.colorScheme.primary) }
        },
        dismissButton = { IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, stringResource(R.string.cancel)) } },
    )
}
