package app.voicetodo.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Tasks in a local SQLite table. Plain SQLiteOpenHelper rather than Room:
 * one table does not earn an annotation processor.
 *
 * [tasks] is reloaded after every write, so the UI and the alarm code read
 * the same list.
 */
class TaskStore private constructor(context: Context) :
    SQLiteOpenHelper(context, "tasks.db", null, 1) {

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks

    init {
        reload()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE tasks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                detail TEXT,
                due_date TEXT,
                due_time TEXT,
                due_kind TEXT,
                remind_at TEXT,
                remind_before_min INTEGER,
                source TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL,
                done INTEGER NOT NULL DEFAULT 0,
                snooze_until TEXT,
                last_notified TEXT
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun get(id: Long): Task? = readableDatabase
        .query("tasks", null, "id = ?", arrayOf(id.toString()), null, null, null)
        .use { c -> if (c.moveToFirst()) c.toTask() else null }

    fun insert(task: Task): Long {
        val id = writableDatabase.insert("tasks", null, task.toValues())
        reload()
        return id
    }

    fun update(task: Task) {
        writableDatabase.update("tasks", task.toValues(), "id = ?", arrayOf(task.id.toString()))
        reload()
    }

    fun delete(id: Long) {
        writableDatabase.delete("tasks", "id = ?", arrayOf(id.toString()))
        reload()
    }

    private fun reload() {
        _tasks.value = readableDatabase
            .query("tasks", null, null, null, null, null, "done, due_date IS NULL, due_date, due_time, id")
            .use { c -> buildList { while (c.moveToNext()) add(c.toTask()) } }
    }

    private fun Task.toValues() = ContentValues().apply {
        put("title", title)
        put("detail", detail)
        put("due_date", dueDate?.toString())
        put("due_time", dueTime?.toString())
        put("due_kind", dueKind)
        put("remind_at", remindAt?.toString())
        put("remind_before_min", remindBefore?.toMinutes())
        put("source", source)
        put("created_at", createdAt.toString())
        put("done", if (done) 1 else 0)
        put("snooze_until", snoozeUntil?.toString())
        put("last_notified", lastNotified?.toString())
    }

    private fun Cursor.str(col: String): String? = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getString(it) }

    private fun Cursor.toTask() = Task(
        id = getLong(getColumnIndexOrThrow("id")),
        title = str("title") ?: "",
        detail = str("detail"),
        dueDate = str("due_date")?.let(LocalDate::parse),
        dueTime = str("due_time")?.let(LocalTime::parse),
        dueKind = str("due_kind"),
        remindAt = str("remind_at")?.let(LocalDateTime::parse),
        remindBefore = str("remind_before_min")?.toLong()?.let(Duration::ofMinutes),
        source = str("source") ?: "",
        createdAt = LocalDateTime.parse(str("created_at")),
        done = getInt(getColumnIndexOrThrow("done")) == 1,
        snoozeUntil = str("snooze_until")?.let(LocalDateTime::parse),
        lastNotified = str("last_notified")?.let(LocalDateTime::parse),
    )

    companion object {
        @Volatile
        private var instance: TaskStore? = null

        fun get(context: Context): TaskStore =
            instance ?: synchronized(this) {
                instance ?: TaskStore(context.applicationContext).also { instance = it }
            }
    }
}
