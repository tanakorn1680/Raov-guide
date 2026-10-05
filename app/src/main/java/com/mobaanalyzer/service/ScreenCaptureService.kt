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
import com.mobaanalyzer.data.HeroDatabase
import com.mobaanalyzer.engine.ScreenReading
import com.mobaanalyzer.engine.ScreenState
import com.mobaanalyzer.model.GamePhase
import com.mobaanalyzer.model.GameState
import com.mobaanalyzer.model.HeroInfo
import com.mobaanalyzer.ocr.GameScreenReader
import com.mobaanalyzer.ocr.ReadResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG                 = "ScreenCaptureService"
        private const val CHANNEL_ID          = "moba_capture"
        private const val NOTIF_ID            = 2001
        private const val CAPTURE_INTERVAL_MS = 2000L

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

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
    private lateinit var screenReader: GameScreenReader
    private val handler = Handler(Looper.getMainLooper())

    private var screenWidth  = 0
    private var screenHeight = 0
    private var screenDpi    = 0

    private val captureLoop = object : Runnable {
        override fun run() {
            captureAndAnalyze()
            handler.postDelayed(this, CAPTURE_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        // โหลด HeroDatabase จาก assets/heroes.json
        val db = HeroDatabase.load(applicationContext)
        screenReader = GameScreenReader(db)
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        Log.d(TAG, "created — ${db.heroes.size} heroes loaded")
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

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(captureLoop)
        teardownProjection()
        screenReader.close()
        serviceScope.cancel()
        Log.d(TAG, "destroyed")
    }

    private fun setupScreenMetrics() {
        val dm = resources.displayMetrics
        screenWidth  = dm.widthPixels
        screenHeight = dm.heightPixels
        screenDpi    = dm.densityDpi
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        val pm = getSystemService(MediaProjectionManager::class.java)
        mediaProjection = pm.getMediaProjection(resultCode, data)
        mediaProjection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "projection stopped by system")
                stopSelf()
            }
        }, handler)
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "MobaCapture", screenWidth, screenHeight, screenDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )
    }

    private fun teardownProjection() {
        try {
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "teardown: ${e.message}")
        } finally {
            virtualDisplay = null; imageReader = null; mediaProjection = null
        }
    }

    private fun captureAndAnalyze() {
        val reader = imageReader ?: return
        val image  = reader.acquireLatestImage() ?: return
        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride   = planes[0].rowStride
            val rowPadding  = rowStride - pixelStride * screenWidth

            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
            bitmap.recycle()

            serviceScope.launch {
                try {
                    // ScreenReading มี allies, enemies, grid, timer, score
                    val result  = screenReader.read(cropped)
                    val reading = result.reading

                    // แปลง ScreenReading → GameState พร้อม rawOcrText
                    val state = reading.toGameState(result.rawText)
                    AppState.updateGameState(state)
                    AppState.updateScreenReading(reading)
                    broadcastUpdate()

                    Log.d(TAG, "allies=${reading.allies.map{it.name}} enemies=${reading.enemies.map{it.name}} state=${reading.state}")
                } catch (e: Exception) {
                    Log.w(TAG, "analyze error: ${e.message}")
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
        sendBroadcast(Intent(AppState.ACTION_GAME_STATE_UPDATED))
    }

    // แปลง ScreenReading → GameState
    private fun ScreenReading.toGameState(rawText: String = ""): GameState {
        val timeSec = seconds
        return GameState(
            gameTimeSeconds = timeSec,
            gameTimeText    = timeSec?.let { "%d:%02d".format(it / 60, it % 60) },
            myHeroes        = allies.map { HeroInfo(name = it.name) },
            enemyHeroes     = enemies.map { HeroInfo(name = it.name) },
            myScore         = allyKills,
            enemyScore      = enemyKills,
            gamePhase       = timeSec?.let { GamePhase.fromSeconds(it) } ?: GamePhase.UNKNOWN,
            rawOcrText      = rawText
        )
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MOBA Analyzer")
            .setContentText("กำลังวิเคราะห์หน้าจอ...")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setOngoing(true).setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        NotificationChannel(CHANNEL_ID, "MOBA Capture", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
            nm.createNotificationChannel(this)
        }
    }
}
