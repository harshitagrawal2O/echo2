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
}
