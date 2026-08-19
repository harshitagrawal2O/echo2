package com.fersaiyan.cyanbridge.media

import android.content.Context

object GlassesMediaPrefs {
    private const val PREFS = "glasses_media"
    private const val KEY_VIDEO_RECORDING = "video_recording"

    fun isVideoRecording(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_VIDEO_RECORDING, false)
    }

    fun setVideoRecording(context: Context, recording: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_VIDEO_RECORDING, recording)
            .apply()
    }

    /**
     * The last progress value the glasses reported for a recording they are running themselves.
     *
     * [isVideoRecording] only knows about recordings the *app* started, so one begun by
     * double-pressing the picture button was invisible: asking the assistant to stop it was refused
     * as "nothing is recording". Notify `0x0b` is the glasses saying otherwise, and it was falling
     * through as an unhandled code.
     *
     * **The unit is not known.** It was read as elapsed seconds on three samples, then measured
     * again: four reports spanning 9 s of wall clock moved the value by 2, while an earlier run moved
     * it 41 -> 46 in 6 s. Neither fits a clock. Do not speak this number to the wearer or convert it
     * to a duration until it has been identified - only its *freshness* is trustworthy, which is
     * enough to know a recording is running.
     *
     * Deliberately not called "video" either: `0x01`'s media counters suggest double-press and
     * long-press produce different types, and neither has been confirmed.
     */
    fun setRecordingProgress(context: Context, progress: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_RECORDING_PROGRESS, progress)
            .putLong(KEY_RECORDING_REPORTED_AT, System.currentTimeMillis())
            .apply()
    }

    /**
     * The last reported progress value, if it arrived recently enough that a recording is still live.
     *
     * Reports stop arriving when the recording stops and there is no explicit "stopped" frame, so
     * freshness is the only available signal. [RECORDING_REPORT_STALE_MS] is several times the
     * observed ~3 s reporting interval, so a live recording is never mistaken for a finished one.
     */
    fun recordingProgressOrNull(context: Context): Int? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val reportedAt = prefs.getLong(KEY_RECORDING_REPORTED_AT, 0L)
        if (reportedAt <= 0L) return null
        if (System.currentTimeMillis() - reportedAt > RECORDING_REPORT_STALE_MS) return null
        return prefs.getInt(KEY_RECORDING_PROGRESS, 0)
    }

    /** True if either the app started a recording or the glasses say one is running. */
    fun isAnyRecordingActive(context: Context): Boolean =
        isVideoRecording(context) || recordingProgressOrNull(context) != null

    private const val KEY_RECORDING_PROGRESS = "recording_progress"
    private const val KEY_RECORDING_REPORTED_AT = "recording_reported_at"
    private const val RECORDING_REPORT_STALE_MS = 12_000L
}
