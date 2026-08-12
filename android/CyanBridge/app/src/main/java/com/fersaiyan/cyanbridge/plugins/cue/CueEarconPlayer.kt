package com.fersaiyan.cyanbridge.plugins.cue

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Synthesises and plays the earcon vocabulary.
 *
 * Earcons are learned in about ten minutes and cost near zero attention afterwards. This is how
 * screen readers already work and the target user is fluent in the paradigm, so Cue leans on it
 * hard: the high frequency events carry no words at all.
 *
 * Two constraints shape every tone here:
 *  - **Under 200ms.** An earcon that outlasts the moment it describes is a sentence.
 *  - **Under ~3.4 kHz.** If the SCO microphone route holds the link for the whole session, output
 *    may be forced to 8 kHz mono narrowband, so every partial above that ceiling disappears. The
 *    highest fundamental used here is 1200 Hz, which survives the codec — but this must still be
 *    auditioned through the actual glasses speaker, not laptop monitors.
 *
 * Tones are generated once at construction and reused, so playback costs no synthesis.
 */
class CueEarconPlayer(
    private val volume: Float = 0.85f,
) : CueEarconSink {

    private val tones: Map<CueEarcon, ShortArray> = buildTones()

    @Volatile
    private var voiceRoute = false

    private var track: AudioTrack? = null
    private val lock = Any()

    /**
     * Switches output between the media path and the call path.
     *
     * While the SCO route is held open for the microphone, the phone is in communication mode and
     * media-usage audio may not reach the glasses at all. Cue follows the mic.
     */
    fun setVoiceRoute(enabled: Boolean) {
        synchronized(lock) {
            if (voiceRoute == enabled) return
            voiceRoute = enabled
            releaseTrack()
        }
    }

    override fun play(earcon: CueEarcon) {
        val samples = tones[earcon] ?: return
        synchronized(lock) {
            val active = ensureTrack() ?: return
            runCatching {
                if (active.playState != AudioTrack.PLAYSTATE_PLAYING) active.play()
                active.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            }.onFailure { Log.w(TAG, "Earcon playback failed", it) }
        }
    }

    fun release() {
        synchronized(lock) { releaseTrack() }
    }

    private fun ensureTrack(): AudioTrack? {
        track?.let { return it }
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "AudioTrack.getMinBufferSize returned $minBuffer")
            return null
        }
        val usage = if (voiceRoute) {
            AudioAttributes.USAGE_VOICE_COMMUNICATION
        } else {
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
        }
        val created = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuffer, MAX_TONE_SAMPLES * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrElse {
            Log.e(TAG, "Could not create AudioTrack", it)
            return null
        }
        if (created.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { created.release() }
            return null
        }
        runCatching { created.setVolume(volume.coerceIn(0f, 1f)) }
        track = created
        return created
    }

    private fun releaseTrack() {
        track?.let { active ->
            runCatching { active.pause() }
            runCatching { active.flush() }
            runCatching { active.release() }
        }
        track = null
    }

    // ── synthesis ──

    private fun buildTones(): Map<CueEarcon, ShortArray> = mapOf(
        // Something got added.
        CueEarcon.PERSON_ENTERED to concat(note(660.0, 70), note(990.0, 90)),
        // Mirror of the above.
        CueEarcon.PERSON_LEFT to concat(note(990.0, 70), note(660.0, 90)),
        // The §2 top row. Doubled because the consequence is distinct, not just the event.
        CueEarcon.ADDRESSEE_LEFT to concat(
            note(990.0, 55), note(660.0, 55), silence(40),
            note(990.0, 55), note(660.0, 70),
        ),
        // Directional metaphor: one soft chime aimed at you.
        CueEarcon.ADDRESSING_YOU to concat(note(880.0, 130, attackMs = 25)),
        // Ambiguity is the point: "your turn".
        CueEarcon.AWAITING_YOU to concat(
            note(440.0, 70), silence(90), note(440.0, 70),
        ),
        // Tier 0.5. Low and short so it reads as background, never as an announcement.
        CueEarcon.AMBIENT to concat(note(300.0, 45)),
        // Covers the photo path latency, which is unmeasured on this hardware.
        CueEarcon.WORKING to concat(sweep(700.0, 1200.0, 90)),
        // Cue never says "I'm sorry, I didn't catch that".
        CueEarcon.FAILED to concat(note(180.0, 130, releaseMs = 70)),
        // Distinct from failure: the command was understood and the device was busy.
        CueEarcon.BUSY to concat(buzz(320.0, 100)),
        // The user must know the system has gone blind.
        CueEarcon.GLASSES_LOST to concat(
            note(990.0, 70), note(780.0, 70), note(620.0, 110),
        ),
    )

    private fun concat(vararg parts: ShortArray): ShortArray {
        val total = parts.sumOf { it.size }
        val out = ShortArray(total)
        var offset = 0
        for (part in parts) {
            part.copyInto(out, offset)
            offset += part.size
        }
        return out
    }

    private fun silence(durationMs: Int) = ShortArray(samplesFor(durationMs))

    /** A shaped sine. The raised-cosine edges matter: a hard start clicks badly through a codec. */
    private fun note(
        frequencyHz: Double,
        durationMs: Int,
        attackMs: Int = 8,
        releaseMs: Int = 20,
    ): ShortArray {
        val count = samplesFor(durationMs)
        val out = ShortArray(count)
        for (index in 0 until count) {
            val phase = 2.0 * PI * frequencyHz * index / SAMPLE_RATE
            out[index] = (sin(phase) * envelope(index, count, attackMs, releaseMs) * PEAK).toInt()
                .toShort()
        }
        return out
    }

    /** A linear frequency sweep, for the "working on it" tick. */
    private fun sweep(fromHz: Double, toHz: Double, durationMs: Int): ShortArray {
        val count = samplesFor(durationMs)
        val out = ShortArray(count)
        var phase = 0.0
        for (index in 0 until count) {
            val progress = index.toDouble() / count
            val frequency = fromHz + (toHz - fromHz) * progress
            phase += 2.0 * PI * frequency / SAMPLE_RATE
            out[index] = (sin(phase) * envelope(index, count, 8, 20) * PEAK).toInt().toShort()
        }
        return out
    }

    /** A soft square, which reads as a buzz rather than a tone. Distinct from every other earcon. */
    private fun buzz(frequencyHz: Double, durationMs: Int): ShortArray {
        val count = samplesFor(durationMs)
        val out = ShortArray(count)
        for (index in 0 until count) {
            val phase = 2.0 * PI * frequencyHz * index / SAMPLE_RATE
            // Two odd harmonics only; anything higher would be lost to the narrowband ceiling.
            val value = sin(phase) + sin(3 * phase) / 3.0
            out[index] = (value / 1.34 * envelope(index, count, 6, 16) * PEAK).toInt().toShort()
        }
        return out
    }

    private fun envelope(index: Int, count: Int, attackMs: Int, releaseMs: Int): Double {
        val attack = samplesFor(attackMs).coerceAtLeast(1)
        val release = samplesFor(releaseMs).coerceAtLeast(1)
        return when {
            index < attack -> 0.5 * (1 - cos(PI * index / attack))
            index > count - release -> 0.5 * (1 - cos(PI * (count - index) / release))
            else -> 1.0
        }
    }

    private fun samplesFor(durationMs: Int) = SAMPLE_RATE * durationMs / 1000

    private companion object {
        const val TAG = "CueEarcon"

        /**
         * 16 kHz. The tones themselves stay under 1.2 kHz so they survive being resampled down to
         * an 8 kHz narrowband link.
         */
        const val SAMPLE_RATE = 16_000
        const val PEAK = 0.72 * Short.MAX_VALUE
        const val MAX_TONE_SAMPLES = SAMPLE_RATE / 2
    }
}
