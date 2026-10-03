package com.mobaanalyzer.engine

import com.mobaanalyzer.data.Hero
import com.mobaanalyzer.data.HeroDatabase

/** One OCR text line; cx/cy = centre as a fraction of the screen (0..1), so it works at any resolution. */
data class OcrLine(val text: String, val cx: Float, val cy: Float)

data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
}

enum class ScreenState { DRAFT, IN_GAME, OTHER }

data class ScreenReading(
    val state: ScreenState,
    val allies: List<Hero> = emptyList(),   // DRAFT: left column
    val enemies: List<Hero> = emptyList(),  // DRAFT: right column
    val grid: List<Hero> = emptyList(),     // DRAFT: heroes visible in the pick grid
    val seconds: Int? = null,               // IN_GAME: raw OCR clock (feed GameClock)
    val allyKills: Int? = null,
    val enemyKills: Int? = null
)

/**
 * Turns OCR lines into "which screen is this + who is on which side".
 * Side is decided by position, not by reading colours: pick screen has allies on the left, enemies on the right.
 */
class ScreenAnalyzer(private val db: HeroDatabase) {

    private val timerRe = Regex("(\\d{1,2})\\s*[:;.]\\s*(\\d{2})")
    private val vsRe = Regex("(\\d+)\\s*vs\\s*(\\d+)", RegexOption.IGNORE_CASE)
    private val digits34 = Regex("^\\d{3,4}$")

    /** [ignore] = screen areas covered by this app's own overlay, so it never reads itself. */
    fun analyze(lines: List<OcrLine>, ignore: List<NormRect> = emptyList()): ScreenReading {
        val usable = lines.filter { l -> ignore.none { it.contains(l.cx, l.cy) } }
        return readHud(usable) ?: readDraft(usable)
    }

    private fun readHud(lines: List<OcrLine>): ScreenReading? {
        val hud = lines.filter { it.cx < 0.25f && it.cy < 0.10f }
        if (hud.isEmpty()) return null

        var seconds: Int? = null
        for (l in hud.sortedBy { it.cx }) {
            val m = timerRe.find(l.text.replace('O', '0').replace('o', '0')) ?: continue
            val mm = m.groupValues[1].toInt()
            val ss = m.groupValues[2].toInt()
            if (mm <= 59 && ss <= 59) { seconds = mm * 60 + ss; break }
        }
        if (seconds == null) {
            // OCR sometimes drops the colon: "0013"
            val l = hud.firstOrNull { it.cx in 0.13f..0.21f && it.cy < 0.07f && digits34.matches(it.text.trim()) }
            if (l != null) {
                val d = l.text.trim().padStart(4, '0')
                val ss = d.substring(2).toInt()
                if (ss <= 59) seconds = d.substring(0, 2).toInt() * 60 + ss
            }
        }
        if (seconds == null) return null

        var ally: Int? = null
        var enemy: Int? = null
        val oneLine = hud.firstNotNullOfOrNull { vsRe.find(it.text) }
        if (oneLine != null) {
            ally = oneLine.groupValues[1].toIntOrNull()
            enemy = oneLine.groupValues[2].toIntOrNull()
        } else {
            val vs = hud.firstOrNull { it.text.trim().equals("vs", true) }
            if (vs != null) {
                val near = hud.filter { kotlin.math.abs(it.cy - vs.cy) < 0.03f && it.text.trim().all { c -> c.isDigit() } }
                ally = near.filter { it.cx < vs.cx }.maxByOrNull { it.cx }?.text?.trim()?.toIntOrNull()
                enemy = near.filter { it.cx > vs.cx }.minByOrNull { it.cx }?.text?.trim()?.toIntOrNull()
            }
        }
        return ScreenReading(ScreenState.IN_GAME, seconds = seconds, allyKills = ally, enemyKills = enemy)
    }

    private fun readDraft(lines: List<OcrLine>): ScreenReading {
        val allies = heroesIn(lines.filter { it.cx < 0.30f && it.cy in 0.12f..0.88f })
        val enemies = heroesIn(lines.filter { it.cx > 0.72f && it.cy in 0.12f..0.88f })
        val grid = heroesIn(lines.filter { it.cx in 0.30f..0.70f && it.cy in 0.20f..0.85f })
        val looksLikeDraft = grid.size >= 3 || (allies.size + enemies.size) >= 2
        if (!looksLikeDraft) return ScreenReading(ScreenState.OTHER)
        return ScreenReading(
            ScreenState.DRAFT,
            allies = allies,
            enemies = enemies.filter { it !in allies },
            grid = grid
        )
    }

    private fun heroesIn(ls: List<OcrLine>): List<Hero> =
        db.findIn(ls.sortedBy { it.cy }.map { it.text })
}
