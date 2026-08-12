package com.fersaiyan.cyanbridge.plugins.cue

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Speech output for Tier 1 whispers and Tier 2 briefings.
 *
 * Two things here are not cosmetic:
 *
 * **Speech rate defaults fast.** The target user listens at 300 to 500 words per minute and is far
 * quicker than any sighted person tuning this would find comfortable. Tuning the rate to what
 * sounds pleasant in a demo makes the product slower than the user's own screen reader.
 *
 * **[stop] is immediate.** A blind user who has heard enough of a briefing has no way to skip it
 * other than waiting, so cutting speech on the pause button is the difference between a tool that
 * respects the user's time and one that lectures them.
 */
class CueSpeaker(
    context: Context,
    private val onReady: (Boolean) -> Unit = {},
) : CueVoice {

    private val appContext = context.applicationContext
    private val ready = AtomicBoolean(false)
    private val speakingUntilMs = AtomicLong(0L)

    @Volatile
    private var voiceRoute = false

    @Volatile
    private var whisperRate = DEFAULT_WHISPER_RATE

    @Volatile
    private var briefingRate = DEFAULT_BRIEFING_RATE

    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(appContext) { status ->
            val ok = status == TextToSpeech.SUCCESS
            ready.set(ok)
            if (ok) {
                tts?.language = Locale.getDefault()
                applyAudioAttributes()
                tts?.setOnUtteranceProgressListener(progressListener)
            } else {
                Log.w(TAG, "TextToSpeech unavailable (status=$status)")
            }
            onReady(ok)
        }
    }

    val isReady: Boolean get() = ready.get()

    /** True while an utterance is in flight, so the arbiter does not stack output on top of itself. */
    override val isSpeaking: Boolean
        get() = ready.get() && (tts?.isSpeaking == true || System.currentTimeMillis() < speakingUntilMs.get())

    fun setRates(whisper: Float, briefing: Float) {
        whisperRate = whisper.coerceIn(MIN_RATE, MAX_RATE)
        briefingRate = briefing.coerceIn(MIN_RATE, MAX_RATE)
    }

    /** Follows the microphone: while SCO holds the link, media-usage audio may not reach the glasses. */
    fun setVoiceRoute(enabled: Boolean) {
        if (voiceRoute == enabled) return
        voiceRoute = enabled
        applyAudioAttributes()
    }

    /**
     * One to four words at whisper rate. A whisper is never a sentence; anything needing a verb
     * belongs in [briefing].
     */
    override fun whisper(text: String, utteranceId: String): Boolean =
        speak(text, whisperRate, TextToSpeech.QUEUE_FLUSH, utteranceId)

    /** A full spoken response. Only ever user-initiated, or on session start. */
    override fun briefing(text: String, utteranceId: String): Boolean =
        speak(text, briefingRate, TextToSpeech.QUEUE_FLUSH, utteranceId)

    /** Cuts speech immediately. Wired to the pause button and to the vendor assistant handoff. */
    override fun stop() {
        speakingUntilMs.set(0L)
        runCatching { tts?.stop() }
    }

    fun release() {
        ready.set(false)
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
    }

    private fun speak(text: String, rate: Float, queueMode: Int, utteranceId: String): Boolean {
        val engine = tts ?: return false
        if (!ready.get() || text.isBlank()) return false
        runCatching { engine.setSpeechRate(rate) }
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        val result = runCatching { engine.speak(text, queueMode, params, utteranceId) }
            .getOrDefault(TextToSpeech.ERROR)
        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "speak() failed for \"$text\"")
            return false
        }
        // isSpeaking lags the call on some engines; this covers the window before it flips true.
        speakingUntilMs.set(System.currentTimeMillis() + estimateDurationMs(text, rate))
        return true
    }

    private fun applyAudioAttributes() {
        val engine = tts ?: return
        val usage = if (voiceRoute) {
            AudioAttributes.USAGE_VOICE_COMMUNICATION
        } else {
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
        }
        runCatching {
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
        }
    }

    private fun estimateDurationMs(text: String, rate: Float): Long {
        val words = text.split(' ').count { it.isNotBlank() }.coerceAtLeast(1)
        val wordsPerMinute = BASE_WORDS_PER_MINUTE * rate
        return (words / wordsPerMinute * 60_000).toLong().coerceAtLeast(200L)
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            speakingUntilMs.set(0L)
        }

        // Abstract in UtteranceProgressListener and deprecated in the same breath, so it has to be
        // implemented and there is nothing to migrate to. The two-argument overload below is the
        // one engines actually call.
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onError(utteranceId: String?) {
            speakingUntilMs.set(0L)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            speakingUntilMs.set(0L)
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            speakingUntilMs.set(0L)
        }
    }

    companion object {
        private const val TAG = "CueSpeaker"

        /** Fast by default, on purpose. See the class doc. */
        const val DEFAULT_WHISPER_RATE = 1.6f
        const val DEFAULT_BRIEFING_RATE = 1.4f
        const val MIN_RATE = 0.5f
        const val MAX_RATE = 3.0f
        private const val BASE_WORDS_PER_MINUTE = 165.0
    }
}
