package com.fersaiyan.cyanbridge.plugins.cue

/**
 * Domain model for Cue: ambient situational awareness for blind and low vision users.
 *
 * Everything in this file is pure Kotlin so the decision logic that decides *whether Cue speaks*
 * can be unit tested without a device. The rule those decisions implement:
 *
 *   Speak when the user's model of the room has diverged from the room, weighted by what it will
 *   cost them to act on the stale version. Stay silent otherwise.
 */

/** Confidence in a name binding. A wrong name is worse than no name, so this gates every utterance. */
enum class NameConfidence { HIGH, MEDIUM, LOW }

/**
 * Non-speech audio. Under 200ms, no language processing, carries the high frequency events.
 *
 * The SCO microphone route may force output to 8 kHz mono for the whole session, so every earcon
 * has to survive a ~3.4 kHz ceiling and narrowband voice coding. Tone choices live in
 * [CueEarconPlayer] and stay under that ceiling.
 */
enum class CueEarcon {
    /** Two ascending notes: something got added. */
    PERSON_ENTERED,

    /** Two descending notes: mirror of the above. */
    PERSON_LEFT,

    /** Two descending notes, doubled. Distinct because the consequence is distinct. */
    ADDRESSEE_LEFT,

    /** Single soft chime: someone is addressing you directly. */
    ADDRESSING_YOU,

    /** Slow double pulse: someone is waiting for you to respond. */
    AWAITING_YOU,

    /** Single low tick: an ambient event happened near you. */
    AMBIENT,

    /** Rising tick: Cue is working on your request. Covers the photo path latency. */
    WORKING,

    /** Low muted thud. Cue never says "I'm sorry, I didn't catch that". */
    FAILED,

    /** Short flat buzz: the glasses are busy and the command was rejected. Distinct from failure. */
    BUSY,

    /**
     * Three descending notes: Cue lost the glasses.
     *
     * The user must know the system has gone blind. Silence is indistinguishable from an empty
     * room, and that ambiguity is dangerous.
     */
    GLASSES_LOST,
}

/** Output tiers, ranked by interruption cost. Cue always uses the cheapest tier that carries the information. */
enum class CueTier {
    /** Non-speech audio only. */
    EARCON,

    /** One to four words, spoken fast, only in a detected speech gap. Never a sentence. */
    WHISPER,

    /** A full spoken response. Only ever user-initiated, or on session start. */
    BRIEFING,
}

/**
 * Someone Cue has heard. [speakerLabel] is the diarizer's label; [name] is filled in by passive
 * roll call when someone introduces themselves.
 */
data class Person(
    val speakerLabel: String,
    val name: String? = null,
    val confidence: NameConfidence = NameConfidence.LOW,
    val firstHeardMs: Long,
    val lastHeardMs: Long,
    val isPresent: Boolean = true,
) {
    /**
     * The name Cue is allowed to say out loud, or null when it must degrade to wordless output.
     * High confidence speaks the name, medium degrades to a hedge, low says nothing.
     */
    fun spokenName(): String? = when {
        name.isNullOrBlank() -> null
        confidence == NameConfidence.HIGH -> name
        else -> null
    }
}

/** One utterance from one speaker. [energyRms] feeds wearer detection; the mic favours the wearer. */
data class Turn(
    val speakerLabel: String,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val energyRms: Double = 0.0,
)

/** A non-speech sound a sighted person would have registered without thinking. */
data class AmbientEvent(val category: String, val atMs: Long)

/** The last photo Cue captured, kept so a visual question can be answered against real context. */
data class PhotoContext(
    val thumbnailPath: String?,
    val capturedAtMs: Long,
    val caption: String? = null,
)

/** Whatever Cue said last, cached verbatim so repeat-last replays rather than regenerates. */
data class SpokenOutput(
    val tier: CueTier,
    val text: String?,
    val earcon: CueEarcon?,
    val atMs: Long,
)

/**
 * The rolling state Cue reasons over. This is what lets Cue answer questions rather than describe
 * pixels: a photo alone gets "a man is holding a piece of paper", a photo plus this gets "Grant is
 * holding up the invoice he just mentioned".
 *
 * Staleness is first class. Divergence is computed against [Person.lastHeardMs] clocks, not against
 * events: someone who has not spoken in 90 seconds is not necessarily gone, but confidence that
 * they are present has decayed, and the arbiter should know that.
 */
data class ConversationContext(
    val sessionStartMs: Long,
    val wearerLabel: String? = null,
    val roster: List<Person> = emptyList(),
    val addressee: Person? = null,
    val turns: List<Turn> = emptyList(),
    val ambientEvents: List<AmbientEvent> = emptyList(),
    val lastPhoto: PhotoContext? = null,
    val lastOutput: SpokenOutput? = null,
    val pendingQuestion: Boolean = false,
    val userLastSpokeMs: Long = 0L,
) {
    /** Everyone currently believed present, excluding the wearer. */
    fun presentOthers(): List<Person> =
        roster.filter { it.isPresent && it.speakerLabel != wearerLabel }

    /** The last [windowMs] of transcript, wearer included, oldest first. */
    fun recentTranscript(nowMs: Long, windowMs: Long = TRANSCRIPT_WINDOW_MS): List<Turn> =
        turns.filter { nowMs - it.endMs <= windowMs }

    companion object {
        const val TRANSCRIPT_WINDOW_MS = 60_000L
    }
}

/**
 * Something Cue noticed. Events are raw observations; whether any of them reaches the user's ears
 * is [CueDivergenceArbiter]'s decision, not the engine's.
 */
sealed interface CueEvent {
    /** A known speaker started a turn. */
    data class SpeakerTurnStarted(val person: Person, val atMs: Long) : CueEvent

    /** A voice Cue has not heard before in this session. */
    data class PersonJoined(val person: Person, val atMs: Long) : CueEvent

    /** A known voice has been silent past the presence threshold. */
    data class PersonLeft(val person: Person, val atMs: Long) : CueEvent

    /**
     * The person the user was addressing has gone silent while the user is still talking.
     *
     * This is the highest value event in the product: total divergence, severe and public
     * consequence, and the one case where interrupting is correct.
     */
    data class AddresseeLeft(val person: Person, val atMs: Long) : CueEvent

    /** A name was bound to a diarization label by passive roll call. */
    data class PersonNamed(val person: Person, val atMs: Long) : CueEvent

    /** An on-device audio classification fired. */
    data class Ambient(val category: String, val atMs: Long) : CueEvent

    /** BLE went away. The user must know the system has gone blind. */
    data class GlassesLost(val atMs: Long) : CueEvent
}

/**
 * What Cue decided to do about one event.
 *
 * [requiresGap] gates the spoken part only — a 200ms earcon costs little enough to overlap speech.
 * [earconRequiresSilence] additionally holds the earcon back, and is set for exactly one tier:
 * ambient events, which are the feature most likely to turn Cue into a narrator.
 *
 * [interrupt] is the escape hatch from the gap detector and is true for exactly two events:
 * addressee departure, and losing the glasses. Everything else waits for silence.
 */
data class CueDecision(
    val earcon: CueEarcon? = null,
    val whisper: String? = null,
    val requiresGap: Boolean = true,
    val earconRequiresSilence: Boolean = false,
    val interrupt: Boolean = false,
) {
    val isSilent: Boolean get() = earcon == null && whisper == null

    companion object {
        val SILENT = CueDecision()
    }
}
