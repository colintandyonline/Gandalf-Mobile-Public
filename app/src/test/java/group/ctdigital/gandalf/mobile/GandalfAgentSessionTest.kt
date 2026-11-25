package group.ctdigital.gandalf.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GandalfAgentSessionTest {
    @Test fun carriesShortAnswerIntoOriginalRequest() {
        GandalfAgentSession.clear()
        GandalfAgentSession.awaiting("Find a contact", "Which contact?")
        val next = GandalfAgentSession.resolve("Wanda")
        assertTrue(next.contains("Find a contact"))
        assertTrue(next.contains("Which contact?"))
        assertTrue(next.contains("Wanda"))
        GandalfAgentSession.clear()
    }

    @Test fun doesNotAttachNewTaskToPreviousQuestion() {
        GandalfAgentSession.clear()
        GandalfAgentSession.awaiting("Find a contact", "Which contact?")
        assertEquals("Open Chrome", GandalfAgentSession.resolve("Open Chrome"))
        assertEquals("Hello", GandalfAgentSession.resolve("Hello"))
    }

    @Test fun pendingQuestionIsConsumedOnce() {
        GandalfAgentSession.clear()
        GandalfAgentSession.awaiting("Find a contact", "Which contact?")
        GandalfAgentSession.resolve("Wanda")
        assertEquals("Another request", GandalfAgentSession.resolve("Another request"))
    }
}
