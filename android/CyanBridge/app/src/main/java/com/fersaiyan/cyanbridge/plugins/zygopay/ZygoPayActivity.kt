package com.fersaiyan.cyanbridge.plugins.zygopay

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.fersaiyan.cyanbridge.ai.image.ImageThumbnailQuality
import com.fersaiyan.cyanbridge.ai.live.GeminiLiveGlassesImageCapture
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import kotlinx.coroutines.launch

/**
 * The payment screen, and the only place a payment can be authorised.
 *
 * Designed for someone who cannot see it. Every state change is spoken and marked as a live region
 * so TalkBack reads it without the user hunting; the confirm control is a single large button whose
 * label states what it will do rather than saying "confirm"; and a live payment needs two presses,
 * because a mis-tap on a screen you cannot see should not be able to spend real money.
 *
 * An activity rather than a service, deliberately. The wallet's approval screen arrives as an
 * activity result, and a person has to be present to agree — both of which make a background
 * payment path impossible by construction.
 */
class ZygoPayActivity : AppCompatActivity() {

    private lateinit var sender: ActivityResultSender
    private lateinit var voice: ZygoVoice
    private lateinit var flow: ZygoPayFlow

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Must be constructed during onCreate: it registers an activity-result launcher.
        sender = ActivityResultSender(this)
        voice = ZygoVoice(this).also { it.start() }
        flow = ZygoPayFlow(
            client = ZygoPayPlugin.client(this),
            wallet = ZygoWallet(),
        )

        val captured = intent?.getByteArrayExtra(EXTRA_CAPTURE_JPEG)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PayScreen(
                        flow = flow,
                        voice = voice,
                        initialCapture = captured,
                        onConfirm = { lifecycleScope.launch { onConfirmed() } },
                        onCaptureRequested = { lifecycleScope.launch { captureFromGlasses() } },
                        onCancel = { flow.cancel(); finish() },
                        onAmount = { minor -> lifecycleScope.launch { flow.setAmount(minor) } },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        voice.stop()
        super.onDestroy()
    }

    private suspend fun onConfirmed() {
        val state = flow.confirm(sender)
        if (state is ZygoPayFlow.State.Settling) {
            flow.awaitSettlement(state.orderId)
        }
    }

    /**
     * Asks the glasses for a still.
     *
     * `DETAILED` is the highest fidelity the vendor selector exposes, and a QR code is a dense
     * grid — the thumbnail that is good enough to describe a room may not carry enough pixels to
     * decode one. Whether it does at all is the first thing to test on hardware; if it does not,
     * this flow needs the Wi-Fi full-resolution path instead, not a lower threshold.
     */
    private suspend fun captureFromGlasses() {
        val jpeg = runCatching {
            GeminiLiveGlassesImageCapture().capture(ImageThumbnailQuality.DETAILED)
        }.getOrElse { error ->
            voice.say(error.message ?: "The glasses did not take a photo.")
            return
        }
        flow.onCapture(jpeg)
    }

    companion object {
        const val EXTRA_CAPTURE_JPEG = "capture_jpeg"
    }
}

@Composable
private fun PayScreen(
    flow: ZygoPayFlow,
    voice: ZygoVoice,
    initialCapture: ByteArray?,
    onConfirm: () -> Unit,
    onCaptureRequested: () -> Unit,
    onCancel: () -> Unit,
    onAmount: (Long) -> Unit,
) {
    val state by flow.state.collectAsState()
    val view = LocalView.current
    var confirmArmed by remember { mutableStateOf(false) }
    var amountText by remember { mutableStateOf("") }

    LaunchedEffect(initialCapture) {
        if (initialCapture != null) flow.onCapture(initialCapture)
    }

    // Everything the user needs to hear, said once per state change. The screen's visible text and
    // the spoken text come from the same place so they cannot drift apart.
    val spoken = spokenFor(state, flow)
    LaunchedEffect(spoken) {
        if (spoken.isNotBlank()) {
            voice.say(spoken) { message -> view.announceForAccessibility(message) }
        }
    }

    // A new payment must never inherit an armed confirmation from a previous one.
    LaunchedEffect(state) {
        if (state !is ZygoPayFlow.State.AwaitingConfirmation) confirmArmed = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = spoken,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier
                .fillMaxWidth()
                // Announced on change without stealing focus, so a user moving through the screen
                // still hears "waiting for your wallet" when it happens.
                .semantics { liveRegion = LiveRegionMode.Polite },
        )

        when (val current = state) {
            is ZygoPayFlow.State.NeedsAmount -> {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { entered -> amountText = entered.filter { it.isDigit() || it == '.' } },
                    label = { Text("Amount in rupees") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = "Amount in rupees to pay " +
                                current.merchant.merchantName
                        },
                )
                BigButton(
                    label = "Continue",
                    description = "Price this payment",
                    enabled = amountText.isNotBlank(),
                    onClick = { rupeesToMinor(amountText)?.let(onAmount) },
                )
            }

            is ZygoPayFlow.State.AwaitingConfirmation -> {
                val amount = spokenRupees(current.quote.fiatAmountMinor)
                // Live money takes two presses. The label changes between them so the second press
                // is a different, deliberate act rather than a repeat of the same one.
                val needsArming = current.isLive && !confirmArmed
                BigButton(
                    label = if (needsArming) "Pay $amount — press again to confirm" else "Pay $amount",
                    description = if (needsArming) {
                        "Real payment of $amount to ${current.merchant.merchantName}. " +
                            "Press to arm, then press again to pay."
                    } else {
                        "Pay $amount to ${current.merchant.merchantName}"
                    },
                    onClick = {
                        if (needsArming) confirmArmed = true else onConfirm()
                    },
                )
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .semantics { contentDescription = "Do not pay. Cancel and go back." },
                ) { Text("Cancel") }
            }

            is ZygoPayFlow.State.Failed -> {
                if (current.recoverable) {
                    BigButton(
                        label = "Try again",
                        description = "Take another photo of the payment code",
                        onClick = onCaptureRequested,
                    )
                }
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp),
                ) { Text("Close") }
            }

            is ZygoPayFlow.State.Settled -> {
                BigButton(
                    label = "Done",
                    description = "Payment complete. Close this screen.",
                    onClick = onCancel,
                )
            }

            ZygoPayFlow.State.Idle -> {
                BigButton(
                    label = "Scan a payment code",
                    description = "Ask the glasses to photograph the payment code",
                    onClick = onCaptureRequested,
                )
            }

            // Decoding, resolving, ordering, preparing, waiting on the wallet, settling: the user
            // has nothing to decide, so the screen offers nothing to press. The live region is
            // already saying what is happening.
            else -> Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun BigButton(
    label: String,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        // Large enough to hit without looking, which is the only way this button gets pressed.
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp)
            .semantics { contentDescription = description },
        colors = ButtonDefaults.buttonColors(),
    ) {
        Text(text = label, style = MaterialTheme.typography.titleLarge)
    }
}

/**
 * The one place a state becomes words. Shared by the screen and the speech so a user listening and
 * a user reading are told the same thing.
 */
private fun spokenFor(state: ZygoPayFlow.State, flow: ZygoPayFlow): String = when (state) {
    ZygoPayFlow.State.Idle -> "Ready to scan a payment code."
    ZygoPayFlow.State.Decoding -> "Reading the code."
    ZygoPayFlow.State.Resolving -> "Checking who this pays."
    is ZygoPayFlow.State.NeedsAmount ->
        "${state.merchant.merchantName}. The code does not say how much. How much should I pay?"

    is ZygoPayFlow.State.AwaitingConfirmation -> flow.confirmationScript(state)
    ZygoPayFlow.State.ConnectingWallet -> "Opening your wallet."
    ZygoPayFlow.State.Ordering -> "Starting the payment."
    ZygoPayFlow.State.Preparing -> "Preparing the transfer."
    ZygoPayFlow.State.AwaitingWalletApproval -> "Approve it in your wallet."
    is ZygoPayFlow.State.Settling -> "Sent. Waiting for the shop to be paid."
    is ZygoPayFlow.State.Settled -> "Paid ${spokenRupees(state.amountMinor)}."
    is ZygoPayFlow.State.Failed -> state.reason
}

/** "12.50" -> 1250 paise. Integer arithmetic only; this is money. */
private fun rupeesToMinor(text: String): Long? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val parts = trimmed.split('.')
    if (parts.size > 2) return null
    val rupees = parts[0].ifEmpty { "0" }.toLongOrNull() ?: return null
    val paise = if (parts.size == 1) 0L else parts[1].padEnd(2, '0').take(2).toLongOrNull() ?: return null
    return rupees * 100 + paise
}
