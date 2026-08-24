package com.fersaiyan.cyanbridge.plugins.zygopay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Talks to the Zygo service in `services/zygo/`.
 *
 * The app never talks to `api.zygo.cash` directly and holds no merchant key: the SDK's own README
 * requires that the key stay server-side, and an APK is a zip file. What this app does hold is a
 * bearer token for the service, which is an abuse gate rather than a secret — see `lib/http.js` for
 * why that is acceptable while the user's own wallet is the only thing that can move funds.
 */
class ZygoClient(
    private val baseUrl: String,
    private val proxyToken: String,
    private val http: OkHttpClient = defaultClient(),
) {

    class ServiceError(
        val code: String,
        override val message: String,
        val retryable: Boolean,
        val httpStatus: Int,
    ) : IOException(message)

    val isConfigured: Boolean get() = baseUrl.isNotBlank() && proxyToken.isNotBlank()

    suspend fun resolveQr(rawQr: String): ResolvedMerchant {
        val json = post("resolve", JSONObject().put("rawQr", rawQr))
        return ResolvedMerchant(
            environment = json.optString("environment", "sandbox"),
            merchantPaymentDestinationId = json.getString("merchantPaymentDestinationId"),
            merchantName = json.getString("merchantName"),
            payeeIdentifier = json.getString("payeeIdentifier"),
            railCode = json.optString("railCode", ""),
            suggestedAmountMinor = json.optLongOrNull("suggestedAmountMinor"),
            merchantCategory = json.optStringOrNull("merchantCategory"),
        )
    }

    suspend fun createQuote(destinationId: String, fiatAmountMinor: Long): PaymentQuote {
        val json = post(
            "quote",
            JSONObject()
                .put("merchantPaymentDestinationId", destinationId)
                .put("fiatAmountMinor", fiatAmountMinor),
        )
        val asset = json.getJSONObject("asset")
        val amounts = json.getJSONObject("amounts")
        return PaymentQuote(
            quoteId = json.getString("quoteId"),
            publicId = json.optString("publicId", ""),
            fiatCurrency = json.optString("fiatCurrency", "INR"),
            fiatAmountMinor = json.getLong("fiatAmountMinor"),
            expiresAt = json.optString("expiresAt", ""),
            asset = QuoteAsset(
                assetId = asset.getString("assetId"),
                symbol = asset.optString("symbol", "USDC"),
                decimals = asset.optInt("decimals", 6),
                chainName = asset.optString("chainName", ""),
                mint = asset.optString("mint", ""),
            ),
            amounts = QuoteAmounts(
                stablecoinAmountBase = amounts.getLong("stablecoinAmountBase"),
                lpSpreadBase = amounts.optLong("lpSpreadBase", 0),
                platformFeeBase = amounts.optLong("platformFeeBase", 0),
                networkFeeEstimateBase = amounts.optLong("networkFeeEstimateBase", 0),
                totalBase = amounts.getLong("totalBase"),
            ),
        )
    }

    /**
     * [idempotencyKey] is supplied by the caller and reused across retries of the same intent, so a
     * response the app never saw cannot become a second order.
     */
    suspend fun createOrder(quoteId: String, idempotencyKey: String): PaymentOrder {
        val json = post(
            "order",
            JSONObject().put("quoteId", quoteId).put("idempotencyKey", idempotencyKey),
        )
        return PaymentOrder(
            orderId = json.getString("orderId"),
            publicId = json.optString("publicId", ""),
            currentState = json.optString("currentState", ""),
            fiatCurrency = json.optString("fiatCurrency", "INR"),
            fiatAmountMinor = json.getLong("fiatAmountMinor"),
            stablecoinAmountBase = json.optLong("stablecoinAmountBase", 0),
        )
    }

    /** Not a read: this advances the order to escrow-pending, so it must not be retried blindly. */
    suspend fun prepareDeposit(orderId: String, payerPublicKey: String): PreparedDeposit {
        val json = post(
            "deposit",
            JSONObject().put("orderId", orderId).put("payerPublicKey", payerPublicKey),
        )
        val deposit = json.getJSONObject("deposit")
        return PreparedDeposit(
            transactionBase64 = json.getString("transactionBase64"),
            recentBlockhash = json.optStringOrNull("recentBlockhash"),
            deposit = DepositDetails(
                escrowId = deposit.optString("escrowId", ""),
                chainName = deposit.optString("chainName", ""),
                mint = deposit.optString("mint", ""),
                vaultAddress = deposit.optString("vaultAddress", ""),
                amountBase = deposit.getLong("amountBase"),
                memo = deposit.optString("memo", ""),
                path = deposit.optString("path", ""),
            ),
        )
    }

    suspend fun orderStatus(orderId: String): OrderStatus {
        val json = post("status", JSONObject().put("orderId", orderId))
        return OrderStatus(
            orderId = json.getString("orderId"),
            currentState = json.optString("currentState", ""),
            fiatAmountMinor = json.optLong("fiatAmountMinor", 0),
            refundTxHash = json.optStringOrNull("refundTxHash"),
        )
    }

    private suspend fun post(path: String, body: JSONObject): JSONObject =
        withContext(Dispatchers.IO) {
            check(isConfigured) {
                "Zygo service is not configured. Set ZYGO_SERVICE_URL and ZYGO_PROXY_TOKEN as " +
                    "Gradle properties, or in the plugin settings screen."
            }
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/api/" + path)
                .header("authorization", "Bearer " + proxyToken)
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .build()

            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (!response.isSuccessful) {
                    throw ServiceError(
                        code = json?.optString("error").orEmpty().ifEmpty { "HTTP_" + response.code },
                        message = json?.optString("message").orEmpty().ifEmpty {
                            "Zygo service returned " + response.code
                        },
                        // 5xx and 429 are worth another attempt; a 4xx means the request itself is
                        // wrong and retrying it only spends the user's time.
                        retryable = json?.optBoolean("retryable")
                            ?: (response.code >= 500 || response.code == 429),
                        httpStatus = response.code,
                    )
                }
                json ?: throw ServiceError(
                    "BAD_RESPONSE",
                    "Zygo service returned a non-JSON body",
                    retryable = false,
                    httpStatus = response.code,
                )
            }
        }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // Retries are the caller's decision here, not OkHttp's: `deposit` and `order` change
            // state, and a transparent retry of those is how one payment becomes two.
            .retryOnConnectionFailure(false)
            .build()
    }
}

/** `optString` returns "" for JSON null, which is not the same as absent when the field is a hash. */
private fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

private fun JSONObject.optLongOrNull(name: String): Long? =
    if (!has(name) || isNull(name)) null else optLong(name)
