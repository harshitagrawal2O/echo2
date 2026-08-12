package com.fersaiyan.cyanbridge.plugins.cue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CueWearerBinderTest {

    private fun turn(label: String, rms: Double) =
        Turn(speakerLabel = label, text = "hello", startMs = 0, endMs = 1_000, energyRms = rms)

    @Test
    fun `the loudest consistent voice is the wearer`() {
        val binder = CueWearerBinder()

        assertNull(binder.observe(turn(WEARER, 3_000.0)))
        assertNull(binder.observe(turn(ALICE, 600.0)))
        assertNull(binder.observe(turn(WEARER, 2_800.0)))
        val bound = binder.observe(turn(ALICE, 700.0))

        assertEquals(WEARER, bound)
        assertEquals(WEARER, binder.boundLabel)
    }

    @Test
    fun `it abstains rather than guessing when nobody dominates`() {
        val binder = CueWearerBinder()

        binder.observe(turn(WEARER, 1_000.0))
        binder.observe(turn(ALICE, 950.0))
        binder.observe(turn(WEARER, 1_020.0))
        val bound = binder.observe(turn(ALICE, 990.0))

        assertNull(bound)
        assertNull(binder.boundLabel)
    }

    @Test
    fun `it never guesses from a single voice`() {
        val binder = CueWearerBinder()

        repeat(6) { binder.observe(turn(WEARER, 3_000.0)) }

        assertNull(binder.boundLabel)
    }

    @Test
    fun `arming takes the very next voice regardless of loudness`() {
        val binder = CueWearerBinder()
        binder.armExplicitBinding()

        val bound = binder.observe(turn(ALICE, 10.0))

        assertEquals(ALICE, bound)
        assertEquals(ALICE, binder.boundLabel)
    }

    @Test
    fun `the wearer is reported once so the roster is not rewritten mid-conversation`() {
        val binder = CueWearerBinder()
        binder.armExplicitBinding()

        val first = binder.observe(turn(ALICE, 10.0))
        val second = binder.observe(turn(ALICE, 10.0))

        assertEquals(ALICE, first)
        assertNull(second)
    }

    private companion object {
        const val WEARER = "speaker_0"
        const val ALICE = "speaker_1"
    }
}
