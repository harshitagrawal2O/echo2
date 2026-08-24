package com.fersaiyan.cyanbridge.plugins.zygopay

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.fersaiyan.cyanbridge.ai.feedback.SpeechRouter
import com.fersaiyan.cyanbridge.ai.image.ImageThumbnailQuality
import com.fersaiyan.cyanbridge.ai.image.PhoneCameraCapture
import com.fersaiyan.cyanbridge.ai.live.GeminiLiveGlassesImageCapture
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/**
 * The payment screen, and the only place a payment can be authorised.
 *
 * Designed for someone who cannot see it. Every state change is spoken; the confirm control is a
 * single large button whose label states what it will do rather than saying "confirm"; and a live
 * payment needs two presses, because a mis-tap on a screen you cannot see should not be able to
 * spend real money.
 *
 * Speech goes through [SpeechRouter] rather than an engine of this screen's own. `SpeechRouter` is
 * a process singleton shared with the rest of the app, which is why this activity never starts or
 * stops it — doing either would affect every other screen's speech, not just this one. Before this
 * plugin had it, it ran its own `TextToSpeech` instance, which would have made a fourth
 * uncoordinated voice in an app where a screen reader, the answer flow's TTS and this screen could
 * already all be talking; that was always meant to collapse into `SpeechRouter` once the two
 * existed in the same tree, and this is that collapse.
 *
 * The confirmation itself, and the final paid amount, go through [SpeechRouter.speakContent] rather
 * than [SpeechRouter.speakState]: content is always self-voiced regardless of whether TalkBack is
 * running, because the merchant name and the amount are the one thing a blind user cannot verify
 * any other way, and they must be heard even if a screen reader is also active. Pure progress
 * narration ("opening your wallet", "preparing the transfer") is state, and follows the same
 * convention `SpeechRouter`'s own doc uses — it names "capture failed" as a state example — so it
 * defers to TalkBack when one is running rather than talking over it.
 *
 * An activity rather than a service, deliberately. The wallet's approval screen arrives as an
 * activity result, and a person has to be present to agree — both of which make a background
 * payment path impossible by construction.
 */
class ZygoPayActivity : AppCompatActivity() {

    private lateinit var sender: ActivityResultSender
    private lateinit var flow: ZygoPayFlow

    // Bridges the callback-based permission launcher into the suspend call captureFromPhone()
    // needs. Must be registered here, before STARTED, which is why it is a property rather than
    // something created on demand inside a click handler.
    private var pendingCameraPermission: CompletableDeferred<Boolean>? = null
    private val cameraPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pendingCameraPermission?.complete(granted)
            pendingCameraPermission = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Must be constructed during onCreate: it registers an activity-result launcher.
        sender = ActivityResultSender(this)
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
                        initialCapture = captured,
                        onConfirm = { lifecycleScope.launch { onConfirmed() } },
                        onCaptureRequested = { lifecycleScope.launch { captureFromGlasses() } },
                        onCapturePhoneRequested = { lifecycleScope.launch { captureFromPhone() } },
                        onCancel = { flow.cancel(); finish() },
                        onAmount = { minor -> lifecycleScope.launch { flow.setAmount(minor) } },
                    )
                }
            }
        }
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
            SpeechRouter.get(this).speakState(error.message ?: "The glasses did not take a photo.")
            return
        }
        flow.onCapture(jpeg)
    }

    /**
     * The fallback path: a merchant QR taken with the phone's own camera. Exists for the case
     * hardware makes moot for the primary path -- glasses not paired, or the BLE thumbnail proving
     * too small to decode a dense QR grid (see docs/upi-payments.md, item 1 of the unproven list).
     * A full-resolution phone photo sidesteps that specific risk even if it is not the intended
     * path once the glasses are confirmed working.
     *
     * Requests CAMERA explicitly before capturing. `PhoneCameraCapture.hasPermission` only checks;
     * nothing upstream of this call requests it for a user who has never touched the Meta Ray-Ban
     * flow, which is a real, separate gap -- see the phone-camera vision path's own review history.
     * This call site owns its own request rather than assuming the permission is already granted,
     * so it does not inherit that gap.
     */
    private suspend fun captureFromPhone() {
        if (!PhoneCameraCapture.hasPermission(this) && !requestCameraPermission()) {
            SpeechRouter.get(this).speakState("Camera permission is needed to use the phone camera.")
            return
        }

        val result = PhoneCameraCapture(this).capture(this)
        when (result) {
            is PhoneCameraCapture.Result.Success -> {
                // The bytes are what this flow needs; the file on disk is a photograph of someone's
                // payment code and has no reason to outlive the read.
                val jpeg = runCatching { result.file.readBytes() }.getOrNull()
                result.file.delete()
                if (jpeg == null) {
                    SpeechRouter.get(this).speakState("I could not read that photo.")
                    return
                }
                flow.onCapture(jpeg)
            }

            is PhoneCameraCapture.Result.Failure ->
                SpeechRouter.get(this).speakState(result.reason)
        }
    }

    private suspend fun requestCameraPermission(): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingCameraPermission = deferred
        cameraPermissionRequest.launch(android.Manifest.permission.CAMERA)
        return deferred.await()
    }

    companion object {
        const val EXTRA_CAPTURE_JPEG = "capture_jpeg"
    }
}

@Composable
private fun PayScreen(
    flow: ZygoPayFlow,
    initialCapture: ByteArray?,
    onConfirm: () -> Unit,
    onCaptureRequested: () -> Unit,
    onCapturePhoneRequested: () -> Unit,
    onCancel: () -> Unit,
    onAmount: (Long) -> Unit,
) {
    val state by flow.state.collectAsState()
    val context = LocalContext.current
    var confirmArmed by remember { mutableStateOf(false) }
    var amountText by remember { mutableStateOf("") }

    LaunchedEffect(initialCapture) {
        if (initialCapture != null) flow.onCapture(initialCapture)
    }

    // Everything the user needs to hear, said once per state change. The screen's visible text and
    // the spoken text come from the same place so they cannot drift apart. Routed by SpeechRouter
    // rather than announced here as well: doing both would be the same text heard twice whenever a
    // screen reader is active, which is exactly the double-speech SpeechRouter exists to prevent.
    val speech = speechFor(state, flow)
    LaunchedEffect(speech) {
        if (speech.text.isNotBlank()) {
            val router = SpeechRouter.get(context)
            if (speech.isContent) router.speakContent(speech.text) else router.speakState(speech.text)
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
        // No live-region semantics here: SpeechRouter already announces state changes to TalkBack
        // when one is running, and marking this text as a live region as well would announce every
        // change a second time through Compose's own mechanism.
        Text(
            text = speech.text,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth(),
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
                OutlinedButton(
                    onClick = onCapturePhoneRequested,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .semantics {
                            contentDescription =
                                "Use the phone camera instead of the glasses to scan the code"
                        },
                ) { Text("Use phone camera instead") }
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
 *
 * [Speech.isContent] decides which [SpeechRouter] method carries it. The merchant name, the
 * confirmation and the final paid amount are content: the one thing a user who cannot see the
 * screen has no other way to check, so they must be heard even when a screen reader is also
 * running. Everything else is the app narrating its own progress, which defers to TalkBack when one
 * is active rather than speaking over it.
 */
private data class Speech(val text: String, val isContent: Boolean)

private fun speechFor(state: ZygoPayFlow.State, flow: ZygoPayFlow): Speech = when (state) {
    ZygoPayFlow.State.Idle -> Speech("Ready to scan a payment code.", isContent = false)
    ZygoPayFlow.State.Decoding -> Speech("Reading the code.", isContent = false)
    ZygoPayFlow.State.Resolving -> Speech("Checking who this pays.", isContent = false)
    is ZygoPayFlow.State.NeedsAmount -> Speech(
        "${state.merchant.merchantName}. The code does not say how much. How much should I pay?",
        isContent = true,
    )

    is ZygoPayFlow.State.AwaitingConfirmation ->
        Speech(flow.confirmationScript(state), isContent = true)

    ZygoPayFlow.State.ConnectingWallet -> Speech("Opening your wallet.", isContent = false)
    ZygoPayFlow.State.Ordering -> Speech("Starting the payment.", isContent = false)
    ZygoPayFlow.State.Preparing -> Speech("Preparing the transfer.", isContent = false)
    ZygoPayFlow.State.AwaitingWalletApproval -> Speech("Approve it in your wallet.", isContent = false)
    is ZygoPayFlow.State.Settling ->
        Speech("Sent. Waiting for the shop to be paid.", isContent = false)

    is ZygoPayFlow.State.Settled ->
        Speech("Paid ${spokenRupees(state.amountMinor)}.", isContent = true)

    is ZygoPayFlow.State.Failed -> Speech(state.reason, isContent = false)
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
