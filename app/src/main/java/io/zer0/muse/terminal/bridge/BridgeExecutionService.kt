package io.zer0.muse.terminal.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import io.zer0.common.Logger
import io.zer0.muse.R

/**
 * Keeps a Termux / Nightzuku job alive after Muse leaves the foreground.
 *
 * Type is `dataSync`: the work is running a command and bringing the result back. Android 16
 * enforces a quota on that type; [onTimeout] classifies the active jobs as a lost service instead
 * of letting the process die silently.
 */
class BridgeExecutionService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val notification = buildNotification()
        val started = runCatching { startForegroundCompat(notification) }
        if (started.isFailure) {
            Logger.w(TAG, "startForeground failed: ${started.exceptionOrNull()?.javaClass?.simpleName}")
            stopSelf()
            return
        }
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Muse:BridgeJob")
            .also { lock ->
                lock.setReferenceCounted(false)
                runCatching { lock.acquire() }
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, foregroundServiceType: Int) {
        Logger.w(TAG, "dataSync timeout type=$foregroundServiceType")
        runCatching { BridgeCoordinator.get(this).onForegroundTimeout() }
        stopServiceQuietly()
    }

    override fun onDestroy() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.bridge_notif_title))
            .setContentText(getString(R.string.bridge_notif_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.bridge_notif_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopServiceQuietly() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    companion object {
        private const val TAG = "BridgeService"
        private const val CHANNEL_ID = "bridge_jobs"
        private const val NOTIFICATION_ID = 2042

        fun start(context: Context) {
            val intent = Intent(context, BridgeExecutionService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BridgeExecutionService::class.java))
        }
    }
}
