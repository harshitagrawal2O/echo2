package com.fersaiyan.cyanbridge.plugins.zygopay

import android.util.Log
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * One payment, from a photographed QR to a settled order.
 *
 * The design constraint that shapes everything here: the person paying cannot see the code they
 * just photographed, cannot see the merchant name, and cannot see the amount. Every fact that a
 * sighted user would check visually has to be said out loud and then explicitly agreed to. So the
 * flow deliberately stops at [State.AwaitingConfirmation] and cannot leave it on its own — only a
 * call to [confirm] moves it, and that call exists to be wired to a real, deliberate action.
 *
 * Nothing before that point spends anything: decode, resolve and quote are all reversible. Nothing
 * after it happens without the user's wallet also agreeing, in its own UI, outside this app.
 */
class ZygoPayFlow(
    private val client: ZygoClient,
    private val wallet: ZygoWallet,
    private val decoder: ZygoQrDecoder = ZygoQrDecoder(),
    private val maxAmountMinor: Long = DEFAULT_MAX_AMOUNT_MINOR,
) {

    sealed interface State {
        data object Idle : State
        data object Decoding : State
        data object Resolving : State

        /** The QR named no amount, so the user has to. Nothing is quoted until they do. */
        data class NeedsAmount(val merchant: ResolvedMerchant) : State

        /**
         * Everything is known and nothing is committed. The flow waits here indefinitely.
         *
         * [isLive] is carried separately from the merchant because it changes what must be said:
         * a live payment moves the user's real money and the spoken prompt has to lead with that.
         */
        data class AwaitingConfirmation(
            val merchant: ResolvedMerchant,
            val quote: PaymentQuote,
            val isLive: Boolean,
        ) : State

        data object ConnectingWallet : State
        data object Ordering : State
        data object Preparing : State

        /** Handed to the wallet. The user is looking at the wallet's own approval screen. */
        data object AwaitingWalletApproval : State

        /** Signed and broadcast; waiting for the merchant to be paid out. */
        data class Settling(val signature: String, val orderId: String) : State

        data class Settled(val signature: String, val orderId: String, val amountMinor: Long) : State

        /**
         * Ended without paying. [recoverable] distinguishes "try again" from "this cannot work",
         * because telling someone to retry something that will always fail wastes the one sense
         * they are using to follow along.
         */
        data class Failed(val reason: String, val recoverable: Boolean) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Reused across retries of one confirmed intent so that a response the app never saw cannot
     * become a second order. Minted at confirmation, not at order time, for exactly that reason.
     */
    private var idempotencyKey: String? = null

    private var pendingAmountMinor: Long? = null

    /**
     * Step one: read the still. Returns the state it settled into so a caller can speak it.
     *
     * Every failure here is phrased as an instruction rather than a diagnosis, because "no code
     * found" is only useful to someone who can be told what to do about it.
     */
    suspend fun onCapture(jpeg: ByteArray): State {
        _state.value = State.Decoding
        val outcome = decoder.decode(jpeg)
        return when (outcome) {
            is ZygoQrDecoder.Outcome.Upi -> resolve(outcome.qr)

            ZygoQrDecoder.Outcome.NoCode -> fail(
                "I could not find a payment code. Try again, a little closer.",
                recoverable = true,
            )

            ZygoQrDecoder.Outcome.NotUpi -> fail(
                "That is a code, but not a payment code.",
                recoverable = true,
            )

            is ZygoQrDecoder.Outcome.Ambiguous -> fail(
                "I can see ${outcome.count} payment codes. Move closer to just one.",
                recoverable = true,
            )

            is ZygoQrDecoder.Outcome.Failed -> fail(
                "I could not read that photo.",
                recoverable = true,
            )
        }
    }

    private suspend fun resolve(qr: UpiQr): State {
        _state.value = State.Resolving
        val merchant = try {
            client.resolveQr(qr.raw)
        } catch (error: ZygoClient.ServiceError) {
            return fail(serviceMessage(error), recoverable = error.retryable)
        } catch (error: Exception) {
            Log.w(TAG, "resolve failed", error)
            return fail("I could not reach the payment service.", recoverable = true)
        }

        // The QR's own amount is preferred over the server's suggestion: a merchant QR that names a
        // price is the price the user is standing in front of. The name shown to the user always
        // comes from the resolve call, never from the QR, because QR text is attacker-controlled.
        val amount = qr.amountMinor() ?: merchant.suggestedAmountMinor
        return if (amount == null) {
            _state.value = State.NeedsAmount(merchant)
            _state.value
        } else {
            quote(merchant, amount)
        }
    }

    /** Supplies the amount when the QR did not name one. */
    suspend fun setAmount(amountMinor: Long): State {
        val merchant = (_state.value as? State.NeedsAmount)?.merchant
            ?: return fail("Nothing is waiting for an amount.", recoverable = false)
        return quote(merchant, amountMinor)
    }

    private suspend fun quote(merchant: ResolvedMerchant, amountMinor: Long): State {
        if (amountMinor <= 0) {
            return fail("That amount is not a payment.", recoverable = true)
        }
        // Checked here as well as on the server. The server's limit is the one that binds; this one
        // exists so the user hears the refusal immediately rather than after a round trip.
        if (amountMinor > maxAmountMinor) {
            return fail(
                "That is more than the ${spokenRupees(maxAmountMinor)} limit for a single payment.",
                recoverable = false,
            )
        }

        val quote = try {
            client.createQuote(merchant.merchantPaymentDestinationId, amountMinor)
        } catch (error: ZygoClient.ServiceError) {
            return fail(serviceMessage(error), recoverable = error.retryable)
        } catch (error: Exception) {
            Log.w(TAG, "quote failed", error)
            return fail("I could not price that payment.", recoverable = true)
        }

        pendingAmountMinor = amountMinor
        _state.value = State.AwaitingConfirmation(merchant, quote, merchant.isLive)
        return _state.value
    }

    /**
     * What the app should say before asking for a yes.
     *
     * Order matters. Who is being paid comes first, then how much, then the wallet cost, because
     * that is the order in which a wrong payment is caught: the wrong shop is obvious, the wrong
     * amount is subtle, and the fee is detail. The live-money warning precedes all of it.
     */
    fun confirmationScript(awaiting: State.AwaitingConfirmation): String {
        val quote = awaiting.quote
        val merchant = awaiting.merchant
        val total = formatBaseUnits(quote.amounts.totalBase, quote.asset.decimals)
        return buildString {
            if (awaiting.isLive) append("Real payment. ")
            append("Pay ${merchant.merchantName}, ")
            append("${spokenRupees(quote.fiatAmountMinor)}. ")
            append("That costs $total ${quote.asset.symbol} from your wallet. ")
            append("Say yes to pay, or no to stop.")
        }
    }

    /**
     * The only way past [State.AwaitingConfirmation].
     *
     * Requires the [ActivityResultSender] because the wallet's approval screen is an activity
     * result — which is a useful accident: this cannot be called from a background service that has
     * no user in front of it.
     */
    suspend fun confirm(sender: ActivityResultSender): State {
        val awaiting = _state.value as? State.AwaitingConfirmation
            ?: return fail("There is no payment waiting to be confirmed.", recoverable = false)

        val key = idempotencyKey ?: UUID.randomUUID().toString().also { idempotencyKey = it }

        _state.value = State.ConnectingWallet
        val authorization = wallet.authorize(sender, useMainnet = awaiting.isLive)
        val authorized = when (authorization) {
            is ZygoWallet.Authorization.Ok -> authorization
            ZygoWallet.Authorization.NoWallet -> return fail(
                "You need a Solana wallet app installed to pay.",
                recoverable = false,
            )

            is ZygoWallet.Authorization.Declined -> return fail(
                "The wallet did not connect.",
                recoverable = true,
            )
        }

        _state.value = State.Ordering
        val order = try {
            client.createOrder(awaiting.quote.quoteId, key)
        } catch (error: ZygoClient.ServiceError) {
            return fail(serviceMessage(error), recoverable = error.retryable)
        } catch (error: Exception) {
            Log.w(TAG, "order failed", error)
            return fail("I could not start that payment.", recoverable = true)
        }

        _state.value = State.Preparing
        val prepared = try {
            client.prepareDeposit(order.orderId, authorized.address)
        } catch (error: ZygoClient.ServiceError) {
            // Deliberately not retryable regardless of what the server says: preparing a deposit
            // advances the order's state, so a blind retry can leave two escrow attempts behind.
            return fail(serviceMessage(error), recoverable = false)
        } catch (error: Exception) {
            Log.w(TAG, "prepare failed", error)
            return fail("I could not prepare that payment.", recoverable = false)
        }

        _state.value = State.AwaitingWalletApproval
        val signed = wallet.signAndSend(
            sender = sender,
            useMainnet = awaiting.isLive,
            authToken = authorized.authToken,
            transactionBase64 = prepared.transactionBase64,
        )

        return when (signed) {
            is ZygoWallet.Signed.Ok -> {
                _state.value = State.Settling(signed.signature, order.orderId)
                _state.value
            }

            ZygoWallet.Signed.NoWallet -> fail(
                "The wallet app disappeared before signing.",
                recoverable = false,
            )

            // Includes the user saying no in their wallet, which is not an error and must not sound
            // like one.
            is ZygoWallet.Signed.Declined -> fail("The payment was not approved.", recoverable = true)
        }
    }

    /** Abandons a payment that has not been confirmed. */
    fun cancel(): State = fail("Payment cancelled.", recoverable = false)

    /**
     * Polls until the order settles or fails.
     *
     * The app polls rather than the server blocking, so whatever "still working" cue is playing can
     * keep playing. Timing out here reports *unknown*, never failure: the money may well be on its
     * way, and telling someone their payment failed when it did not is the worst answer available.
     */
    suspend fun awaitSettlement(
        orderId: String,
        timeoutMs: Long = SETTLEMENT_TIMEOUT_MS,
        intervalMs: Long = SETTLEMENT_POLL_MS,
    ): State {
        val signature = (_state.value as? State.Settling)?.signature.orEmpty()
        var waited = 0L
        while (waited < timeoutMs) {
            delay(intervalMs)
            waited += intervalMs
            val status = try {
                client.orderStatus(orderId)
            } catch (error: Exception) {
                // A failed poll is not a failed payment. Keep waiting; the deadline is the only
                // thing that ends this loop unsuccessfully.
                Log.w(TAG, "status poll failed", error)
                continue
            }

            if (status.isSettled) {
                _state.value = State.Settled(signature, orderId, status.fiatAmountMinor)
                return _state.value
            }
            if (status.isTerminalFailure) {
                val refunded = !status.refundTxHash.isNullOrBlank()
                return fail(
                    if (refunded) {
                        "The payment did not go through. A refund is on its way."
                    } else {
                        "The payment did not go through."
                    },
                    recoverable = false,
                )
            }
        }

        return fail(
            "The payment is taking longer than expected. It may still complete — check before " +
                "paying again.",
            recoverable = false,
        )
    }

    private fun fail(reason: String, recoverable: Boolean): State {
        _state.value = State.Failed(reason, recoverable)
        return _state.value
    }

    /**
     * Service errors are for the log; the user gets one sentence they can act on. An error code
     * read aloud to someone who cannot see the screen is noise.
     */
    private fun serviceMessage(error: ZygoClient.ServiceError): String {
        Log.w(TAG, "service error ${error.code} (${error.httpStatus}): ${error.message}")
        return when (error.code) {
            "SERVICE_MISCONFIGURED" -> "Payments are not set up yet."
            "UNAUTHORIZED" -> "This app is not allowed to take payments."
            "BAD_AMOUNT" -> "That amount is not allowed."
            else -> if (error.retryable) "The payment service is busy. Try again." else
                "The payment service refused that."
        }
    }

    companion object {
        private const val TAG = "ZygoPayFlow"

        /** 2,000.00 INR. Mirrors the service default; the server's ceiling is the binding one. */
        const val DEFAULT_MAX_AMOUNT_MINOR = 200_000L

        const val SETTLEMENT_TIMEOUT_MS = 180_000L
        const val SETTLEMENT_POLL_MS = 3_000L
    }
}
