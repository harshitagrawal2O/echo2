package com.fersaiyan.cyanbridge.plugins.cue

import android.content.Context

/**
 * Cue's settings.
 *
 * Several values here are deliberately preferences rather than constants because they are the
 * outputs of hardware spikes that have to be run on a real CY-01: whether the ENC mic array
 * captures non-wearers usably ([preferGlassesMic]), and how frequent 400ms gaps actually are in
 * three-person speech ([requiredGapMs]). Hard-coding either would bake in an assumption the
 * hardware may well contradict.
 *
 * API keys are held in the app's private preferences. Nothing about a session — transcript, roster,
 * names, audio — is written to disk at any point.
 */
object CuePreferences {

    private const val PREFS = "cue_prefs"

    private const val KEY_ENABLED = "enabled"
    private const val KEY_ANTHROPIC_KEY = "anthropic_api_key"
    private const val KEY_TRANSCRIPTION_KEY = "transcription_api_key"
    private const val KEY_PREFER_GLASSES_MIC = "prefer_glasses_mic"
    private const val KEY_REQUIRED_GAP_MS = "required_gap_ms"
    private const val KEY_ADDRESSEE_SILENCE_MS = "addressee_silence_ms"
    private const val KEY_PRESENCE_TIMEOUT_MS = "presence_timeout_ms"
    private const val KEY_WHISPER_RATE = "whisper_rate"
    private const val KEY_BRIEFING_RATE = "briefing_rate"
    private const val KEY_EARCON_VOLUME = "earcon_volume"
    private const val KEY_AMBIENT_ENABLED = "ambient_enabled"
    private const val KEY_REHEARSAL_ENABLED = "rehearsal_enabled"
    private const val KEY_REHEARSAL_SCRIPT = "rehearsal_script"
    private const val KEY_THUMBNAIL_QUALITY = "thumbnail_quality"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_WEAR_LIFECYCLE = "wear_lifecycle"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getAnthropicApiKey(context: Context): String =
        prefs(context).getString(KEY_ANTHROPIC_KEY, "").orEmpty().trim()

    fun setAnthropicApiKey(context: Context, value: String) {
        prefs(context).edit().putString(KEY_ANTHROPIC_KEY, value.trim()).apply()
    }

    /** Key for the streaming diarizing STT backend. Without it Cue runs earcon-only. */
    fun getTranscriptionApiKey(context: Context): String =
        prefs(context).getString(KEY_TRANSCRIPTION_KEY, "").orEmpty().trim()

    fun setTranscriptionApiKey(context: Context, value: String) {
        prefs(context).edit().putString(KEY_TRANSCRIPTION_KEY, value.trim()).apply()
    }

    /**
     * Whether to listen through the glasses or the phone.
     *
     * Defaults to the glasses, but this is the setting to flip first if the two people who are
     * *not* wearing the glasses come back unintelligible: environmental noise cancellation on a
     * headset is tuned to isolate the wearer, which is exactly backwards for Cue.
     */
    fun preferGlassesMic(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PREFER_GLASSES_MIC, true)

    fun setPreferGlassesMic(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_PREFER_GLASSES_MIC, value).apply()
    }

    /** Silence required before a whisper plays. Set this from measured gap data, not from taste. */
    fun getRequiredGapMs(context: Context): Long =
        prefs(context).getInt(KEY_REQUIRED_GAP_MS, 400).coerceIn(100, 1_500).toLong()

    fun setRequiredGapMs(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_REQUIRED_GAP_MS, value.coerceIn(100, 1_500)).apply()
    }

    fun getAddresseeSilenceMs(context: Context): Long =
        prefs(context).getInt(KEY_ADDRESSEE_SILENCE_MS, 6_000).coerceIn(2_000, 15_000).toLong()

    fun setAddresseeSilenceMs(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_ADDRESSEE_SILENCE_MS, value.coerceIn(2_000, 15_000)).apply()
    }

    fun getPresenceTimeoutMs(context: Context): Long =
        prefs(context).getInt(KEY_PRESENCE_TIMEOUT_MS, 90_000).coerceIn(20_000, 300_000).toLong()

    fun setPresenceTimeoutMs(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_PRESENCE_TIMEOUT_MS, value.coerceIn(20_000, 300_000)).apply()
    }

    fun getWhisperRate(context: Context): Float =
        prefs(context).getFloat(KEY_WHISPER_RATE, CueSpeaker.DEFAULT_WHISPER_RATE)
            .coerceIn(CueSpeaker.MIN_RATE, CueSpeaker.MAX_RATE)

    fun setWhisperRate(context: Context, value: Float) {
        prefs(context).edit()
            .putFloat(KEY_WHISPER_RATE, value.coerceIn(CueSpeaker.MIN_RATE, CueSpeaker.MAX_RATE))
            .apply()
    }

    fun getBriefingRate(context: Context): Float =
        prefs(context).getFloat(KEY_BRIEFING_RATE, CueSpeaker.DEFAULT_BRIEFING_RATE)
            .coerceIn(CueSpeaker.MIN_RATE, CueSpeaker.MAX_RATE)

    fun setBriefingRate(context: Context, value: Float) {
        prefs(context).edit()
            .putFloat(KEY_BRIEFING_RATE, value.coerceIn(CueSpeaker.MIN_RATE, CueSpeaker.MAX_RATE))
            .apply()
    }

    fun getEarconVolume(context: Context): Float =
        prefs(context).getFloat(KEY_EARCON_VOLUME, 0.85f).coerceIn(0.1f, 1.0f)

    fun setEarconVolume(context: Context, value: Float) {
        prefs(context).edit().putFloat(KEY_EARCON_VOLUME, value.coerceIn(0.1f, 1.0f)).apply()
    }

    /** Tier 0.5 ambient earcons. Off by default: this is the feature most able to make Cue a narrator. */
    fun isAmbientEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AMBIENT_ENABLED, false)

    fun setAmbientEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_AMBIENT_ENABLED, value).apply()
    }

    /** Replays a recorded conversation instead of listening. The rehearsed demo fallback. */
    fun isRehearsalEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REHEARSAL_ENABLED, false)

    fun setRehearsalEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_REHEARSAL_ENABLED, value).apply()
    }

    fun getRehearsalScript(context: Context): String =
        prefs(context).getString(KEY_REHEARSAL_SCRIPT, "").orEmpty()

    fun setRehearsalScript(context: Context, value: String) {
        prefs(context).edit().putString(KEY_REHEARSAL_SCRIPT, value).apply()
    }

    fun getThumbnailQuality(context: Context): Int =
        prefs(context).getInt(KEY_THUMBNAIL_QUALITY, 5).coerceIn(0, 5)

    fun setThumbnailQuality(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_THUMBNAIL_QUALITY, value.coerceIn(0, 5)).apply()
    }

    fun getLanguage(context: Context): String =
        prefs(context).getString(KEY_LANGUAGE, "en").orEmpty().ifBlank { "en" }

    fun setLanguage(context: Context, value: String) {
        prefs(context).edit().putString(KEY_LANGUAGE, value.trim()).apply()
    }

    /**
     * Start and stop the session on wear detection rather than on BLE connect.
     *
     * `GlassesTouchSupportRsp.isWearCheckSupport()` is a capability flag and the vendor AAR is
     * shared with a smartwatch line, so class existence proves nothing about the CY-01. Cue queries
     * capabilities at connect and falls back to connection state when wear check is unsupported.
     */
    fun isWearLifecycleEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WEAR_LIFECYCLE, true)

    fun setWearLifecycleEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_WEAR_LIFECYCLE, value).apply()
    }
}
