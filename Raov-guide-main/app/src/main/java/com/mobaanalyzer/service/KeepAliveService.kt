package com.mobaanalyzer.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mobaanalyzer.MainActivity
import com.mobaanalyzer.R
import com.mobaanalyzer.receiver.AlarmReceiver

/**
 * KeepAliveService
 *
 * ยืม pattern จาก bankconfirm โดยตรง — hardened สำหรับ MIUI/HyperOS/Samsung
 *
 * ทำหน้าที่:
 * - WakeLock ป้องกัน Doze ฆ่า service
 * - AlarmManager backup restart ทุก 15 นาที
 * - Watchdog renew WakeLock ทุก 30 นาที
 * - onTaskRemoved + onDestroy restart guard
 */
class KeepAliveService : Service() {

    companion object {
        private const val TAG               = "KeepAliveService"
        private const val CHANNEL_ID        = "moba_keepalive"
        private const val NOTIF_ID          = 1001
        private const val WAKE_TAG          = "com.mobaanalyzer:keepalive"

        private const val WATCHDOG_MS       = 30 * 60 * 1000L
        private const val WAKE_TIMEOUT_MS   = 65 * 60 * 1000L
        private const val ALARM_INTERVAL_MS = 15 * 60 * 1000L
        private const val ALARM_REQUEST     = 9001

        fun scheduleAlarm(context: android.content.Context) {
            val am = context.getSystemService(AlarmManager::class.java)
            val pi = alarmIntent(context)
            am.cancel(pi)
            am.setRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + ALARM_INTERVAL_MS,
                ALARM_INTERVAL_MS,
                pi
            )
            Log.d(TAG, "alarm scheduled every ${ALARM_INTERVAL_MS / 60000} min")
        }

        fun cancelAlarm(context: android.content.Context) {
            context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context))
        }

        private fun alarmIntent(context: android.content.Context): PendingIntent =
            PendingIntent.getBroadcast(
                context, ALARM_REQUEST,
                Intent(context, AlarmReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    private val watchdog = object : Runnable {
        override fun run() {
            Log.d(TAG, "watchdog ping")
            renewWakeLock()
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        acquireWakeLock()
        scheduleAlarm(applicationContext)
        handler.post(watchdog)
        Log.d(TAG, "created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (wakeLock == null || wakeLock?.isHeld == false) acquireWakeLock()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        startService(Intent(applicationContext, KeepAliveService::class.java))
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(watchdog)
        releaseWakeLock()
        startService(Intent(applicationContext, KeepAliveService::class.java))
    }

    // ── WakeLock ──────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKE_TIMEOUT_MS)
        }
    }

    private fun renewWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
                it.acquire(WAKE_TIMEOUT_MS)
            } ?: acquireWakeLock()
        } catch (e: Exception) {
            acquireWakeLock()
        }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }
        catch (_: Exception) {}
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MOBA Analyzer")
            .setContentText("กำลังทำงานอยู่เบื้องหลัง")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        NotificationChannel(CHANNEL_ID, "MOBA Keep Alive", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
            nm.createNotificationChannel(this)
        }
    }
}
