package com.mobaanalyzer.data

import com.mobaanalyzer.engine.ScreenReading
import com.mobaanalyzer.model.GameState

object AppState {

    const val ACTION_GAME_STATE_UPDATED = "com.mobaanalyzer.ACTION_GAME_STATE_UPDATED"
    const val MAX_STATE_HISTORY = 20

    @Volatile private var currentState: GameState? = null
    @Volatile private var currentReading: ScreenReading? = null
    private val stateHistory = mutableListOf<GameState>()

    @Synchronized
    fun updateGameState(state: GameState) {
        currentState = state
        stateHistory.add(0, state)
        if (stateHistory.size > MAX_STATE_HISTORY) stateHistory.removeAt(stateHistory.size - 1)
    }

    @Synchronized
    fun updateScreenReading(reading: ScreenReading) {
        currentReading = reading
    }

    @Synchronized fun getGameState(): GameState? = currentState
    @Synchronized fun getScreenReading(): ScreenReading? = currentReading
    @Synchronized fun getStateHistory(): List<GameState> = stateHistory.toList()

    @Volatile var lastStatusText: String = "ยังไม่เริ่ม"
        private set

    /** พื้นที่ที่ overlay ของแอปนี้ทับอยู่บนจอ (พิกเซล) — ใช้กัน OCR อ่าน overlay ตัวเอง */
    @Volatile var overlayBounds: android.graphics.Rect? = null

    @Synchronized
    fun updateStatus(text: String) { lastStatusText = text }
}
