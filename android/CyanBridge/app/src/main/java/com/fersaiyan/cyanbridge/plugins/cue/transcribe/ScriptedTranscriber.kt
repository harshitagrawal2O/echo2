package com.fersaiyan.cyanbridge.plugins.cue.transcribe

import android.util.Log
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** One scripted line: when it starts relative to session start, who says it, and how long it runs. */
data class ScriptedLine(
    val atMs: Long,
    val speakerLabel: String,
    val text: String,
    val durationMs: Long,
)

/**
 * Replays a pre-recorded conversation instead of transcribing live audio.
 *
 * Diarization degrades badly with overlapping speech and in noisy rooms, and demo venues are noisy
 * rooms. This is the rehearsed fallback behind a mode flag: identical downstream behaviour, no
 * network, no microphone, deterministic timing. It is also what the integration tests drive.
 *
 * It deliberately does *not* fake anything downstream — the roster, the arbiter, the gap detector
 * and the speaker all run exactly as they do live. Only the source of the transcript changes.
 */
class ScriptedTranscriber(
    private val lines: List<ScriptedLine>,
    private val loop: Boolean = false,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) : CueTranscriber {

    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    override fun start(listener: CueTranscriber.Listener): Boolean {
        if (lines.isEmpty()) {
            Log.w(TAG, "Rehearsal script is empty")
            return false
        }
        if (!running.compareAndSet(false, true)) return true

        worker = Thread(Playback(listener), "cue-rehearsal").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "Rehearsal mode: replaying ${lines.size} lines")
        return true
    }

    private inner class Playback(private val listener: CueTranscriber.Listener) : Runnable {
        override fun run() {
            try {
                do {
                    if (!playOnce()) return
                } while (loop && running.get())
                if (running.get()) listener.onClosed()
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Throwable) {
                Log.e(TAG, "Rehearsal playback failed", error)
                listener.onError(error.message ?: "rehearsal playback failed")
                listener.onClosed()
            }
        }

        /** Plays the script once. Returns false when playback was stopped part way through. */
        private fun playOnce(): Boolean {
            val cycleStart = clock()
            for (line in lines.sortedBy { it.atMs }) {
                if (!running.get()) return false
                val dueAtMs = cycleStart + line.atMs
                val waitMs = dueAtMs - clock()
                if (waitMs > 0) sleeper(waitMs)
                if (!running.get()) return false
                listener.onSegment(
                    TranscriptSegment(
                        speakerLabel = line.speakerLabel,
                        text = line.text,
                        startMs = dueAtMs,
                        endMs = dueAtMs + line.durationMs,
                        isFinal = true,
                    ),
                )
            }
            return true
        }
    }

    /** Live audio is ignored in rehearsal mode; the microphone still runs for the gap detector. */
    override fun send(pcm: ByteArray, length: Int) = Unit

    override fun stop() {
        if (!running.compareAndSet(true, false)) return
        worker?.interrupt()
        worker = null
    }

    companion object {
        private const val TAG = "CueRehearsal"

        /**
         * Parses a rehearsal script.
         *
         * ```json
         * {"segments": [
         *   {"at_ms": 0,    "speaker": "speaker_0", "text": "Hi, I'm Sarah.", "duration_ms": 1400},
         *   {"at_ms": 2200, "speaker": "speaker_1", "text": "I'm Priya.",     "duration_ms": 1100}
         * ]}
         * ```
         */
        fun parse(json: String): List<ScriptedLine> {
            val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
            val segments = root.optJSONArray("segments") ?: return emptyList()
            return buildList {
                for (index in 0 until segments.length()) {
                    val entry = segments.optJSONObject(index) ?: continue
                    val text = entry.optString("text").trim()
                    if (text.isEmpty()) continue
                    add(
                        ScriptedLine(
                            atMs = entry.optLong("at_ms", 0L),
                            speakerLabel = entry.optString("speaker").ifBlank { "speaker_0" },
                            text = text,
                            durationMs = entry.optLong("duration_ms", 1_200L),
                        ),
                    )
                }
            }
        }
    }
}
