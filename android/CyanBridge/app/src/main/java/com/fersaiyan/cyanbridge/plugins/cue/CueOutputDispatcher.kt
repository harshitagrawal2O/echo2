package com.fersaiyan.cyanbridge.plugins.cue

data class CueDispatchConfig(
    /**
     * Silence required before a whisper may start.
     *
     * 400ms is the placeholder, not the answer. Natural turn transitions in a three-person
     * conversation are frequently under 200ms and often overlapping, so this must be set from
     * [CueGapDetector.gapHistogram] on a real recording. If long gaps turn out to be rare, the
     * honest fallback is naming at end-of-turn rather than turn-start.
     */
    val requiredGapMs: Long = 400L,
    /**
     * How long a queued whisper stays relevant.
     *
     * A name that arrives four seconds after the turn it describes is not late information, it is
     * wrong information — the speaker has probably changed again. Stale whispers are dropped in
     * silence rather than played late.
     */
    val maxPendingAgeMs: Long = 3_500L,
)

/** Counters for the metrics that decide whether the interaction model actually worked. */
data class CueOutputStats(
    val whispersSpoken: Int = 0,
    val whispersDroppedStale: Int = 0,
    /** Whispers cut short because a human started speaking under them. */
    val whispersAborted: Int = 0,
    val ambientSuppressed: Int = 0,
    /**
     * Whispers that began while a human was speaking. The headline metric, and the target is zero.
     *
     * Pair it with the zero-wrong-names count, or a cynical reader will notice that a system which
     * says nothing scores perfectly on this one.
     */
    val interruptions: Int = 0,
)

/**
 * Turns arbiter decisions into actual sound, subject to the gap rule.
 *
 * The single most important behaviour in this class: nothing in Tier 1 plays while a human is
 * speaking. A whisper that cannot find a gap waits, and if it waits too long it is dropped rather
 * than forced through.
 *
 * The one exception is [CueDecision.interrupt], which currently means addressee departure or a lost
 * link. Both are cases where the user acting on a stale model costs more than the interruption.
 *
 * Single threaded: the session dispatches on one scope.
 */
class CueOutputDispatcher(
    private val gapDetector: CueGapDetector,
    private val earcons: CueEarconSink,
    private val speaker: CueVoice,
    private val configProvider: () -> CueDispatchConfig = { CueDispatchConfig() },
    private val onSpoken: (SpokenOutput) -> Unit = {},
) {

    private data class Pending(val text: String, val queuedAtMs: Long)

    private var pending: Pending? = null

    /** When the whisper currently in flight began, so a returning voice can cut it off. */
    private var whisperStartedAtMs: Long? = null
    private var stats = CueOutputStats()

    fun stats(): CueOutputStats = stats

    fun reset() {
        pending = null
        whisperStartedAtMs = null
        stats = CueOutputStats()
    }

    /** Drops anything queued without playing it. Used when the vendor assistant takes the mic. */
    fun clearPending() {
        pending = null
    }

    fun submit(decision: CueDecision, nowMs: Long) {
        if (decision.isSilent) return

        if (decision.interrupt) {
            // Cut whatever is in flight. The user needs this now, not after Cue finishes a name.
            speaker.stop()
            pending = null
            decision.earcon?.let(earcons::play)
            decision.whisper?.let { speakNow(it, nowMs, interrupting = true) }
            return
        }

        decision.earcon?.let { earcon ->
            if (decision.earconRequiresSilence && gapDetector.isSpeechActive) {
                stats = stats.copy(ambientSuppressed = stats.ambientSuppressed + 1)
            } else {
                earcons.play(earcon)
            }
        }

        val whisper = decision.whisper ?: return
        if (!decision.requiresGap) {
            speakNow(whisper, nowMs, interrupting = false)
            return
        }
        if (canSpeakNow(nowMs)) {
            speakNow(whisper, nowMs, interrupting = false)
        } else {
            // Newest wins: if two whispers are waiting, the older one describes a room that has
            // already moved on.
            pending?.let { stats = stats.copy(whispersDroppedStale = stats.whispersDroppedStale + 1) }
            pending = Pending(whisper, nowMs)
        }
    }

    /** Called on every scheduler tick and whenever the detector reports a fresh gap. */
    fun tick(nowMs: Long) {
        abortWhisperIfSpeechResumed()

        val queued = pending ?: return
        val config = configProvider()
        if (nowMs - queued.queuedAtMs > config.maxPendingAgeMs) {
            pending = null
            stats = stats.copy(whispersDroppedStale = stats.whispersDroppedStale + 1)
            return
        }
        if (!canSpeakNow(nowMs)) return
        pending = null
        speakNow(queued.text, nowMs, interrupting = false)
    }

    /** Replays the last thing Cue said, verbatim from cache. Never regenerates. */
    fun repeat(last: SpokenOutput?, nowMs: Long): Boolean {
        if (last == null) {
            earcons.play(CueEarcon.FAILED)
            return false
        }
        speaker.stop()
        last.earcon?.let(earcons::play)
        val text = last.text
        if (text.isNullOrBlank()) return last.earcon != null
        val spoken = when (last.tier) {
            CueTier.BRIEFING -> speaker.briefing(text, "cue-repeat")
            else -> speaker.whisper(text, "cue-repeat")
        }
        if (!spoken) earcons.play(CueEarcon.FAILED)
        return spoken
    }

    /** Tier 2. User-initiated only, so it does not wait for a gap — the user just asked for it. */
    fun briefing(text: String, nowMs: Long): Boolean {
        pending = null
        speaker.stop()
        val spoken = speaker.briefing(text)
        if (spoken) {
            onSpoken(SpokenOutput(CueTier.BRIEFING, text, null, nowMs))
        } else {
            earcons.play(CueEarcon.FAILED)
        }
        return spoken
    }

    fun playEarcon(earcon: CueEarcon, nowMs: Long) {
        earcons.play(earcon)
        onSpoken(SpokenOutput(CueTier.EARCON, null, earcon, nowMs))
    }

    /**
     * Cuts a whisper short when someone starts speaking under it.
     *
     * Finding a gap at the moment a whisper starts is not enough — a name is a second of audio and
     * the other person can come back inside it. Half a name is a small cost; the last half of a
     * name landing on top of someone's first word is the failure this whole tier exists to avoid.
     */
    private fun abortWhisperIfSpeechResumed() {
        val startedAtMs = whisperStartedAtMs ?: return
        if (!gapDetector.speechResumedSince(startedAtMs)) return
        whisperStartedAtMs = null
        if (!speaker.isSpeaking) return
        speaker.stop()
        stats = stats.copy(whispersAborted = stats.whispersAborted + 1)
    }

    private fun canSpeakNow(nowMs: Long): Boolean {
        if (speaker.isSpeaking) return false
        return gapDetector.hasGap(nowMs, configProvider().requiredGapMs)
    }

    private fun speakNow(text: String, nowMs: Long, interrupting: Boolean) {
        if (!interrupting && gapDetector.isSpeechActive) {
            // Should be unreachable; counted rather than hidden so the zero-interruptions claim is
            // measured rather than asserted.
            stats = stats.copy(interruptions = stats.interruptions + 1)
        }
        if (speaker.whisper(text)) {
            whisperStartedAtMs = nowMs
            stats = stats.copy(whispersSpoken = stats.whispersSpoken + 1)
            onSpoken(SpokenOutput(CueTier.WHISPER, text, null, nowMs))
        }
    }
}
