package app.voicetodo.topics

import android.content.Context
import app.voicetodo.data.Task
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A user-defined theme for tasks. It only colours them; the list stays one list. */
data class Topic(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** ARGB. */
    val color: Long,
    /** Words that put a task under this topic; the name itself always counts too. */
    val keywords: List<String> = emptyList(),
)

/**
 * Which topic a task belongs to. A manual choice wins (an empty string means
 * "none, on purpose"); otherwise the topic whose keywords appear most often in
 * the task's title and the sentence it came from, earlier topics winning ties.
 * Computed when shown rather than stored, so editing a topic's keywords
 * recolours every task at once.
 */
object TopicClassifier {
    fun topicOf(task: Task, topics: List<Topic>): Topic? {
        task.topic?.let { manual -> return topics.firstOrNull { it.id == manual } }
        val text = (task.title + " " + task.source).lowercase()
        var best: Topic? = null
        var bestScore = 0
        for (t in topics) {
            val score = (t.keywords + t.name)
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .distinct()
                .count { text.contains(it) }
            if (score > bestScore) {
                best = t
                bestScore = score
            }
        }
        return best
    }
}

/** Topics in SharedPreferences as JSON: a handful of small records, edited rarely. */
class TopicStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("topics", Context.MODE_PRIVATE)
    private val _topics = MutableStateFlow(load())
    val topics: StateFlow<List<Topic>> = _topics

    private fun load(): List<Topic> {
        val raw = prefs.getString(KEY, null) ?: return DEFAULTS.also { save(it) }
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val kw = o.getJSONArray("keywords")
            Topic(o.getString("id"), o.getString("name"), o.getLong("color"), (0 until kw.length()).map(kw::getString))
        }
    }

    private fun save(list: List<Topic>) {
        val arr = JSONArray()
        list.forEach { t ->
            arr.put(
                JSONObject().put("id", t.id).put("name", t.name).put("color", t.color)
                    .put("keywords", JSONArray(t.keywords)),
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun put(topic: Topic) {
        val list = _topics.value
        val next = if (list.any { it.id == topic.id }) list.map { if (it.id == topic.id) topic else it } else list + topic
        _topics.value = next
        save(next)
    }

    fun delete(id: String) {
        val next = _topics.value.filter { it.id != id }
        _topics.value = next
        save(next)
    }

    companion object {
        private const val KEY = "topics"

        /** Material-ish hues that stay readable as a thin bar and as a light tint. */
        val PALETTE = listOf(
            0xFF3A6EA5, 0xFF2E7D32, 0xFFE65100, 0xFF8E24AA,
            0xFFC62828, 0xFF00838F, 0xFF6D4C41, 0xFFAD1457,
        )

        /** Starting points the user edits or deletes; keywords in both languages. */
        val DEFAULTS = listOf(
            Topic(
                "work", "工作 Work", PALETTE[0],
                listOf("会议", "开会", "报告", "客户", "老板", "项目", "方案", "合同", "邮件", "同事",
                    "meeting", "report", "client", "boss", "project", "deadline", "email", "office", "ppt"),
            ),
            Topic(
                "study", "学习 Study", PALETTE[1],
                listOf("作业", "考试", "论文", "上课", "复习", "讲座", "课", "老师",
                    "lecture", "essay", "exam", "homework", "class", "assignment", "submit", "thesis"),
            ),
            Topic(
                "life", "生活 Life", PALETTE[2],
                listOf("买", "超市", "快递", "房租", "医院", "体检", "健身", "吃饭", "电费", "水费", "妈",
                    "buy", "groceries", "rent", "doctor", "dentist", "gym", "dinner", "bill", "mom"),
            ),
        )

        @Volatile
        private var instance: TopicStore? = null

        fun get(context: Context): TopicStore =
            instance ?: synchronized(this) { instance ?: TopicStore(context.applicationContext).also { instance = it } }
    }
}
