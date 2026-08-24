package com.fersaiyan.cyanbridge.plugins.zygopay

import android.net.Uri

/**
 * A locally parsed UPI QR payload.
 *
 * Parsed here only so the app has something to say and something to sanity-check before it sends
 * the payload anywhere. It is deliberately **not** authoritative: whether a payee is a real,
 * payable merchant is the server's answer, and the name shown to the user comes from the resolve
 * call, not from the QR. A QR is attacker-controlled text - anyone can print one that claims to be
 * a hospital - so nothing in here is trusted beyond "this looks like a UPI intent at all".
 */
data class UpiQr(
    val payeeAddress: String,
    val payeeName: String?,
    val amountRupees: String?,
    val note: String?,
    val raw: String,
) {

    /** The amount encoded in the QR, in paise, or null when the QR leaves it open. */
    fun amountMinor(): Long? {
        val amount = amountRupees?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // Deliberately not Double: binary floating point cannot represent every two-decimal rupee
        // value exactly, and this number is money.
        val parts = amount.split('.')
        if (parts.size > 2) return null
        val rupees = parts[0].toLongOrNull() ?: return null
        val paise = when {
            parts.size == 1 -> 0L
            else -> parts[1].padEnd(2, '0').take(2).toLongOrNull() ?: return null
        }
        if (rupees < 0 || paise < 0) return null
        return rupees * 100 + paise
    }

    companion object {

        /**
         * Returns null when the text is not a UPI payment intent.
         *
         * Being strict here is the point: the decoder will happily read any QR code in frame -
         * a product barcode, a Wi-Fi credential, a URL - and none of those should start a payment
         * flow. A user who cannot see what they photographed is relying on this refusal.
         */
        fun parse(text: String): UpiQr? {
            val trimmed = text.trim()
            if (!trimmed.startsWith("upi://", ignoreCase = true)) return null
            val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
            if (!uri.host.equals("pay", ignoreCase = true)) return null
            val payee = uri.getQueryParameter("pa")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return UpiQr(
                payeeAddress = payee,
                payeeName = uri.getQueryParameter("pn")?.trim()?.takeIf { it.isNotEmpty() },
                amountRupees = uri.getQueryParameter("am")?.trim()?.takeIf { it.isNotEmpty() },
                note = uri.getQueryParameter("tn")?.trim()?.takeIf { it.isNotEmpty() },
                raw = trimmed,
            )
        }
    }
}
