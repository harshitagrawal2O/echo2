package com.fersaiyan.cyanbridge.devices.metarayban

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Non-Meta variant: a stand-in for the Meta Ray-Ban manager that reports "not available" for
 * everything, so the app builds and runs without the Wearables DAT SDK.
 *
 * This exists because the DAT SDK is only obtainable from a GitHub Packages repository that
 * requires a `read:packages` grant on `facebook/meta-wearables-dat-android`. Anyone without that
 * grant previously got a build that skipped the dependency but still compiled 200-odd unresolved
 * references, because the code imported the SDK unconditionally. The target hardware for this app
 * is the HeyCyan CY-01; Ray-Ban support should not be a precondition for compiling.
 *
 * **This file must keep the same public surface as the `src/meta` implementation.** Exactly one of
 * the two is compiled, chosen by `-PmetaSupport` in `app/build.gradle`, so a member added there
 * without being added here breaks the default build — which is the one most people run.
 *
 * Behaviour throughout is "unavailable", never a crash. Every caller already handles the
 * unavailable path, because it is the same path taken when a real Meta device is absent: the
 * registration state stays [RegistrationState.UNAVAILABLE], readiness checks return false, and
 * anything that would need hardware calls back through `onError`.
 */
class MetaRaybanManager private constructor(context: Context) {

    companion object {
        private const val UNAVAILABLE =
            "Meta Ray-Ban support is not included in this build (built without -PmetaSupport)."

        @Volatile
        private var instance: MetaRaybanManager? = null

        fun getInstance(context: Context): MetaRaybanManager {
            return instance ?: synchronized(this) {
                instance ?: MetaRaybanManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _registrationState = MutableStateFlow(RegistrationState.UNAVAILABLE)
    val registrationState: StateFlow<RegistrationState> = _registrationState.asStateFlow()

    private val _availableDeviceCount = MutableStateFlow(0)
    val availableDeviceCount: StateFlow<Int> = _availableDeviceCount.asStateFlow()

    private val _selectedDeviceName = MutableStateFlow<String?>(null)
    val selectedDeviceName: StateFlow<String?> = _selectedDeviceName.asStateFlow()

    private val _selectedDeviceIsDisplayCapable = MutableStateFlow(false)
    val selectedDeviceIsDisplayCapable: StateFlow<Boolean> =
        _selectedDeviceIsDisplayCapable.asStateFlow()

    private val _deviceSessionState = MutableStateFlow(DeviceSessionState.IDLE)
    val deviceSessionState: StateFlow<DeviceSessionState> = _deviceSessionState.asStateFlow()

    private val _streamState = MutableStateFlow(StreamState.STOPPED)
    val streamState: StateFlow<StreamState> = _streamState.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _lastCapturedPhoto = MutableStateFlow<CapturedPhoto?>(null)
    val lastCapturedPhoto: StateFlow<CapturedPhoto?> = _lastCapturedPhoto.asStateFlow()

    private val _isDisplayActive = MutableStateFlow(false)
    val isDisplayActive: StateFlow<Boolean> = _isDisplayActive.asStateFlow()

    /**
     * Populated on construction rather than left null.
     *
     * Screens surface `lastError` when Meta features misbehave, so stating the reason up front
     * turns "the Meta button does nothing" into "this build has no Meta support".
     */
    private val _lastError = MutableStateFlow<String?>(UNAVAILABLE)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** No SDK to initialise. Stays uninitialised so every readiness check answers false. */
    fun initialize() = Unit

    fun startRegistration(activity: Activity) = Unit

    fun startUnregistration(activity: Activity) = Unit

    fun isRegistered(): Boolean = false

    fun isCameraReady(): Boolean = false

    /** Returns immediately rather than burning the caller's timeout waiting for absent hardware. */
    suspend fun awaitCameraReady(timeoutMs: Long = 10_000L): Boolean = false

    fun refreshRegistrationState() = Unit

    /** No Meta registration flow exists, so no callback intent can belong to it. */
    fun handleRegistrationCallback(intent: android.content.Intent): Boolean = false

    fun checkCameraPermission(
        onGranted: () -> Unit,
        onRequestNeeded: () -> Unit,
        onError: (String) -> Unit,
    ) {
        onError(UNAVAILABLE)
    }

    fun startSession(onSuccess: () -> Unit, onError: (String) -> Unit) {
        onError(UNAVAILABLE)
    }

    fun stopSession() = Unit

    fun startStreaming(
        onFrame: (android.graphics.Bitmap) -> Unit,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        onError(UNAVAILABLE)
    }

    fun stopStreaming() = Unit

    fun capturePhoto(onSuccess: (CapturedPhoto) -> Unit, onError: (String) -> Unit) {
        onError(UNAVAILABLE)
    }

    /**
     * Throws rather than returning null, matching the real implementation's contract.
     *
     * Callers `runCatching` this and fall back to the glasses or phone camera, so an exception is
     * the path they already handle. Returning a fake photo would be worse than failing.
     */
    suspend fun capturePhotoOnce(timeoutMs: Long = 20_000L): CapturedPhoto =
        throw IllegalStateException(UNAVAILABLE)

    suspend fun savePhotoForProcessing(photo: CapturedPhoto, namePrefix: String): File =
        throw IllegalStateException(UNAVAILABLE)

    fun startDisplay(onSuccess: () -> Unit, onError: (String) -> Unit) {
        onError(UNAVAILABLE)
    }

    fun stopDisplay() = Unit

    fun reportExternalError(operation: String, message: String): String = "$operation: $message"

    fun diagnosticsSnapshot(): String = UNAVAILABLE

    fun destroy() = Unit

    data class CapturedPhoto(
        val bytes: ByteArray,
        val mimeType: String,
        val uri: android.net.Uri?,
    )

    enum class RegistrationState {
        UNAVAILABLE,
        AVAILABLE,
        REGISTERED,
        REGISTERING,
        UNREGISTERING,
    }

    enum class DeviceSessionState {
        IDLE,
        STARTING,
        STARTED,
        PAUSED,
        STOPPING,
        STOPPED,
    }

    enum class StreamState {
        STOPPED,
        STARTING,
        STARTED,
        STREAMING,
        STOPPING,
        PAUSED,
        CLOSED,
    }
}
