package com.fersaiyan.cyanbridge.plugins.cue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The zero-interruption rule, proved rather than asserted.
 *
 * "Cue never speaks over a human" is the headline metric and the thing a room feels instantly when
 * it breaks. Verifying it only by ear during rehearsal is not verification.
 */
class CueOutputDispatcherTest {

    private class FakeEarcons : CueEarconSink {
        val played = mutableListOf<CueEarcon>()
        override fun play(earcon: CueEarcon) {
            played += earcon
        }
    }

    private class FakeVoice : CueVoice {
        val spoken = mutableListOf<String>()
        val briefed = mutableListOf<String>()
        var stops = 0
        override var isSpeaking: Boolean = false

        override fun whisper(text: String, utteranceId: String): Boolean {
            spoken += text
            return true
        }

        override fun briefing(text: String, utteranceId: String): Boolean {
            briefed += text
            return true
        }

        override fun stop() {
            stops++
            isSpeaking = false
        }
    }

    private val gapConfig = CueGapConfig(
        absoluteRmsFloor = 120.0,
        onsetMs = 60L,
        hangoverMs = 120L,
        calibrationFrames = 3,
    )

    private class Harness {
        val detector = CueGapDetector(
            CueGapConfig(
                absoluteRmsFloor = 120.0,
                onsetMs = 60L,
                hangoverMs = 120L,
                calibrationFrames = 3,
            ),
        )
        val earcons = FakeEarcons()
        val voice = FakeVoice()
        val spoken = mutableListOf<SpokenOutput>()
        val dispatcher = CueOutputDispatcher(
            gapDetector = detector,
            earcons = earcons,
            speaker = voice,
            configProvider = { CueDispatchConfig(requiredGapMs = 400L, maxPendingAgeMs = 3_500L) },
            onSpoken = { spoken += it },
        )

        /** Drives the detector into a settled speech or silence state at [atMs]. */
        fun settle(loud: Boolean, atMs: Long): Long = frames(loud, count = 20, atMs = atMs)

        /** Feeds exactly [count] 20ms frames, so a test can land on a precise silence duration. */
        fun frames(loud: Boolean, count: Int, atMs: Long): Long {
            var t = atMs
            repeat(count) {
                detector.onFrame(if (loud) 3_000.0 else 40.0, t)
                t += 20
            }
            return t
        }
    }

    @Test
    fun `a whisper never starts while a human is speaking`() {
        val h = Harness()
        val atMs = h.settle(loud = true, atMs = 0)

        h.dispatcher.submit(CueDecision(whisper = "Sarah"), atMs)

        assertTrue(h.voice.spoken.isEmpty())
        assertEquals(0, h.dispatcher.stats().interruptions)
    }

    @Test
    fun `the queued whisper plays as soon as a long enough gap opens`() {
        val h = Harness()
        var atMs = h.settle(loud = true, atMs = 0)
        h.dispatcher.submit(CueDecision(whisper = "Sarah"), atMs)

        // Eight quiet frames is 160ms: enough to clear the 120ms hangover and declare silence,
        // but only ~40ms of gap accrued — short of the 400ms a whisper needs.
        atMs = h.frames(loud = false, count = 8, atMs = atMs)
        h.dispatcher.tick(atMs)
        assertTrue(h.voice.spoken.isEmpty())

        atMs += 500
        h.dispatcher.tick(atMs)

        assertEquals(listOf("Sarah"), h.voice.spoken)
        assertEquals(0, h.dispatcher.stats().interruptions)
        assertEquals(1, h.dispatcher.stats().whispersSpoken)
    }

    @Test
    fun `a whisper that waited too long is dropped in silence rather than played late`() {
        val h = Harness()
        var atMs = h.settle(loud = true, atMs = 0)
        h.dispatcher.submit(CueDecision(whisper = "Sarah"), atMs)

        // The room stays busy well past the staleness window.
        atMs = h.settle(loud = true, atMs = atMs + 5_000)
        h.dispatcher.tick(atMs)

        assertTrue(h.voice.spoken.isEmpty())
        assertEquals(1, h.dispatcher.stats().whispersDroppedStale)
    }

    @Test
    fun `only the newest queued whisper survives, because the older one describes a stale room`() {
        val h = Harness()
        var atMs = h.settle(loud = true, atMs = 0)
        h.dispatcher.submit(CueDecision(whisper = "Sarah"), atMs)
        h.dispatcher.submit(CueDecision(whisper = "Priya"), atMs + 200)

        atMs = h.settle(loud = false, atMs = atMs + 400) + 500
        h.dispatcher.tick(atMs)

        assertEquals(listOf("Priya"), h.voice.spoken)
    }

    @Test
    fun `addressee departure cuts through speech, which is the one time that is correct`() {
        val h = Harness()
        val atMs = h.settle(loud = true, atMs = 0)
        h.voice.isSpeaking = true

        h.dispatcher.submit(
            CueDecision(earcon = CueEarcon.ADDRESSEE_LEFT, requiresGap = false, interrupt = true),
            atMs,
        )

        assertEquals(listOf(CueEarcon.ADDRESSEE_LEFT), h.earcons.played)
        assertEquals(1, h.voice.stops)
    }

    @Test
    fun `an interrupt discards anything that was queued`() {
        val h = Harness()
        var atMs = h.settle(loud = true, atMs = 0)
        h.dispatcher.submit(CueDecision(whisper = "Sarah"), atMs)

        h.dispatcher.submit(
            CueDecision(earcon = CueEarcon.ADDRESSEE_LEFT, requiresGap = false, interrupt = true),
            atMs,
        )
        atMs = h.settle(loud = false, atMs = atMs) + 1_000
        h.dispatcher.tick(atMs)

        assertTrue("the stale name must not follow the departure earcon", h.voice.spoken.isEmpty())
    }

    @Test
    fun `a whisper is cut short when someone starts speaking under it`() {
        val h = Harness()
        var atMs = h.frames(loud = false, count = 20, atMs = 0) + 500
        h.dispatcher.submit(CueDecision(whisper = "Sarah"), atMs)
        assertEquals(listOf("Sarah"), h.voice.spoken)
        h.voice.isSpeaking = true

        // The other person comes back in mid-name.
        atMs = h.frames(loud = true, count = 10, atMs = atMs)
        h.dispatcher.tick(atMs)

        assertEquals(1, h.voice.stops)
        assertEquals(1, h.dispatcher.stats().whispersAborted)
    }

    @Test
    fun `a whisper that finishes in the clear is not counted as aborted`() {
        val h = Harness()
        var atMs = h.frames(loud = false, count = 20, atMs = 0) + 500
        h.dispatcher.submit(CueDecision(whisper = "Sarah"), atMs)
        h.voice.isSpeaking = false

        atMs = h.frames(loud = false, count = 10, atMs = atMs)
        h.dispatcher.tick(atMs)

        assertEquals(0, h.voice.stops)
        assertEquals(0, h.dispatcher.stats().whispersAborted)
    }

    @Test
    fun `presence earcons play immediately but ambient ones wait for silence`() {
        val h = Harness()
        val atMs = h.settle(loud = true, atMs = 0)

        h.dispatcher.submit(CueDecision(earcon = CueEarcon.PERSON_ENTERED), atMs)
        h.dispatcher.submit(
            CueDecision(earcon = CueEarcon.AMBIENT, earconRequiresSilence = true),
            atMs,
        )

        assertEquals(listOf(CueEarcon.PERSON_ENTERED), h.earcons.played)
        assertEquals(1, h.dispatcher.stats().ambientSuppressed)
    }

    @Test
    fun `repeat replays from cache and never regenerates`() {
        val h = Harness()

        val replayed = h.dispatcher.repeat(
            SpokenOutput(CueTier.WHISPER, "Sarah", null, 1_000),
            2_000,
        )

        assertTrue(replayed)
        assertEquals(listOf("Sarah"), h.voice.spoken)
        // Replaying is not a new observation, so it must not overwrite the repeat cache.
        assertTrue(h.spoken.isEmpty())
    }

    @Test
    fun `repeat with nothing to say fails audibly rather than silently`() {
        val h = Harness()

        val replayed = h.dispatcher.repeat(null, 1_000)

        assertTrue(!replayed)
        assertEquals(listOf(CueEarcon.FAILED), h.earcons.played)
    }

    @Test
    fun `a briefing is user-initiated so it does not wait for a gap`() {
        val h = Harness()
        val atMs = h.settle(loud = true, atMs = 0)

        h.dispatcher.briefing("Sarah, Priya, and one other.", atMs)

        assertEquals(listOf("Sarah, Priya, and one other."), h.voice.briefed)
        assertEquals(CueTier.BRIEFING, h.spoken.single().tier)
    }
}
