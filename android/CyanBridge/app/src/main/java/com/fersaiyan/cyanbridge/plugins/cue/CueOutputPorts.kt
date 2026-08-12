package com.fersaiyan.cyanbridge.plugins.cue

/**
 * The two ways Cue can make a sound, narrowed to what [CueOutputDispatcher] actually needs.
 *
 * These exist so the gap rule — never speak while a human is speaking — can be tested without an
 * `AudioTrack` or a TTS engine. That rule is the single most important behaviour in the product and
 * the one a demo audience feels instantly when it breaks, so it should not be the part that is only
 * ever verified by ear.
 */

/** Plays a preloaded earcon. Implemented by [CueEarconPlayer]. */
fun interface CueEarconSink {
    fun play(earcon: CueEarcon)
}

/** Speaks. Implemented by [CueSpeaker]. */
interface CueVoice {
    /** True while an utterance is in flight. */
    val isSpeaking: Boolean

    fun whisper(text: String, utteranceId: String = "cue-whisper"): Boolean

    fun briefing(text: String, utteranceId: String = "cue-briefing"): Boolean

    /** Cuts speech immediately, mid-word if necessary. */
    fun stop()
}
