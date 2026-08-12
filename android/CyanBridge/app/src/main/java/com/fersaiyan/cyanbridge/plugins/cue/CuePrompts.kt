package com.fersaiyan.cyanbridge.plugins.cue

/**
 * Every prompt Cue sends, and the constraints that make its answers usable.
 *
 * The shared system prompt is short and absolute on purpose. Cue's output competes with a live
 * human conversation for the user's attention, so a model that opens with "The image shows" has
 * already spent the budget before saying anything.
 */
object CuePrompts {

    /**
     * The house style. Enforced hard because each rule maps to a way the output becomes unusable:
     * length competes with a conversation, unrequested description is the narrating failure mode,
     * "I see" wastes the first second, split hedging doubles the listening cost, and descriptions
     * instead of names undo the entire roster.
     */
    private const val STYLE = """
You speak into the ear of a blind user during a live conversation. Follow these rules exactly.

- Under 15 words, unless the user explicitly asks you to elaborate.
- Never describe anything the user did not ask about.
- Never open with "I see", "The image shows", "It looks like", or any similar preamble.
- If you are uncertain, say the short uncertain thing. Never hedge across two sentences.
- Use names, not descriptions, for anyone on the roster.
- No markdown, no lists, no emoji. Plain spoken sentences only.
"""

    /** Answers "who's here", from state already in memory. */
    fun rosterSystemPrompt(): String = STYLE.trim() + """

You are answering "who is here". List only the people present, by name, shortest form possible.
If someone has no name yet, count them as "one other person" rather than describing them.
""".trimEnd()

    /**
     * Answers a visual question.
     *
     * The instruction to use the transcript is what separates Cue from the AI photo button this
     * hardware already ships. A photo alone gets "a man is holding a piece of paper"; the photo
     * plus the last thirty seconds gets "Grant is holding up the invoice he just mentioned".
     */
    fun visualSystemPrompt(): String = STYLE.trim() + """

You are answering a question about what is in front of the user, from one photo plus the recent
conversation. Use the conversation to work out what the user actually wants to know, and answer
that. Read specific values, numbers, and words in the image out loud when they are the point.
""".trimEnd()

    /** Builds the "who's here" turn. Answered from the in-memory roster, so this needs no network
     * when the model is unavailable — see [rosterFallback]. */
    fun rosterUserPrompt(context: ConversationContext, nowMs: Long): String = buildString {
        val present = context.presentOthers()
        append("People heard in this session:\n")
        if (present.isEmpty()) {
            append("(none)\n")
        } else {
            for (person in present) {
                val name = person.spokenName() ?: "unnamed"
                val secondsAgo = ((nowMs - person.lastHeardMs) / 1000).coerceAtLeast(0)
                append("- $name (last spoke ${secondsAgo}s ago)\n")
            }
        }
        append("\nSay who is here.")
    }

    /**
     * The offline answer, assembled locally.
     *
     * "Who's here" must work with no internet: it is a read of state Cue already holds, and making
     * it depend on a network call would mean the roster disappears exactly when the venue Wi-Fi
     * does.
     */
    fun rosterFallback(context: ConversationContext): String {
        val present = context.presentOthers()
        if (present.isEmpty()) return "No one else yet."
        val named = present.mapNotNull { it.spokenName() }
        val unnamed = present.size - named.size
        return when {
            named.isEmpty() && unnamed == 1 -> "One person, no name yet."
            named.isEmpty() -> "$unnamed people, no names yet."
            unnamed == 0 -> named.joinToString(", ")
            unnamed == 1 -> named.joinToString(", ") + ", and one other."
            else -> named.joinToString(", ") + ", and $unnamed others."
        }
    }

    /**
     * Builds the visual question turn.
     *
     * Never send a photo alone. Photo plus transcript plus roster is the product; photo alone is
     * the button the firmware already has.
     */
    fun visualUserPrompt(
        context: ConversationContext,
        nowMs: Long,
        spokenQuestion: String?,
    ): String = buildString {
        val present = context.presentOthers()
        if (present.isNotEmpty()) {
            append("People present: ")
            append(present.joinToString(", ") { it.spokenName() ?: "unnamed speaker" })
            append("\n\n")
        }
        val transcript = context.recentTranscript(nowMs, RECENT_WINDOW_MS)
        if (transcript.isNotEmpty()) {
            append("Last 30 seconds of conversation:\n")
            for (turn in transcript) {
                val who = context.roster
                    .firstOrNull { it.speakerLabel == turn.speakerLabel }
                    ?.spokenName()
                    ?: if (turn.speakerLabel == context.wearerLabel) "User" else turn.speakerLabel
                append(who).append(": ").append(turn.text.trim()).append('\n')
            }
            append('\n')
        }
        if (!spokenQuestion.isNullOrBlank()) {
            append("The user asks: ").append(spokenQuestion.trim())
        } else {
            append("The user pressed the button without saying anything. ")
            append("Answer whatever the conversation implies they want to know about this image.")
        }
    }

    /** System prompt for the roll call mapping call. One prompt, not a trained model. */
    fun rollCallSystemPrompt(): String =
        "You map speaker labels to names from a transcript. You output JSON only, never prose. " +
            "You never guess a name that is not stated in the transcript."

    private const val RECENT_WINDOW_MS = 30_000L
}
