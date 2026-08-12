package com.fersaiyan.cyanbridge.plugins.cue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * If Cue only gets one thing right it should be this: never talk over a human.
 *
 * The detector is therefore biased toward reporting speech — the failure mode is Cue staying quiet,
 * never Cue interrupting.
 */
class CueGapDetectorTest {

    private val frameMs = 20L
    private val quiet = 40.0
    private val loud = 3_000.0

    private fun detector() = CueGapDetector(
        CueGapConfig(
            absoluteRmsFloor = 120.0,
            speechRatio = 2.2,
            onsetMs = 60L,
            hangoverMs = 120L,
            calibrationFrames = 5,
        ),
    )

    /** Feeds [count] frames at [rms] starting at [startMs]; returns the timestamp after the last. */
    private fun feed(detector: CueGapDetector, rms: Double, count: Int, startMs: Long): Long {
        var atMs = startMs
        repeat(count) {
            detector.onFrame(rms, atMs)
            atMs += frameMs
        }
        return atMs
    }

    @Test
    fun `speech is assumed until the noise floor has calibrated`() {
        val detector = detector()

        // Two frames in, still calibrating.
        detector.onFrame(quiet, 0)
        detector.onFrame(quiet, frameMs)

        assertTrue(detector.isSpeechActive)
        assertFalse(detector.hasGap(1_000, 400))
    }

    @Test
    fun `silence is declared only after the hangover elapses`() {
        val detector = detector()
        var atMs = feed(detector, quiet, 5, 0)

        // Three quiet frames is 60ms, short of the 120ms hangover.
        atMs = feed(detector, quiet, 3, atMs)
        assertTrue(detector.isSpeechActive)

        feed(detector, quiet, 6, atMs)
        assertFalse(detector.isSpeechActive)
    }

    @Test
    fun `a whisper waits for the required gap and then becomes eligible`() {
        val detector = detector()
        var atMs = feed(detector, quiet, 20, 0)

        assertFalse(detector.isSpeechActive)
        // Barely any silence accrued yet.
        assertFalse(detector.hasGap(atMs, 400))

        atMs += 500
        assertTrue(detector.hasGap(atMs, 400))
    }

    @Test
    fun `speech reopening closes the gap immediately`() {
        val detector = detector()
        var atMs = feed(detector, quiet, 20, 0)
        atMs += 500
        assertTrue(detector.hasGap(atMs, 400))

        atMs = feed(detector, loud, 10, atMs)

        assertTrue(detector.isSpeechActive)
        assertFalse(detector.hasGap(atMs, 400))
    }

    @Test
    fun `a whisper in flight can tell that speech resumed under it`() {
        val detector = detector()
        var atMs = feed(detector, quiet, 20, 0)
        val whisperStartedAtMs = atMs + 500

        assertFalse(detector.speechResumedSince(whisperStartedAtMs))

        atMs = feed(detector, loud, 10, whisperStartedAtMs)

        assertTrue(detector.speechResumedSince(whisperStartedAtMs))
    }

    @Test
    fun `inter-turn silences are measured, which is what sets the threshold`() {
        val detector = detector()
        var atMs = feed(detector, quiet, 10, 0)
        atMs = feed(detector, loud, 20, atMs)
        // A ~500ms pause between turns.
        atMs = feed(detector, quiet, 25, atMs)
        atMs = feed(detector, loud, 20, atMs)

        val gaps = detector.observedGaps()

        assertEquals(1, gaps.size)
        assertTrue("gap was ${gaps.single()}ms", gaps.single() in 300..700)
        assertEquals(1, detector.gapHistogram().values.sum())
        assertEquals(1.0, detector.gapAvailability(300), 0.0001)
        assertEquals(0.0, detector.gapAvailability(2_000), 0.0001)
    }

    @Test
    fun `a conversation of short gaps reports low availability at 400ms`() {
        val detector = detector()
        var atMs = feed(detector, quiet, 10, 0)

        // Six turn transitions with only the hangover's worth of silence between them, which is
        // the "gaps are scarce" case that would otherwise silently zero out the product.
        repeat(6) {
            atMs = feed(detector, loud, 15, atMs)
            atMs = feed(detector, quiet, 8, atMs)
        }
        feed(detector, loud, 10, atMs)

        assertTrue(detector.observedGaps().isNotEmpty())
        assertTrue(
            "availability was ${detector.gapAvailability(400)}",
            detector.gapAvailability(400) < 0.5,
        )
    }
}
