package com.mobaanalyzer.engine

import com.mobaanalyzer.data.DamageType
import com.mobaanalyzer.data.Hero
import com.mobaanalyzer.data.Role

data class Recommendation(val hero: Hero, val score: Int, val reasons: List<String>)

/**
 * Offline pick advisor. Works with any number of known picks (0..9): every rule only fires when
 * there is evidence for it, so call it on every frame and the advice sharpens as picks come in.
 * Scores come from hero tags (roles, damage type, cc, mobile...) plus optional per-hero
 * counters / weakTo lists in heroes.json.
 */
class PickAdvisor {

    /** How much the advice can be trusted, from how many picks are known and have data. */
    fun confidence(allies: List<Hero>, enemies: List<Hero>): String {
        val n = allies.count { it.hasData } + enemies.count { it.hasData }
        return when {
            n <= 2 -> "ต่ำ"
            n <= 6 -> "กลาง"
            else -> "สูง"
        }
    }

    /**
     * [allies] = teammates already shown (not you). [pool] = heroes you could pick
     * (all heroes, or the ones seen in your pick grid). [myLanes] optional, e.g. setOf("JUNGLE").
     */
    fun recommend(
        allies: List<Hero>,
        enemies: List<Hero>,
        pool: List<Hero>,
        myLanes: Set<String> = emptySet(),
        top: Int = 3
    ): List<Recommendation> {
        val taken = (allies + enemies).map { it.id }.toSet()

        val aTanky = allies.count { it.has("tanky") }
        val aCc = allies.count { it.has("cc") }
        val aMagic = allies.count { it.damage == DamageType.MAGIC }
        val aPhys = allies.count { it.damage == DamageType.PHYSICAL }
        val aCarry = allies.count { it.role == Role.CARRY }

        val eAssassin = enemies.count { it.role == Role.ASSASSIN }
        val eMobile = enemies.count { it.has("mobile") }
        val eCarry = enemies.count { it.role == Role.CARRY }
        val eMage = enemies.count { it.role == Role.MAGE }
        val eTanky = enemies.count { it.has("tanky") }
        val ePoke = enemies.count { it.has("poke") }
        val eKnown = enemies.count { it.hasData }

        val out = ArrayList<Recommendation>()
        for (c in pool) {
            if (c.id in taken || !c.hasData) continue
            var score = 0
            val why = ArrayList<String>()
            fun add(points: Int, text: String) { score += points; why.add(text) }

            // 1) direct matchups from the data file
            for (e in enemies) {
                if (e.id in c.counters || c.id in e.weakTo) add(4, "แก้ทาง ${e.name}")
                if (e.id in c.weakTo || c.id in e.counters) add(-4, "โดน ${e.name} แก้ทาง")
            }

            // 2) what our team is missing
            if (allies.size >= 2) {
                if (aTanky == 0 && c.has("tanky")) add(3, "ทีมเรายังขาดตัวถึก")
                if (aMagic == 0 && c.damage == DamageType.MAGIC) add(3, "ทีมเรายังไม่มีดาเมจเวท")
                if (aPhys == 0 && c.damage == DamageType.PHYSICAL) add(3, "ทีมเรายังไม่มีดาเมจกายภาพ")
                if (aCc == 0 && c.has("cc")) add(2, "ทีมเราขาดตัวคุมฝูง")
            }
            if (c.role == Role.CARRY && aCarry >= 1) add(-4, "ทีมเรามีแครี่แล้ว")
            else if (c.role != Role.CARRY && allies.count { it.role == c.role } >= 2) add(-2, "บทบาทซ้ำกับเพื่อน")

            // 3) what the enemy team looks like
            if (eAssassin >= 2) {
                if (c.has("tanky") || c.has("cc")) add(2, "ศัตรูมีนักฆ่าหลายตัว ต้องมีตัวทนหรือคุมฝูง")
                if (c.role == Role.CARRY) add(-2, "แครี่เปราะ ศัตรูมีนักฆ่าหลายตัว")
            }
            if (eMobile >= 2 && c.has("cc")) add(2, "ศัตรูเคลื่อนที่ไว มีคุมฝูงจะจับตัวได้")
            if (eCarry >= 1 && c.has("burst") && c.has("mobile")) add(2, "บุกจับแครี่ศัตรูได้")
            if (eMage >= 2 && c.role == Role.ASSASSIN) add(1, "นักฆ่าเข้าจับจอมเวทที่เปราะ")
            if (eKnown >= 3 && eTanky == 0 && c.has("burst")) add(1, "ศัตรูไม่มีตัวถึก เบิร์สต์ได้ผล")
            if (eTanky >= 2 && c.role == Role.ASSASSIN) add(-1, "ศัตรูตัวถึกเยอะ นักฆ่าเก็บยาก")
            if (ePoke >= 2 && c.has("heal")) add(1, "ศัตรูสายรุมจิก มีฮีลช่วยได้")

            // 4) lane you can play
            if (myLanes.isNotEmpty()) {
                if (c.lanes.any { it in myLanes }) add(2, "เล่นเลนที่คุณเล่นได้")
                else add(-3, "ไม่ตรงเลนที่คุณเล่น")
            }

            out.add(Recommendation(c, score, why))
        }
        return out
            .filter { it.score > 0 }
            .sortedWith(compareByDescending<Recommendation> { it.score }.thenBy { it.hero.name })
            .take(top)
    }
}
