package com.fersaiyan.cyanbridge.plugins.cue

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.fersaiyan.cyanbridge.plugins.walkingaid.WalkingAidImageCapture
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.communication.ICommandResponse
import com.oudmon.ble.base.communication.ILargeDataResponse
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassesAiVoiceRsp
import com.oudmon.ble.base.communication.bigData.resp.GlassesTouchSupportRsp
import com.oudmon.ble.base.communication.bigData.resp.GlassesWearRsp
import com.oudmon.ble.base.communication.rsp.MusicCommandRsp

/**
 * Turns glasses hardware events into Cue actions.
 *
 * The buttons are fixed in firmware and cannot be remapped, but the glasses already report events
 * to the phone that CyanBridge receives and throws away. Cue is not remapping firmware — it is
 * interpreting events that already arrive and are currently discarded:
 *
 * | Code   | Meaning              | Previously            | Cue uses it for            |
 * |--------|----------------------|-----------------------|----------------------------|
 * | `0x02` | AI photo ready       | image-question flow   | "what am I looking at"     |
 * | `0x0c` | Pause event          | an empty `//to do`    | "who's here" + interrupt   |
 * | `0x0e` | Memory low           | an empty block        | storage warning            |
 * | `0x12` | Volume change        | decoded, then Toasted | repeat last                |
 *
 * There is a second input channel too. Touchpad play/pause/next/prev arrive separately as
 * `MusicCommandRsp`, which CyanBridge references nowhere. Intercepting it before the vendor handler
 * gives Cue a set of silent, tactile, eyes-free triggers on the temple of a pair of sunglasses —
 * no phone in hand, no wake word.
 */
class CueGlassesRouter(
    context: Context,
    private val sessionProvider: () -> CueSession?,
) {

    private val appContext = context.applicationContext
    private val audioManager by lazy {
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    /** Cached on the first `0x12` of the session; a later one means the user pressed something. */
    private var volumeBaselineSeen = false

    /** Set by the service to start and stop the session on wear state. */
    var onWearChanged: ((Boolean) -> Unit)? = null

    @Volatile
    var wearCheckSupported: Boolean = false
        private set

    @Volatile
    var volumeControlSupported: Boolean = false
        private set

    // ── device notify (BLE `0x??` codes) ──

    /**
     * Handles one decoded device-notify frame. Returns true when Cue consumed it, so the caller
     * can skip its own handling.
     */
    fun onDeviceNotify(code: Int, loadData: ByteArray): Boolean {
        val session = sessionProvider()?.takeIf { it.isRunning } ?: return false
        return when (code) {
            PAUSE_EVENT -> {
                if (loadData.size > 7 && loadData[7].toInt() == 1) onPausePressed(session)
                true
            }

            MEMORY_LOW_EVENT -> {
                // Prevents a silent failure mid-session: the glasses stop storing and nothing says so.
                Log.w(TAG, "Glasses reported low storage")
                session.onGlassesBusy()
                true
            }

            VOLUME_EVENT -> {
                onVolumeEvent(session)
                true
            }

            PHOTO_READY_EVENT -> {
                // WalkingAid asks the glasses for photos of its own, and its notify arrives on the
                // same code. Whoever requested the shot owns it — Cue only claims the ones nobody
                // asked for, which are exactly the hardware AI-button presses.
                if (WalkingAidImageCapture.isAwaitingPhotoReady()) {
                    false
                } else {
                    session.answerVisualQuestion()
                    true
                }
            }

            else -> false
        }
    }

    /**
     * Pause does double duty, and the split matters.
     *
     * While Cue is talking, pause means stop talking — a blind user who has heard enough of a
     * briefing otherwise has no way out but to wait. When Cue is quiet, pause means "who's here".
     */
    private fun onPausePressed(session: CueSession) {
        if (session.isSpeakingNow()) {
            session.interruptSpeech()
        } else {
            session.whosHere()
        }
    }

    /**
     * The volume rocker becomes repeat-last.
     *
     * The first `0x12` after session start is just the device reporting its current state, so it
     * only sets the baseline. Every one after that is a press.
     *
     * Repeat-last is not a nice to have: the user is in a live conversation with their attention on
     * a human, they will miss whispers constantly, and audio has no scrollback. Every screen reader
     * has this command for exactly this reason.
     */
    private fun onVolumeEvent(session: CueSession) {
        if (!volumeBaselineSeen) {
            volumeBaselineSeen = true
            return
        }
        session.repeatLast()
    }

    fun onSessionStarted() {
        volumeBaselineSeen = false
    }

    // ── touchpad (`MusicCommandRsp`, notify id 29) ──

    private val musicListener = object : ICommandResponse<MusicCommandRsp> {
        override fun onDataResponse(response: MusicCommandRsp?) {
            val session = sessionProvider()?.takeIf { it.isRunning } ?: return
            if (response == null || response.status != 0) return
            when (response.action) {
                MUSIC_PLAY_PAUSE -> onPausePressed(session)
                MUSIC_PREVIOUS -> session.repeatLast()
                MUSIC_NEXT -> session.answerVisualQuestion()
                // Volume keeps working as volume. Stealing it would leave the user unable to
                // adjust the level of the very output Cue is producing.
                MUSIC_VOLUME_UP -> adjustVolume(AudioManager.ADJUST_RAISE)
                MUSIC_VOLUME_DOWN -> adjustVolume(AudioManager.ADJUST_LOWER)
                else -> Unit
            }
        }
    }

    private fun adjustVolume(direction: Int) {
        runCatching {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        }
    }

    // ── capability + state subscriptions ──

    /**
     * Registers everything Cue listens to on the glasses.
     *
     * Capabilities are queried rather than assumed: the vendor AAR is shared with a smartwatch line
     * and carries heart rate, sleep, and menstruation commands, so a class existing in it proves
     * nothing about what a CY-01 actually supports.
     */
    fun attach() {
        if (!BleOperateManager.getInstance().isConnected) return

        runCatching {
            BleOperateManager.getInstance().addNotifyListener(MUSIC_NOTIFY_CMD, musicListener)
        }.onFailure { Log.w(TAG, "Could not intercept touchpad commands", it) }

        runCatching {
            LargeDataHandler.getInstance().wearFunctionSupport(
                object : ILargeDataResponse<GlassesTouchSupportRsp> {
                    override fun parseData(cmdType: Int, response: GlassesTouchSupportRsp?) {
                        wearCheckSupported = response?.isWearCheckSupport == true
                        volumeControlSupported = response?.isVolumeControl == true
                        Log.i(
                            TAG,
                            "Capabilities: wearCheck=$wearCheckSupported " +
                                "volumeControl=$volumeControlSupported model=${response?.glassesModel}",
                        )
                        if (wearCheckSupported && CuePreferences.isWearLifecycleEnabled(appContext)) {
                            subscribeWear()
                        }
                    }
                },
            )
        }.onFailure { Log.w(TAG, "Capability query failed", it) }

        subscribeVendorAssistant()
    }

    fun detach() {
        runCatching { BleOperateManager.getInstance().removeNotifyListener(MUSIC_NOTIFY_CMD) }
    }

    /**
     * Binds the session to wear detection, so there is no start button at all: put the glasses on,
     * Cue starts and briefs the room; take them off, the session ends and the roster clears.
     */
    private fun subscribeWear() {
        runCatching {
            LargeDataHandler.getInstance().wearCheck(
                true,
                true,
                object : ILargeDataResponse<GlassesWearRsp> {
                    override fun parseData(cmdType: Int, response: GlassesWearRsp?) {
                        val worn = response?.isOpen == true
                        Log.i(TAG, "Wear state changed: worn=$worn")
                        onWearChanged?.invoke(worn)
                    }
                },
            )
        }.onFailure { Log.w(TAG, "Wear subscription failed", it) }
    }

    /**
     * Watches the vendor's own assistant so a collision becomes a handoff.
     *
     * Cue never uses a wake word — "Hey, Cyan" opens a device mode that collides with everything
     * Cue does — but avoidance is not the same as cooperation. When the vendor assistant opens or
     * speaks, Cue goes silent and releases the mic, then resumes.
     */
    private fun subscribeVendorAssistant() {
        runCatching {
            // The false/false form is the SDK's *query* shape: it registers the callback for
            // pushes without writing any state to the glasses. Cue never sends a set command here
            // — changing the vendor assistant's own configuration is not its business.
            LargeDataHandler.getInstance().aiVoiceWake(
                false,
                false,
                object : ILargeDataResponse<GlassesAiVoiceRsp> {
                    override fun parseData(cmdType: Int, response: GlassesAiVoiceRsp?) {
                        val open = response?.isOpen == true
                        sessionProvider()?.onVendorAssistantActive(open)
                    }
                },
            )
        }.onFailure { Log.w(TAG, "Vendor assistant subscription failed", it) }
    }

    companion object {
        private const val TAG = "CueGlasses"

        const val PHOTO_READY_EVENT = 0x02
        const val PAUSE_EVENT = 0x0c
        const val MEMORY_LOW_EVENT = 0x0e
        const val VOLUME_EVENT = 0x12

        /**
         * Notify id that carries `MusicCommandRsp`, read off the vendor SDK's own bean factory.
         * Registering here replaces the vendor handler, which is the interception the touchpad
         * triggers depend on.
         */
        const val MUSIC_NOTIFY_CMD = 29

        // Actions the vendor's own MusicCommandListener maps to media key events.
        private const val MUSIC_PLAY_PAUSE = 1
        private const val MUSIC_PREVIOUS = 2
        private const val MUSIC_NEXT = 3
        private const val MUSIC_VOLUME_UP = 4
        private const val MUSIC_VOLUME_DOWN = 5
    }
}
