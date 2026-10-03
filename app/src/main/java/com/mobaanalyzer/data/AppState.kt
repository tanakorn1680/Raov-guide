package com.mobaanalyzer.data

import com.mobaanalyzer.model.GameState

/**
 * AppState — shared state ระหว่าง services และ UI
 *
 * Thread-safe ด้วย @Synchronized
 * ยืม pattern จาก bankconfirm AppState
 */
object AppState {

    const val ACTION_GAME_STATE_UPDATED = "com.mobaanalyzer.ACTION_GAME_STATE_UPDATED"
    const val MAX_STATE_HISTORY = 20

    // ── GameState history ─────────────────────────────────────────────────────

    @Volatile private var currentState: GameState? = null
    private val stateHistory = mutableListOf<GameState>()

    @Synchronized
    fun updateGameState(state: GameState) {
        currentState = state
        stateHistory.add(0, state)
        if (stateHistory.size > MAX_STATE_HISTORY) stateHistory.removeAt(stateHistory.size - 1)
    }

    @Synchronized
    fun getGameState(): GameState? = currentState

    @Synchronized
    fun getStateHistory(): List<GameState> = stateHistory.toList()

    // ── Status text สำหรับ debug UI ───────────────────────────────────────────

    @Volatile var lastStatusText: String = "ยังไม่เริ่ม"
        private set

    @Synchronized
    fun updateStatus(text: String) { lastStatusText = text }
}
