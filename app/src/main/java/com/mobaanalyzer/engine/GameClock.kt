package com.mobaanalyzer.engine

/**
 * OCR reads the match clock unreliably (a digit is sometimes wrong), so we don't trust single frames.
 * The clock runs on the phone's own timer and OCR only corrects it:
 *  - a reading within 3 s of our estimate refines it
 *  - a reading far from it is accepted only after 3 mutually consistent readings
 */
class GameClock(private val now: () -> Long = { System.currentTimeMillis() }) {
    private var anchorGame = -1
    private var anchorReal = 0L
    private var pendingValue = -1
    private var pendingReal = 0L
    private var pendingCount = 0

    fun estimate(): Int? = if (anchorGame < 0) null else anchorGame + ((now() - anchorReal) / 1000L).toInt()

    /** Feed the OCR reading for this frame (or null if none). Returns the best current estimate. */
    fun update(reading: Int?): Int? {
        if (reading != null) {
            val est = estimate()
            if (est == null || kotlin.math.abs(reading - est) <= 3) {
                anchor(reading)
                pendingCount = 0
            } else {
                val projected = pendingValue + ((now() - pendingReal) / 1000L).toInt()
                pendingCount = if (pendingCount > 0 && kotlin.math.abs(reading - projected) <= 3) pendingCount + 1 else 1
                pendingValue = reading
                pendingReal = now()
                if (pendingCount >= 3) {
                    anchor(reading)
                    pendingCount = 0
                }
            }
        }
        return estimate()
    }

    /** Call when the match is over / the screen is no longer in-game for a while. */
    fun reset() {
        anchorGame = -1
        pendingCount = 0
    }

    private fun anchor(seconds: Int) {
        anchorGame = seconds
        anchorReal = now()
    }
}
