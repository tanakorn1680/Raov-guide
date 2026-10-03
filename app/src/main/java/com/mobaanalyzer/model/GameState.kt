package com.mobaanalyzer.model

/**
 * GameState — snapshot ของสถานะเกม ณ เวลาที่อ่านหน้าจอ
 *
 * ทุก field เป็น nullable เพราะ OCR อาจอ่านไม่ได้ทุก field ในแต่ละ frame
 */
data class GameState(
    val timestamp: Long = System.currentTimeMillis(),

    // ── เวลาเกม ──────────────────────────────────────────────────────────────
    val gameTimeSeconds: Int? = null,       // เช่น 754 = 12:34
    val gameTimeText: String? = null,       // ข้อความดิบ เช่น "12:34"

    // ── ฮีโร่ฝั่งเรา (สูงสุด 5 คน) ──────────────────────────────────────────
    val myHeroes: List<HeroInfo> = emptyList(),

    // ── ฮีโร่ฝั่งศัตรู ───────────────────────────────────────────────────────
    val enemyHeroes: List<HeroInfo> = emptyList(),

    // ── สกอร์ ─────────────────────────────────────────────────────────────────
    val myScore: Int? = null,               // kill ฝั่งเรา
    val enemyScore: Int? = null,            // kill ฝั่งศัตรู
    val myKDA: String? = null,              // "K/D/A" ของตัวเรา เช่น "3/1/7"

    // ── Gold ──────────────────────────────────────────────────────────────────
    val myGold: Int? = null,
    val goldAdvantage: Int? = null,         // + เราได้เปรียบ / - เราเสียเปรียบ

    // ── แมพ / โซน ─────────────────────────────────────────────────────────────
    val towerStatus: TowerStatus = TowerStatus(),

    // ── Phase เกม ────────────────────────────────────────────────────────────
    val gamePhase: GamePhase = GamePhase.UNKNOWN,

    // ── Raw OCR text (debug) ─────────────────────────────────────────────────
    val rawOcrText: String = ""
)

/**
 * ข้อมูลฮีโร่แต่ละตัว
 */
data class HeroInfo(
    val name: String = "",
    val hpPercent: Float? = null,       // 0.0 - 1.0
    val hpText: String? = null,         // เช่น "3520/4800"
    val mpPercent: Float? = null,
    val isAlive: Boolean = true,
    val level: Int? = null,
    val position: MapPosition? = null   // ตำแหน่งบน minimap
)

/**
 * ตำแหน่งบน minimap (0.0 = ซ้าย/บน, 1.0 = ขวา/ล่าง)
 */
data class MapPosition(
    val x: Float,   // 0.0–1.0
    val y: Float    // 0.0–1.0
)

/**
 * สถานะหอคอย
 */
data class TowerStatus(
    val myTowersDown: Int = 0,
    val enemyTowersDown: Int = 0
)

/**
 * Phase ของเกม — ใช้คาดเดาจากเวลาเกม
 */
enum class GamePhase {
    UNKNOWN,
    EARLY_GAME,     // 0–8 นาที
    MID_GAME,       // 8–18 นาที
    LATE_GAME;      // 18+ นาที

    companion object {
        fun fromSeconds(seconds: Int): GamePhase = when {
            seconds < 480  -> EARLY_GAME
            seconds < 1080 -> MID_GAME
            else           -> LATE_GAME
        }
    }
}
