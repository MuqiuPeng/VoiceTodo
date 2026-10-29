package app.voicetodo.parser

/**
 * A proper noun the user cares about: "腾讯", "王晓明", "Shopee".
 *
 * `aliases` are what the recognizer tends to write instead, for words whose
 * sound does not follow from their spelling (English names, brands):
 * Shopee ← "虾皮", "秀匹". Chinese terms usually need none; pinyin matching
 * finds "疼讯" for "腾讯" on its own.
 */
data class Term(val text: String, val aliases: List<String> = emptyList())

private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

/** One replacement made, with its range in the corrected text, so the UI can show and undo it. */
data class Correction(val from: String, val to: String, val range: IntRange)

data class Corrected(val text: String, val corrections: List<Correction>)

/** Toneless pinyin per character, ü written v, from Unicode's Unihan readings. */
object Pinyin {
    private val table: Map<Char, List<String>> by lazy {
        val stream = Pinyin::class.java.getResourceAsStream("pinyin.txt")
            ?: error("pinyin.txt missing from resources")
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter { it.isNotEmpty() && !it.startsWith("#") }
                .associate { it[0] to it.substring(2).split(',') }
        }
    }

    fun of(c: Char): List<String>? = table[c]

    /**
     * The sound with common accent mergers folded together, so speakers who
     * do not separate zh/z, n/l, f/h or -ing/-in still match.
     */
    fun fuzzy(syllable: String): String {
        var s = syllable
        s = when {
            s.startsWith("zh") -> "z" + s.substring(2)
            s.startsWith("ch") -> "c" + s.substring(2)
            s.startsWith("sh") -> "s" + s.substring(2)
            s.startsWith("n") && s.length > 1 && s != "ng" -> "l" + s.substring(1)
            s.startsWith("f") -> "h" + s.substring(1)
            else -> s
        }
        return when {
            s.endsWith("ing") -> s.dropLast(1)
            s.endsWith("eng") -> s.dropLast(1)
            s.endsWith("ang") -> s.dropLast(1)
            else -> s
        }
    }
}

/**
 * Rewrites what the recognizer heard into the user's spelling of their
 * proper nouns. Runs before task parsing.
 *
 * A Chinese term matches a same-length run of text when every character
 * either is the term's character or sounds like it. Exact homophones always
 * count; accent-level near misses ("腾讯" said as "藤寻") are allowed for at
 * most a third of the characters, because a looser rule starts rewriting
 * ordinary words.
 */
class VocabularyCorrector(terms: List<Term>) {
    private class Candidate(val start: Int, val end: Int, val to: String, val viaAlias: Boolean)

    private val terms = terms.filter { it.text.isNotBlank() }.distinctBy { it.text }

    private val soundTerms = this.terms
        .map { it.text }
        .filter { t -> t.length >= 2 && t.all { Pinyin.of(it) != null } }

    fun correct(text: String): Corrected {
        if (terms.isEmpty()) return Corrected(text, emptyList())
        val candidates = mutableListOf<Candidate>()

        for (term in terms) {
            // A Latin-letter term is its own alias, so "bob" from an all-caps
            // English transcript comes back as "Bob".
            val own = if (term.text.any { it in 'a'..'z' || it in 'A'..'Z' }) listOf(term.text) else emptyList()
            for (alias in (term.aliases + own).filter { it.isNotBlank() }.distinct()) {
                var i = text.indexOf(alias, ignoreCase = true)
                while (i >= 0) {
                    val end = i + alias.length
                    // Whole words only for Latin text: "Bob" must not rewrite "Bobby".
                    val wordOk = !alias.first().isLetter() || alias.first().code > 0x7f ||
                        ((i == 0 || !text[i - 1].isAsciiLetter()) && (end == text.length || !text[end].isAsciiLetter()))
                    if (wordOk && text.substring(i, end) != term.text) candidates += Candidate(i, end, term.text, viaAlias = true)
                    i = text.indexOf(alias, end, ignoreCase = true)
                }
            }
        }
        for (term in soundTerms) {
            for (start in 0..text.length - term.length) {
                val window = text.substring(start, start + term.length)
                if (window != term && soundsLike(window, term)) {
                    candidates += Candidate(start, start + term.length, term, viaAlias = false)
                }
            }
        }

        // Leftmost first, then longest, then aliases over sound matches.
        val chosen = mutableListOf<Candidate>()
        for (c in candidates.sortedWith(compareBy({ it.start }, { -(it.end - it.start) }, { !it.viaAlias }))) {
            if (chosen.none { it.start < c.end && c.start < it.end }) chosen += c
        }
        if (chosen.isEmpty()) return Corrected(text, emptyList())

        val out = StringBuilder()
        val corrections = mutableListOf<Correction>()
        var pos = 0
        for (c in chosen.sortedBy { it.start }) {
            out.append(text, pos, c.start)
            val from = out.length
            out.append(c.to)
            corrections += Correction(text.substring(c.start, c.end), c.to, from until out.length)
            pos = c.end
        }
        out.append(text, pos, text.length)
        return Corrected(out.toString(), corrections)
    }

    private fun soundsLike(window: String, term: String): Boolean {
        var nearMisses = 0
        for (i in term.indices) {
            val a = window[i]
            val b = term[i]
            if (a == b) continue
            val pa = Pinyin.of(a) ?: return false
            val pb = Pinyin.of(b) ?: return false
            if (pa.any { it in pb }) continue
            val fb = pb.map(Pinyin::fuzzy)
            if (pa.any { Pinyin.fuzzy(it) in fb }) nearMisses++ else return false
        }
        return nearMisses <= term.length / 3
    }
}

/**
 * When the user fixes a title by hand ("去疼讯开会" → "去腾讯开会"), the fix
 * is usually a misheard name worth remembering. Returns the term to offer,
 * with what was heard as its alias, or null if the edit does not look like one
 * (text only added or removed, or the title rewritten wholesale).
 *
 * A one-character difference is widened by a neighbouring character, since a
 * name is rarely one character and the user can trim the suggestion anyway.
 */
fun suggestTerm(before: String, after: String): Term? {
    if (before == after) return null
    var p = 0
    while (p < before.length && p < after.length && before[p] == after[p]) p++
    var s = 0
    while (s < before.length - p && s < after.length - p &&
        before[before.length - 1 - s] == after[after.length - 1 - s]
    ) s++
    var start = p
    var endBefore = before.length - s
    var endAfter = after.length - s
    if (endBefore == start || endAfter == start) return null
    if (endAfter - start > 20 || endAfter - start > after.length * 2 / 3 + 1) return null
    while (endAfter - start < 2) {
        when {
            s > 0 -> { endBefore++; endAfter++; s-- }
            start > 0 -> start--
            else -> return null
        }
    }
    val heard = before.substring(start, endBefore)
    val meant = after.substring(start, endAfter)
    return if (heard.isBlank() || meant.isBlank()) null else Term(meant, listOf(heard))
}
