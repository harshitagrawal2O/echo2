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
            val file = File(context.cacheDir, "Phone_AI_${System.currentTimeMillis()}.jpg")
            when (val outcome = takePicture(imageCapture, file)) {
                is Result.Success -> Result.Success(file, System.currentTimeMillis() - startedAt)
                is Result.Failure -> outcome
            }
        } catch (error: Exception) {
            Log.e(TAG, "Failed to bind the phone camera", error)
            Result.Failure("Could not start the phone camera", error)
        } finally {
            // Release the camera immediately; holding it would deny it to other apps and to any
            // later capture on this path.
            runCatching { provider.unbindAll() }
        }
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
