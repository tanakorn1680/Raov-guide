package com.mobaanalyzer.service

import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
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
import kotlin.math.abs
import kotlin.math.max

/**
 * Overlay: แผงข้อมูลแบบเรียบ ย่อเป็นก้อนกลมได้ ลากได้อิสระ
 * ไม่มีปุ่มปิด — หยุดได้จากในแอปเท่านั้น (MainActivity → OverlayService.stop)
 */
class OverlayService : Service() {

    companion object {
        private const val TAG        = "OverlayService"
        private const val CHANNEL_ID = "moba_overlay"
        private const val NOTIF_ID   = 3001
        private const val PREFS      = "overlay_prefs"
        private const val BUILD      = "v7"

        fun start(context: Context) =
            context.startForegroundService(Intent(context, OverlayService::class.java))

        fun stop(context: Context) =
            context.stopService(Intent(context, OverlayService::class.java))
    }

    private val colorOk   = Color.parseColor("#34D399")
    private val colorIdle = Color.parseColor("#6B7280")
    private val colorErr  = Color.parseColor("#F87171")

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences

    private var root: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var bubble: View? = null
    private var statusDot: View? = null

    private var tvPhase:   TextView? = null
    private var tvAllies:  TextView? = null
    private var tvEnemies: TextView? = null
    private var tvTip:     TextView? = null
    private var tvDebug:   TextView? = null

    private var collapsed = true
    private var fx = 0.01f      // ตำแหน่งเป็นสัดส่วนของจอ (กันหลุดจอตอนหมุน)
    private var fy = 0.52f
    private var lastSw = 0
    private var lastSh = 0
    private var touchSlop = 8

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
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        collapsed = prefs.getBoolean("collapsed", true)
        fx = prefs.getFloat("fx", 0.01f)
        fy = prefs.getFloat("fy", 0.52f)

        createOverlay()

        val filter = IntentFilter(AppState.ACTION_GAME_STATE_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stateReceiver, filter)
        }
        updateOverlay()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(stateReceiver)
        removeOverlay()
    }

    // ───────────────────────── สร้าง / ลบ overlay ─────────────────────────

    private fun createOverlay() {
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        params = p

        val v = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)
        root      = v
        panel     = v.findViewById(R.id.overlayPanel)
        bubble    = v.findViewById(R.id.overlayBubble)
        statusDot = v.findViewById(R.id.overlayStatusDot)
        tvPhase   = v.findViewById(R.id.tvOverlayPhase)
        tvAllies  = v.findViewById(R.id.tvOverlayAllies)
        tvEnemies = v.findViewById(R.id.tvOverlayEnemies)
        tvTip     = v.findViewById(R.id.tvOverlayTip)
        tvDebug   = v.findViewById(R.id.tvOverlayDebug)

        // ลากได้ทั้งก้อนกลม, หัวแผง และตัวแผง — แตะก้อนกลม = ขยาย, แตะหัวแผง = โชว์/ซ่อนบรรทัด debug
        bubble?.setOnTouchListener(DragListener(onTap = { setCollapsed(false) }))
        panel?.setOnTouchListener(DragListener(onTap = null))
        v.findViewById<View>(R.id.overlayHeader)
            .setOnTouchListener(DragListener(onTap = { toggleDebug() }))
        v.findViewById<View>(R.id.btnCollapse).setOnClickListener { setCollapsed(true) }

        applyCollapsedState()
        positionFromFractions(p)
        try {
            windowManager.addView(v, p)
            publishBounds()
        } catch (e: Exception) {
            Log.e(TAG, "addView failed: ${e.message}")
        }
    }

    private fun removeOverlay() {
        AppState.overlayBounds = null
        root?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            root = null
        }
    }

    // ───────────────────────── ย่อ / ขยาย / ตำแหน่ง ─────────────────────────

    private fun applyCollapsedState() {
        panel?.visibility  = if (collapsed) View.GONE else View.VISIBLE
        bubble?.visibility = if (collapsed) View.VISIBLE else View.GONE
    }

    private fun setCollapsed(value: Boolean) {
        collapsed = value
        prefs.edit().putBoolean("collapsed", value).apply()
        applyCollapsedState()
        keepOnScreen(forcePush = true)
        if (value) saveFractions()
    }

    private fun toggleDebug() {
        val d = tvDebug ?: return
        d.visibility = if (d.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        keepOnScreen(forcePush = true)
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Point {
        val p = Point()
        try {
            getSystemService(DisplayManager::class.java)
                .getDisplay(Display.DEFAULT_DISPLAY).getRealSize(p)
        } catch (e: Exception) {
            val dm = resources.displayMetrics
            p.set(dm.widthPixels, dm.heightPixels)
        }
        return p
    }

    /** วัดขนาด overlay ตอนนี้ (ก้อนกลม หรือ แผง) */
    private fun measureRoot(): Point {
        val v = root ?: return Point(0, 0)
        v.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        return Point(v.measuredWidth, v.measuredHeight)
    }

    private fun clamp(p: WindowManager.LayoutParams, screen: Point) {
        val size = measureRoot()
        p.x = p.x.coerceIn(0, max(0, screen.x - size.x))
        p.y = p.y.coerceIn(0, max(0, screen.y - size.y))
    }

    private fun positionFromFractions(p: WindowManager.LayoutParams) {
        val s = screenSize()
        lastSw = s.x
        lastSh = s.y
        p.x = (fx * s.x).toInt()
        p.y = (fy * s.y).toInt()
        clamp(p, s)
    }

    private fun saveFractions() {
        val p = params ?: return
        val s = screenSize()
        if (s.x <= 0 || s.y <= 0) return
        fx = p.x.toFloat() / s.x
        fy = p.y.toFloat() / s.y
        prefs.edit().putFloat("fx", fx).putFloat("fy", fy).apply()
    }

    private fun pushLayout() {
        val v = root ?: return
        val p = params ?: return
        try {
            windowManager.updateViewLayout(v, p)
        } catch (e: Exception) {
            Log.w(TAG, "updateViewLayout: ${e.message}")
        }
        publishBounds()
    }

    /** บอก ScreenCaptureService ว่า overlay ทับตรงไหน จะได้ไม่อ่านตัวเอง */
    private fun publishBounds() {
        val p = params
        if (root == null || p == null) {
            AppState.overlayBounds = null
            return
        }
        val size = measureRoot()
        AppState.overlayBounds = Rect(p.x, p.y, p.x + size.x, p.y + size.y)
    }

    /** กันหลุดจอ (เช่นหมุนจอ หรือแผงสูงขึ้น) */
    private fun keepOnScreen(forcePush: Boolean = false) {
        val p = params ?: return
        val s = screenSize()
        if (s.x != lastSw || s.y != lastSh) {
            positionFromFractions(p)
            pushLayout()
            return
        }
        val ox = p.x
        val oy = p.y
        clamp(p, s)
        if (forcePush || p.x != ox || p.y != oy) pushLayout() else publishBounds()
    }

    private inner class DragListener(private val onTap: (() -> Unit)?) : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var downX = 0f
        private var downY = 0f
        private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val p = params ?: return false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = p.x
                    startY = p.y
                    downX = e.rawX
                    downY = e.rawY
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) moved = true
                    if (moved) {
                        p.x = startX + dx.toInt()
                        p.y = startY + dy.toInt()
                        clamp(p, screenSize())
                        pushLayout()
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) saveFractions() else onTap?.invoke()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> return true
            }
            return false
        }
    }

    // ───────────────────────── แสดงผล ─────────────────────────

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun setStatusColor(color: Int) {
        (statusDot?.background?.mutate() as? GradientDrawable)?.setColor(color)
        (bubble?.background?.mutate() as? GradientDrawable)?.setStroke(dp(2), color)
    }

    private fun updateOverlay() {
        tvDebug?.text = "$BUILD · ${AppState.lastStatusText}"

        val reading     = AppState.getScreenReading()
        val state       = AppState.getGameState()
        val screenState = reading?.state
        val allies      = reading?.allies?.map { it.name }  ?: emptyList()
        val enemies     = reading?.enemies?.map { it.name } ?: emptyList()

        tvPhase?.text = when (screenState) {
            ScreenState.DRAFT   -> "Ban / Pick"
            ScreenState.IN_GAME -> inGameLine(state)
            else                -> "กำลังอ่านหน้าจอ"
        }
        tvAllies?.text  = if (allies.isEmpty())  "—" else allies.joinToString(", ")
        tvEnemies?.text = if (enemies.isEmpty()) "—" else enemies.joinToString(", ")

        tvTip?.text = when {
            screenState == ScreenState.DRAFT && enemies.isNotEmpty() -> generatePickTip(reading?.allies ?: emptyList(), reading?.enemies ?: emptyList())
            screenState == ScreenState.IN_GAME                       -> generateGameTip(state)
            else                                                     -> "รอข้อมูลจากหน้าจอ"
        }

        val status  = AppState.lastStatusText
        val isError = status.contains("ไม่ได้") || status.contains("พัง")
        setStatusColor(
            when {
                isError                                                  -> colorErr
                screenState == ScreenState.DRAFT ||
                screenState == ScreenState.IN_GAME                       -> colorOk
                else                                                     -> colorIdle
            }
        )
        keepOnScreen()
    }

    private fun inGameLine(state: GameState?): String {
        val phase = when (state?.gamePhase) {
            GamePhase.EARLY_GAME -> "Early"
            GamePhase.MID_GAME   -> "Mid"
            GamePhase.LATE_GAME  -> "Late"
            else                 -> "In game"
        }
        val time = state?.gameTimeText
        val my   = state?.myScore
        val en   = state?.enemyScore
        val score = if (my != null && en != null) "$my–$en" else null
        return listOfNotNull(phase, time, score).joinToString(" · ")
    }

        private fun generatePickTip(allies: List<com.mobaanalyzer.engine.ScreenHero>, enemies: List<com.mobaanalyzer.engine.ScreenHero>): String {
        val db = AppState.heroDb ?: return "เลือกตัวที่ counter ศัตรูได้"
        val allyHeroes  = allies.mapNotNull  { db.match(it.name) }
        val enemyHeroes = enemies.mapNotNull { db.match(it.name) }
        val pool        = db.heroes
        val advisor     = PickAdvisor()
        val recs        = advisor.recommend(allyHeroes, enemyHeroes, pool, top = 3)
        if (recs.isEmpty()) return "ยังวิเคราะห์ไม่ได้ — รอศัตรูเลือกเพิ่ม"
        return buildString {
            appendLine("💡 แนะนำ:")
            for (r in recs) {
                append("• ${r.hero.name} (${r.scores.label})")
                if (r.reasons.isNotEmpty()) append(" — ${r.reasons.take(2).joinToString(", ")}")
                appendLine()
            }
        }.trim()
    }

    private fun generateGameTip(state: GameState?): String {
        if (state == null) return "รอข้อมูลจากหน้าจอ"
        val diff = (state.myScore ?: 0) - (state.enemyScore ?: 0)
        return when (state.gamePhase) {
            GamePhase.EARLY_GAME -> if (diff >= 2) "ได้เปรียบ — push lane" else "Farm + Jungle"
            GamePhase.MID_GAME   -> if (diff >= 3) "ได้เปรียบ — ตีหอ" else "Contest Objective"
            GamePhase.LATE_GAME  -> if (diff >= 2) "รวมทีม push" else "Def รอ mistake"
            else -> "รอข้อมูลจากหน้าจอ"
        }
    }

    // ───────────────────────── Notification ─────────────────────────

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
