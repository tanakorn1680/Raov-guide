package com.mobaanalyzer.engine

import com.mobaanalyzer.data.DamageType
import com.mobaanalyzer.data.Hero
import com.mobaanalyzer.data.Role

/**
 * Draft phase based on how many picks are already known.
 * Weights shift as more information becomes available.
 */
enum class DraftPhase {
    BLIND,       // 0–1 known heroes
    EARLY,       // 2–4
    MID,         // 5–7
    LATE,        // 8–9
    FINAL        // 10 (all picks known)
}

data class HeroScores(
    val counterScore: Int,    // แก้ศัตรูปัจจุบัน
    val synergyScore: Int,    // เข้ากับทีมเรา
    val compositionScore: Int,// เติมสิ่งที่ทีมขาด
    val flexibilityScore: Int,// เลือกได้โดยไม่เสียเปรียบมากถ้าศัตรูตอบกลับ
    val laneScore: Int,       // เลนที่เล่นได้
    val finalScore: Int,
    val label: String         // "Best Overall" / "Best Counter" / "Best Synergy"
)

data class Recommendation(
    val hero: Hero,
    val scores: HeroScores,
    val reasons: List<String>,
    val avoidReasons: List<String> = emptyList(),
    val confidence: String = "กลาง"
)

/**
 * Dynamic pick advisor — analyzes every state from blind pick through final pick.
 *
 * Weights adjust automatically based on how many picks are known (DraftPhase).
 * Returns up to [top] recommendations split into Best Overall / Best Counter / Best Synergy.
 */
class PickAdvisor {

    // ─── Phase detection ────────────────────────────────────────────────────

    fun draftPhase(allies: List<Hero>, enemies: List<Hero>): DraftPhase {
        val known = allies.count { it.hasData } + enemies.count { it.hasData }
        return when {
            known <= 1  -> DraftPhase.BLIND
            known <= 4  -> DraftPhase.EARLY
            known <= 7  -> DraftPhase.MID
            known <= 9  -> DraftPhase.LATE
            else        -> DraftPhase.FINAL
        }
    }

    fun confidence(allies: List<Hero>, enemies: List<Hero>): String {
        val remaining = 2 // unknown enemy slots (rough estimate)
        val known = allies.count { it.hasData } + enemies.count { it.hasData }
        return when {
            known <= 2 -> "ต่ำ"
            known <= 6 -> "กลาง"
            else       -> "สูง"
        }
    }

    // ─── Weight tables per phase ─────────────────────────────────────────────

    private data class Weights(
        val counter: Float,
        val synergy: Float,
        val composition: Float,
        val flexibility: Float,
        val lane: Float
    )

    private fun weightsFor(phase: DraftPhase): Weights = when (phase) {
        DraftPhase.BLIND -> Weights(
            counter = 0.05f, synergy = 0.25f, composition = 0.25f,
            flexibility = 0.30f, lane = 0.15f
        )
        DraftPhase.EARLY -> Weights(
            counter = 0.20f, synergy = 0.22f, composition = 0.20f,
            flexibility = 0.23f, lane = 0.15f
        )
        DraftPhase.MID -> Weights(
            counter = 0.28f, synergy = 0.22f, composition = 0.18f,
            flexibility = 0.17f, lane = 0.15f
        )
        DraftPhase.LATE -> Weights(
            counter = 0.32f, synergy = 0.22f, composition = 0.16f,
            flexibility = 0.12f, lane = 0.18f
        )
        DraftPhase.FINAL -> Weights(
            counter = 0.35f, synergy = 0.22f, composition = 0.15f,
            flexibility = 0.08f, lane = 0.20f
        )
    }

    // ─── Main recommend ──────────────────────────────────────────────────────

    /**
     * [allies]  = teammates already picked (not you).
     * [enemies] = enemy picks known so far.
     * [pool]    = heroes available to pick (all heroes, or pick-grid heroes).
     * [myLanes] = lanes you play, e.g. setOf("JUNGLE").
     * [top]     = max results returned.
     */
    fun recommend(
        allies: List<Hero>,
        enemies: List<Hero>,
        pool: List<Hero>,
        myLanes: Set<String> = emptySet(),
        top: Int = 3
    ): List<Recommendation> {

        val phase = draftPhase(allies, enemies)
        val conf  = confidence(allies, enemies)
        val w     = weightsFor(phase)
        val taken = (allies + enemies).map { it.id }.toSet()

        // ── Team stats ──
        val aTanky  = allies.count { it.has("tanky") }
        val aCc     = allies.count { it.has("cc") }
        val aMagic  = allies.count { it.damage == DamageType.MAGIC }
        val aPhys   = allies.count { it.damage == DamageType.PHYSICAL }
        val aCarry  = allies.count { it.role == Role.CARRY }
        val aHeal   = allies.count { it.has("heal") }
        val aBurst  = allies.count { it.has("burst") }

        // ── Enemy stats ──
        val eAssassin = enemies.count { it.role == Role.ASSASSIN }
        val eMobile   = enemies.count { it.has("mobile") }
        val eCarry    = enemies.count { it.role == Role.CARRY }
        val eMage     = enemies.count { it.role == Role.MAGE }
        val eTanky    = enemies.count { it.has("tanky") }
        val ePoke     = enemies.count { it.has("poke") }
        val eKnown    = enemies.count { it.hasData }

        // Enemy IDs known (for counter lookup)
        val enemyIds = enemies.map { it.id }.toSet()

        val scored = ArrayList<Triple<Hero, HeroScores, Pair<List<String>, List<String>>>>()

        for (c in pool) {
            if (c.id in taken || !c.hasData) continue

            val why     = ArrayList<String>()
            val avoid   = ArrayList<String>()

            // ── 1. Counter score ──────────────────────────────────────────
            var counterRaw = 0
            for (e in enemies) {
                if (e.id in c.counters || c.id in e.weakTo) {
                    counterRaw += 4
                    why.add("แก้ทาง ${e.name}")
                }
                if (e.id in c.weakTo || c.id in e.counters) {
                    counterRaw -= 4
                    avoid.add("โดน ${e.name} แก้ทาง")
                }
            }

            // ── 2. Synergy score (เข้ากับทีมเรา) ──────────────────────────
            var synergyRaw = 0

            // CC + engage synergy
            if (aCc >= 1 && c.has("burst")) {
                synergyRaw += 2; why.add("ทีมมี CC ให้ Burst ตาม")
            }
            if (aBurst >= 1 && c.has("cc")) {
                synergyRaw += 2; why.add("ทีมมี Burst ให้ CC นำ")
            }
            if (aTanky >= 1 && (c.role == Role.CARRY || c.role == Role.MAGE)) {
                synergyRaw += 1; why.add("ทีมมีตัวรับ Damage ให้แล้ว")
            }
            // avoid duplicate carry
            if (c.role == Role.CARRY && aCarry >= 1) {
                synergyRaw -= 4; avoid.add("ทีมเรามีแครี่แล้ว")
            } else if (c.role != Role.CARRY && allies.count { it.role == c.role } >= 2) {
                synergyRaw -= 2; avoid.add("บทบาทซ้ำกับเพื่อน")
            }

            // ── 3. Composition score (เติมสิ่งที่ขาด) ─────────────────────
            var compRaw = 0
            if (allies.size >= 2) {
                if (aTanky == 0 && c.has("tanky"))               { compRaw += 3; why.add("ทีมขาดตัวถึก") }
                if (aCc == 0 && c.has("cc"))                     { compRaw += 2; why.add("ทีมขาดตัวคุมฝูง") }
                if (aMagic == 0 && c.damage == DamageType.MAGIC) { compRaw += 3; why.add("ทีมยังไม่มีดาเมจเวท") }
                if (aPhys == 0 && c.damage == DamageType.PHYSICAL){ compRaw += 3; why.add("ทีมยังไม่มีดาเมจกายภาพ") }
                if (aHeal == 0 && c.has("heal"))                 { compRaw += 2; why.add("ทีมขาดตัวฮีล") }
            }
            // Threat response
            if (eAssassin >= 2 && (c.has("tanky") || c.has("cc"))) {
                compRaw += 2; why.add("ศัตรูมีนักฆ่าหลาย — ต้องการตัวรับ/CC")
            }
            if (eAssassin >= 2 && c.role == Role.CARRY) {
                compRaw -= 2; avoid.add("แครี่เปราะ ศัตรูมีนักฆ่าหลายตัว")
            }
            if (eMobile >= 2 && c.has("cc")) {
                compRaw += 2; why.add("ศัตรูเคลื่อนที่ไว — CC ช่วยจับได้")
            }
            if (eCarry >= 1 && c.has("burst") && c.has("mobile")) {
                compRaw += 2; why.add("บุกจับแครี่ศัตรูได้")
            }
            if (eMage >= 2 && c.role == Role.ASSASSIN) {
                compRaw += 1; why.add("นักฆ่าไล่จอมเวทที่เปราะ")
            }
            if (eKnown >= 3 && eTanky == 0 && c.has("burst")) {
                compRaw += 1; why.add("ศัตรูไม่มีตัวถึก — Burst ได้ผล")
            }
            if (eTanky >= 2 && c.role == Role.ASSASSIN) {
                compRaw -= 1; avoid.add("ศัตรูตัวถึกเยอะ — นักฆ่าเก็บยาก")
            }
            if (ePoke >= 2 && c.has("heal")) {
                compRaw += 1; why.add("ศัตรูสายรุมจิก — ฮีลช่วยทน")
            }

            // ── 4. Flexibility score (ทนต่อการตอบกลับของศัตรู) ─────────────
            // Heroes that are "safe" picks: high tier, multi-role, or not easily countered
            var flexRaw = 0
            val tier = c.tierValue()
            when {
                tier >= 4 -> { flexRaw += 3; why.add("Tier สูง ปลอดภัยในหลายสถานการณ์") }
                tier == 3 -> { flexRaw += 1 }
                tier <= 1 -> { flexRaw -= 1; avoid.add("Tier ต่ำ เสี่ยงถ้าศัตรูเลือกตัว Counter") }
            }
            // Multi-lane = more flexible
            if (c.lanes.size >= 2) { flexRaw += 1; why.add("เล่นได้หลายเลน") }
            // Low counter risk = flexible (few enemies counter this hero)
            val beingCounteredBy = enemies.count { c.id in it.counters || it.id in c.weakTo }
            if (beingCounteredBy == 0 && eKnown >= 1) { flexRaw += 2; why.add("ศัตรูที่รู้แล้วไม่มีตัวแก้") }

            // In BLIND phase, penalise heroes with narrow role (assassin/carry are high-risk blind)
            if (phase == DraftPhase.BLIND) {
                if (c.role == Role.ASSASSIN || c.role == Role.CARRY) flexRaw -= 1
                if (c.has("tanky") || c.has("cc")) flexRaw += 1
            }

            // ── 5. Lane score ────────────────────────────────────────────────
            var laneRaw = 0
            if (myLanes.isNotEmpty()) {
                if (c.lanes.any { it in myLanes }) { laneRaw += 3; why.add("ตรงเลนที่คุณเล่น") }
                else { laneRaw -= 3; avoid.add("ไม่ตรงเลนที่คุณเล่น") }
            }

            // ── Weighted final score ─────────────────────────────────────────
            val counterClamped  = counterRaw.coerceIn(-8, 24)
            val synergyClamped  = synergyRaw.coerceIn(-6, 10)
            val compClamped     = compRaw.coerceIn(-4, 14)
            val flexClamped     = flexRaw.coerceIn(-3, 7)
            val laneClamped     = laneRaw.coerceIn(-3, 3)

            val finalScore = (
                counterClamped  * w.counter      +
                synergyClamped  * w.synergy      +
                compClamped     * w.composition  +
                flexClamped     * w.flexibility  +
                laneClamped     * w.lane
            ).toInt()

            val label = when {
                counterClamped >= 8 && counterClamped >= synergyClamped + compClamped -> "Best Counter"
                synergyClamped >= 6 && synergyClamped >= counterClamped -> "Best Synergy"
                else -> "Best Overall"
            }

            val hs = HeroScores(
                counterScore     = counterClamped,
                synergyScore     = synergyClamped,
                compositionScore = compClamped,
                flexibilityScore = flexClamped,
                laneScore        = laneClamped,
                finalScore       = finalScore,
                label            = label
            )

            scored.add(Triple(c, hs, Pair(why.toList(), avoid.toList())))
        }

        // Sort by final score
        val sorted = scored
            .filter { it.second.finalScore > 0 }
            .sortedWith(
                compareByDescending<Triple<Hero, HeroScores, Pair<List<String>, List<String>>>> { it.second.finalScore }
                    .thenBy { it.first.name }
            )

        // Ensure at least one of each label type when possible
        val picked = ArrayList<Recommendation>()
        val usedLabels = HashSet<String>()

        // First pass: pick best of each label
        for (label in listOf("Best Overall", "Best Counter", "Best Synergy")) {
            if (picked.size >= top) break
            val best = sorted.firstOrNull { it.second.label == label && it.first.id !in picked.map { r -> r.hero.id } }
            if (best != null) {
                picked.add(
                    Recommendation(best.first, best.second, best.third.first, best.third.second, conf)
                )
                usedLabels.add(label)
            }
        }

        // Fill remaining slots by score
        for (entry in sorted) {
            if (picked.size >= top) break
            if (picked.any { it.hero.id == entry.first.id }) continue
            picked.add(
                Recommendation(entry.first, entry.second, entry.third.first, entry.third.second, conf)
            )
        }

        return picked.take(top)
    }
}

// ── Hero extension ───────────────────────────────────────────────────────────

/** Numeric value for tier: S+ = 5, S = 4, A = 3, B = 2, C = 1, null = 2 */
fun Hero.tierValue(): Int {
    val t = tier ?: return 2
    return when (t.uppercase()) {
        "S+" -> 5
        "S"  -> 4
        "A"  -> 3
        "B"  -> 2
        else -> 1  // C or lower
    }
}
