package com.fersaiyan.cyanbridge.ai.feedback

import android.content.Context
import android.os.Build
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One voice per engine, plus arbitration against the screen reader.
 *
 * The app is partly self-voicing: AI answers arrive through its own text-to-speech, which is what
 * an eyes-free user without a screen reader needs. But when TalkBack is running there are two
 * voices sharing one ear, and everything the app narrates gets said twice. The vOICe handles the
 * same collision by reducing self-voicing whenever a screen reader is detected; this router is
 * that rule, in one place.
 *
 * What it cannot do is make the app speak with a single voice overall. `QUEUE_FLUSH` only flushes
 * this router's own engine, and MainActivity's answer flow and Cue each own a separate
 * [TextToSpeech] instance, so a tap-to-hear read and an arriving answer can still overlap. That
 * collapses only when every speaker routes through here - which is blocked on the router being
 * able to express Cue's SCO audio routing, not on anything in this file.
 *
 * - [speakContent] - the thing the user asked for (an AI answer, a history entry read aloud).
 *   Always self-voiced: screen readers do not spontaneously read new content, so there is no
 *   double-speech risk and no substitute.
 * - [speakState] - the app narrating itself ("capture failed", "glasses disconnected"). With a
 *   screen reader active this is handed to it as an accessibility announcement so it is spoken
 *   once, in the user's configured voice, interruptible on their terms. Without one, it is
 *   self-voiced, because silence is indistinguishable from failure.
 *
 * A process singleton rather than an activity member deliberately: Cue runs from a foreground
 * service with no activity to call into, and the three-voice problem (MainActivity's TTS, Cue's
 * TTS, TalkBack) only collapses if there is a single place to route through. Components that own
 * a specialised engine (MainActivity's answer flow with its stream routing and utterance
 * callbacks) may keep using it for content; state narration should come through here.
 */
class SpeechRouter private constructor(private val appContext: Context) {

    enum class StateRoute { SCREEN_READER_ANNOUNCEMENT, SELF_VOICE }

    companion object {
        private const val TAG = "SpeechRouter"

        @Volatile
        private var instance: SpeechRouter? = null

        fun get(context: Context): SpeechRouter =
            instance ?: synchronized(this) {
                instance ?: SpeechRouter(context.applicationContext).also { instance = it }
            }

        /** The routing rule, separated so it can be pinned by a unit test. */
        fun routeForState(screenReaderActive: Boolean): StateRoute =
            if (screenReaderActive) StateRoute.SCREEN_READER_ANNOUNCEMENT else StateRoute.SELF_VOICE
    }

    private val ttsReady = AtomicBoolean(false)
    private val tts: TextToSpeech by lazy {
        TextToSpeech(appContext) { status ->
            ttsReady.set(status == TextToSpeech.SUCCESS)
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "Fallback TTS engine failed to initialise (status=$status)")
            }
        }
    }

    /** True when a spoken-feedback service (TalkBack or equivalent) is running. */
    fun screenReaderActive(): Boolean {
        val manager = appContext.getSystemService(Context.ACCESSIBILITY_SERVICE)
            as? AccessibilityManager ?: return false
        return manager.isEnabled && manager.isTouchExplorationEnabled
    }

    /** Content the user asked to hear. Always self-voiced. */
    fun speakContent(text: String) {
        if (text.isBlank()) return
        selfVoice(text)
    }

    /** The app narrating its own state. Routed so it is heard exactly once. */
    fun speakState(message: String) {
        if (message.isBlank()) return
        when (routeForState(screenReaderActive())) {
            StateRoute.SCREEN_READER_ANNOUNCEMENT -> announce(message)
            StateRoute.SELF_VOICE -> selfVoice(message)
        }
    }

    private fun selfVoice(text: String) {
        // Touching `tts` lazily initialises the engine; the first utterance after a cold start
        // may be dropped while it warms up, which is acceptable for state and rare for content.
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "router_${System.nanoTime()}")
        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "Self-voice failed (ready=${ttsReady.get()}, result=$result)")
        }
    }

    private fun announce(message: String) {
        val manager = appContext.getSystemService(Context.ACCESSIBILITY_SERVICE)
            as? AccessibilityManager ?: return
        if (!manager.isEnabled) return
        val event = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AccessibilityEvent()
        } else {
            @Suppress("DEPRECATION")
            AccessibilityEvent.obtain()
        }
        event.eventType = AccessibilityEvent.TYPE_ANNOUNCEMENT
        event.packageName = appContext.packageName
        event.className = SpeechRouter::class.java.name
        event.text.add(message)
        runCatching { manager.sendAccessibilityEvent(event) }
            .onFailure { Log.w(TAG, "Accessibility announcement failed", it) }
    }
}
