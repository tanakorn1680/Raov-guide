package com.mobaanalyzer.service

import android.app.*
import android.content.*
import android.graphics.PixelFormat
import android.os.*
import android.util.Log
import android.view.*
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.mobaanalyzer.MainActivity
import com.mobaanalyzer.R
import com.mobaanalyzer.data.AppState
import com.mobaanalyzer.engine.ScreenState
import com.mobaanalyzer.model.GamePhase
import com.mobaanalyzer.model.GameState

class OverlayService : Service() {

    companion object {
        private const val TAG        = "OverlayService"
        private const val CHANNEL_ID = "moba_overlay"
        private const val NOTIF_ID   = 3001

        fun start(context: Context) =
            context.startForegroundService(Intent(context, OverlayService::class.java))

        fun stop(context: Context) =
            context.stopService(Intent(context, OverlayService::class.java))
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null

    private var tvTimer:  TextView? = null
    private var tvScore:  TextView? = null
    private var tvPhase:  TextView? = null
    private var tvTip:    TextView? = null
    private var tvHeroes: TextView? = null
    private var tvDebug:  TextView? = null

    private var initialX = 0; private var initialY = 0
    private var initialTouchX = 0f; private var initialTouchY = 0f

    // รับ broadcast ทุกครั้งที่ ScreenCaptureService วิเคราะห์เสร็จ
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateOverlay()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        windowManager = getSystemService(WindowManager::class.java)
        createOverlay()
        val filter = IntentFilter(AppState.ACTION_GAME_STATE_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stateReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(stateReceiver)
        removeOverlay()
    }

    private fun createOverlay() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 0; y = 200 }

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)
        overlayView = view
        tvTimer  = view.findViewById(R.id.tvOverlayTimer)
        tvScore  = view.findViewById(R.id.tvOverlayScore)
        tvPhase  = view.findViewById(R.id.tvOverlayPhase)
        tvTip    = view.findViewById(R.id.tvOverlayTip)
        tvHeroes = view.findViewById(R.id.tvOverlayHeroes)
        tvDebug  = view.findViewById(R.id.tvOverlayDebug)

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    initialTouchX = event.rawX; initialTouchY = event.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(view, params); true
                }
                else -> false
            }
        }

        view.findViewById<View>(R.id.btnOverlayClose)?.setOnClickListener { removeOverlay() }

        try { windowManager.addView(view, params) } catch (e: Exception) {
            Log.e(TAG, "addView failed: ${e.message}")
        }
    }

    private fun removeOverlay() {
        overlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            overlayView = null
        }
    }

    private fun updateOverlay() {
        tvDebug?.text = "v6 · " + AppState.lastStatusText
        val reading = AppState.getScreenReading()
        val state   = AppState.getGameState()

        // ===== แสดงผลหลัก =====
        tvTimer?.text = state?.gameTimeText ?: "--:--"

        val my = state?.myScore ?: "-"
        val en = state?.enemyScore ?: "-"
        tvScore?.text = "$my ⚔ $en"

        // ===== ฮีโร่ที่ตรวจพบ แยกฝั่ง =====
        val allies  = reading?.allies?.map { it.name }  ?: emptyList()
        val enemies = reading?.enemies?.map { it.name } ?: emptyList()
        val grid    = reading?.grid?.map { it.name }    ?: emptyList()

        val heroText = buildString {
            if (allies.isNotEmpty())  append("🔵 ${allies.joinToString(", ")}\n")
            if (enemies.isNotEmpty()) append("🔴 ${enemies.joinToString(", ")}\n")
            if (allies.isEmpty() && enemies.isEmpty()) {
                // DEBUG: แสดง OCR text ดิบ เพื่อดูว่าอ่านได้อะไร
                val raw = state?.rawOcrText ?: ""
                if (raw.isNotBlank()) {
                    append("📡 OCR:\n${raw.take(120)}")
                } else {
                    append("ยังไม่ตรวจพบฮีโร่")
                }
            }
            if (grid.isNotEmpty()) append("🎯 ${grid.take(3).joinToString(", ")}")
        }
        tvHeroes?.text = heroText.trim()

        // ===== Phase และ Tip =====
        val screenState = reading?.state
        tvPhase?.text = when {
            screenState == ScreenState.DRAFT   -> "📋 Ban/Pick"
            screenState == ScreenState.IN_GAME -> when (state?.gamePhase) {
                GamePhase.EARLY_GAME -> "🌅 Early"
                GamePhase.MID_GAME   -> "⚔️ Mid"
                GamePhase.LATE_GAME  -> "🔥 Late"
                else                 -> "🎮 In Game"
            }
            else -> "📡 กำลังอ่าน..."
        }

        tvTip?.text = when {
            screenState == ScreenState.DRAFT && enemies.isNotEmpty() ->
                generatePickTip(allies, enemies)
            screenState == ScreenState.IN_GAME ->
                generateGameTip(state)
            else -> "📡 กำลังอ่านหน้าจอ..."
        }
    }

    private fun generatePickTip(allies: List<String>, enemies: List<String>): String {
        return buildString {
            append("ศัตรู: ${enemies.joinToString(", ")}\n")
            append("เพื่อน: ${if (allies.isEmpty()) "?" else allies.joinToString(", ")}\n")
            append("💡 เลือกตัวที่ counter ศัตรูได้")
        }
    }

    private fun generateGameTip(state: GameState?): String {
        if (state == null) return "📡 กำลังอ่าน..."
        val diff = (state.myScore ?: 0) - (state.enemyScore ?: 0)
        return when (state.gamePhase) {
            GamePhase.EARLY_GAME -> if (diff >= 2) "✅ ได้เปรียบ — push lane" else "🎯 Farm + Jungle"
            GamePhase.MID_GAME   -> if (diff >= 3) "🏆 ได้เปรียบ — ตีหอ" else "🗺️ Contest Objective"
            GamePhase.LATE_GAME  -> if (diff >= 2) "⚔️ รวมทีม push" else "🏰 Def รอ mistake"
            else -> "📡 กำลังอ่าน..."
        }
    }

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
            .setOngoing(true).setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        NotificationChannel(CHANNEL_ID, "MOBA Overlay", NotificationManager.IMPORTANCE_LOW)
            .apply { setShowBadge(false); nm.createNotificationChannel(this) }
    }
}
