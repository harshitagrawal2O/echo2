package com.fersaiyan.cyanbridge.ai.vision

/**
 * Rolling conversation history for the glasses assistant, so a turn can refer to the ones before it.
 *
 * Every request used to be built as exactly two messages - a system prompt and the current question
 * - so the assistant had no past. Asked "what was the previous thing I asked you", it answered
 * "I'm unable to recall previous interactions", which is correct and useless: follow-ups like
 * "read it again", "what about the one on the left" or "is that the same bottle" are the normal way
 * people talk, and none of them worked.
 *
 * **Text only, deliberately.** Past photos are not replayed. Each frame is ~60 KB of base64 and the
 * wearer takes one per turn, so carrying them would grow every request without bound and slow the
 * answer a blind user is waiting on. The consequence is honest and worth knowing: the assistant
 * remembers what was *said* about an earlier photo, not the photo itself, so "look at that again"
 * cannot work while "what did you say it was" can.
 *
 * Bounded twice over - by turns and by characters - because an unbounded history silently becomes a
 * bigger request every turn until it fails at the token limit, and that failure would land far from
 * its cause.
 *
 * Process-scoped and not persisted. A restart is a fresh conversation, which is the right default
 * for a device someone wears intermittently, and it keeps this out of the memory vault's remit.
 */
object GlassesConversationMemory {

    /** One exchange. Roles match what the OpenAI-compatible payload builder expects. */
    private data class Turn(val question: String, val answer: String)

    private const val MAX_TURNS = 8
    private const val MAX_CHARS = 6_000

    private val turns = ArrayDeque<Turn>()

    /** Prior exchanges, oldest first, ready to splice between the system prompt and the new question. */
    @Synchronized
    fun history(): List<Map<String, String>> = turns.flatMap { turn ->
        listOf(
            mapOf("role" to "User", "content" to turn.question),
            mapOf("role" to "Assistant", "content" to turn.answer),
        )
    }

    @Synchronized
    fun record(question: String, answer: String) {
        val q = question.trim()
        val a = answer.trim()
        if (q.isEmpty() || a.isEmpty()) return
        turns.addLast(Turn(q, a))
        trim()
    }

    /** Number of remembered exchanges, for logging. */
    @Synchronized
    fun turnCount(): Int = turns.size

    /** Starts a fresh conversation. */
    @Synchronized
    fun clear() {
        turns.clear()
    }

    private fun trim() {
        while (turns.size > MAX_TURNS) turns.removeFirst()
        while (turns.size > 1 && charCount() > MAX_CHARS) turns.removeFirst()
    }

    private fun charCount(): Int = turns.sumOf { it.question.length + it.answer.length }
}
