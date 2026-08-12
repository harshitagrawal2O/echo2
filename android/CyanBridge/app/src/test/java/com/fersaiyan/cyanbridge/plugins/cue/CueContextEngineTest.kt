package com.fersaiyan.cyanbridge.plugins.cue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The addressee state machine is the hero feature, so most of this file is about exactly when it
 * fires and — more importantly — when it does not.
 */
class CueContextEngineTest {

    private fun engine(
        addresseeSilenceMs: Long = 6_000L,
        presenceTimeoutMs: Long = 90_000L,
    ) = CueContextEngine(
        CueEngineConfig(
            addresseeSilenceMs = addresseeSilenceMs,
            presenceTimeoutMs = presenceTimeoutMs,
        ),
    ).apply { start(0L) }

    private fun turn(label: String, startMs: Long, endMs: Long, text: String = "hello") =
        Turn(speakerLabel = label, text = text, startMs = startMs, endMs = endMs)

    @Test
    fun `a new voice joins the roster and announces itself`() {
        val engine = engine()
        engine.bindWearer(WEARER)

        val events = engine.onTurn(turn(ALICE, 1_000, 2_000))

        assertTrue(events.any { it is CueEvent.PersonJoined })
        assertTrue(events.any { it is CueEvent.SpeakerTurnStarted })
        assertEquals(1, engine.snapshot().presentOthers().size)
    }

    @Test
    fun `the wearer is never in the roster and never announced`() {
        val engine = engine()
        engine.bindWearer(WEARER)

        val events = engine.onTurn(turn(WEARER, 1_000, 2_000))

        assertTrue(events.isEmpty())
        assertTrue(engine.snapshot().presentOthers().isEmpty())
    }

    @Test
    fun `binding the wearer late evicts them from the roster`() {
        val engine = engine()
        // The wearer speaks before the binder has had enough turns to identify them.
        engine.onTurn(turn(WEARER, 1_000, 2_000))
        assertEquals(1, engine.snapshot().roster.size)

        engine.bindWearer(WEARER)

        assertTrue(engine.snapshot().roster.isEmpty())
        assertTrue(engine.snapshot().presentOthers().isEmpty())
    }

    @Test
    fun `the addressee is whoever the user last exchanged turns with`() {
        val engine = engine()
        engine.bindWearer(WEARER)

        engine.onTurn(turn(ALICE, 1_000, 2_000))
        engine.onTurn(turn(WEARER, 2_500, 3_500))

        assertEquals(ALICE, engine.snapshot().addressee?.speakerLabel)
    }

    @Test
    fun `someone talking across the room never becomes the addressee`() {
        val engine = engine()
        engine.bindWearer(WEARER)

        // Alice and Bob talk to each other; the user says nothing.
        engine.onTurn(turn(ALICE, 1_000, 2_000))
        engine.onTurn(turn(BOB, 2_200, 3_000))
        engine.onTurn(turn(ALICE, 3_200, 4_000))

        assertNull(engine.snapshot().addressee)
    }

    @Test
    fun `addressee departure fires while the user is still talking`() {
        val engine = engine(addresseeSilenceMs = 6_000L)
        engine.bindWearer(WEARER)

        engine.onTurn(turn(ALICE, 1_000, 2_000))
        engine.onTurn(turn(WEARER, 2_500, 3_500))
        // The user carries on. Alice has said nothing since 2_000.
        engine.onTurn(turn(WEARER, 4_000, 9_000))

        val events = engine.onTick(10_000)

        val departure = events.filterIsInstance<CueEvent.AddresseeLeft>().singleOrNull()
        assertEquals(ALICE, departure?.person?.speakerLabel)
        // Well inside the 8 second bar from the addressee's last utterance to the earcon.
        assertTrue(10_000 - 2_000 <= 8_000)
    }

    @Test
    fun `no departure while it is the user's turn to reply`() {
        val engine = engine(addresseeSilenceMs = 6_000L)
        engine.bindWearer(WEARER)

        engine.onTurn(turn(WEARER, 1_000, 2_000))
        // Alice replied and is now waiting on the user. A long pause here is the user thinking,
        // not Alice leaving.
        engine.onTurn(turn(ALICE, 2_500, 3_500))

        val events = engine.onTick(20_000)

        assertTrue(events.none { it is CueEvent.AddresseeLeft })
    }

    @Test
    fun `addressee departure fires once, not on every tick`() {
        val engine = engine(addresseeSilenceMs = 6_000L)
        engine.bindWearer(WEARER)
        engine.onTurn(turn(ALICE, 1_000, 2_000))
        engine.onTurn(turn(WEARER, 2_500, 9_000))

        val first = engine.onTick(10_000)
        val second = engine.onTick(11_000)
        val third = engine.onTick(30_000)

        assertEquals(1, first.filterIsInstance<CueEvent.AddresseeLeft>().size)
        assertTrue(second.none { it is CueEvent.AddresseeLeft })
        assertTrue(third.none { it is CueEvent.AddresseeLeft })
    }

    @Test
    fun `a returning voice re-joins and can depart again`() {
        val engine = engine(presenceTimeoutMs = 5_000L)
        engine.bindWearer(WEARER)
        engine.onTurn(turn(ALICE, 1_000, 2_000))

        val left = engine.onTick(10_000)
        assertEquals(1, left.filterIsInstance<CueEvent.PersonLeft>().size)

        val rejoined = engine.onTurn(turn(ALICE, 12_000, 13_000))
        assertTrue(rejoined.any { it is CueEvent.PersonJoined })

        val leftAgain = engine.onTick(20_000)
        assertEquals(1, leftAgain.filterIsInstance<CueEvent.PersonLeft>().size)
    }

    @Test
    fun `a quiet person is not treated as gone before the timeout`() {
        val engine = engine(presenceTimeoutMs = 90_000L)
        engine.bindWearer(WEARER)
        engine.onTurn(turn(ALICE, 1_000, 2_000))

        val events = engine.onTick(60_000)

        assertTrue(events.isEmpty())
        assertEquals(1, engine.snapshot().presentOthers().size)
    }

    @Test
    fun `name binding never downgrades a confident binding`() {
        val engine = engine()
        engine.bindWearer(WEARER)
        engine.onTurn(turn(ALICE, 1_000, 2_000))

        engine.bindName(ALICE, "Sarah", NameConfidence.HIGH, 2_100)
        val downgrade = engine.bindName(ALICE, "Sara", NameConfidence.MEDIUM, 2_200)

        assertNull(downgrade)
        val person = engine.snapshot().roster.single()
        assertEquals("Sarah", person.name)
        assertEquals(NameConfidence.HIGH, person.confidence)
    }

    @Test
    fun `taking the glasses off clears everything`() {
        val engine = engine()
        engine.bindWearer(WEARER)
        engine.onTurn(turn(ALICE, 1_000, 2_000))
        engine.bindName(ALICE, "Sarah", NameConfidence.HIGH, 2_100)

        engine.clear()

        val context = engine.snapshot()
        assertTrue(context.roster.isEmpty())
        assertTrue(context.turns.isEmpty())
        assertNull(context.addressee)
        assertNull(context.wearerLabel)
    }

    @Test
    fun `transcript older than the window is dropped`() {
        val engine = CueContextEngine(CueEngineConfig(transcriptWindowMs = 10_000L))
            .apply { start(0L) }
        engine.bindWearer(WEARER)
        engine.onTurn(turn(ALICE, 1_000, 2_000))
        engine.onTurn(turn(ALICE, 3_000, 4_000))

        engine.onTick(30_000)

        assertTrue(engine.snapshot().turns.isEmpty())
    }

    private companion object {
        const val WEARER = "speaker_0"
        const val ALICE = "speaker_1"
        const val BOB = "speaker_2"
    }
}
