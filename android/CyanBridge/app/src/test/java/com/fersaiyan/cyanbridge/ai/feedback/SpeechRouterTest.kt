package com.fersaiyan.cyanbridge.ai.feedback

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechRouterTest {

    /**
     * The one-voice rule: with a screen reader running, app state is handed to it as an
     * announcement so it is spoken once; without one, the app self-voices, because silence is
     * indistinguishable from failure for a user who cannot see the screen.
     */
    @Test
    fun stateNarrationGoesToTheScreenReaderWhenOneIsRunningAndSelfVoicesOtherwise() {
        assertEquals(
            SpeechRouter.StateRoute.SCREEN_READER_ANNOUNCEMENT,
            SpeechRouter.routeForState(screenReaderActive = true),
        )
        assertEquals(
            SpeechRouter.StateRoute.SELF_VOICE,
            SpeechRouter.routeForState(screenReaderActive = false),
        )
    }

    /**
     * A ToneGenerator must outlive the tone it started: release() tears down the native player,
     * so holding only for a caller-supplied settle gap silently truncates any cue that asks for
     * no gap - which was every thinking pulse.
     */
    @Test
    fun aToneIsHeldAtLeastAsLongAsItLasts() {
        // The thinking pulse: 60 ms tone, no settle gap requested.
        assertEquals(60L, AskFeedback.toneHoldMs(durationMs = 60, settleMs = 0L))
        // The listening cues are unchanged: 240 ms tone, 300 ms settle, still held 300 ms.
        assertEquals(300L, AskFeedback.toneHoldMs(durationMs = 240, settleMs = 300L))
    }
}
