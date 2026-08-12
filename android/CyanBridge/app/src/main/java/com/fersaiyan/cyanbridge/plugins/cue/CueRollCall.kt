package com.fersaiyan.cyanbridge.plugins.cue

import org.json.JSONArray
import org.json.JSONObject

/** A diarization label bound to a name, with the confidence that gates whether Cue says it aloud. */
data class NameBinding(
    val speakerLabel: String,
    val name: String,
    val confidence: NameConfidence,
)

/**
 * Passive roll call: the social ritual of introducing yourself *is* the enrollment flow.
 *
 * No setup screen, no training step, no trigger. People say their names out loud when they meet,
 * because that is what humans do, so Cue watches the transcript and binds diarization labels to
 * names as they appear. Enrollment then happens in conversations the user did not plan for, which
 * is most of them.
 *
 * Two paths, and the local one runs first so enrollment survives a dead network:
 *  - [detectLocally] is regex over the transcript window. Instant, offline, conservative.
 *  - [buildPrompt] / [parseResponse] hand the same window to Haiku, which catches the phrasings
 *    the regexes miss ("this is Priya", "Priya, nice to meet you").
 */
object CueRollCall {

    /**
     * First-person introductions. Each pattern captures one or two capitalised-looking words.
     *
     * Kept deliberately narrow: a missed introduction costs one unnamed speaker, a false one costs
     * a wrong name spoken confidently into a room, which is the failure this product must not have.
     */
    private val FIRST_PERSON = listOf(
        Regex("""\bmy name'?s\s+([A-Za-z][\w'-]*(?:\s+[A-Z][\w'-]*)?)""", RegexOption.IGNORE_CASE),
        Regex("""\bmy name is\s+([A-Za-z][\w'-]*(?:\s+[A-Z][\w'-]*)?)""", RegexOption.IGNORE_CASE),
        Regex("""\bi'?m\s+([A-Za-z][\w'-]*(?:\s+[A-Z][\w'-]*)?)""", RegexOption.IGNORE_CASE),
        Regex("""\bi am\s+([A-Za-z][\w'-]*(?:\s+[A-Z][\w'-]*)?)""", RegexOption.IGNORE_CASE),
        Regex("""\bcall me\s+([A-Za-z][\w'-]*)""", RegexOption.IGNORE_CASE),
        Regex("""^\s*([A-Z][\w'-]*)\s+here\b"""),
        Regex("""\bthis is\s+([A-Z][\w'-]*)\s*,?\s*(?:speaking|here)\b"""),
    )

    /**
     * Words that follow "I'm" far more often than a name does.
     *
     * Capitalisation is the primary discriminator, but streaming transcripts are not reliably
     * cased, so this catches the common false positives when they are not.
     */
    private val NOT_NAMES = setOf(
        "a", "about", "afraid", "after", "against", "all", "already", "also", "always", "an",
        "and", "any", "around", "as", "at", "away", "back", "bad", "because", "been", "being",
        "better", "busy", "but", "by", "certain", "close", "coming", "confused", "curious",
        "definitely", "doing", "done", "down", "excited", "fine", "for", "from", "getting",
        "gonna", "good", "going", "great", "happy", "having", "he", "hearing", "her", "here",
        "his", "hoping", "how", "i", "if", "in", "interested", "into", "is", "it", "just",
        "kind", "kinda", "leaving", "like", "listening", "looking", "making", "maybe", "me",
        "more", "most", "much", "my", "near", "no", "not", "now", "of", "off", "ok", "okay",
        "on", "one", "only", "or", "our", "out", "over", "pretty", "probably", "quite", "ready",
        "really", "right", "running", "same", "saying", "seeing", "she", "should", "so", "some",
        "sorry", "starting", "still", "sure", "taking", "telling", "thankful", "that", "the",
        "their", "then", "there", "these", "they", "thinking", "this", "those", "through", "to",
        "today", "tomorrow", "too", "trying", "under", "unsure", "up", "us", "using", "very",
        "waiting", "walking", "was", "watching", "we", "well", "what", "when", "where", "which",
        "while", "who", "why", "will", "with", "wondering", "working", "would", "writing",
        "yeah", "yes", "yet", "you", "your",
    )

    private const val MAX_NAME_CHARS = 32

    /**
     * Scans the transcript window for self-introductions.
     *
     * Only binds names to the label that *said* them: a third-party introduction ("this is Priya")
     * names someone, but not necessarily the person speaking, so the local path leaves those to the
     * model rather than guessing which label they belong to.
     */
    fun detectLocally(turns: List<Turn>, wearerLabel: String? = null): List<NameBinding> {
        val bindings = LinkedHashMap<String, NameBinding>()
        for (turn in turns) {
            if (turn.speakerLabel == wearerLabel) continue
            val binding = detectInText(turn.text)?.let {
                NameBinding(turn.speakerLabel, it.first, it.second)
            } ?: continue
            val existing = bindings[turn.speakerLabel]
            // A later, more confident introduction wins; otherwise first mention stands.
            if (existing == null || existing.confidence.ordinal > binding.confidence.ordinal) {
                bindings[turn.speakerLabel] = binding
            }
        }
        return bindings.values.toList()
    }

    /** Returns the name and its confidence, or null when nothing in the text looks like one. */
    internal fun detectInText(text: String): Pair<String, NameConfidence>? {
        for (pattern in FIRST_PERSON) {
            val raw = pattern.find(text)?.groupValues?.getOrNull(1)?.trim() ?: continue
            val name = normalise(raw) ?: continue
            val head = raw.substringBefore(' ')
            val confidence = when {
                // Mid-sentence capitalisation in a punctuated transcript is a strong signal.
                head.first().isUpperCase() -> NameConfidence.HIGH
                // Uncased transcript: the word is plausibly a name but Cue will not say it aloud.
                else -> NameConfidence.MEDIUM
            }
            return name to confidence
        }
        return null
    }

    private fun normalise(raw: String): String? {
        val words = raw.split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return null
        if (words.first().lowercase() in NOT_NAMES) return null
        val cleaned = words.joinToString(" ") { word ->
            word.trim('.', ',', '!', '?', ';', ':', '"', '\'')
                .replaceFirstChar { it.uppercase() }
        }.trim()
        if (cleaned.isEmpty() || cleaned.length > MAX_NAME_CHARS) return null
        if (cleaned.any { !it.isLetter() && it != ' ' && it != '\'' && it != '-' }) return null
        return cleaned
    }

    /**
     * One model call over a transcript window. This is a prompt, not a model: no voice embeddings
     * are trained, and an unmapped speaker degrades to "someone new", which is still useful.
     */
    fun buildPrompt(turns: List<Turn>, wearerLabel: String?): String = buildString {
        append("Transcript of a live conversation, one line per turn, labelled by speaker.\n")
        if (wearerLabel != null) {
            append("Speaker $wearerLabel is the device wearer; never emit a binding for them.\n")
        }
        append("\n")
        for (turn in turns) {
            append(turn.speakerLabel).append(": ").append(turn.text.trim()).append('\n')
        }
        append("\nMap each speaker label to the person's own name, using only introductions present ")
        append("in the transcript. Do not guess from context, tone, or common names.\n")
        append("Confidence rules: \"high\" only when the speaker states their own name explicitly ")
        append("(\"I'm Sarah\", \"my name is Sarah\"). \"medium\" when someone else names them or the ")
        append("phrasing is indirect. Omit the speaker entirely when there is no evidence.\n")
        append("Respond with JSON only, no prose: ")
        append("""{"speakers":[{"label":"speaker_0","name":"Sarah","confidence":"high"}]}""")
    }

    /** Parses the model reply. Anything malformed yields no bindings rather than a bad name. */
    fun parseResponse(raw: String): List<NameBinding> {
        val json = raw.substringAfter('{', "").let { if (it.isEmpty()) return emptyList() else "{$it" }
        val obj = runCatching { JSONObject(json.substringBeforeLast('}') + "}") }.getOrNull()
            ?: return emptyList()
        val speakers: JSONArray = obj.optJSONArray("speakers") ?: return emptyList()
        val bindings = mutableListOf<NameBinding>()
        for (index in 0 until speakers.length()) {
            val entry = speakers.optJSONObject(index) ?: continue
            val label = entry.optString("label").trim().takeIf { it.isNotEmpty() } ?: continue
            val name = entry.optString("name").trim().takeIf { it.isNotEmpty() } ?: continue
            if (name.length > MAX_NAME_CHARS) continue
            val confidence = when (entry.optString("confidence").lowercase()) {
                "high" -> NameConfidence.HIGH
                "medium" -> NameConfidence.MEDIUM
                else -> NameConfidence.LOW
            }
            if (confidence == NameConfidence.LOW) continue
            bindings += NameBinding(label, name, confidence)
        }
        return bindings
    }
}
