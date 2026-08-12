package com.fersaiyan.cyanbridge.plugins.cue

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.fersaiyan.cyanbridge.plugins.startPluginVoiceForeground
import com.fersaiyan.cyanbridge.plugins.startPluginVoiceService
import com.oudmon.ble.base.bluetooth.BleOperateManager

/**
 * Holds a Cue session for as long as the glasses are on.
 *
 * Session lifecycle is bound to the hardware rather than to a button. Put the glasses on, Cue
 * starts and briefs the room; take them off, the session ends and the roster clears. That is
 * zero-UI session control on a device for blind users, and it is also the privacy story told as a
 * physical act instead of a settings toggle.
 *
 * Wear detection is used where the hardware reports it and connection state where it does not,
 * because `GlassesTouchSupportRsp.isWearCheckSupport()` is a capability flag on an AAR shared with
 * a smartwatch line.
 */
class CueService : Service() {

    internal var session: CueSession? = null
        private set

    internal lateinit var router: CueGlassesRouter
        private set

    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        CueNotificationHelper.ensureChannel(this)
        router = CueGlassesRouter(this) { session }
        router.onWearChanged = ::onWearChanged
        CuePlugin.attach(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSession()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_WHOS_HERE -> session?.whosHere()
            ACTION_REPEAT -> session?.repeatLast()
            ACTION_BIND_WEARER -> session?.bindWearerOnNextVoice()
            else -> {
                if (!CuePreferences.isEnabled(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!ensureForeground()) {
                    Log.w(TAG, "Missing microphone or notification permission")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startSession()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopSession()
        session?.release()
        session = null
        runCatching { router.detach() }
        CuePlugin.detach(this)
        super.onDestroy()
    }

    // ── session lifecycle ──

    private fun startSession() {
        if (session?.isRunning == true) return
        val active = session ?: CueSession(this, ::onStatusChanged).also { session = it }
        router.onSessionStarted()
        router.attach()
        active.start()
        updateNotification()
    }

    private fun stopSession() {
        session?.stop()
        updateNotification()
    }

    /**
     * Wear detection drives the session directly when the hardware supports it, which is what makes
     * the demo land: the audience watches someone put on sunglasses and hear the room.
     */
    private fun onWearChanged(worn: Boolean) {
        if (!CuePreferences.isWearLifecycleEnabled(this)) return
        if (worn) startSession() else stopSession()
    }

    fun onGlassesConnected() {
        router.attach()
        // Without wear detection, connection state is the lifecycle. The fallback is not a
        // degraded mode — on a unit that reports no wear support it is the only correct one.
        if (!router.wearCheckSupported || !CuePreferences.isWearLifecycleEnabled(this)) {
            startSession()
        }
    }

    fun onGlassesDisconnected() {
        session?.onGlassesLost()
        stopSession()
    }

    fun status(): CueSessionStatus? = session?.status()

    private fun onStatusChanged(status: CueSessionStatus) {
        updateNotification(status)
    }

    private fun updateNotification(status: CueSessionStatus? = session?.status()) {
        if (!foregroundStarted) return
        CueNotificationHelper.update(this, describe(status))
    }

    /**
     * Notification text doubles as the dev overlay.
     *
     * The mic route and whether transcription is live are the two things that silently make Cue
     * useless, and neither is visible from the outside — a session with a dead transcriber looks
     * exactly like a quiet room.
     */
    private fun describe(status: CueSessionStatus?): String {
        if (status == null || !status.running) {
            return getString(com.fersaiyan.cyanbridge.R.string.compose_cue_status_stopped)
        }
        val mic = when (status.micRoute) {
            CueMicRoute.GLASSES -> "glasses mic"
            CueMicRoute.PHONE -> "phone mic"
            CueMicRoute.NONE -> "no mic"
        }
        val transcription = when {
            status.rehearsal -> "rehearsal"
            status.transcriptionLive -> "live"
            else -> "earcons only"
        }
        return "Listening · $mic · $transcription · ${status.rosterSize} present"
    }

    private fun ensureForeground(): Boolean {
        if (foregroundStarted) return true
        val started = startPluginVoiceForeground(
            service = this,
            notificationId = CueNotificationHelper.NOTIFICATION_ID,
            notification = CueNotificationHelper.buildNotification(this, "Starting…"),
        )
        foregroundStarted = started
        return started
    }

    companion object {
        private const val TAG = "CueService"

        const val ACTION_START = "com.fersaiyan.cyanbridge.cue.START"
        const val ACTION_STOP = "com.fersaiyan.cyanbridge.cue.STOP"
        const val ACTION_WHOS_HERE = "com.fersaiyan.cyanbridge.cue.WHOS_HERE"
        const val ACTION_REPEAT = "com.fersaiyan.cyanbridge.cue.REPEAT"
        const val ACTION_BIND_WEARER = "com.fersaiyan.cyanbridge.cue.BIND_WEARER"

        fun start(context: Context) {
            if (!CuePreferences.isEnabled(context)) return
            startPluginVoiceService(
                context,
                Intent(context, CueService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, CueService::class.java).setAction(ACTION_STOP),
                )
            }
        }

        fun send(context: Context, action: String) {
            if (!CuePreferences.isEnabled(context)) return
            runCatching {
                context.startService(Intent(context, CueService::class.java).setAction(action))
            }
        }

        /** Whether the glasses link is up, used to decide if a session can start at all. */
        fun isGlassesConnected(): Boolean =
            runCatching { BleOperateManager.getInstance().isConnected }.getOrDefault(false)
    }
}
