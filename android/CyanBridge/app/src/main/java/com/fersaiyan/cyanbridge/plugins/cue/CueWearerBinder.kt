package com.fersaiyan.cyanbridge.plugins.cue

data class CueWearerConfig(
    /** Turns to observe before an energy-based guess is allowed. */
    val minTurns: Int = 4,
    /** Distinct voices required before the loudest one means anything. */
    val minLabels: Int = 2,
    /**
     * How much louder the wearer must be than the next speaker.
     *
     * The mic sits on the wearer's face. On a headset array that gap is usually large; if it is
     * not, the binder abstains rather than guessing, and the wearer stays in the roster until the
     * user binds themselves explicitly.
     */
    val dominanceRatio: Double = 1.6,
)

/**
 * Works out which diarization label is the wearer, so they can be excluded everywhere.
 *
 * The user talks constantly and a headset mic array is tuned to favour them, so without this the
 * roster gains a permanent phantom participant, the user gets announced to themselves, and the
 * addressee state machine can decide the user has walked away from themselves.
 *
 * Two paths:
 *  - [armExplicitBinding] takes the next voice heard. This is what the onboarding "say your name"
 *    step and the settings re-bind control use, and it is exact.
 *  - [observe] accumulates per-label loudness and picks the dominant voice. This is the fallback
 *    when the user never runs onboarding, and it abstains rather than guessing wrong.
 */
class CueWearerBinder(private val config: CueWearerConfig = CueWearerConfig()) {

    private data class Stats(var totalRms: Double = 0.0, var turns: Int = 0) {
        val meanRms: Double get() = if (turns == 0) 0.0 else totalRms / turns
    }

    private val stats = LinkedHashMap<String, Stats>()
    private var explicitArmed = false
    private var bound: String? = null

    val boundLabel: String? get() = bound

    fun reset() {
        stats.clear()
        explicitArmed = false
        bound = null
    }

    /** Arms the exact path: whichever voice speaks next is the wearer. */
    fun armExplicitBinding() {
        explicitArmed = true
    }

    /**
     * Feeds one turn. Returns the wearer's label the first time it is determined, otherwise null.
     *
     * Returning the label only once keeps the caller's binding idempotent — re-binding mid-session
     * would rewrite the roster underneath a live conversation.
     */
    fun observe(turn: Turn): String? {
        if (bound != null) return null

        if (explicitArmed) {
            explicitArmed = false
            bound = turn.speakerLabel
            return bound
        }

        val entry = stats.getOrPut(turn.speakerLabel) { Stats() }
        entry.totalRms += turn.energyRms
        entry.turns += 1

        val totalTurns = stats.values.sumOf { it.turns }
        if (totalTurns < config.minTurns || stats.size < config.minLabels) return null

        val ranked = stats.entries.sortedByDescending { it.value.meanRms }
        val loudest = ranked[0]
        val runnerUp = ranked[1]
        if (loudest.value.meanRms <= 0.0 || runnerUp.value.meanRms <= 0.0) return null
        if (loudest.value.meanRms < runnerUp.value.meanRms * config.dominanceRatio) return null

        bound = loudest.key
        return bound
    }
}
