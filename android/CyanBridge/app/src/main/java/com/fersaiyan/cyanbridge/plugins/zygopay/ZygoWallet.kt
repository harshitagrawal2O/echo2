package com.fersaiyan.cyanbridge.plugins.zygopay

import android.net.Uri
import android.util.Base64
import android.util.Log
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.DefaultTransactionParams
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.Solana
import com.solana.mobilewalletadapter.clientlib.TransactionResult

/**
 * The user's own wallet, reached through the Solana Mobile Wallet Adapter.
 *
 * The private key never enters this process. The app hands an unsigned transaction to whichever
 * wallet the user has installed, that wallet shows its own approval screen, and only a signature
 * comes back. Nothing here — and nothing in the Zygo service — can move funds on its own, which is
 * what makes it acceptable for the service's bearer token to ship inside the APK.
 *
 * Authorisation and signing are two separate wallet round trips on purpose. The payer's address is
 * needed *before* the deposit transaction can be built, and building it means a network call to the
 * service; holding the wallet association open across that call risks the association timing out
 * mid-payment. So: authorise, note the address, build, then reopen to sign.
 */
class ZygoWallet(
    private val identityName: String = "CyanBridge",
    private val identityUri: Uri = Uri.parse("https://cyanbridge.app"),
    private val iconUri: Uri = Uri.parse("favicon.ico"),
) {

    sealed interface Authorization {
        data class Ok(val address: String, val authToken: String, val accountLabel: String?) :
            Authorization

        /** No MWA-capable wallet is installed. The user needs to be told what to install, not why. */
        data object NoWallet : Authorization

        data class Declined(val message: String) : Authorization
    }

    sealed interface Signed {
        /** Base58 transaction signature, as it would appear in an explorer. */
        data class Ok(val signature: String) : Signed

        data object NoWallet : Signed

        /**
         * Includes the user rejecting the transaction in their wallet, which is a normal outcome
         * and must not be reported as an error.
         */
        data class Declined(val message: String) : Signed
    }

    private var adapter: MobileWalletAdapter? = null

    private fun adapter(useMainnet: Boolean): MobileWalletAdapter {
        val existing = adapter
        if (existing != null) return existing
        val created = MobileWalletAdapter(
            connectionIdentity = ConnectionIdentity(
                identityUri = identityUri,
                iconUri = iconUri,
                identityName = identityName,
            ),
        )
        // Devnet unless explicitly told otherwise, matching the service's sandbox-by-default rule.
        // A wallet pointed at mainnet while the service is in sandbox would prompt the user to
        // spend real USDC against a test order.
        created.blockchain = if (useMainnet) Solana.Mainnet else Solana.Devnet
        adapter = created
        return created
    }

    /** Asks the wallet to authorise this app and reports the address that will pay. */
    suspend fun authorize(sender: ActivityResultSender, useMainnet: Boolean): Authorization {
        val mwa = adapter(useMainnet)
        return when (val result = mwa.connect(sender)) {
            is TransactionResult.Success -> {
                val auth = result.authResult
                // `accounts` rather than the deprecated top-level `publicKey`/`accountLabel`:
                // multi-account authorisation is why those were deprecated, and reading the flat
                // fields would silently keep working while describing only one of several accounts.
                // The first entry is the account the wallet nominated to pay.
                val account = auth.accounts.firstOrNull()
                    ?: return Authorization.Declined("The wallet authorised no account.")
                mwa.authToken = auth.authToken
                Authorization.Ok(
                    address = Base58.encode(account.publicKey),
                    authToken = auth.authToken,
                    accountLabel = account.accountLabel,
                )
            }

            is TransactionResult.NoWalletFound -> Authorization.NoWallet
            is TransactionResult.Failure -> {
                Log.w(TAG, "Wallet authorisation failed: ${result.message}", result.e)
                Authorization.Declined(result.message)
            }
        }
    }

    /**
     * Hands the unsigned transaction to the wallet to sign **and** broadcast.
     *
     * `signAndSendTransactions` rather than `signTransactions` plus our own RPC send: the wallet
     * already has a working RPC connection and the app does not, and a transaction this app signed
     * but failed to broadcast is the worst state to be in — the user has approved a payment whose
     * outcome nobody knows.
     */
    suspend fun signAndSend(
        sender: ActivityResultSender,
        useMainnet: Boolean,
        authToken: String?,
        transactionBase64: String,
    ): Signed {
        val mwa = adapter(useMainnet)
        if (!authToken.isNullOrBlank()) mwa.authToken = authToken

        val transaction = runCatching { Base64.decode(transactionBase64, Base64.DEFAULT) }
            .getOrElse {
                return Signed.Declined("The prepared transaction could not be read.")
            }

        val result = mwa.transact(sender) { _ ->
            signAndSendTransactions(arrayOf(transaction), DefaultTransactionParams)
        }

        return when (result) {
            is TransactionResult.Success -> {
                val signatures = result.payload.signatures
                val first = signatures.firstOrNull()
                if (first == null) {
                    // The wallet reported success with nothing to show for it. Treated as unknown
                    // rather than as success: claiming a payment went through without a signature
                    // to check is worse than admitting we do not know.
                    Signed.Declined("The wallet returned no signature.")
                } else {
                    Signed.Ok(Base58.encode(first))
                }
            }

            is TransactionResult.NoWalletFound -> Signed.NoWallet
            is TransactionResult.Failure -> {
                Log.w(TAG, "Wallet signing failed: ${result.message}", result.e)
                Signed.Declined(result.message)
            }
        }
    }

    private companion object {
        const val TAG = "ZygoWallet"
    }
}
