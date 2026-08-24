package com.fersaiyan.cyanbridge.plugins.zygopay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Finds a UPI QR code in a still that has already been taken.
 *
 * Deliberately a decoder over a finished frame rather than a live `ImageAnalysis` pipeline. The app
 * has no live-preview analyser anywhere and does not need one: the user presses the glasses button,
 * a still arrives, and this reads it. That keeps the payment flow identical in shape to the ask
 * loop the user already knows, and it works on the glasses camera - which is head-aimed, so a
 * blind user does not have to frame anything, unlike holding a phone at a printed code.
 *
 * The bundled ML Kit scanner is used rather than the Play-services-dispatched one so the first
 * payment cannot be blocked behind a model download on a bad connection.
 */
class ZygoQrDecoder(
    private val scanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build(),
    ),
) {

    sealed interface Outcome {
        /** Exactly one UPI payment QR was found. */
        data class Upi(val qr: UpiQr) : Outcome

        /** Nothing decodable in the frame - the common case for a bad photo. */
        data object NoCode : Outcome

        /**
         * Codes were found but none is a UPI payment intent. Reported separately from [NoCode]
         * because the advice differs: "I can't see a code" means take another photo, "that isn't a
         * payment code" means it was pointed at the wrong thing.
         */
        data object NotUpi : Outcome

        /**
         * More than one distinct UPI code in frame. Refused rather than guessed: picking the
         * largest or the first would mean a user who cannot see the frame paying whichever
         * merchant happened to win a heuristic.
         */
        data class Ambiguous(val count: Int) : Outcome

        data class Failed(val error: Throwable) : Outcome
    }

    suspend fun decode(jpeg: ByteArray): Outcome {
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            ?: return Outcome.Failed(IllegalArgumentException("Frame is not a decodable image"))
        return try {
            decode(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun decode(bitmap: Bitmap): Outcome {
        val barcodes = try {
            awaitBarcodes(bitmap)
        } catch (error: Exception) {
            Log.w(TAG, "Barcode scan failed", error)
            return Outcome.Failed(error)
        }

        if (barcodes.isEmpty()) return Outcome.NoCode

        val upi = barcodes
            .mapNotNull { it.rawValue }
            .mapNotNull { UpiQr.parse(it) }
            .distinctBy { it.raw }

        return when {
            upi.isEmpty() -> Outcome.NotUpi
            upi.size > 1 -> Outcome.Ambiguous(upi.size)
            else -> Outcome.Upi(upi.first())
        }
    }

    private suspend fun awaitBarcodes(bitmap: Bitmap): List<Barcode> =
        suspendCancellableCoroutine { continuation ->
            // Rotation 0: the still is already upright by the time it reaches here, and QR codes
            // are orientation-independent for the detector in any case.
            scanner.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result -> if (continuation.isActive) continuation.resume(result) }
                .addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
                .addOnCanceledListener {
                    if (continuation.isActive) continuation.resume(emptyList())
                }
        }

    fun close() = runCatching { scanner.close() }.let { }

    private companion object {
        const val TAG = "ZygoQrDecoder"
    }
}
