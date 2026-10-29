package app.voicetodo.update

/** "v0.2.1" / "0.2.1" as comparable numbers; anything after a '-' is ignored. */
data class Version(val parts: List<Int>) : Comparable<Version> {
    override fun compareTo(other: Version): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val c = parts.getOrElse(i) { 0 }.compareTo(other.parts.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    override fun toString() = parts.joinToString(".")

    companion object {
        fun parse(s: String): Version? {
            val core = s.trim().removePrefix("v").removePrefix("V").substringBefore('-')
            val parts = core.split('.').map { it.toIntOrNull() ?: return null }
            return if (parts.isEmpty()) null else Version(parts)
        }
    }
}
