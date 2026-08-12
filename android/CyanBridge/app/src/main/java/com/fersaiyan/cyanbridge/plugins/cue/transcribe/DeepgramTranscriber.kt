package com.fersaiyan.cyanbridge.plugins.cue.transcribe

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Streaming transcription with diarization over Deepgram's websocket API.
 *
 * The whole of Cue's speaker attribution rides on this returning `speaker_N` labels within a few
 * hundred milliseconds of a turn starting. The fast path then maps label to cached name locally —
 * if naming a speaker ever needs a network round trip, the 800ms budget is already blown.
 *
 * Timestamps arrive relative to stream open, so they are rebased onto the wall clock captured at
 * [start]; everything downstream reasons in epoch milliseconds.
 */
class DeepgramTranscriber(
    private val apiKey: String,
    private val sampleRate: Int,
    private val languageTag: String = "en",
    private val model: String = DEFAULT_MODEL,
    private val client: OkHttpClient = defaultClient(),
) : CueTranscriber {

    private val running = AtomicBoolean(false)
    private val streamStartedAtMs = AtomicLong(0L)
    private val lastSendAtMs = AtomicLong(0L)

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var listener: CueTranscriber.Listener? = null

    override fun start(listener: CueTranscriber.Listener): Boolean {
        if (apiKey.isBlank()) {
            Log.w(TAG, "No Deepgram key configured; live attribution is unavailable")
            return false
        }
        if (!running.compareAndSet(false, true)) return true

        this.listener = listener
        streamStartedAtMs.set(System.currentTimeMillis())
        lastSendAtMs.set(System.currentTimeMillis())

        val url = buildString {
            append("wss://api.deepgram.com/v1/listen")
            append("?model=").append(model)
            append("&language=").append(languageTag)
            append("&encoding=linear16")
            append("&sample_rate=").append(sampleRate)
            append("&channels=1")
            // Diarization is the entire point of choosing this backend.
            append("&diarize=true")
            append("&punctuate=true")
            // Casing from smart formatting is what lets roll call tell "I'm Sarah" from "I'm going".
            append("&smart_format=true")
            append("&interim_results=true")
            // Short endpointing keeps turn boundaries tight enough for the 800ms budget.
            append("&endpointing=200")
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Token $apiKey")
            .build()

        socket = client.newWebSocket(request, SocketListener())
        return true
    }

    override fun send(pcm: ByteArray, length: Int) {
        if (!running.get() || length <= 0) return
        val socket = socket ?: return
        lastSendAtMs.set(System.currentTimeMillis())
        socket.send(pcm.toByteString(0, length))
    }

    /** Keeps an idle stream alive rather than letting the backend close it during a long silence. */
    fun keepAliveIfIdle(nowMs: Long) {
        if (!running.get()) return
        if (nowMs - lastSendAtMs.get() < KEEPALIVE_IDLE_MS) return
        lastSendAtMs.set(nowMs)
        runCatching { socket?.send("""{"type":"KeepAlive"}""") }
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) return
        val socket = socket
        this.socket = null
        this.listener = null
        runCatching { socket?.send("""{"type":"CloseStream"}""") }
        runCatching { socket?.close(1000, "session ended") }
    }

    private inner class SocketListener : WebSocketListener() {

        override fun onMessage(webSocket: WebSocket, text: String) {
            val payload = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (payload.optString("type")) {
                "Results" -> emitSegments(payload)
                "Metadata", "SpeechStarted", "UtteranceEnd" -> Unit
                else -> Unit
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!running.get()) return
            val detail = response?.code?.let { "HTTP $it" } ?: t.message ?: "unknown error"
            Log.w(TAG, "Transcription stream failed: $detail", t)
            listener?.onError(detail)
            listener?.onClosed()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!running.get()) return
            Log.i(TAG, "Transcription stream closed: $code $reason")
            listener?.onClosed()
        }
    }

    /**
     * Groups a result's words into one segment per contiguous speaker run.
     *
     * A single result can contain more than one speaker when two people overlap, and collapsing
     * that into one label would attribute half a sentence to the wrong person.
     */
    private fun emitSegments(payload: JSONObject) {
        val listener = listener ?: return
        val isFinal = payload.optBoolean("is_final", false)
        val alternative = payload.optJSONObject("channel")
            ?.optJSONArray("alternatives")
            ?.optJSONObject(0)
            ?: return
        val words = alternative.optJSONArray("words") ?: return
        if (words.length() == 0) return

        val base = streamStartedAtMs.get()
        var currentSpeaker = -1
        var startSec = 0.0
        var endSec = 0.0
        val builder = StringBuilder()

        fun flush() {
            if (builder.isBlank()) return
            listener.onSegment(
                TranscriptSegment(
                    speakerLabel = speakerLabel(currentSpeaker),
                    text = builder.toString().trim(),
                    startMs = base + (startSec * 1000).toLong(),
                    endMs = base + (endSec * 1000).toLong(),
                    isFinal = isFinal,
                ),
            )
            builder.setLength(0)
        }

        for (index in 0 until words.length()) {
            val word = words.optJSONObject(index) ?: continue
            val speaker = word.optInt("speaker", 0)
            val token = word.optString("punctuated_word").ifBlank { word.optString("word") }
            if (token.isBlank()) continue

            if (speaker != currentSpeaker) {
                flush()
                currentSpeaker = speaker
                startSec = word.optDouble("start", 0.0)
            }
            endSec = word.optDouble("end", startSec)
            if (builder.isNotEmpty()) builder.append(' ')
            builder.append(token)
        }
        flush()
    }

    private fun speakerLabel(speaker: Int): String =
        if (speaker < 0) "speaker_0" else "speaker_$speaker"

    companion object {
        private const val TAG = "CueTranscribe"
        private const val DEFAULT_MODEL = "nova-3"
        private const val KEEPALIVE_IDLE_MS = 5_000L

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            // A live stream must never be timed out for being quiet.
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
