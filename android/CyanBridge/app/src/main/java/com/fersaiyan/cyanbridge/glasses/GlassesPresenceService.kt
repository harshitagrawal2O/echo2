package com.fersaiyan.cyanbridge.glasses

import android.app.Notification
import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.fersaiyan.cyanbridge.R
import com.oudmon.ble.base.communication.LargeDataHandler

/**
 * Keeps the process alive while the glasses are connected, so the AI button still works when the
 * wearer is in another app.
 *
 * The glasses notify listener is registered process-globally:
 *
 * ```
 * LargeDataHandler.getInstance().addOutDeviceListener(100, deviceNotifyListener)
 * ```
 *
 * and nothing ever unregisters it, so it survives the activity being destroyed. What it does not
 * survive is the *process* being killed - and on an aggressive ROM that happens as soon as the user
 * switches apps. Measured on a Nothing Phone: open WhatsApp, and `pidof` returns nothing. Battery
 * optimisation was already exempted and made no difference, because nothing held the process up.
 *
 * So this service exists only to be a foreground component. It does no work. That is the whole
 * point: the cheap fix for "the AI stops when I switch apps" is to stop the process dying, not to
 * move ten thousand lines of listener out of the activity.
 *
 * Someone will eventually want the listener to live here properly, and they should - an activity
 * owning the device connection is the wrong shape, and the inner class leaks the activity for the
 * life of the process. This is the small correct step, not the finished architecture.
 *
 * `connectedDevice` is the honest foreground type: the justification for staying alive is a
 * connected pair of glasses, and when they disconnect this stops.
 */
class GlassesPresenceService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Glasses connection",
                    // LOW: the wearer does not need to be told, they need the app to stay alive.
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TOGGLE_ASSISTANT) {
            val muted = AssistantMutePrefs.toggle(this)
            Log.i(TAG, "Assistant switched " + if (muted) "off" else "on")
            // Ask the glasses to stop listening for the wake word too, so a muted assistant is not
            // merely ignoring triggers it still wakes up to receive. The app-side gate in
            // AssistantMutePrefs is what actually guarantees silence - this call can be refused, and
            // the wearer must not be left with a mute that quietly did nothing.
            runCatching {
                LargeDataHandler.getInstance().aiVoiceWake(true, !muted) { _, response ->
                    Log.i(TAG, "Glasses wake-word detector now open=" + response.isOpen)
                }
            }.onFailure { Log.w(TAG, "Could not change the glasses wake-word detector", it) }
        }
        // The microphone type is what makes the AI button usable from another app.
        //
        // Without it, `dumpsys audio` logs every capture as `src:VOICE_RECOGNITION silenced` -
        // Android hands the recognizer silence rather than audio, because a backgrounded app with no
        // microphone foreground-service claim is not allowed to listen. The symptom is
        // indistinguishable from a broken headset: one RMS sample at -2 dB, `streamLookedDead`, no
        // transcript. It reads as a Bluetooth routing fault because it only shows up while the app is
        // off-screen - which is exactly when someone uses the glasses.
        //
        // `connectedDevice` stays because the reason to be alive at all is a connected pair of
        // glasses; `microphone` is what lets the listening work.
        val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }

        if (!startForegroundWithTypes(types)) {
            // A microphone-typed start can be refused - the permission may be missing, or the OS may
            // disallow it from the current process state. Falling back keeps the original benefit of
            // the process staying alive rather than losing both.
            Log.w(TAG, "Microphone foreground type refused; holding the process without it")
            val fallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            }
            if (!startForegroundWithTypes(fallback)) {
                Log.w(TAG, "Could not enter the foreground; process may still be killed")
                stopSelf()
            }
        }
        return START_STICKY
    }

    /** Returns true when the service entered the foreground with [types]. */
    private fun startForegroundWithTypes(types: Int): Boolean = runCatching {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), types)
        Log.i(TAG, "Holding the process up while the glasses are connected (types=" + types + ")")
        true
    }.getOrElse {
        Log.w(TAG, "Foreground start refused for types=" + types, it)
        false
    }

    override fun onDestroy() {
        Log.i(TAG, "Releasing the process hold")
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val muted = AssistantMutePrefs.isMuted(this)
        // The state has to be legible from the notification itself, because this is the surface a
        // wearer reaches for when the assistant is behaving unexpectedly, and "is it switched off?"
        // is the first question. TalkBack reads both the text and the action label.
        val toggle = PendingIntent.getService(
            this,
            REQUEST_TOGGLE,
            Intent(this, GlassesPresenceService::class.java).setAction(ACTION_TOGGLE_ASSISTANT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(if (muted) "Assistant off" else "Glasses connected")
            .setContentText(
                if (muted) {
                    "Not responding to the glasses. Tap Turn on to resume."
                } else {
                    "Listening for the AI button"
                },
            )
            .addAction(
                0,
                if (muted) "Turn on" else "Turn off",
                toggle,
            )
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "GlassesPresence"
        private const val CHANNEL_ID = "glasses_presence"
        private const val NOTIFICATION_ID = 4711
        private const val REQUEST_TOGGLE = 4712
        const val ACTION_TOGGLE_ASSISTANT = "com.fersaiyan.cyanbridge.TOGGLE_ASSISTANT"

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, GlassesPresenceService::class.java),
                )
            }.onFailure { Log.w(TAG, "Could not start the presence service", it) }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, GlassesPresenceService::class.java))
            }.onFailure { Log.w(TAG, "Could not stop the presence service", it) }
        }
    }
}
