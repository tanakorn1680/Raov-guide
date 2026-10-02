package com.mobaanalyzer.ocr

import android.util.Log
import com.mobaanalyzer.model.GamePhase
import com.mobaanalyzer.model.GameState
import com.mobaanalyzer.model.HeroInfo
import com.mobaanalyzer.model.TowerStatus
import java.util.regex.Pattern

/**
 * GameStateParser
 *
 * แปลง raw OCR text → GameState
 *
 * หลักการ: ใช้ Regex ดึงข้อมูลแต่ละส่วน ถ้า parse ไม่ได้ → null (ไม่ crash)
 *
 * ROV UI layout (landscape):
 *  ┌─────────────────────────────────────────────────────────────┐
 *  │ [SCORE L]   [TIMER top-center]   [SCORE R]                 │
 *  │  HP bars ฝั่งเรา (ล่างซ้าย)      HP bars ศัตรู (ล่างขวา)  │
 *  │                [minimap ล่างกลาง]                           │
 *  └─────────────────────────────────────────────────────────────┘
 */
class GameStateParser {

    companion object {
        private const val TAG = "GameStateParser"

        // ── Timer ─────────────────────────────────────────────────────────────
        // รูปแบบ "12:34" หรือ "1:23"
        private val TIMER_PATTERN = Pattern.compile("""(\d{1,2}):(\d{2})""")

        // ── Score ─────────────────────────────────────────────────────────────
        // รูปแบบ "5 - 3" หรือ "5-3" หรือ "05 03" (OCR อาจอ่านต่างกัน)
        private val SCORE_PATTERN = Pattern.compile("""(\d+)\s*[-–]\s*(\d+)""")

        // ── KDA ──────────────────────────────────────────────────────────────
        // รูปแบบ "3/1/7"
        private val KDA_PATTERN = Pattern.compile("""(\d+)/(\d+)/(\d+)""")

        // ── HP ───────────────────────────────────────────────────────────────
        // รูปแบบ "3520/4800" หรือ "3520 / 4800"
        private val HP_PATTERN = Pattern.compile("""(\d{3,5})\s*/\s*(\d{3,5})""")

        // ── Gold ─────────────────────────────────────────────────────────────
        // รูปแบบ "1234" หรือ "1.2K" (ROV ใช้ตัวย่อ K)
        private val GOLD_PATTERN = Pattern.compile("""(\d+\.?\d*)[Kk]?\s*(?:gold|Gold)?""")

        // ── ชื่อฮีโร่ที่รู้จักใน ROV (ขยายได้เรื่อยๆ) ────────────────────────
        val ROV_HEROES = listOf(
            // Tank
            "Grakk", "Ormarr", "Maloch", "Thane", "Zip", "Cresht", "Arthur",
            // Warrior
            "Nakroth", "Zephys", "Murad", "Qi", "Omen", "Florentino", "Valhein",
            // Mage
            "Liliana", "Kahlii", "Ignis", "Tulen", "Natalya", "Sinestrea",
            // Marksman
            "Yorn", "Laville", "Elsu", "Capheny", "Tel'Annas", "Violet",
            // Support
            "Alice", "Aleister", "Lumburr", "Annette", "Chaugnar",
            // Assassin
            "Butterfly", "Kriknak", "Hayate", "Wiro", "Zill", "Keera",
            // Fighter
            "Lindis", "Superman", "Batman", "Flash", "Wonder Woman"
        )
    }

    /**
     * entry point หลัก — รับ raw OCR text คืน GameState
     */
    fun parse(rawText: String): GameState {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }

        val gameTimeSec = parseTimer(rawText)
        val (myScore, enemyScore) = parseScore(rawText)
        val myKDA = parseKDA(rawText)
        val heroesFound = parseHeroes(rawText)
        val hpValues = parseHPBars(rawText)

        val phase = gameTimeSec?.let { GamePhase.fromSeconds(it) } ?: GamePhase.UNKNOWN

        Log.d(TAG, "parsed → time=${gameTimeSec}s phase=$phase score=$myScore-$enemyScore heroes=${heroesFound.size}")

        return GameState(
            gameTimeSeconds = gameTimeSec,
            gameTimeText    = gameTimeSec?.let { formatTime(it) },
            myScore         = myScore,
            enemyScore      = enemyScore,
            myKDA           = myKDA,
            myHeroes        = heroesFound.take(5),
            gamePhase       = phase,
            rawOcrText      = rawText
        )
    }

    // ── Timer ─────────────────────────────────────────────────────────────────

    private fun parseTimer(text: String): Int? {
        val m = TIMER_PATTERN.matcher(text)
        // หา match ที่ดูเหมือนเวลาเกม (ไม่เกิน 60:00)
        while (m.find()) {
            val min = m.group(1)?.toIntOrNull() ?: continue
            val sec = m.group(2)?.toIntOrNull() ?: continue
            if (sec < 60 && min < 60) {
                return min * 60 + sec
            }
        }
        return null
    }

    private fun formatTime(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return "%d:%02d".format(m, s)
    }

    // ── Score ─────────────────────────────────────────────────────────────────

    private fun parseScore(text: String): Pair<Int?, Int?> {
        val m = SCORE_PATTERN.matcher(text)
        if (m.find()) {
            val left  = m.group(1)?.toIntOrNull()
            val right = m.group(2)?.toIntOrNull()
            return Pair(left, right)
        }
        return Pair(null, null)
    }

    // ── KDA ──────────────────────────────────────────────────────────────────

    private fun parseKDA(text: String): String? {
        val m = KDA_PATTERN.matcher(text)
        if (m.find()) return m.group(0)
        return null
    }

    // ── ฮีโร่ ─────────────────────────────────────────────────────────────────

    private fun parseHeroes(text: String): List<HeroInfo> {
        val found = mutableListOf<HeroInfo>()
        val upperText = text.uppercase()

        for (hero in ROV_HEROES) {
            if (upperText.contains(hero.uppercase())) {
                found.add(HeroInfo(name = hero))
            }
        }
        return found
    }

    // ── HP Bars ───────────────────────────────────────────────────────────────

    private fun parseHPBars(text: String): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        val m = HP_PATTERN.matcher(text)
        while (m.find()) {
            val current = m.group(1)?.toIntOrNull() ?: continue
            val max     = m.group(2)?.toIntOrNull() ?: continue
            if (max > 0 && current <= max) {
                result.add(Pair(current, max))
            }
        }
        return result
    }
}
