package com.fersaiyan.cyanbridge.plugins.cue.transcribe

/**
 * One diarized chunk of speech. [speakerLabel] is the diarizer's own label (`speaker_0`,
 * `speaker_1`), not a name — binding labels to names is passive roll call's job.
 *
 * Interim segments arrive first and get revised; only [isFinal] segments are allowed to move the
 * roster, because streaming diarizers relabel speakers retroactively and a name whispered off an
 * interim label can be wrong.
 */
data class TranscriptSegment(
    val speakerLabel: String,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val isFinal: Boolean,
)

/**
 * A streaming speech-to-text backend that returns speaker labels.
 *
 * Speaker labels are the hard requirement here, which is why Android's own `SpeechRecognizer` is
 * not an option for this path: it returns no labels, segments on long silences, and holds a
 * process-global single-listener lock.
 */
interface CueTranscriber {

    interface Listener {
        fun onSegment(segment: TranscriptSegment)

        /** Recoverable problem. The session degrades to earcon-only rather than stopping. */
        fun onError(message: String)

        /** The backend went away and will not deliver more segments without a restart. */
        fun onClosed()
    }

    /** Opens the stream. Returns false when the backend cannot start at all (no key, no network). */
    fun start(listener: Listener): Boolean

    /** Feeds signed 16-bit little-endian mono PCM at the sample rate the backend was built for. */
    fun send(pcm: ByteArray, length: Int)

    fun stop()
}
