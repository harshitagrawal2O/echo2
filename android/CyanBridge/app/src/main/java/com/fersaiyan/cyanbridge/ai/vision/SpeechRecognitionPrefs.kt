package com.fersaiyan.cyanbridge.ai.vision

import android.content.Context

/**
 * The language the question microphone listens in, stored separately from the answer language.
 *
 * These were the same value, derived from the device locale. On a handset set to `en-GB` that meant
 * the recognizer listened in British English while the wearer asked questions in Hindi, so only the
 * English words survived and the rest arrived as approximate English phonetics:
 *
 * ```
 * resultLength=78
 * prompt: vah Udhar Gaya Main uska dikkat Nahin Hai Main idhar I am working on like this
 * ```
 *
 * Every "the microphone is not hearing me" symptom traced back here rather than to audio routing.
 * Speaking and reading are genuinely different preferences - asking in Hindi and hearing the answer
 * in English is a normal thing to want - so they get separate settings.
 *
 * Blank means "follow the response language", which preserves the previous behaviour for anyone who
 * has not set it.
 */
object SpeechRecognitionPrefs {

    private const val PREFS = "speech_recognition_prefs"
    private const val KEY_LANGUAGE_TAG = "recognition_language_tag"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** BCP-47 tag to listen in, or blank to follow the response language. */
    fun getLanguageTag(context: Context): String =
        prefs(context).getString(KEY_LANGUAGE_TAG, "")?.trim().orEmpty()

    fun setLanguageTag(context: Context, tag: String) {
        prefs(context).edit().putString(KEY_LANGUAGE_TAG, tag.trim()).apply()
    }
}
