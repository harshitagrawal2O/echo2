package com.fersaiyan.cyanbridge.plugins.cue

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.fersaiyan.cyanbridge.glasses.GlassesSessionCoordinator
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.communication.LargeDataHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** The glasses were mid-operation. Cue drops the request rather than queueing it. */
class GlassesBusyException : Exception("Glasses are busy with another operation")

/**
 * Pulls the AI-photo thumbnail over BLE.
 *
 * This is the only Cue feature that claims a device mode, and it is deliberately **not** a media
 * sync. During a Wi-Fi Direct transfer the process is bound to the P2P network and the phone is off
 * the normal internet — fatal for a product whose every answer is a cloud call. The AI photo
 * thumbnail arrives over BLE instead: no transfer mode, no internet loss. Full resolution media, if
 * ever needed, syncs after the glasses come off.
 *
 * A rejected command gets the busy earcon and is dropped, never queued. An action that fires eight
 * seconds later into a conversation that has moved on is worse than no action.
 */
class CuePhotoCapture(context: Context) {

    private val appContext = context.applicationContext

    data class CapturedThumbnail(val file: File, val receivedAtMs: Long)

    /**
     * Receives the thumbnail for a photo the hardware button already took.
     *
     * The `0x02` notify means the image exists on the glasses; this only fetches it, so no capture
     * command is sent and no shutter fires twice.
     */
    suspend fun receiveThumbnail(namePrefix: String = "cue"): CapturedThumbnail {
        check(BleOperateManager.getInstance().isConnected) { "Glasses are not connected" }
        val permit = GlassesSessionCoordinator.tryAcquireBackgroundCommand()
            ?: throw GlassesBusyException()
        return try {
            val outputFile = newOutputFile(namePrefix)
            transfer(outputFile)
            CapturedThumbnail(outputFile, System.currentTimeMillis())
        } finally {
            GlassesSessionCoordinator.releaseBackgroundCommand(permit)
        }
    }

    /**
     * Takes a fresh photo and pulls it.
     *
     * Used by the app-initiated path (touchpad, phone control). The hardware AI button does not go
     * through here — its photo already exists by the time Cue hears about it.
     */
    suspend fun captureFresh(qualityLevel: Int, namePrefix: String = "cue"): CapturedThumbnail {
        check(BleOperateManager.getInstance().isConnected) { "Glasses are not connected" }
        val permit = GlassesSessionCoordinator.tryAcquireBackgroundCommand()
            ?: throw GlassesBusyException()
        val outputFile = newOutputFile(namePrefix)
        return try {
            val command = buildCaptureCommand(qualityLevel)
            LargeDataHandler.getInstance().glassesControl(command) { _, response ->
                Log.i(TAG, "Capture response dataType=${response.dataType} error=${response.errorCode}")
            }
            delay(SETTLE_MS)
            transfer(outputFile)
            CapturedThumbnail(outputFile, System.currentTimeMillis())
        } catch (error: Throwable) {
            outputFile.delete()
            throw error
        } finally {
            GlassesSessionCoordinator.releaseBackgroundCommand(permit)
        }
    }

    private suspend fun transfer(outputFile: File) {
        val completed = AtomicBoolean(false)
        val accepting = AtomicBoolean(true)
        val receivedBytes = AtomicLong(0L)
        val done = CompletableDeferred<Boolean>()
        val writeLock = Any()
        val stream = FileOutputStream(outputFile, false)

        LargeDataHandler.getInstance().getPictureThumbnails { _, isComplete, data ->
            if (data != null && data.isNotEmpty()) {
                synchronized(writeLock) {
                    if (accepting.get()) {
                        stream.write(data)
                        receivedBytes.addAndGet(data.size.toLong())
                    }
                }
            }
            if (isComplete && completed.compareAndSet(false, true)) {
                done.complete(receivedBytes.get() >= MIN_IMAGE_BYTES)
            }
        }

        val succeeded = withTimeoutOrNull(TRANSFER_TIMEOUT_MS) { done.await() } == true
        synchronized(writeLock) {
            accepting.set(false)
            runCatching { stream.flush() }
            runCatching { stream.close() }
        }
        if (!succeeded) {
            outputFile.delete()
            error("Thumbnail transfer timed out after ${TRANSFER_TIMEOUT_MS}ms")
        }
        val decoded = BitmapFactory.decodeFile(outputFile.absolutePath)
        if (decoded == null) {
            outputFile.delete()
            error("Glasses returned an unreadable thumbnail")
        }
        decoded.recycle()
    }

    private fun newOutputFile(namePrefix: String): File {
        val directory = appContext.getExternalFilesDir("cue") ?: appContext.filesDir
        directory.mkdirs()
        return File(directory, "${namePrefix}_${System.currentTimeMillis()}.jpg").also { it.delete() }
    }

    companion object {
        private const val TAG = "CuePhoto"
        private const val TRANSFER_TIMEOUT_MS = 14_000L
        private const val SETTLE_MS = 250L
        private const val MIN_IMAGE_BYTES = 1_024L

        /** Chat-AI capture, no trailing Home-AI mode byte. Quality is the documented 0..5 knob. */
        fun buildCaptureCommand(qualityLevel: Int): ByteArray {
            val quality = qualityLevel.coerceIn(0, 5).toByte()
            return byteArrayOf(0x02, 0x01, 0x06, quality, quality)
        }

        /** Base64 with no line breaks, which is what the Messages API expects. */
        fun encodeBase64(file: File): String? = runCatching {
            Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        }.getOrNull()
    }
}
