package group.ctdigital.gandalf.mobile

/**
 * Ephemeral, process-local continuation for a clarification question.
 * Nothing is persisted, uploaded independently, or interpreted as approval.
 */
internal object GandalfAgentSession {
    private var pendingGoal: String? = null
    private var pendingQuestion: String? = null
    data class Turn(val role: String, val text: String)
    private val recentTurns = ArrayDeque<Turn>()

    @Synchronized
    fun context(): List<Turn> = recentTurns.toList()

    @Synchronized
    fun remember(user: String, reply: String) {
        if (user.isBlank() || reply.isBlank()) return
        recentTurns.addLast(Turn("user", user.trim().take(1200)))
        recentTurns.addLast(Turn("assistant", reply.trim().take(1600)))
        while (recentTurns.size > 12) recentTurns.removeFirst()
    }

    @Synchronized
    fun resolve(utterance: String): String {
        val request = utterance.trim()
        val previous = pendingGoal
        val question = pendingQuestion
        pendingGoal = null
        pendingQuestion = null
        if (previous == null || question == null) return request
        // Don't attach stale intent to a new, full-length instruction.
        if (request.length > 80 || Regex(
                """^(open|launch|search|navigate|go|set|create|find|read|list|send|stop|cancel)\b""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(request)
        ) return request
        return "Previous request: $previous\n" +
            "Gandalf asked: $question\n" +
            "User's answer: $request"
    }

    @Synchronized
    fun awaiting(goal: String, question: String) {
        pendingGoal = goal.take(1500)
        pendingQuestion = question.take(300)
    }

    @Synchronized
    fun clear() {
        pendingGoal = null
        pendingQuestion = null
    }
}
