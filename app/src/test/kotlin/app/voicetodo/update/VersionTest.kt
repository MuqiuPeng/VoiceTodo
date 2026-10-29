package app.voicetodo.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VersionTest {
    private fun v(s: String) = Version.parse(s)!!

    @Test
    fun `parses tags with and without prefix`() {
        assertEquals(listOf(0, 1, 0), v("v0.1.0").parts)
        assertEquals(listOf(1, 2), v("1.2").parts)
        assertEquals(listOf(1, 0, 0), v("v1.0.0-beta").parts)
        assertNull(Version.parse("latest"))
    }

    @Test
    fun `compares numerically, not as text`() {
        assertTrue(v("0.10.0") > v("0.9.9"))
        assertTrue(v("v0.1.1") > v("0.1.0"))
        assertEquals(0, v("1.0").compareTo(v("1.0.0")))
        assertTrue(v("0.1.0") < v("0.2"))
    }
}
