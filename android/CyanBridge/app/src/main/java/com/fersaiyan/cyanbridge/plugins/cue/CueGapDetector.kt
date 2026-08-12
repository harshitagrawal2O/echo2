package com.fersaiyan.cyanbridge.plugins.cue

data class CueGapConfig(
    /** Absolute RMS floor, below which nothing counts as speech no matter how quiet the room is. */
    val absoluteRmsFloor: Double = 120.0,
    /** How far above the tracked noise floor a frame must sit to count as speech. */
    val speechRatio: Double = 2.2,
    /** Continuous above-threshold time before speech is declared. Rejects clicks and taps. */
    val onsetMs: Long = 60L,
    /** Continuous below-threshold time before silence is declared. Bridges gaps inside a word. */
    val hangoverMs: Long = 120L,
    /** Frames to observe before the noise floor is trusted. Until then Cue assumes speech. */
    val calibrationFrames: Int = 25,
)

/**
 * Voice activity detection, and the gap detector built on it.
 *
 * Nothing in Tier 1 ever plays while a human is speaking. Ever. A system that speaks over people is
 * worse than no system, and judges feel it instantly even when they cannot articulate why — so this
 * is deliberately conservative: while the noise floor is still calibrating it reports speech, so
 * the failure mode is Cue staying quiet rather than Cue talking over someone.
 *
 * The detector also histograms every inter-speech silence it observes. Natural turn transitions in
 * a three-person conversation are frequently under 200ms and often overlapping, so a 400ms
 * threshold can silently zero out the product. [gapHistogram] is how that threshold gets chosen
 * from recorded conversation instead of from a placeholder.
 *
 * Not thread safe: frames arrive on the capture thread and it owns this object.
 */
class CueGapDetector(private val config: CueGapConfig = CueGapConfig()) {

    private var noiseFloor = config.absoluteRmsFloor
    private var framesSeen = 0

    private var speechActive = true
    private var candidateSinceMs = Long.MIN_VALUE
    private var lastSpeechEndMs = Long.MIN_VALUE
    private var lastSpeechStartMs = Long.MIN_VALUE

    private val observedGapsMs = ArrayDeque<Long>()

    /** True while a human is believed to be speaking. */
    val isSpeechActive: Boolean get() = speechActive

    fun reset() {
        noiseFloor = config.absoluteRmsFloor
        framesSeen = 0
        speechActive = true
        candidateSinceMs = Long.MIN_VALUE
        lastSpeechEndMs = Long.MIN_VALUE
        lastSpeechStartMs = Long.MIN_VALUE
        observedGapsMs.clear()
    }

    /**
     * Feeds one analysis frame. [rms] is the root mean square of the frame's samples.
     *
     * Returns true when this frame flipped the detector into silence, which is the moment a queued
     * whisper becomes eligible to play.
     */
    fun onFrame(rms: Double, atMs: Long): Boolean {
        framesSeen++
        trackNoiseFloor(rms)

        if (framesSeen < config.calibrationFrames) return false

        val threshold = maxOf(config.absoluteRmsFloor, noiseFloor * config.speechRatio)
        val loud = rms >= threshold

        if (loud == speechActive) {
            // Steady state: no pending transition.
            candidateSinceMs = Long.MIN_VALUE
            return false
        }

        if (candidateSinceMs == Long.MIN_VALUE) {
            candidateSinceMs = atMs
            return false
        }

        val heldMs = atMs - candidateSinceMs
        val requiredMs = if (loud) config.onsetMs else config.hangoverMs
        if (heldMs < requiredMs) return false

        candidateSinceMs = Long.MIN_VALUE
        return if (loud) {
            // Speech resumed. The silence that just ended is a real inter-turn gap; record it.
            if (lastSpeechEndMs != Long.MIN_VALUE) {
                recordGap(atMs - lastSpeechEndMs)
            }
            speechActive = true
            lastSpeechStartMs = atMs
            false
        } else {
            speechActive = false
            // The gap started when the frames first went quiet, not when we confirmed it.
            lastSpeechEndMs = atMs - config.hangoverMs
            true
        }
    }

    /** How long the room has been quiet, or zero while someone is speaking. */
    fun silenceDurationMs(nowMs: Long): Long {
        if (speechActive || lastSpeechEndMs == Long.MIN_VALUE) return 0L
        return (nowMs - lastSpeechEndMs).coerceAtLeast(0L)
    }

    /** Whether a whisper of the given length may start now without stepping on anyone. */
    fun hasGap(nowMs: Long, requiredSilenceMs: Long): Boolean =
        !speechActive && silenceDurationMs(nowMs) >= requiredSilenceMs

    /** True when speech began after [sinceMs], which is the signal to abort a whisper in flight. */
    fun speechResumedSince(sinceMs: Long): Boolean =
        speechActive && lastSpeechStartMs >= sinceMs

    /**
     * Every inter-speech silence observed this session, newest last.
     *
     * Feed this a five minute recording of real three-person conversation and pick the whisper
     * threshold off the distribution. If long gaps turn out to be rare, the honest fallback is
     * naming at end-of-turn rather than turn-start — and this is the measurement that says so.
     */
    fun observedGaps(): List<Long> = observedGapsMs.toList()

    /** Bucketed view of [observedGaps], for the dev overlay and for reporting the spike result. */
    fun gapHistogram(): Map<String, Int> {
        val buckets = linkedMapOf(
            "<100ms" to 0,
            "100-200ms" to 0,
            "200-300ms" to 0,
            "300-400ms" to 0,
            "400-600ms" to 0,
            "600-1000ms" to 0,
            ">=1000ms" to 0,
        )
        for (gap in observedGapsMs) {
            val key = when {
                gap < 100 -> "<100ms"
                gap < 200 -> "100-200ms"
                gap < 300 -> "200-300ms"
                gap < 400 -> "300-400ms"
                gap < 600 -> "400-600ms"
                gap < 1000 -> "600-1000ms"
                else -> ">=1000ms"
            }
            buckets[key] = (buckets[key] ?: 0) + 1
        }
        return buckets
    }

    /** Fraction of observed gaps at least this long. The number that decides the threshold. */
    fun gapAvailability(thresholdMs: Long): Double {
        if (observedGapsMs.isEmpty()) return 0.0
        return observedGapsMs.count { it >= thresholdMs }.toDouble() / observedGapsMs.size
    }

    private fun recordGap(gapMs: Long) {
        if (gapMs <= 0) return
        observedGapsMs.addLast(gapMs)
        while (observedGapsMs.size > MAX_TRACKED_GAPS) observedGapsMs.removeFirst()
    }

    /**
     * Tracks the room's noise floor: drops fast toward quiet frames, creeps up slowly.
     *
     * The asymmetry matters. A room that gets noisier should not instantly raise the bar for what
     * counts as speech, or Cue goes deaf partway through a busy venue.
     */
    private fun trackNoiseFloor(rms: Double) {
        noiseFloor = if (rms < noiseFloor) {
            noiseFloor * 0.90 + rms * 0.10
        } else {
            noiseFloor * 0.999 + rms * 0.001
        }
        if (noiseFloor < MIN_NOISE_FLOOR) noiseFloor = MIN_NOISE_FLOOR
    }

    private companion object {
        const val MAX_TRACKED_GAPS = 2_000
        const val MIN_NOISE_FLOOR = 8.0
    }
}
