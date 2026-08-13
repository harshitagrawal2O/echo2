package com.fersaiyan.cyanbridge.ai.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageQuestionContractsTest {
    @Test
    fun detailedBlePreviewIsDefaultAndFallbackRequiresAnExplicitChoice() {
        assertEquals(ImageQuestionSource.FAST_PREVIEW, ImageQuestionSourcePolicy.defaultSource())
        assertEquals(ImageThumbnailQuality.DETAILED, ImageQuestionSourcePolicy.defaultThumbnailQuality())
        assertEquals(
            ImageSourceResolution.AWAITING_EXPLICIT_FALLBACK_CHOICE,
            ImageQuestionSourcePolicy.onHighQualityFailure(),
        )
        assertEquals(
            ImageSourceResolution.FAST_PREVIEW,
            ImageQuestionSourcePolicy.resolveHighQualityFailure(HighQualityFailureChoice.USE_FAST_PREVIEW),
        )
    }

    @Test
    fun questionsFallBackToThePhoneCameraWhenTheGlassesCannotSupplyAnImage() {
        // Both glasses sources need a live BLE link, so neither can answer without one.
        assertEquals(
            ImageQuestionSource.PHONE_CAMERA,
            ImageQuestionSourcePolicy.sourceForQuestion(
                glassesConnected = false,
                preferred = ImageQuestionSource.FAST_PREVIEW,
            ),
        )
        assertEquals(
            ImageQuestionSource.PHONE_CAMERA,
            ImageQuestionSourcePolicy.sourceForQuestion(
                glassesConnected = false,
                preferred = ImageQuestionSource.HIGH_QUALITY,
            ),
        )
    }

    @Test
    fun connectedGlassesKeepTheRequestedSourceAndPhoneCameraIsNeverOverridden() {
        assertEquals(
            ImageQuestionSource.HIGH_QUALITY,
            ImageQuestionSourcePolicy.sourceForQuestion(
                glassesConnected = true,
                preferred = ImageQuestionSource.HIGH_QUALITY,
            ),
        )
        // An explicit phone request stays on the phone even with the glasses connected, so the
        // choice is never silently upgraded back onto hardware the caller did not ask for.
        assertEquals(
            ImageQuestionSource.PHONE_CAMERA,
            ImageQuestionSourcePolicy.sourceForQuestion(
                glassesConnected = true,
                preferred = ImageQuestionSource.PHONE_CAMERA,
            ),
        )
    }

    @Test
    fun officialBleThumbnailChoicesCoverTheVerifiedZeroToFiveRange() {
        assertEquals(
            listOf(0, 1, 2, 3, 4, 5),
            ImageThumbnailQuality.entries.map(ImageThumbnailQuality::sdkValue),
        )
    }

    @Test
    fun payloadPreservesImageQuestionAndCallbackBinding() {
        val extras = ImageQuestionBroadcast.Payload(
            type = ImageQuestionBroadcast.TYPE_IMAGE,
            imagePath = "/data/user/0/com.fersaiyan.cyanbridge/files/image.jpg",
            imageUri = "content://com.fersaiyan.cyanbridge.fileprovider/external_files/image.jpg",
            question = "What does the label say?",
            assistant = "Gemini",
            source = ImageQuestionSource.HIGH_QUALITY,
            handoffMode = ImageQuestionBroadcast.HANDOFF_DIRECT_SHARE,
            callbackAction = "com.fersaiyan.cyanbridge.AI_IMAGE_STATUS",
            callbackSession = "session-1",
            callbackToken = "token-1",
        ).extras()

        assertEquals("What does the label say?", extras[ImageQuestionBroadcast.EXTRA_QUESTION])
        assertEquals("content://com.fersaiyan.cyanbridge.fileprovider/external_files/image.jpg", extras[ImageQuestionBroadcast.EXTRA_IMAGE_URI])
        assertEquals("session-1", extras[ImageQuestionBroadcast.EXTRA_CALLBACK_SESSION])
        assertEquals("token-1", extras[ImageQuestionBroadcast.EXTRA_CALLBACK_TOKEN])
    }

    @Test
    fun automationCallbacksMustArriveInOrderBeforeAnswerIsReady() {
        val idle = ExternalImageAutomationState()
        val started = ExternalImageAutomationStateMachine.transition(
            idle,
            ExternalImageAutomationStage.IMAGE_STARTED,
        )
        val attached = ExternalImageAutomationStateMachine.transition(
            started,
            ExternalImageAutomationStage.IMAGE_ATTACHED,
        )
        val sent = ExternalImageAutomationStateMachine.transition(
            attached,
            ExternalImageAutomationStage.PROMPT_SENT,
        )
        val answered = ExternalImageAutomationStateMachine.transition(
            sent,
            ExternalImageAutomationStage.ANSWER_READY,
        )

        assertEquals(ExternalImageAutomationStage.ANSWER_READY, answered.stage)
        assertEquals(idle, ExternalImageAutomationStateMachine.transition(idle, ExternalImageAutomationStage.ANSWER_READY))
        assertNull(answered.error)
    }
}
