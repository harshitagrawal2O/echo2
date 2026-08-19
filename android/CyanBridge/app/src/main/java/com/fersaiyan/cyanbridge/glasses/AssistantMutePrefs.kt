package com.fersaiyan.cyanbridge.glasses

import android.content.Context

/**
 * Whether the assistant is allowed to respond to a trigger from the glasses.
 *
 * Three separate controls raise the same trigger on a CY-01 - the "Hey Cyan" wake word, a single
 * press of the AI button, and a double tap on the right touch strip - and they arrive as a
 * byte-identical notify frame, so the app cannot tell them apart or ignore one of them selectively.
 * The touch strip is a bare capacitive surface on the arm of a pair of glasses, which makes an
 * accidental trigger a matter of when rather than whether.
 *
 * So the wearer needs a way to say "not now" that covers all three. Muting here gates the app's
 * response; [com.oudmon.ble.base.communication.bigData.LargeDataHandler.aiVoiceWake] additionally
 * asks the glasses to stop listening for the wake word at all. The gate is the guarantee, because
 * the hardware call can fail or be refused and a mute that silently does nothing is worse than none.
 *
 * Deliberately named "assistant off" rather than "mute" in anything the wearer hears: this does not
 * disable the glasses' own microphone hardware, and calling it a mic mute would be a privacy claim
 * the app cannot honour.
 */
object AssistantMutePrefs {

    private const val PREFS = "assistant_mute"
    private const val KEY_MUTED = "muted"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isMuted(context: Context): Boolean = prefs(context).getBoolean(KEY_MUTED, false)

    fun setMuted(context: Context, muted: Boolean) {
        prefs(context).edit().putBoolean(KEY_MUTED, muted).apply()
    }

    /** Flips the state and returns the new value, for a control that toggles. */
    fun toggle(context: Context): Boolean {
        val next = !isMuted(context)
        setMuted(context, next)
        return next
    }
}
