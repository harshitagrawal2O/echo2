package com.fersaiyan.cyanbridge.plugins.cue

/**
 * Tuning for the rolling state machine. Every threshold here is a product decision, and the two
 * silence thresholds in particular are the difference between "Cue noticed" and "Cue nagged".
 */
data class CueEngineConfig(
    /**
     * How long the addressee must be silent, while the user keeps talking, before Cue interrupts.
     *
     * The acceptance bar is under 8 seconds from the addressee's last utterance to the earcon, so
     * this sits below that with room for the detector to fire.
     */
    val addresseeSilenceMs: Long = 6_000L,
    /**
     * How long any other known voice must be silent before Cue treats them as gone.
     *
     * Long, because a quiet person in a conversation is normal and a false departure is noise.
     */
    val presenceTimeoutMs: Long = 90_000L,
    /** How much transcript to keep in memory. Nothing is written to disk. */
    val transcriptWindowMs: Long = ConversationContext.TRANSCRIPT_WINDOW_MS,
    /** How much ambient history to keep, for the same window. */
    val ambientWindowMs: Long = 60_000L,
)

/**
 * The rolling state object, and the state machine over it.
 *
 * The engine only observes: it turns transcript segments and clock ticks into [CueEvent]s. It never
 * decides whether the user hears anything — that is [CueDivergenceArbiter]'s job, so that the
 * "should Cue speak" rule lives in exactly one testable place.
 *
 * All mutation is synchronized because turns arrive on the transcription thread while ticks arrive
 * on the session scheduler.
 */
class CueContextEngine(
    private val config: CueEngineConfig = CueEngineConfig(),
) {

    private val lock = Any()
    private var context = ConversationContext(sessionStartMs = 0L)

    /** Set of labels that have already produced a departure event, so each one fires once. */
    private val departedLabels = mutableSetOf<String>()

    /** True once the addressee departure has fired for the current addressee. */
    private var addresseeDepartureFired = false

    /** The speaker of the previous turn, used to derive who the user is addressing. */
    private var previousSpeakerLabel: String? = null

    fun start(nowMs: Long) = synchronized(lock) {
        context = ConversationContext(sessionStartMs = nowMs)
        departedLabels.clear()
        addresseeDepartureFired = false
        previousSpeakerLabel = null
    }

    /**
     * Clears every trace of the session. Called when the glasses come off, which is the privacy
     * story expressed as a physical act rather than a settings toggle.
     */
    fun clear() = synchronized(lock) {
        context = ConversationContext(sessionStartMs = 0L)
        departedLabels.clear()
        addresseeDepartureFired = false
        previousSpeakerLabel = null
    }

    fun snapshot(): ConversationContext = synchronized(lock) { context }

    /**
     * Binds the wearer's diarization label. The wearer is suppressed everywhere: they are not in
     * the roster, never announced, and never the addressee.
     *
     * The user talks constantly and a headset mic array is tuned to favour them, so leaving them
     * in the roster produces a permanent phantom participant.
     */
    fun bindWearer(label: String) = synchronized(lock) {
        context = context.copy(
            wearerLabel = label,
            roster = context.roster.filterNot { it.speakerLabel == label },
            addressee = context.addressee?.takeIf { it.speakerLabel != label },
        )
    }

    /** Binds a name to a diarization label, replacing any weaker binding for the same label. */
    fun bindName(label: String, name: String, confidence: NameConfidence, nowMs: Long): CueEvent? =
        synchronized(lock) {
            val existing = context.roster.firstOrNull { it.speakerLabel == label } ?: return null
            if (existing.name == name && existing.confidence == confidence) return null
            // Never downgrade a high-confidence binding on a weaker later guess.
            if (existing.confidence == NameConfidence.HIGH && confidence != NameConfidence.HIGH) return null
            val updated = existing.copy(name = name, confidence = confidence)
            replacePerson(updated)
            CueEvent.PersonNamed(updated, nowMs)
        }

    /**
     * Ingests one completed turn and returns everything that changed as a result.
     *
     * Order matters: a brand new voice produces a join before its turn-started event, so the
     * arbiter can play the enter earcon before deciding whether to whisper a name.
     */
    fun onTurn(turn: Turn): List<CueEvent> = synchronized(lock) {
        val events = mutableListOf<CueEvent>()
        val isWearer = turn.speakerLabel == context.wearerLabel

        val existing = context.roster.firstOrNull { it.speakerLabel == turn.speakerLabel }
        val person: Person
        if (existing == null) {
            person = Person(
                speakerLabel = turn.speakerLabel,
                firstHeardMs = turn.startMs,
                lastHeardMs = turn.endMs,
            )
            if (!isWearer) {
                context = context.copy(roster = context.roster + person)
                events += CueEvent.PersonJoined(person, turn.startMs)
            }
        } else {
            val returning = !existing.isPresent
            person = existing.copy(lastHeardMs = turn.endMs, isPresent = true)
            replacePerson(person)
            departedLabels -= turn.speakerLabel
            if (returning && !isWearer) {
                events += CueEvent.PersonJoined(person, turn.startMs)
            }
        }

        appendTurn(turn)
        if (isWearer) {
            context = context.copy(userLastSpokeMs = turn.endMs)
        } else {
            events += CueEvent.SpeakerTurnStarted(person, turn.startMs)
        }

        updateAddressee(turn, person, isWearer)
        previousSpeakerLabel = turn.speakerLabel
        events
    }

    /**
     * Advances the staleness clocks. Returns departures that became true since the last tick.
     *
     * Addressee departure is checked first and reported alone: if the person you were talking to
     * has walked away, nothing else Cue could say matters as much.
     */
    fun onTick(nowMs: Long): List<CueEvent> = synchronized(lock) {
        pruneWindows(nowMs)

        val addressee = context.addressee
        if (
            addressee != null &&
            !addresseeDepartureFired &&
            nowMs - addressee.lastHeardMs >= config.addresseeSilenceMs &&
            // Only fires while the user is still speaking into the void. A mutual pause is not a
            // departure, and treating it as one would make Cue cry wolf in every quiet moment.
            context.userLastSpokeMs > addressee.lastHeardMs
        ) {
            addresseeDepartureFired = true
            val departed = addressee.copy(isPresent = false)
            replacePerson(departed)
            departedLabels += departed.speakerLabel
            context = context.copy(addressee = departed)
            return listOf(CueEvent.AddresseeLeft(departed, nowMs))
        }

        val events = mutableListOf<CueEvent>()
        for (person in context.roster) {
            if (!person.isPresent) continue
            if (person.speakerLabel == context.wearerLabel) continue
            if (person.speakerLabel in departedLabels) continue
            if (nowMs - person.lastHeardMs < config.presenceTimeoutMs) continue
            val departed = person.copy(isPresent = false)
            replacePerson(departed)
            departedLabels += departed.speakerLabel
            events += CueEvent.PersonLeft(departed, nowMs)
        }
        events
    }

    /** Records an on-device ambient classification. Rate limiting happens in the arbiter. */
    fun onAmbient(category: String, nowMs: Long): CueEvent = synchronized(lock) {
        context = context.copy(ambientEvents = context.ambientEvents + AmbientEvent(category, nowMs))
        CueEvent.Ambient(category, nowMs)
    }

    fun onPhoto(photo: PhotoContext) = synchronized(lock) {
        context = context.copy(lastPhoto = photo)
    }

    fun onSpoke(output: SpokenOutput) = synchronized(lock) {
        context = context.copy(lastOutput = output)
    }

    fun lastOutput(): SpokenOutput? = synchronized(lock) { context.lastOutput }

    // ── internals ──

    /**
     * Who the user is talking *to*: the speaker they most recently exchanged turns with.
     *
     * An exchange is a turn boundary that crosses the wearer, in either direction. Someone talking
     * across the room without the user answering never becomes the addressee, which is what keeps
     * P0-4 from firing on people the user was not engaged with.
     */
    private fun updateAddressee(turn: Turn, person: Person, isWearer: Boolean) {
        val previous = previousSpeakerLabel
        val wearer = context.wearerLabel
        val candidate = when {
            // The user just replied to whoever spoke last.
            isWearer && previous != null && previous != wearer ->
                context.roster.firstOrNull { it.speakerLabel == previous }
            // Someone just replied to the user.
            !isWearer && previous != null && previous == wearer -> person
            else -> null
        } ?: return

        if (context.addressee?.speakerLabel != candidate.speakerLabel) {
            addresseeDepartureFired = false
        }
        context = context.copy(addressee = candidate)
    }

    private fun replacePerson(person: Person) {
        context = context.copy(
            roster = context.roster.map { if (it.speakerLabel == person.speakerLabel) person else it },
            addressee = context.addressee?.let {
                if (it.speakerLabel == person.speakerLabel) person else it
            },
        )
    }

    private fun appendTurn(turn: Turn) {
        context = context.copy(turns = context.turns + turn)
    }

    /** Drops transcript and ambient history older than the window. Nothing is persisted. */
    private fun pruneWindows(nowMs: Long) {
        val turns = context.turns.filter { nowMs - it.endMs <= config.transcriptWindowMs }
        val ambient = context.ambientEvents.filter { nowMs - it.atMs <= config.ambientWindowMs }
        if (turns.size != context.turns.size || ambient.size != context.ambientEvents.size) {
            context = context.copy(turns = turns, ambientEvents = ambient)
        }
    }
}
