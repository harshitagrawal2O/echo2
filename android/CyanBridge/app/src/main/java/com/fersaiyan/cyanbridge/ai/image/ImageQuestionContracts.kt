package com.fersaiyan.cyanbridge.ai.image

/** The bytes supplied to the model, not a cosmetic image-quality preference. */
enum class ImageQuestionSource(
    val wireName: String,
    val label: String,
) {
    HIGH_QUALITY(
        wireName = "high_quality",
        label = "High quality (Wi-Fi full resolution)",
    ),
    FAST_PREVIEW(
        wireName = "fast_preview",
        label = "Fast preview (BLE thumbnail)",
    ),

    /**
     * The phone's own camera, with no glasses involved.
     *
     * Serves two purposes. It lets the app answer questions about what the camera sees when the
     * glasses are flat, out of range, or not owned at all, which is the form factor most assistive
     * vision apps ship. It also makes every path downstream of capture testable without hardware.
     *
     * Nothing here acquires a [com.fersaiyan.cyanbridge.glasses.GlassesSessionCoordinator] lease:
     * the vendor callback slots are global singletons, and taking one for a phone capture would
     * block media sync and OTA for a transfer that never touches the glasses.
     */
    PHONE_CAMERA(
        wireName = "phone_camera",
        label = "Phone camera (no glasses)",
    ),
}

/** Verified thumbnail sizes exposed by the vendor app's AI clarity selector. */
enum class ImageThumbnailQuality(
    val sdkValue: Int,
    val label: String,
) {
    INSTANT(0, "Instant"),
    QUICK(1, "Quick"),
    SMOOTH(2, "Smooth"),
    FINE(3, "Fine"),
    CLEARER(4, "Clearer"),
    DETAILED(5, "Detailed"),
}

enum class HighQualityFailureChoice {
    RETRY_HIGH_QUALITY,
    USE_FAST_PREVIEW,
    CANCEL,
}

enum class ImageSourceResolution {
    HIGH_QUALITY,
    FAST_PREVIEW,
    AWAITING_EXPLICIT_FALLBACK_CHOICE,
    CANCELLED,
}

object ImageQuestionSourcePolicy {
    fun defaultSource(): ImageQuestionSource = ImageQuestionSource.FAST_PREVIEW

    /**
     * Chooses where the bytes come from for one question.
     *
     * Falls back to the phone camera whenever the glasses cannot supply an image, so the question
     * is answered instead of failing. Both glasses sources need a live BLE link: [ImageQuestionSource.FAST_PREVIEW]
     * reads a thumbnail over it and [ImageQuestionSource.HIGH_QUALITY] negotiates Wi-Fi Direct across it.
     *
     * Asking and getting an answer beats asking and being told the glasses are not connected —
     * particularly for a user who cannot see that they have gone flat. It also means every path
     * downstream of capture can be exercised on a phone with no hardware present.
     */
    fun sourceForQuestion(
        glassesConnected: Boolean,
        preferred: ImageQuestionSource = defaultSource(),
    ): ImageQuestionSource = when {
        preferred == ImageQuestionSource.PHONE_CAMERA -> ImageQuestionSource.PHONE_CAMERA
        glassesConnected -> preferred
        else -> ImageQuestionSource.PHONE_CAMERA
    }

    fun defaultThumbnailQuality(): ImageThumbnailQuality = ImageThumbnailQuality.DETAILED

    /** A Wi-Fi failure must never silently turn into a BLE-thumbnail request. */
    fun onHighQualityFailure(): ImageSourceResolution =
        ImageSourceResolution.AWAITING_EXPLICIT_FALLBACK_CHOICE

    fun resolveHighQualityFailure(choice: HighQualityFailureChoice): ImageSourceResolution = when (choice) {
        HighQualityFailureChoice.RETRY_HIGH_QUALITY -> ImageSourceResolution.HIGH_QUALITY
        HighQualityFailureChoice.USE_FAST_PREVIEW -> ImageSourceResolution.FAST_PREVIEW
        HighQualityFailureChoice.CANCEL -> ImageSourceResolution.CANCELLED
    }
}

/** Values sent in the public CyanBridge -> Tasker broadcast. */
object ImageQuestionBroadcast {
    const val EXTRA_TYPE = "type"
    const val EXTRA_PATH = "path"
    const val EXTRA_IMAGE_URI = "image_uri"
    const val EXTRA_QUESTION = "question"
    const val EXTRA_ASSISTANT = "assistant"
    const val EXTRA_SOURCE = "image_source"
    const val EXTRA_HANDOFF_MODE = "handoff_mode"
    const val EXTRA_CALLBACK_ACTION = "callback_action"
    const val EXTRA_CALLBACK_SESSION = "callback_session"
    const val EXTRA_CALLBACK_TOKEN = "callback_token"

    const val TYPE_IMAGE = "image"
    const val HANDOFF_DIRECT_SHARE = "direct_share"
    const val HANDOFF_AUTOINPUT_FALLBACK = "autoinput_fallback"

    data class Payload(
        val type: String,
        val imagePath: String? = null,
        val imageUri: String? = null,
        val question: String? = null,
        val assistant: String? = null,
        val source: ImageQuestionSource? = null,
        val handoffMode: String? = null,
        val callbackAction: String? = null,
        val callbackSession: String? = null,
        val callbackToken: String? = null,
    ) {
        fun extras(): Map<String, String> = buildMap {
            put(EXTRA_TYPE, type)
            imagePath?.let { put(EXTRA_PATH, it) }
            imageUri?.let { put(EXTRA_IMAGE_URI, it) }
            // Keep the resolved prompt byte-for-byte intact for Tasker/Gemini.
            question?.let { put(EXTRA_QUESTION, it) }
            assistant?.let { put(EXTRA_ASSISTANT, it) }
            source?.let { put(EXTRA_SOURCE, it.wireName) }
            handoffMode?.let { put(EXTRA_HANDOFF_MODE, it) }
            callbackAction?.let { put(EXTRA_CALLBACK_ACTION, it) }
            callbackSession?.let { put(EXTRA_CALLBACK_SESSION, it) }
            callbackToken?.let { put(EXTRA_CALLBACK_TOKEN, it) }
        }
    }
}

enum class ExternalImageAutomationStage(val wireName: String) {
    IDLE("idle"),
    IMAGE_STARTED("image_started"),
    IMAGE_ATTACHED("image_attached"),
    PROMPT_SENT("prompt_sent"),
    ANSWER_READY("answer_ready"),
    FAILED("failed"),
    ;

    companion object {
        fun fromWireName(value: String?): ExternalImageAutomationStage? =
            entries.firstOrNull { it.wireName == value }
    }
}

data class ExternalImageAutomationState(
    val stage: ExternalImageAutomationStage = ExternalImageAutomationStage.IDLE,
    val error: String? = null,
)

/** Rejects stale/out-of-order automation callbacks instead of opening a follow-up early. */
object ExternalImageAutomationStateMachine {
    fun transition(
        current: ExternalImageAutomationState,
        next: ExternalImageAutomationStage,
        error: String? = null,
    ): ExternalImageAutomationState {
        if (next == ExternalImageAutomationStage.IMAGE_STARTED) {
            return if (
                current.stage == ExternalImageAutomationStage.IDLE ||
                current.stage == ExternalImageAutomationStage.FAILED
            ) {
                ExternalImageAutomationState(next)
            } else {
                current
            }
        }
        if (next == ExternalImageAutomationStage.FAILED) {
            return ExternalImageAutomationState(next, error?.takeIf { it.isNotBlank() } ?: "External automation failed")
        }
        if (next == current.stage) return current

        val accepted = when (next) {
            ExternalImageAutomationStage.IMAGE_ATTACHED ->
                current.stage == ExternalImageAutomationStage.IMAGE_STARTED
            ExternalImageAutomationStage.PROMPT_SENT ->
                current.stage == ExternalImageAutomationStage.IMAGE_ATTACHED
            ExternalImageAutomationStage.ANSWER_READY ->
                current.stage == ExternalImageAutomationStage.PROMPT_SENT
            ExternalImageAutomationStage.IDLE,
            ExternalImageAutomationStage.IMAGE_STARTED,
            ExternalImageAutomationStage.FAILED -> false
        }
        return if (accepted) ExternalImageAutomationState(next) else current
    }
}
