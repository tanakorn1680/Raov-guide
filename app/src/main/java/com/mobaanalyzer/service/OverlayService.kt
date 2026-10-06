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
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.mobaanalyzer.MainActivity
import com.mobaanalyzer.R
import com.mobaanalyzer.data.AppState
import com.mobaanalyzer.data.Hero
import com.mobaanalyzer.engine.DraftPhase
import com.mobaanalyzer.engine.PickAdvisor
import com.mobaanalyzer.engine.Recommendation
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

    // แถวคำแนะนำฮีโร่ (สร้างครั้งเดียว แล้วแค่เติมข้อมูล)
    private var recContainer: LinearLayout? = null
    private val recRows = ArrayList<RecRow>()
    private val advisor = PickAdvisor()

    private class RecRow(
        val root: View,
        val rank: TextView,
        val name: TextView,
        val score: TextView,
        val fill: View,
        val rest: View,
        val detail: TextView,
        val caution: TextView
    )

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
        recContainer = v.findViewById(R.id.overlayRecContainer)
        val inflater = LayoutInflater.from(this)
        repeat(3) {
            val row = inflater.inflate(R.layout.item_rec, recContainer, false)
            recContainer?.addView(row)
            recRows.add(
                RecRow(
                    row,
                    row.findViewById(R.id.tvRecRank),
                    row.findViewById(R.id.tvRecName),
                    row.findViewById(R.id.tvRecScore),
                    row.findViewById(R.id.recBarFill),
                    row.findViewById(R.id.recBarRest),
                    row.findViewById(R.id.tvRecDetail),
                    row.findViewById(R.id.tvRecCaution)
                )
            )
        }

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

        // คำแนะนำฮีโร่ (เฉพาะหน้า Draft ที่เห็นศัตรูอย่างน้อย 1 ตัว)
        val allyHeroes:  List<Hero> = reading?.allies  ?: emptyList()
        val enemyHeroes: List<Hero> = reading?.enemies ?: emptyList()
        var recs: List<Recommendation> = emptyList()
        var draftLine = "Ban / Pick"
        if (screenState == ScreenState.DRAFT && enemies.isNotEmpty()) {
            val db = AppState.heroDb
            if (db != null) {
                recs = advisor.recommend(allyHeroes, enemyHeroes, db.heroes, top = 3)
                if (recs.isNotEmpty()) {
                    val phase = advisor.draftPhase(allyHeroes, enemyHeroes)
                    draftLine = "${phaseName(phase)} · ความมั่นใจ ${recs.first().scores.confidence}"
                }
            }
        }

        tvPhase?.text = when (screenState) {
            ScreenState.DRAFT   -> draftLine
            ScreenState.IN_GAME -> inGameLine(state)
            else                -> "กำลังอ่านหน้าจอ"
        }
        tvAllies?.text  = if (allies.isEmpty())  "—" else allies.joinToString(", ")
        tvEnemies?.text = if (enemies.isEmpty()) "—" else enemies.joinToString(", ")

        val message: String? = when {
            recs.isNotEmpty()                                        -> null
            screenState == ScreenState.DRAFT && enemies.isNotEmpty() -> "รอศัตรูเลือกเพิ่มเพื่อวิเคราะห์"
            screenState == ScreenState.IN_GAME                       -> generateGameTip(state)
            else                                                     -> "รอข้อมูลจากหน้าจอ"
        }
        tvTip?.visibility = if (message == null) View.GONE else View.VISIBLE
        tvTip?.text = message ?: ""
        recContainer?.visibility = if (recs.isEmpty()) View.GONE else View.VISIBLE
        for ((i, row) in recRows.withIndex()) bindRecRow(row, i, recs.getOrNull(i))

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

    private fun phaseName(p: DraftPhase): String = when (p) {
        DraftPhase.BLIND -> "Blind Pick"
        DraftPhase.EARLY -> "Early Draft"
        DraftPhase.MID   -> "Mid Draft"
        DraftPhase.LATE  -> "Late Draft"
        DraftPhase.FINAL -> "Final Pick"
    }

    /** รวมเหตุผลสองข้อที่ขึ้นต้นด้วยคำเดียวกัน เช่น "แก้ทาง A" + "แก้ทาง B" → "แก้ทาง A, B" */
    private fun compactReasons(reasons: List<String>): String {
        val r = reasons.take(2)
        if (r.size < 2) return r.joinToString("")
        val head = r[0].substringBefore(' ', "")
        if (head.isNotEmpty() && r[1].startsWith("$head ")) {
            return r[0] + ", " + r[1].substring(head.length + 1)
        }
        return r.joinToString(", ")
    }

    private fun setWeight(v: View, weight: Float) {
        val lp = v.layoutParams as? LinearLayout.LayoutParams ?: return
        if (lp.weight != weight) {
            lp.weight = weight
            v.layoutParams = lp
        }
    }

    /** เติมข้อมูลหนึ่งแถว: ลำดับ ชื่อ แถบคะแนน คะแนน เหตุผล (และข้อควรระวังถ้ามี) */
    private fun bindRecRow(row: RecRow, index: Int, rec: Recommendation?) {
        if (rec == null) {
            row.root.visibility = View.GONE
            return
        }
        row.root.visibility = View.VISIBLE
        val score = rec.scores.finalScore.coerceIn(0, 100)
        val top = index == 0

        row.rank.text  = "${index + 1}"
        row.name.text  = rec.hero.name
        row.score.text = score.toString()
        row.rank.setTextColor(if (top) colorOk else colorIdle)
        row.fill.setBackgroundColor(if (top) colorOk else colorIdle)
        setWeight(row.fill, score.toFloat())
        setWeight(row.rest, (100 - score).toFloat())

        val label   = rec.scores.label.removePrefix("Best ")
        val reasons = compactReasons(rec.reasons)
        row.detail.text = if (reasons.isEmpty()) label else "$label · $reasons"

        val caution = rec.avoidReasons.firstOrNull()
        row.caution.visibility = if (caution == null) View.GONE else View.VISIBLE
        row.caution.text = if (caution == null) "" else "ระวัง · $caution"
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
