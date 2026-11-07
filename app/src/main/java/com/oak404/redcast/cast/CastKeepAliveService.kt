package com.oak404.redcast.cast

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.oak404.redcast.R
import com.oak404.redcast.ui.MainActivity

/**
 * Keeps the process alive during a Cast session.
 *
 * The receiver only holds a short window of the feed, so the phone has to stay running to top
 * it up. Without this, Android can kill the app once the screen locks and the TV stops when the
 * queue runs out. The Cast SDK doesn't provide a foreground service of its own.
 */
class CastKeepAliveService : Service() {

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    // Video reaches the TV through this phone, so it has to keep streaming with the screen off.
    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        wifiLock = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, LOCK_TAG)
            .apply { acquire() }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOCK_TAG)
            .apply { acquire() }
    }

    override fun onDestroy() {
        wifiLock?.release()
        wakeLock?.release()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val device = intent?.getStringExtra(EXTRA_DEVICE) ?: "TV"
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(device),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        return START_NOT_STICKY
    }

    private fun buildNotification(device: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Casting", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Playing on $device")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "casting"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_DEVICE = "device"
        private const val LOCK_TAG = "redcast:cast"

        fun start(context: Context, device: String) {
            val intent = Intent(context, CastKeepAliveService::class.java)
                .putExtra(EXTRA_DEVICE, device)
            // Refused when a session resumes while the app is in the background. Casting
            // still works then; it just isn't protected until the app is next opened.
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CastKeepAliveService::class.java))
        }
    }
}
