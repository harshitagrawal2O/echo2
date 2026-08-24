package com.fersaiyan.cyanbridge.plugins.zygopay

/**
 * The wire types of the Zygo service in `services/zygo/`, and the money-formatting the spoken
 * confirmation depends on.
 *
 * Amounts stay in minor units (paise) and base units end to end. Nothing here converts money to a
 * floating point number: `am=0.1` in a QR plus binary floating point is how a payment becomes a
 * different payment.
 */

data class ResolvedMerchant(
    val environment: String,
    val merchantPaymentDestinationId: String,
    val merchantName: String,
    val payeeIdentifier: String,
    val railCode: String,
    val suggestedAmountMinor: Long?,
    val merchantCategory: String?,
) {
    val isLive: Boolean get() = environment == "live"
}

data class QuoteAsset(
    val assetId: String,
    val symbol: String,
    val decimals: Int,
    val chainName: String,
    val mint: String,
)

data class QuoteAmounts(
    val stablecoinAmountBase: Long,
    val lpSpreadBase: Long,
    val platformFeeBase: Long,
    val networkFeeEstimateBase: Long,
    val totalBase: Long,
)

data class PaymentQuote(
    val quoteId: String,
    val publicId: String,
    val fiatCurrency: String,
    val fiatAmountMinor: Long,
    val expiresAt: String,
    val asset: QuoteAsset,
    val amounts: QuoteAmounts,
)

data class PaymentOrder(
    val orderId: String,
    val publicId: String,
    val currentState: String,
    val fiatCurrency: String,
    val fiatAmountMinor: Long,
    val stablecoinAmountBase: Long,
)

data class DepositDetails(
    val escrowId: String,
    val chainName: String,
    val mint: String,
    val vaultAddress: String,
    val amountBase: Long,
    val memo: String,
    val path: String,
)

data class PreparedDeposit(
    val transactionBase64: String,
    val recentBlockhash: String?,
    val deposit: DepositDetails,
)

data class OrderStatus(
    val orderId: String,
    val currentState: String,
    val fiatAmountMinor: Long,
    val refundTxHash: String?,
) {
    val isSettled: Boolean get() = currentState.equals("completed", ignoreCase = true)

    /**
     * Terminal states other than success. Matched by prefix rather than an exhaustive list because
     * the server owns the state machine: an unrecognised terminal state must not be mistaken for
     * "still working", which would leave the user waiting forever on a payment that already failed.
     */
    val isTerminalFailure: Boolean
        get() = TERMINAL_FAILURES.any { currentState.startsWith(it, ignoreCase = true) }

    private companion object {
        val TERMINAL_FAILURES = listOf("failed", "cancel", "expired", "refund", "reject")
    }
}

/** Rupees for speech: 45067 -> "450 rupees 67 paise". Never a decimal read aloud as digits. */
fun spokenRupees(amountMinor: Long): String {
    val rupees = amountMinor / 100
    val paise = amountMinor % 100
    val rupeeWord = if (rupees == 1L) "rupee" else "rupees"
    return when {
        paise == 0L -> "$rupees $rupeeWord"
        rupees == 0L -> "$paise paise"
        else -> "$rupees $rupeeWord $paise paise"
    }
}

/** Rupees for display: 45067 -> "450.67". */
fun formatRupees(amountMinor: Long): String {
    val rupees = amountMinor / 100
    val paise = amountMinor % 100
    return "$rupees.${paise.toString().padStart(2, '0')}"
}

/**
 * Base units to a decimal string, for stating the stablecoin figure exactly.
 *
 * Integer arithmetic only: 6-decimal USDC through a Double is precise enough today and wrong at
 * some balance nobody will predict.
 */
fun formatBaseUnits(amountBase: Long, decimals: Int): String {
    if (decimals <= 0) return amountBase.toString()
    val divisor = generateSequence(1L) { it * 10 }.take(decimals + 1).last()
    val whole = amountBase / divisor
    val fraction = (amountBase % divisor).toString().padStart(decimals, '0').trimEnd('0')
    return if (fraction.isEmpty()) whole.toString() else "$whole.$fraction"
}
