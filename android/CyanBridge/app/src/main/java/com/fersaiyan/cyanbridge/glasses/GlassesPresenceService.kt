package com.fersaiyan.cyanbridge.glasses

import android.app.Notification
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
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else {
                    0
                },
            )
            Log.i(TAG, "Holding the process up while the glasses are connected")
        }.onFailure {
            // A foreground start can be refused; the app then behaves as it did before this
            // existed rather than crashing.
            Log.w(TAG, "Could not enter the foreground; process may still be killed", it)
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Releasing the process hold")
        super.onDestroy()
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Glasses connected")
            .setContentText("Listening for the AI button")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    companion object {
        private const val TAG = "GlassesPresence"
        private const val CHANNEL_ID = "glasses_presence"
        private const val NOTIFICATION_ID = 4711

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
