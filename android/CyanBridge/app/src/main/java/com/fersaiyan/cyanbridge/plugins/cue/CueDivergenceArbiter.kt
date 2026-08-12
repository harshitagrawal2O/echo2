package com.fersaiyan.cyanbridge.plugins.cue

data class CueArbiterConfig(
    /**
     * At most one ambient earcon per this interval, and none while a human is speaking.
     *
     * Ambient awareness is the feature most likely to turn Cue into the narrating product it is
     * explicitly not, so this limit is a correctness requirement, not a polish item.
     */
    val ambientMinIntervalMs: Long = 15_000L,
    /**
     * Floor between any two Tier 1 whispers.
     *
     * Streaming diarizers flap mid-turn and can emit several speaker changes in a second. Without
     * a floor that becomes a burst of names, which is exactly the noise that gets assistive
     * wearables abandoned.
     */
    val minWhisperIntervalMs: Long = 3_000L,
)

/**
 * Decides whether Cue says anything, and in the cheapest tier that carries the information.
 *
 * Three questions per candidate utterance:
 *  1. Divergence — does the user's model currently differ from reality?
 *  2. Consequence — if they act on the stale model in the next thirty seconds, how badly does it go?
 *  3. Cost — what does saying it cost right now?
 *
 * Note what is *absent*: there is no event for "someone across the room stood up" or "the lights
 * changed". Zero divergence that matters means Cue never generates the event at all, rather than
 * generating it and filtering it later.
 *
 * Not thread safe; the session dispatches events on a single scope.
 */
class CueDivergenceArbiter(
    private val config: CueArbiterConfig = CueArbiterConfig(),
) {

    /**
     * Null rather than a sentinel timestamp.
     *
     * A `Long.MIN_VALUE` sentinel looks harmless until `atMs - lastWhisperAtMs` overflows to a
     * negative number, which reads as "too soon" and silently swallows the very first whisper and
     * the very first ambient earcon of every session — the two moments most likely to be watched.
     */
    private var lastAmbientAtMs: Long? = null
    private var lastWhisperAtMs: Long? = null
    private var lastNamedLabel: String? = null
    private val announcedNames = mutableSetOf<String>()

    fun reset() {
        lastAmbientAtMs = null
        lastWhisperAtMs = null
        lastNamedLabel = null
        announcedNames.clear()
    }

    fun decide(event: CueEvent, context: ConversationContext): CueDecision = when (event) {
        is CueEvent.AddresseeLeft -> addresseeLeft(event)
        is CueEvent.GlassesLost -> glassesLost()
        is CueEvent.PersonJoined -> personJoined(event)
        is CueEvent.PersonLeft -> personLeft(event)
        is CueEvent.PersonNamed -> personNamed(event)
        is CueEvent.SpeakerTurnStarted -> speakerTurnStarted(event, context)
        is CueEvent.Ambient -> ambient(event)
    }

    /**
     * Divergence total, consequence severe and public. The only case where interrupting is correct,
     * because the user is already speaking into a void and every additional second is worse.
     *
     * Wordless on purpose: a doubled descending earcon lands faster than a sentence, and the user
     * needs to stop talking now, not after Cue finishes explaining.
     */
    private fun addresseeLeft(event: CueEvent.AddresseeLeft): CueDecision {
        lastNamedLabel = null
        return CueDecision(
            earcon = CueEarcon.ADDRESSEE_LEFT,
            requiresGap = false,
            interrupt = true,
        )
    }

    /** Silence is indistinguishable from an empty room, so a dead link has to be audible. */
    private fun glassesLost(): CueDecision = CueDecision(
        earcon = CueEarcon.GLASSES_LOST,
        requiresGap = false,
        interrupt = true,
    )

    /** Divergence high, consequence moderate: you cannot address someone you do not know is there. */
    private fun personJoined(event: CueEvent.PersonJoined): CueDecision {
        val name = event.person.spokenName()
        val whisper = when {
            name != null -> name
            // Medium confidence degrades to a hedge rather than a guess. A wrong name is worse
            // than no name: the user will act on it, by name, out loud, in front of people.
            event.person.confidence == NameConfidence.MEDIUM -> SOMEONE_NEW
            else -> null
        }
        if (name != null) {
            announcedNames += event.person.speakerLabel
            lastNamedLabel = event.person.speakerLabel
        }
        return CueDecision(
            earcon = CueEarcon.PERSON_ENTERED,
            whisper = whisper?.takeIf { allowWhisper(event.atMs) },
        )
    }

    private fun personLeft(event: CueEvent.PersonLeft): CueDecision {
        if (lastNamedLabel == event.person.speakerLabel) lastNamedLabel = null
        announcedNames -= event.person.speakerLabel
        val name = event.person.spokenName()
        return CueDecision(
            earcon = CueEarcon.PERSON_LEFT,
            whisper = name?.let { "$it left" }?.takeIf { allowWhisper(event.atMs) },
        )
    }

    /**
     * Passive roll call bound a name. Worth one whisper so the user learns who the earlier earcon
     * referred to, but only once per person and only at high confidence.
     */
    private fun personNamed(event: CueEvent.PersonNamed): CueDecision {
        val name = event.person.spokenName() ?: return CueDecision.SILENT
        if (!announcedNames.add(event.person.speakerLabel)) return CueDecision.SILENT
        if (!allowWhisper(event.atMs)) return CueDecision.SILENT
        lastNamedLabel = event.person.speakerLabel
        return CueDecision(whisper = name)
    }

    /**
     * Moderate divergence, low but constant consequence. Name only, gap only, and only when the
     * speaker actually changed — repeating a name every turn of a two-person exchange is noise.
     */
    private fun speakerTurnStarted(
        event: CueEvent.SpeakerTurnStarted,
        context: ConversationContext,
    ): CueDecision {
        if (event.person.speakerLabel == context.wearerLabel) return CueDecision.SILENT
        if (event.person.speakerLabel == lastNamedLabel) return CueDecision.SILENT
        // Below high confidence this degrades all the way to nothing: an unnamed speaker change is
        // not worth a hedge on every turn.
        val name = event.person.spokenName() ?: run {
            lastNamedLabel = event.person.speakerLabel
            return CueDecision.SILENT
        }
        if (!allowWhisper(event.atMs)) return CueDecision.SILENT
        lastNamedLabel = event.person.speakerLabel
        announcedNames += event.person.speakerLabel
        return CueDecision(whisper = name)
    }

    /** Earcon only, never words, hard rate limited, and suppressed entirely while a human speaks. */
    private fun ambient(event: CueEvent.Ambient): CueDecision {
        val previous = lastAmbientAtMs
        if (previous != null && event.atMs - previous < config.ambientMinIntervalMs) {
            return CueDecision.SILENT
        }
        lastAmbientAtMs = event.atMs
        return CueDecision(earcon = CueEarcon.AMBIENT, earconRequiresSilence = true)
    }

    private fun allowWhisper(atMs: Long): Boolean {
        val previous = lastWhisperAtMs
        if (previous != null && atMs - previous < config.minWhisperIntervalMs) return false
        lastWhisperAtMs = atMs
        return true
    }

    private companion object {
        const val SOMEONE_NEW = "Someone new"
    }
}
