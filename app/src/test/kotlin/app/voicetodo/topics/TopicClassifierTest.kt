package app.voicetodo.topics

import app.voicetodo.data.Task
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TopicClassifierTest {
    private val topics = TopicStore.DEFAULTS

    private fun of(title: String, source: String = "", manual: String? = null) =
        TopicClassifier.topicOf(Task(title = title, source = source, topic = manual), topics)?.id

    @Test
    fun `keywords in the title pick the topic`() {
        assertEquals("work", of("跟客户开会"))
        assertEquals("study", of("去参加一个 lecture"))
        assertEquals("life", of("交房租"))
        assertEquals("work", of("Send the report to the boss"))
    }

    @Test
    fun `the sentence it came from counts too`() {
        assertEquals("study", of("交", source = "下周一要交 essay"))
    }

    @Test
    fun `most matches win, earlier topics win ties`() {
        assertEquals("study", of("复习考试", source = "明天开会前复习考试"))
        assertEquals("work", of("meeting about the exam"))
    }

    @Test
    fun `no match means no topic`() {
        assertNull(of("看看天气"))
    }

    @Test
    fun `a manual choice overrides, and empty means none on purpose`() {
        assertEquals("life", of("跟客户开会", manual = "life"))
        assertNull(of("跟客户开会", manual = ""))
    }
}
