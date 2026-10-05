package com.mobaanalyzer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.mobaanalyzer.MainActivity
import com.mobaanalyzer.R
import com.mobaanalyzer.data.AppState
import com.mobaanalyzer.model.GamePhase
import com.mobaanalyzer.model.GameState

/**
 * OverlayService
 *
 * แสดง floating window ทับหน้าจอเกม
 *
 * Features:
 * - ลาก window ย้ายตำแหน่งได้
 * - รับ broadcast จาก ScreenCaptureService เมื่อมี GameState ใหม่
 * - แสดง: เวลาเกม, สกอร์, phase, คำแนะนำ
 * - กด X ปิด overlay ชั่วคราว
 */
class OverlayService : Service() {

    companion object {
        private const val TAG        = "OverlayService"
        private const val CHANNEL_ID = "moba_overlay"
        private const val NOTIF_ID   = 3001

        fun start(context: Context) {
            context.startForegroundService(Intent(context, OverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null

    // Views ภายใน overlay
    private var tvTimer: TextView?   = null
    private var tvScore: TextView?   = null
    private var tvPhase: TextView?   = null
    private var tvTip: TextView?     = null
    private var tvHeroes: TextView?  = null

    // ── Drag state ────────────────────────────────────────────────────────────
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    // ── Broadcast receiver รับ GameState update ───────────────────────────────
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = AppState.getGameState() ?: return
            updateOverlay(state)
        }
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        windowManager = getSystemService(WindowManager::class.java)
        createOverlay()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                stateReceiver,
                IntentFilter(AppState.ACTION_GAME_STATE_UPDATED),
                RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stateReceiver, IntentFilter(AppState.ACTION_GAME_STATE_UPDATED))
        }
        Log.d(TAG, "created")
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(stateReceiver)
        removeOverlay()
        Log.d(TAG, "destroyed")
    }

    // =========================================================================
    // Overlay Window
    // =========================================================================

    private fun createOverlay() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = 100
        }

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)
        overlayView = view

        tvTimer  = view.findViewById(R.id.tvOverlayTimer)
        tvScore  = view.findViewById(R.id.tvOverlayScore)
        tvPhase  = view.findViewById(R.id.tvOverlayPhase)
        tvTip    = view.findViewById(R.id.tvOverlayTip)
        tvHeroes = view.findViewById(R.id.tvOverlayHeroes)

        // ── Drag to move ──────────────────────────────────────────────────────
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX      = params.x
                    initialY      = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }

        // ── ปุ่มปิด overlay ───────────────────────────────────────────────────
        view.findViewById<View>(R.id.btnOverlayClose)?.setOnClickListener {
            removeOverlay()
        }

        try {
            windowManager.addView(view, params)
            Log.d(TAG, "overlay added")
        } catch (e: Exception) {
            Log.e(TAG, "addView failed: ${e.message}")
        }
    }

    private fun removeOverlay() {
        overlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            overlayView = null
        }
    }

    // =========================================================================
    // Update UI
    // =========================================================================

        private fun updateOverlay(state: GameState) {
        // Timer
        tvTimer?.text = state.gameTimeText ?: "--:--"

        // Score
        val my = state.myScore ?: "-"
        val en = state.enemyScore ?: "-"
        tvScore?.text = "$my  ⚔  $en"

        // Phase
        tvPhase?.text = when (state.gamePhase) {
            GamePhase.EARLY_GAME -> "🌅 Early"
            GamePhase.MID_GAME   -> "⚔️ Mid"
            GamePhase.LATE_GAME  -> "🔥 Late"
            GamePhase.UNKNOWN    -> "?"
        }

        // แสดงฮีโร่ฝ่ายเราและศัตรู แยกกัน
        val reading = AppState.getScreenReading()
        val allies  = reading?.allies?.map { it.name }  ?: state.myHeroes.map { it.name }
        val enemies = reading?.enemies?.map { it.name } ?: state.enemyHeroes.map { it.name }

        val heroText = when {
            allies.isNotEmpty() || enemies.isNotEmpty() -> {
                val a = if (allies.isNotEmpty()) "🔵 " + allies.joinToString(", ") else "🔵 ?"
                val e = if (enemies.isNotEmpty()) "🔴 " + enemies.joinToString(", ") else "🔴 ?"
                a + "\n" + e
            }
            else -> "ยังไม่ตรวจพบฮีโร่"
        }
        tvHeroes?.text = heroText

        // Tip
        tvTip?.text = generateTip(state)
    }

    /**
     * สร้างคำแนะนำเบื้องต้นจาก GameState
     * Phase 3 (AI engine) จะมาแทนที่ส่วนนี้ด้วย logic ที่ซับซ้อนกว่า
     */
    private fun generateTip(state: GameState): String {
        val myScore = state.myScore ?: 0
        val enScore = state.enemyScore ?: 0
        val diff = myScore - enScore

        return when (state.gamePhase) {
            GamePhase.EARLY_GAME -> when {
                diff >= 2  -> "✅ เราได้เปรียบ — push lane กดดันต่อ"
                diff <= -2 -> "⚠️ เราเสียเปรียบ — farm safe ก่อน"
                else       -> "🎯 Early game — focus farm + jungle"
            }
            GamePhase.MID_GAME -> when {
                diff >= 3  -> "🏆 ได้เปรียบชัด — ตีหอร่วมกัน"
                diff <= -2 -> "🛡️ เสียเปรียบ — def + poke ไม่รับ fight"
                else       -> "🗺️ Mid game — contest ทุก objective"
            }
            GamePhase.LATE_GAME -> when {
                diff >= 2  -> "⚔️ Late game — รวมทีม push finish"
                else       -> "🏰 Late — def high ground รอ mistake"
            }
            GamePhase.UNKNOWN -> "📡 กำลังอ่านหน้าจอ..."
        }
    }

    // =========================================================================
    // Notification
    // =========================================================================

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MOBA Overlay")
            .setContentText("Overlay กำลังแสดงผล")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        NotificationChannel(CHANNEL_ID, "MOBA Overlay", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
            nm.createNotificationChannel(this)
        }
    }
}
