package com.fersaiyan.cyanbridge.plugins.cue

/**
 * A short rolling history of input loudness, bucketed by time.
 *
 * The transcriber says *who* spoke and *when*; the microphone says *how loud*. Neither alone
 * identifies the wearer, so this bridges them: given a turn's time span, [meanRmsBetween] returns
 * how loud the room was while it happened, which is what lets [CueWearerBinder] pick out the voice
 * sitting closest to the mic.
 *
 * Bucketed at 100ms rather than per frame so a minute of history costs a few hundred entries
 * instead of a few thousand. Not thread safe; the capture thread writes and the session reads under
 * the same lock.
 */
class CueEnergyTrack(
    private val bucketMs: Long = 100L,
    private val windowMs: Long = 60_000L,
) {

    private class Bucket(val startMs: Long) {
        var total = 0.0
        var count = 0
        val mean: Double get() = if (count == 0) 0.0 else total / count
    }

    private val buckets = ArrayDeque<Bucket>()

    fun clear() = buckets.clear()

    fun record(rms: Double, atMs: Long) {
        val start = atMs - (atMs % bucketMs)
        val last = buckets.lastOrNull()
        val bucket = if (last != null && last.startMs == start) {
            last
        } else {
            Bucket(start).also { buckets.addLast(it) }
        }
        bucket.total += rms
        bucket.count += 1
        prune(atMs)
    }

    /** Mean loudness across the span, or zero when no frames overlap it. */
    fun meanRmsBetween(startMs: Long, endMs: Long): Double {
        var total = 0.0
        var count = 0
        for (bucket in buckets) {
            if (bucket.startMs + bucketMs <= startMs) continue
            if (bucket.startMs > endMs) break
            if (bucket.count == 0) continue
            total += bucket.mean
            count += 1
        }
        return if (count == 0) 0.0 else total / count
    }

    private fun prune(nowMs: Long) {
        while (buckets.isNotEmpty() && nowMs - buckets.first().startMs > windowMs) {
            buckets.removeFirst()
        }
    }
}
