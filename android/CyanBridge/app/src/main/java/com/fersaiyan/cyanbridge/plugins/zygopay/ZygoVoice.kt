package com.fersaiyan.cyanbridge.plugins.zygopay

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.accessibility.AccessibilityManager

/**
 * Says the things a sighted user would read.
 *
 * Implements the same rule as `ai/feedback/SpeechRouter.kt` on the vision branch — when a screen
 * reader is running it owns narration and this stays quiet, otherwise the app self-voices — but does
 * not depend on it, because that file is not on this branch. **This should collapse into
 * `SpeechRouter` when the branches meet**, and the collapse is the point rather than an
 * afterthought: adding a fourth uncoordinated `TextToSpeech` to an app that already has three is
 * how a blind user ends up with several voices talking over each other.
 *
 * Payment amounts are content, not state: they are the thing the user asked for and the only way
 * they can check what is about to be spent. So they are spoken either way — but through TalkBack
 * when it is there, so it arrives once, in the voice and at the rate the user configured.
 */
class ZygoVoice(context: Context) {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false

    fun start() {
        if (tts != null) return
        tts = TextToSpeech(appContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (!ready) Log.w(TAG, "TextToSpeech unavailable (status=$status)")
        }
    }

    fun screenReaderActive(): Boolean {
        val manager = appContext.getSystemService(Context.ACCESSIBILITY_SERVICE)
            as? AccessibilityManager ?: return false
        return manager.isEnabled && manager.isTouchExplorationEnabled
    }

    /**
     * [announce] is the composable's `announceForAccessibility` hook, supplied by the caller because
     * an accessibility announcement needs a view to come from.
     *
     * Falling back to self-voicing when the caller has no view is deliberate: a payment prompt that
     * silently fails to be spoken is the one outcome that must not happen, since silence here is
     * indistinguishable from "nothing is waiting for me".
     */
    fun say(text: String, announce: ((String) -> Unit)? = null) {
        if (text.isBlank()) return
        if (screenReaderActive() && announce != null) {
            announce(text)
            return
        }
        val engine = tts
        if (engine == null || !ready) {
            Log.w(TAG, "Nothing available to speak with; text dropped: $text")
            return
        }
        // QUEUE_FLUSH: the newest payment state is the only one worth hearing. A queued
        // "connecting" arriving after "approved" would describe a moment that has passed.
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "zygo_${System.nanoTime()}")
    }

    fun stop() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }

    private companion object {
        const val TAG = "ZygoVoice"
    }
}
