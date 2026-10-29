package app.voicetodo.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.voicetodo.parser.Term
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The user's proper nouns. A database of its own rather than a second table
 * in tasks.db, so adding it needed no migration of the task data.
 */
class VocabularyStore private constructor(context: Context) :
    SQLiteOpenHelper(context, "vocabulary.db", null, 1) {

    private val _terms = MutableStateFlow<List<Term>>(emptyList())
    val terms: StateFlow<List<Term>> = _terms

    init {
        reload()
    }

    override fun onCreate(db: SQLiteDatabase) {
        // Aliases are newline-separated: the one character no alias can contain.
        db.execSQL("CREATE TABLE terms (text TEXT PRIMARY KEY, aliases TEXT NOT NULL DEFAULT '')")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** Adds the term, or merges new aliases into the existing one. */
    fun put(term: Term) {
        val text = term.text.trim()
        if (text.isEmpty()) return
        val existing = _terms.value.firstOrNull { it.text == text }?.aliases ?: emptyList()
        val aliases = (existing + term.aliases.map(String::trim)).filter { it.isNotEmpty() && it != text }.distinct()
        writableDatabase.insertWithOnConflict(
            "terms", null,
            ContentValues().apply {
                put("text", text)
                put("aliases", aliases.joinToString("\n"))
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        reload()
    }

    fun delete(text: String) {
        writableDatabase.delete("terms", "text = ?", arrayOf(text))
        reload()
    }

    private fun reload() {
        _terms.value = readableDatabase.query("terms", null, null, null, null, null, "text").use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(Term(c.getString(0), c.getString(1).split('\n').filter { it.isNotEmpty() }))
                }
            }
        }
    }

    companion object {
        @Volatile
        private var instance: VocabularyStore? = null

        fun get(context: Context): VocabularyStore =
            instance ?: synchronized(this) {
                instance ?: VocabularyStore(context.applicationContext).also { instance = it }
            }
    }
}
