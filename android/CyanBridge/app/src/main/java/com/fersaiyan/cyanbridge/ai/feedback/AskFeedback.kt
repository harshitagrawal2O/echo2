package com.fersaiyan.cyanbridge.ai.feedback

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The fixed non-speech vocabulary for the ask loop: earcons for state, haptics alongside them,
 * speech reserved for content.
 *
 * Speech is slow and interruptible; a user who cannot see the screen learns these sounds the way
 * a sighted user learns a spinner. The vocabulary is small and each entry is used identically
 * everywhere, because a cue that means different things on different screens is worse than none.
 *
 * The thinking pulse is the load-bearing entry. The gap between asking and hearing an answer runs
 * 5-15 seconds on this class of product, and silence in that gap is indistinguishable from a
 * crash - the user asks again, and the two requests race. A soft repeating pulse says "still
 * working" without saying a word.
 *
 * Tone constants for the two listening cues are unchanged from the previous inline calls, so the
 * sounds users may already know keep their meaning; this class gives them names and siblings.
 */
class AskFeedback(private val context: Context) {

    private companion object {
        const val TAG = "AskFeedback"

        // Matches the pre-existing inline tones: same stream, same volume, same duration.
        const val TONE_VOLUME = 90
        const val TONE_DURATION_MS = 240
        const val TONE_SETTLE_MS = 300L

        const val THINKING_PULSE_INTERVAL_MS = 1_200L
        const val THINKING_PULSE_DURATION_MS = 60
        const val THINKING_PULSE_VOLUME = 55

        const val TICK_MS = 30L
        val FAILURE_BUZZ_PATTERN = longArrayOf(0, 90, 90, 90)
    }

    @Volatile
    private var thinkingJob: Job? = null

    /** The microphone is open; matches the historical TONE_PROP_BEEP cue. */
    suspend fun listeningOpened() {
        playTone(ToneGenerator.TONE_PROP_BEEP)
        tick()
    }

    /** The microphone has closed; matches the historical TONE_PROP_BEEP2 cue. */
    suspend fun listeningClosed() {
        playTone(ToneGenerator.TONE_PROP_BEEP2)
    }

    /** A photo has been taken and accepted. */
    fun captured(scope: CoroutineScope) {
        scope.launch { playTone(ToneGenerator.TONE_PROP_ACK) }
        tick()
    }

    /**
     * The request is in flight. Pulses softly until [stopThinking]; starting twice is a no-op so
     * callers need not track whether a pulse is already running.
     */
    fun startThinking(scope: CoroutineScope) {
        if (thinkingJob?.isActive == true) return
        thinkingJob = scope.launch {
            Log.i(TAG, "Thinking pulse started")
            try {
                while (true) {
                    delay(THINKING_PULSE_INTERVAL_MS)
                    playTone(
                        ToneGenerator.TONE_CDMA_PIP,
                        durationMs = THINKING_PULSE_DURATION_MS,
                        volume = THINKING_PULSE_VOLUME,
                        settleMs = 0L,
                    )
                }
            } finally {
                Log.i(TAG, "Thinking pulse stopped")
            }
        }
    }

    /**
     * Any speech or terminal state ends the pulse. Safe to call redundantly, from any thread -
     * the common failure of feedback like this is a pulse that outlives the answer it promised.
     */
    fun stopThinking() {
        thinkingJob?.cancel()
        thinkingJob = null
    }

    /** The ask failed. The falling NACK plus a double buzz; the caller speaks the reason. */
    fun failure(scope: CoroutineScope) {
        stopThinking()
        scope.launch { playTone(ToneGenerator.TONE_PROP_NACK) }
        vibrate(FAILURE_BUZZ_PATTERN)
    }

    private suspend fun playTone(
        toneType: Int,
        durationMs: Int = TONE_DURATION_MS,
        volume: Int = TONE_VOLUME,
        settleMs: Long = TONE_SETTLE_MS,
    ) {
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, volume) }
            .getOrElse {
                Log.w(TAG, "ToneGenerator unavailable", it)
                return
            }
        try {
            tone.startTone(toneType, durationMs)
            if (settleMs > 0) delay(settleMs)
        } finally {
            tone.release()
        }
    }

    private fun tick() = vibrate(longArrayOf(0, TICK_MS))

    private fun vibrate(pattern: LongArray) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!vibrator.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        }.onFailure { Log.w(TAG, "Vibration failed", it) }
    }
}
