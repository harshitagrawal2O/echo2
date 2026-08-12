package com.fersaiyan.cyanbridge.plugins.cue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Who's here" must work with no internet, because a roster that disappears when the venue Wi-Fi
 * does is worse than no roster: the user cannot tell the difference between "nobody is here" and
 * "Cue cannot answer".
 */
class CuePromptsTest {

    private fun person(
        label: String,
        name: String? = null,
        confidence: NameConfidence = NameConfidence.HIGH,
        lastHeardMs: Long = 1_000,
    ) = Person(
        speakerLabel = label,
        name = name,
        confidence = confidence,
        firstHeardMs = 0,
        lastHeardMs = lastHeardMs,
    )

    private fun context(vararg roster: Person) = ConversationContext(
        sessionStartMs = 0,
        wearerLabel = "speaker_0",
        roster = roster.toList(),
    )

    @Test
    fun `an empty room is said plainly`() {
        assertEquals("No one else yet.", CuePrompts.rosterFallback(context()))
    }

    @Test
    fun `named people are listed by name`() {
        val answer = CuePrompts.rosterFallback(
            context(person("speaker_1", "Sarah"), person("speaker_2", "Priya")),
        )

        assertEquals("Sarah, Priya", answer)
    }

    @Test
    fun `unnamed people are counted, never described`() {
        val answer = CuePrompts.rosterFallback(
            context(person("speaker_1", "Sarah"), person("speaker_2")),
        )

        assertEquals("Sarah, and one other.", answer)
    }

    @Test
    fun `a room of strangers is a count, not a list of nothings`() {
        val answer = CuePrompts.rosterFallback(
            context(person("speaker_1"), person("speaker_2"), person("speaker_3")),
        )

        assertEquals("3 people, no names yet.", answer)
    }

    @Test
    fun `a medium confidence name is counted as unnamed rather than guessed aloud`() {
        val answer = CuePrompts.rosterFallback(
            context(person("speaker_1", "Sarah", NameConfidence.MEDIUM)),
        )

        assertEquals("One person, no name yet.", answer)
    }

    @Test
    fun `the wearer is never counted as someone in the room`() {
        val withWearer = ConversationContext(
            sessionStartMs = 0,
            wearerLabel = "speaker_0",
            roster = listOf(person("speaker_0", "The user"), person("speaker_1", "Sarah")),
        )

        assertEquals("Sarah", CuePrompts.rosterFallback(withWearer))
    }

    @Test
    fun `the visual prompt carries the conversation, not just the photo`() {
        val ctx = ConversationContext(
            sessionStartMs = 0,
            wearerLabel = "speaker_0",
            roster = listOf(person("speaker_1", "Grant")),
            turns = listOf(
                Turn("speaker_1", "What do you think of this number here?", 1_000, 3_000),
            ),
        )

        val prompt = CuePrompts.visualUserPrompt(ctx, nowMs = 5_000, spokenQuestion = null)

        assertTrue(prompt.contains("Grant"))
        assertTrue(prompt.contains("What do you think of this number here?"))
    }

    @Test
    fun `the style rules the answers depend on are actually in the system prompt`() {
        val visual = CuePrompts.visualSystemPrompt()

        assertTrue(visual.contains("Under 15 words"))
        assertTrue(visual.contains("Never open with \"I see\""))
        assertTrue(visual.contains("Use names, not descriptions"))
        // No markdown: every one of these answers is spoken, never read.
        assertFalse(visual.contains("bullet"))
        assertTrue(visual.contains("No markdown"))
    }
}
