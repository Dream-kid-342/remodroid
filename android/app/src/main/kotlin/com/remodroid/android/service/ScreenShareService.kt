package com.remodroid.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.remodroid.android.MainActivity
import com.remodroid.android.R
import com.remodroid.android.RemoDroidApp
import com.remodroid.shared.session.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Holds the one-time MediaProjection consent result between the system dialog and capture start.
 * On Android 14+ each consent can be used for exactly one capture session.
 */
object ProjectionConsent {
    @Volatile
    private var data: Intent? = null

    fun set(intent: Intent) {
        data = intent
    }

    fun take(): Intent? = data.also { data = null }

    fun clear() {
        data = null
    }
}

/**
 * Foreground service of type mediaProjection. It runs only while this device's screen is shared,
 * shows a persistent notification with a Stop action, and stops itself when the session ends.
 */
class ScreenShareService : LifecycleService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val host = (application as RemoDroidApp).container.host
        if (intent?.action == ACTION_STOP) {
            host.endSession()
            stopSelfSafely()
            return START_NOT_STICKY
        }

        ensureChannel()
        val peer = host.ui.value.peerName ?: host.ui.value.incoming?.controllerName
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(peer),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0,
        )
        _running.value = true

        lifecycleScope.launch {
            var sawSession = false
            host.ui.collect { ui ->
                if (ui.sessionId != null) sawSession = true
                // Session over (ended, rejected, failed) -> release the foreground service.
                if (sawSession && ui.sessionId == null) stopSelfSafely()
                if (!sawSession && ui.incoming == null && ui.state != SessionState.NEGOTIATING) stopSelfSafely()
            }
        }
        return START_NOT_STICKY
    }

    private fun stopSelfSafely() {
        _running.value = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        _running.value = false
        ProjectionConsent.clear()
        super.onDestroy()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_sharing), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun buildNotification(peerName: String?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_share)
            .setContentTitle("Your screen is being shared")
            .setContentText(if (peerName != null) "$peerName can see your screen." else "A RemoDroid session is active.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop sharing", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "screen_sharing"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "com.remodroid.android.STOP_SHARING"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ScreenShareService::class.java))
        }
    }
}
