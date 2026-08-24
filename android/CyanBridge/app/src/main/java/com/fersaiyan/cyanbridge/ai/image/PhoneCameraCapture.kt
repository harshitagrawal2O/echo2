package com.fersaiyan.cyanbridge.ai.image

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Captures a still from the phone's own camera for an image question.
 *
 * No [androidx.camera.core.Preview] use case is bound. The primary user cannot see a viewfinder,
 * so rendering one would cost a surface, a layout and a lifecycle for no benefit. Capture is
 * therefore possible from any screen without owning the UI.
 *
 * This deliberately does not touch `GlassesSessionCoordinator`. The vendor SDK's callback slots
 * are global singletons guarded by leases, and taking one here would block media sync or OTA for
 * a capture that never reaches the glasses.
 *
 * The caller is expected to hand [File.getAbsolutePath] to the same `onImageReadyForQuestion`
 * entry point the glasses paths use, so prompt resolution, provider routing, speech and history
 * stay identical regardless of which camera produced the bytes.
 */
class PhoneCameraCapture(private val context: Context) {

    companion object {
        private const val TAG = "PhoneCameraCapture"

        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
    }

    sealed interface Result {
        data class Success(val file: File, val durationMs: Long) : Result
        data class Failure(val reason: String, val cause: Throwable? = null) : Result
    }

    /**
     * Takes one photo and writes it to the cache directory.
     *
     * Returns [Result.Failure] rather than throwing, because every caller sits on the AI button
     * path where a thrown exception would surface as silence to a user who cannot see a toast.
     */
    suspend fun capture(
        lifecycleOwner: LifecycleOwner,
        lensFacing: Int = CameraSelector.LENS_FACING_BACK,
    ): Result {
        if (!hasPermission(context)) {
            return Result.Failure("Camera permission has not been granted")
        }

        val startedAt = System.currentTimeMillis()
        val provider = runCatching { awaitCameraProvider() }
            .getOrElse { return Result.Failure("Camera is unavailable on this device", it) }

        val imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        return try {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, imageCapture)

            val firstFile = newCacheFile()
            val firstShot = takePicture(imageCapture, firstFile)
            if (firstShot is Result.Failure) return firstShot

            // Quality gate: the user cannot verify the frame, so we do. A dark frame (covered
            // lens, pocket, unlit room) is refused with a reason rather than uploaded, because
            // the model would answer confidently about the wrong thing. A blurry frame gets one
            // silent retake and the sharper of the two is used. Analysis failure fails open -
            // a photo we could not measure is still better than no answer.
            val firstQuality = assess(firstFile, shot = 1)
            if (firstQuality != null && ImageQualityMath.isTooDark(firstQuality.meanLuma)) {
                return Result.Failure(
                    "It looks too dark to see anything. Try more light, or check that " +
                        "nothing is covering the camera.",
                )
            }

            var chosen = firstFile
            if (firstQuality != null && ImageQualityMath.shouldRetakeForBlur(firstQuality.sharpness)) {
                val secondFile = newCacheFile()
                val secondShot = takePicture(imageCapture, secondFile)
                if (secondShot is Result.Success) {
                    val secondQuality = assess(secondFile, shot = 2)
                    if (secondQuality != null && secondQuality.sharpness > firstQuality.sharpness) {
                        chosen = secondFile
                    }
                }
            }

            Result.Success(chosen, System.currentTimeMillis() - startedAt)
        } catch (error: Exception) {
            Log.e(TAG, "Failed to bind the phone camera", error)
            Result.Failure("Could not start the phone camera", error)
        } finally {
            // Release the camera immediately; holding it would deny it to other apps and to any
            // later capture on this path.
            runCatching { provider.unbindAll() }
        }
    }

    private fun newCacheFile(): File =
        File(context.cacheDir, "Phone_AI_${System.currentTimeMillis()}.jpg")

    private data class FrameQuality(val meanLuma: Double, val sharpness: Double)

    /**
     * Measures a captured frame at thumbnail size. Returns null when the frame cannot be decoded
     * or measured; callers treat that as "no opinion", never as a failure.
     *
     * Every measurement is logged so [ImageQualityMath]'s thresholds can be calibrated from real
     * capture data instead of guessed.
     */
    private fun assess(file: File, shot: Int): FrameQuality? = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (
            maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >=
            ImageQualityMath.ANALYSIS_MAX_DIMENSION
        ) {
            sample *= 2
        }

        val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
            ?: return null
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val quality = FrameQuality(
                meanLuma = ImageQualityMath.meanLuma(pixels),
                sharpness = ImageQualityMath.laplacianSharpness(pixels, bitmap.width, bitmap.height),
            )
            Log.i(
                TAG,
                "PhoneCaptureQuality shot=$shot luma=%.1f sharpness=%.1f size=${bitmap.width}x${bitmap.height}"
                    .format(quality.meanLuma, quality.sharpness),
            )
            quality
        } finally {
            bitmap.recycle()
        }
    }.getOrElse {
        Log.w(TAG, "Quality assessment failed; proceeding without it", it)
        null
    }

    private suspend fun awaitCameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    runCatching { future.get() }
                        .onSuccess { continuation.resume(it) }
                        .onFailure { continuation.cancel(it) }
                },
                ContextCompat.getMainExecutor(context),
            )
        }

    private suspend fun takePicture(imageCapture: ImageCapture, file: File): Result =
        suspendCancellableCoroutine { continuation ->
            val options = ImageCapture.OutputFileOptions.Builder(file).build()
            imageCapture.takePicture(
                options,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        if (!file.exists() || file.length() == 0L) {
                            continuation.resume(
                                Result.Failure("The camera returned an empty photo"),
                            )
                            return
                        }
                        continuation.resume(Result.Success(file, 0L))
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e(TAG, "takePicture failed", exception)
                        continuation.resume(
                            Result.Failure("The phone camera could not take a photo", exception),
                        )
                    }
                },
            )
        }
}
