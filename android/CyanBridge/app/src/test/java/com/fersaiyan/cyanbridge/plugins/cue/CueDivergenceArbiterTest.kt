package com.fersaiyan.cyanbridge.plugins.cue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arbiter is where "should Cue speak" is decided, so these tests are mostly about silence:
 * what Cue refuses to say is the product.
 */
class CueDivergenceArbiterTest {

    private val context = ConversationContext(sessionStartMs = 0L, wearerLabel = WEARER)

    private fun person(
        name: String? = null,
        confidence: NameConfidence = NameConfidence.LOW,
        label: String = ALICE,
    ) = Person(
        speakerLabel = label,
        name = name,
        confidence = confidence,
        firstHeardMs = 0L,
        lastHeardMs = 0L,
    )

    @Test
    fun `addressee departure interrupts, wordlessly, without waiting for a gap`() {
        val arbiter = CueDivergenceArbiter()

        val decision = arbiter.decide(
            CueEvent.AddresseeLeft(person("Sarah", NameConfidence.HIGH), 1_000),
            context,
        )

        assertEquals(CueEarcon.ADDRESSEE_LEFT, decision.earcon)
        assertTrue(decision.interrupt)
        assertFalse(decision.requiresGap)
        // No words: the user needs to stop talking now, not after a sentence finishes.
        assertNull(decision.whisper)
    }

    @Test
    fun `losing the glasses is audible and immediate`() {
        val decision = CueDivergenceArbiter().decide(CueEvent.GlassesLost(1_000), context)

        assertEquals(CueEarcon.GLASSES_LOST, decision.earcon)
        assertTrue(decision.interrupt)
    }

    @Test
    fun `a high confidence name is spoken`() {
        val decision = CueDivergenceArbiter().decide(
            CueEvent.PersonJoined(person("Sarah", NameConfidence.HIGH), 1_000),
            context,
        )

        assertEquals(CueEarcon.PERSON_ENTERED, decision.earcon)
        assertEquals("Sarah", decision.whisper)
        assertTrue(decision.requiresGap)
    }

    @Test
    fun `a medium confidence name degrades to a hedge rather than a guess`() {
        val decision = CueDivergenceArbiter().decide(
            CueEvent.PersonJoined(person("Sarah", NameConfidence.MEDIUM), 1_000),
            context,
        )

        assertEquals("Someone new", decision.whisper)
    }

    @Test
    fun `a low confidence name degrades all the way to an earcon`() {
        val decision = CueDivergenceArbiter().decide(
            CueEvent.PersonJoined(person("Sarah", NameConfidence.LOW), 1_000),
            context,
        )

        assertEquals(CueEarcon.PERSON_ENTERED, decision.earcon)
        assertNull(decision.whisper)
    }

    @Test
    fun `an unnamed speaker change says nothing at all`() {
        val decision = CueDivergenceArbiter().decide(
            CueEvent.SpeakerTurnStarted(person(), 1_000),
            context,
        )

        assertTrue(decision.isSilent)
    }

    @Test
    fun `the same speaker is not named twice in a row`() {
        val arbiter = CueDivergenceArbiter()
        val sarah = person("Sarah", NameConfidence.HIGH)

        val first = arbiter.decide(CueEvent.SpeakerTurnStarted(sarah, 1_000), context)
        val second = arbiter.decide(CueEvent.SpeakerTurnStarted(sarah, 20_000), context)

        assertEquals("Sarah", first.whisper)
        assertTrue(second.isSilent)
    }

    @Test
    fun `an actual speaker change is named`() {
        val arbiter = CueDivergenceArbiter()
        val sarah = person("Sarah", NameConfidence.HIGH, ALICE)
        val priya = person("Priya", NameConfidence.HIGH, BOB)

        val first = arbiter.decide(CueEvent.SpeakerTurnStarted(sarah, 1_000), context)
        val second = arbiter.decide(CueEvent.SpeakerTurnStarted(priya, 20_000), context)

        assertEquals("Sarah", first.whisper)
        assertEquals("Priya", second.whisper)
    }

    @Test
    fun `a diarizer flapping between speakers does not produce a burst of names`() {
        val arbiter = CueDivergenceArbiter()
        val sarah = person("Sarah", NameConfidence.HIGH, ALICE)
        val priya = person("Priya", NameConfidence.HIGH, BOB)

        val spoken = listOf(
            arbiter.decide(CueEvent.SpeakerTurnStarted(sarah, 1_000), context),
            arbiter.decide(CueEvent.SpeakerTurnStarted(priya, 1_200), context),
            arbiter.decide(CueEvent.SpeakerTurnStarted(sarah, 1_400), context),
            arbiter.decide(CueEvent.SpeakerTurnStarted(priya, 1_600), context),
        ).count { it.whisper != null }

        assertEquals(1, spoken)
    }

    @Test
    fun `the wearer is never named to themselves`() {
        val decision = CueDivergenceArbiter().decide(
            CueEvent.SpeakerTurnStarted(
                person("The user", NameConfidence.HIGH, WEARER),
                1_000,
            ),
            context,
        )

        assertTrue(decision.isSilent)
    }

    @Test
    fun `roll call names a person exactly once`() {
        val arbiter = CueDivergenceArbiter()
        val sarah = person("Sarah", NameConfidence.HIGH)

        val first = arbiter.decide(CueEvent.PersonNamed(sarah, 1_000), context)
        val second = arbiter.decide(CueEvent.PersonNamed(sarah, 20_000), context)

        assertEquals("Sarah", first.whisper)
        assertTrue(second.isSilent)
    }

    @Test
    fun `ambient events are earcon only and hard rate limited`() {
        val arbiter = CueDivergenceArbiter()

        val first = arbiter.decide(CueEvent.Ambient("door", 1_000), context)
        val tooSoon = arbiter.decide(CueEvent.Ambient("knock", 5_000), context)
        val later = arbiter.decide(CueEvent.Ambient("applause", 30_000), context)

        assertEquals(CueEarcon.AMBIENT, first.earcon)
        assertNull(first.whisper)
        // Suppressed entirely while a human is speaking.
        assertTrue(first.earconRequiresSilence)
        assertTrue(tooSoon.isSilent)
        assertEquals(CueEarcon.AMBIENT, later.earcon)
    }

    @Test
    fun `departure of a named person says who left`() {
        val decision = CueDivergenceArbiter().decide(
            CueEvent.PersonLeft(person("Sarah", NameConfidence.HIGH), 1_000),
            context,
        )

        assertEquals(CueEarcon.PERSON_LEFT, decision.earcon)
        assertEquals("Sarah left", decision.whisper)
    }

    private companion object {
        const val WEARER = "speaker_0"
        const val ALICE = "speaker_1"
        const val BOB = "speaker_2"
    }
}
