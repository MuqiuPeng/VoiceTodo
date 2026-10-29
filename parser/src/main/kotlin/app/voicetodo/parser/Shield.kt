package app.voicetodo.parser

/**
 * Hides the user's proper nouns from text rules: each term becomes one
 * private-use character, which no rule matches, and comes back afterwards.
 * "周一科技" stays a company to the time parser; "嗯哼文化" keeps its 嗯 when
 * fillers are stripped.
 */
internal class Shield(protectedTerms: Collection<String>) {
    private val terms = protectedTerms.filter { it.isNotBlank() }.distinct()
        .sortedByDescending { it.length }.take(0x1000)

    fun hide(s: String): String {
        var r = s
        terms.forEachIndexed { i, t -> r = r.replace(t, (BASE + i).toString()) }
        return r
    }

    fun restore(s: String): String {
        var r = s
        terms.forEachIndexed { i, t -> r = r.replace((BASE + i).toString(), t) }
        return r
    }

    private companion object {
        const val BASE = ''
    }
}
