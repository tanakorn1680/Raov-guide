package com.mobaanalyzer

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.mobaanalyzer.data.AppState
import com.mobaanalyzer.model.GamePhase
import com.mobaanalyzer.service.KeepAliveService
import com.mobaanalyzer.ui.HeroDbActivity
import com.mobaanalyzer.service.OverlayService
import com.mobaanalyzer.service.ScreenCaptureService

/**
 * MainActivity
 *
 * Permission flow:
 * 1. SYSTEM_ALERT_WINDOW (Overlay) — Settings.ACTION_MANAGE_OVERLAY_PERMISSION
 * 2. POST_NOTIFICATIONS (Android 13+)
 * 3. MediaProjection — createScreenCaptureIntent()
 *
 * หลังได้ permission ครบ:
 * → start KeepAliveService
 * → start OverlayService
 * → start ScreenCaptureService (พร้อม MediaProjection token)
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var tvStatus: TextView
    private lateinit var tvGameInfo: TextView
    private lateinit var btnStartStop: Button
    private lateinit var btnOverlayPerm: Button
    private lateinit var cardPermWarning: View

    private var isRunning = false

    // ── Broadcast receiver — update UI เมื่อ GameState เปลี่ยน ────────────────
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateGameInfoUI()
        }
    }

    // ── MediaProjection launcher ──────────────────────────────────────────────
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            Log.d(TAG, "MediaProjection granted")
            ScreenCaptureService.start(this, result.resultCode, result.data!!)
            isRunning = true
            updateStartStopButton()
            tvStatus.text = "✅ กำลังวิเคราะห์หน้าจอ"
        } else {
            Log.w(TAG, "MediaProjection denied")
            tvStatus.text = "❌ ไม่ได้รับอนุญาต Screen Capture"
        }
    }

    // ── Notification permission launcher ─────────────────────────────────────
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* ผล handle ใน onResume */ }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus      = findViewById(R.id.tvStatus)
        tvGameInfo    = findViewById(R.id.tvGameInfo)
        btnStartStop  = findViewById(R.id.btnStartStop)
        btnOverlayPerm = findViewById(R.id.btnOverlayPerm)
        cardPermWarning = findViewById(R.id.cardPermWarning)

        btnStartStop.setOnClickListener {
            if (isRunning) stopAll() else startAll()
        }

        findViewById<Button>(R.id.btnHeroDb).setOnClickListener {
            startActivity(Intent(this, HeroDbActivity::class.java))
        }

        btnOverlayPerm.setOnClickListener {
            openOverlayPermissionSettings()
        }

        // เริ่ม KeepAlive ทันทีที่เปิดแอพ
        startKeepAlive()
        requestNotifPermission()
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                stateReceiver,
                IntentFilter(AppState.ACTION_GAME_STATE_UPDATED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stateReceiver, IntentFilter(AppState.ACTION_GAME_STATE_UPDATED))
        }
        refreshPermissionUI()
        updateGameInfoUI()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(stateReceiver)
    }

    // =========================================================================
    // Start / Stop
    // =========================================================================

    private fun startAll() {
        if (!hasOverlayPermission()) {
            tvStatus.text = "⚠️ ต้องอนุญาต Overlay ก่อน"
            openOverlayPermissionSettings()
            return
        }
        // เริ่ม OverlayService ก่อน
        OverlayService.start(this)

        // ขอ MediaProjection → จะ start ScreenCaptureService ใน callback
        val pm = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(pm.createScreenCaptureIntent())
    }

    private fun stopAll() {
        ScreenCaptureService.stop(this)
        OverlayService.stop(this)
        isRunning = false
        updateStartStopButton()
        tvStatus.text = "⏹ หยุดแล้ว"
        tvGameInfo.text = ""
    }

    // =========================================================================
    // UI
    // =========================================================================

    private fun updateStartStopButton() {
        btnStartStop.text = if (isRunning) "⏹ หยุด" else "▶ เริ่มวิเคราะห์"
    }

    private fun updateGameInfoUI() {
        val state = AppState.getGameState() ?: run {
            tvGameInfo.text = "ยังไม่มีข้อมูล"
            return
        }

        val sb = StringBuilder()
        sb.appendLine("⏱ เวลา: ${state.gameTimeText ?: "--:--"}")
        sb.appendLine("⚔️ สกอร์: ${state.myScore ?: "-"} - ${state.enemyScore ?: "-"}")
        sb.appendLine("📍 Phase: ${state.gamePhase.name}")
        if (state.myHeroes.isNotEmpty()) {
            sb.appendLine("🦸 ฮีโร่: ${state.myHeroes.joinToString { it.name }}")
        }
        if (state.myKDA != null) {
            sb.appendLine("📊 KDA: ${state.myKDA}")
        }
        tvGameInfo.text = sb.toString().trimEnd()
    }

    private fun refreshPermissionUI() {
        val hasOverlay = hasOverlayPermission()
        cardPermWarning.visibility = if (hasOverlay) View.GONE else View.VISIBLE
    }

    // =========================================================================
    // Permissions
    // =========================================================================

    private fun hasOverlayPermission(): Boolean =
        Settings.canDrawOverlays(this)

    private fun openOverlayPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED) return
        notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // =========================================================================
    // Service
    // =========================================================================

    private fun startKeepAlive() {
        val intent = Intent(this, KeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }
}
