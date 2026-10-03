package com.mobaanalyzer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mobaanalyzer.MainActivity
import com.mobaanalyzer.R
import com.mobaanalyzer.data.AppState
import com.mobaanalyzer.model.GameState
import com.mobaanalyzer.ocr.GameScreenReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * ScreenCaptureService
 *
 * Foreground service ที่:
 * 1. รับ MediaProjection result จาก MainActivity
 * 2. สร้าง VirtualDisplay + ImageReader
 * 3. จับภาพหน้าจอทุก CAPTURE_INTERVAL_MS
 * 4. ส่ง Bitmap เข้า GameScreenReader (ML Kit OCR)
 * 5. อัปเดต AppState.gameState → OverlayService รับ broadcast ไปแสดงผล
 *
 * ยืม pattern KeepAliveService จาก bankconfirm:
 * - serviceScope + SupervisorJob
 * - START_STICKY + onTaskRemoved restart
 * - cancel scope ใน onDestroy()
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG                = "ScreenCaptureService"
        private const val CHANNEL_ID         = "moba_capture"
        private const val NOTIF_ID           = 2001
        private const val CAPTURE_INTERVAL_MS = 2000L   // จับภาพทุก 2 วินาที

        const val EXTRA_RESULT_CODE    = "result_code"
        const val EXTRA_RESULT_DATA    = "result_data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
    }

    private var serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val screenReader = GameScreenReader()
    private val handler = Handler(Looper.getMainLooper())

    private var screenWidth  = 0
    private var screenHeight = 0
    private var screenDpi    = 0

    // ── captureLoop — วิ่งทุก CAPTURE_INTERVAL_MS ────────────────────────────
    private val captureLoop = object : Runnable {
        override fun run() {
            captureAndAnalyze()
            handler.postDelayed(this, CAPTURE_INTERVAL_MS)
        }
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        Log.d(TAG, "created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode == -1 || resultData == null) {
            Log.w(TAG, "no projection data — stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        setupScreenMetrics()
        setupMediaProjection(resultCode, resultData)
        handler.post(captureLoop)

        Log.d(TAG, "started — ${screenWidth}x${screenHeight} @${screenDpi}dpi")
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.w(TAG, "task removed — service keeps running")
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(captureLoop)
        teardownProjection()
        screenReader.close()
        serviceScope.cancel()
        Log.d(TAG, "destroyed")
    }

    // =========================================================================
    // Setup
    // =========================================================================

    private fun setupScreenMetrics() {
        val dm = resources.displayMetrics
        screenWidth  = dm.widthPixels
        screenHeight = dm.heightPixels
        screenDpi    = dm.densityDpi
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        val pm = getSystemService(MediaProjectionManager::class.java)
        mediaProjection = pm.getMediaProjection(resultCode, data)

        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight,
            PixelFormat.RGBA_8888,
            2   // maxImages — buffer 2 เฟรม
        )

        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "MobaCapture",
            screenWidth, screenHeight, screenDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, null
        )
    }

    private fun teardownProjection() {
        try {
            handler.removeCallbacks(captureLoop)
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "teardown error: ${e.message}")
        } finally {
            virtualDisplay    = null
            imageReader       = null
            mediaProjection   = null
        }
    }

    // =========================================================================
    // Capture + OCR
    // =========================================================================

    private fun captureAndAnalyze() {
        val reader = imageReader ?: return
        val image  = reader.acquireLatestImage() ?: return

        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride  = planes[0].pixelStride
            val rowStride    = planes[0].rowStride
            val rowPadding   = rowStride - pixelStride * screenWidth

            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // ตัด rowPadding ออก ให้ได้ bitmap ขนาดพอดีกับหน้าจอ
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
            bitmap.recycle()

            // ส่งเข้า OCR ใน coroutine
            serviceScope.launch {
                try {
                    val state = screenReader.read(cropped)
                    AppState.updateGameState(state)
                    broadcastUpdate()
                    Log.d(TAG, "state updated: phase=${state.gamePhase} time=${state.gameTimeText}")
                } catch (e: Exception) {
                    Log.w(TAG, "OCR error: ${e.message}")
                } finally {
                    cropped.recycle()
                }
            }

        } catch (e: Exception) {
            Log.w(TAG, "capture error: ${e.message}")
        } finally {
            image.close()
        }
    }

    private fun broadcastUpdate() {
        val intent = Intent(AppState.ACTION_GAME_STATE_UPDATED)
        sendBroadcast(intent)
    }

    // =========================================================================
    // Notification
    // =========================================================================

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MOBA Analyzer")
            .setContentText("กำลังวิเคราะห์หน้าจอ...")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        NotificationChannel(CHANNEL_ID, "MOBA Capture", NotificationManager.IMPORTANCE_LOW).apply {
            description = "จับภาพหน้าจอเพื่อวิเคราะห์เกม"
            setShowBadge(false)
            nm.createNotificationChannel(this)
        }
    }
}
