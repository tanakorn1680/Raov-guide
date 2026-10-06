package com.mobaanalyzer.engine

import com.mobaanalyzer.data.DamageType
import com.mobaanalyzer.data.Hero
import com.mobaanalyzer.data.Role

// ─── Draft Phase ─────────────────────────────────────────────────────────────

enum class DraftPhase {
    BLIND,   // 0–1 known
    EARLY,   // 2–4
    MID,     // 5–7
    LATE,    // 8–9
    FINAL    // 10
}

// ─── Score breakdown ──────────────────────────────────────────────────────────

data class HeroScores(
    val counterScore:     Int,
    val synergyScore:     Int,
    val compositionScore: Int,
    val threatScore:      Int,
    val futureSafety:     Int,
    val flexScore:        Int,
    val laneScore:        Int,
    val finalScore:       Int,
    val label:            String,   // "Best Overall" / "Best Counter" / "Best Synergy"
    val confidence:       String    // "ต่ำ" / "กลาง" / "สูง"
)

data class Recommendation(
    val hero:         Hero,
    val scores:       HeroScores,
    val reasons:      List<String>,
    val avoidReasons: List<String> = emptyList()
)

// ─── Weights per phase ────────────────────────────────────────────────────────

private data class Weights(
    val counter:     Float,
    val synergy:     Float,
    val composition: Float,
    val threat:      Float,
    val future:      Float,
    val flex:        Float,
    val lane:        Float
)

private fun weightsFor(phase: DraftPhase) = when (phase) {
    DraftPhase.BLIND -> Weights(0.05f, 0.25f, 0.20f, 0.00f, 0.10f, 0.25f, 0.15f)
    DraftPhase.EARLY -> Weights(0.20f, 0.20f, 0.20f, 0.10f, 0.10f, 0.10f, 0.10f)
    DraftPhase.MID   -> Weights(0.25f, 0.20f, 0.18f, 0.12f, 0.10f, 0.07f, 0.08f)
    DraftPhase.LATE  -> Weights(0.30f, 0.20f, 0.15f, 0.15f, 0.10f, 0.05f, 0.05f)
    DraftPhase.FINAL -> Weights(0.35f, 0.20f, 0.15f, 0.15f, 0.10f, 0.00f, 0.05f)
}

// ─── Main PickAdvisor ─────────────────────────────────────────────────────────

class PickAdvisor {

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

    private fun confidence(phase: DraftPhase, enemyKnown: Int): String = when {
        phase == DraftPhase.BLIND             -> "ต่ำ"
        phase == DraftPhase.EARLY             -> "ต่ำ-กลาง"
        enemyKnown >= 3                       -> "สูง"
        phase == DraftPhase.MID               -> "กลาง"
        else                                  -> "สูง"
    }

    /**
     * แนะนำฮีโร่ที่เหมาะสมที่สุดตามสถานะ Draft ปัจจุบัน
     *
     * [allies]  = ทีมเราที่เลือกแล้ว (ไม่รวมตัวที่กำลังจะเลือก)
     * [enemies] = ทีมศัตรูที่รู้แล้ว
     * [pool]    = ฮีโร่ทั้งหมดที่เลือกได้
     * [myLanes] = เลนที่ผู้เล่นถนัด
     * [top]     = จำนวน recommendation สูงสุด
     */
    fun recommend(
        allies:  List<Hero>,
        enemies: List<Hero>,
        pool:    List<Hero>,
        myLanes: Set<String> = emptySet(),
        top:     Int = 3
    ): List<Recommendation> {

        val phase     = draftPhase(allies, enemies)
        val w         = weightsFor(phase)
        val taken     = (allies + enemies).map { it.id }.toSet()
        val enemyKnown = enemies.count { it.hasData }
        val conf      = confidence(phase, enemyKnown)

        // ── สถิติทีมเรา ──
        val aTanky = allies.count { it.has("tanky") }
        val aCc    = allies.count { it.has("cc") }
        val aMagic = allies.count { it.damage == DamageType.MAGIC }
        val aPhys  = allies.count { it.damage == DamageType.PHYSICAL }
        val aCarry = allies.count { it.role == Role.CARRY }
        val aHeal  = allies.count { it.has("heal") }
        val aBurst = allies.count { it.has("burst") }

        // ── สถิติทีมศัตรู ──
        val eAssassin = enemies.count { it.role == Role.ASSASSIN }
        val eMobile   = enemies.count { it.has("mobile") }
        val eCarry    = enemies.count { it.role == Role.CARRY }
        val eMage     = enemies.count { it.role == Role.MAGE }
        val eTanky    = enemies.count { it.has("tanky") }
        val ePoke     = enemies.count { it.has("poke") }
        val eTank     = enemies.count { it.role == Role.TANK }

        val results = ArrayList<Triple<Hero, HeroScores, Pair<List<String>, List<String>>>>()

        for (candidate in pool) {
            if (candidate.id in taken || !candidate.hasData) continue

            val why   = ArrayList<String>()
            val avoid = ArrayList<String>()

            // ── 1. Counter Score ──────────────────────────────────────────
            var counterRaw = 0
            for (e in enemies) {
                if (e.id in candidate.counters || candidate.id in e.weakTo) {
                    counterRaw += 4; why.add("แก้ทาง ${e.name}")
                }
                if (e.id in candidate.weakTo || candidate.id in e.counters) {
                    counterRaw -= 4; avoid.add("โดน ${e.name} แก้ทาง")
                }
            }

            // ── 2. Synergy Score ──────────────────────────────────────────
            var synergyRaw = 0
            if (aCc >= 1 && candidate.has("burst"))  { synergyRaw += 2; why.add("ทีมมี CC ให้ Burst ตาม") }
            if (aBurst >= 1 && candidate.has("cc"))  { synergyRaw += 2; why.add("ทีมมี Burst ให้ CC นำ") }
            if (aTanky >= 1 && (candidate.role == Role.CARRY || candidate.role == Role.MAGE)) {
                synergyRaw += 1; why.add("มีตัวรับ Damage อยู่แล้ว")
            }
            if (candidate.role == Role.CARRY && aCarry >= 1) {
                synergyRaw -= 4; avoid.add("ทีมมีแครี่แล้ว")
            } else if (candidate.role != Role.CARRY && allies.count { it.role == candidate.role } >= 2) {
                synergyRaw -= 2; avoid.add("บทบาทซ้ำกับเพื่อน")
            }

            // ── 3. Composition Score ──────────────────────────────────────
            var compRaw = 0
            if (allies.size >= 1) {
                if (aTanky == 0 && candidate.has("tanky"))                { compRaw += 3; why.add("ทีมขาดตัวถึก") }
                if (aCc == 0 && candidate.has("cc"))                      { compRaw += 2; why.add("ทีมขาดตัวคุมฝูง") }
                if (aMagic == 0 && candidate.damage == DamageType.MAGIC)  { compRaw += 3; why.add("ทีมยังไม่มีดาเมจเวท") }
                if (aPhys == 0 && candidate.damage == DamageType.PHYSICAL){ compRaw += 3; why.add("ทีมยังไม่มีดาเมจกายภาพ") }
                if (aHeal == 0 && candidate.has("heal"))                  { compRaw += 2; why.add("ทีมขาดตัวฮีล") }
            }

            // ── 4. Threat Score ───────────────────────────────────────────
            var threatRaw = 0
            if (eAssassin >= 2 && (candidate.has("tanky") || candidate.has("cc"))) {
                threatRaw += 3; why.add("ศัตรูมีนักฆ่าหลายตัว — ต้องการตัวรับ/CC")
            }
            if (eAssassin >= 2 && candidate.role == Role.CARRY) {
                threatRaw -= 2; avoid.add("แครี่เปราะ ศัตรูมีนักฆ่าหลายตัว")
            }
            if (eMobile >= 2 && candidate.has("cc")) {
                threatRaw += 2; why.add("ศัตรูเคลื่อนที่ไว — CC จับได้")
            }
            if (eCarry >= 1 && candidate.has("burst") && candidate.has("mobile")) {
                threatRaw += 2; why.add("บุกจับแครี่ศัตรูได้")
            }
            if (eTanky >= 2 && candidate.role == Role.ASSASSIN) {
                threatRaw -= 1; avoid.add("ศัตรูตัวถึกเยอะ — นักฆ่าเก็บยาก")
            }
            if (eMage >= 2 && candidate.role == Role.ASSASSIN) {
                threatRaw += 1; why.add("นักฆ่าไล่จอมเวทที่เปราะ")
            }
            if (eTank >= 2 && candidate.has("burst")) {
                threatRaw += 1; why.add("Burst ทะลุ Tank ได้")
            }

            // ── 5. Future Draft Safety ────────────────────────────────────
            // จำลองว่าถ้าศัตรูเลือกตัวที่ counter candidate นี้ได้ดีที่สุด เราจะเสียเปรียบแค่ไหน
            var futureSafety = 5 // baseline
            val remainingEnemySlots = (5 - enemies.size).coerceAtLeast(0)

            if (remainingEnemySlots > 0) {
                // นับว่าใน pool มีตัวที่ counter candidate นี้กี่ตัว
                val potentialCounters = pool.count { h ->
                    h.id !in taken && candidate.id in h.counters
                }
                when {
                    potentialCounters == 0 -> { futureSafety += 4; why.add("ยากที่จะ Counter") }
                    potentialCounters <= 2 -> { futureSafety += 2 }
                    potentialCounters >= 5 -> { futureSafety -= 2; avoid.add("ศัตรูมีตัว Counter ได้หลายตัว") }
                }
                // ถ้า tier สูง future safety ดีกว่า
                futureSafety += (candidate.tierValue() - 2)
            }

            // ── 6. Flexibility Score ──────────────────────────────────────
            var flexRaw = 0
            val tier = candidate.tierValue()
            when {
                tier >= 4 -> { flexRaw += 3 }
                tier == 3 -> { flexRaw += 1 }
                tier <= 1 -> { flexRaw -= 1; avoid.add("Tier ต่ำ") }
            }
            if (candidate.lanes.size >= 2) { flexRaw += 1; why.add("เล่นได้หลายเลน") }
            // ยังไม่มีใคร counter ในข้อมูลปัจจุบัน
            val beingCountered = enemies.count { candidate.id in it.counters || it.id in candidate.weakTo }
            if (beingCountered == 0 && enemyKnown >= 1) { flexRaw += 2; why.add("ศัตรูที่รู้แล้วไม่มีตัวแก้") }
            if (phase == DraftPhase.BLIND) {
                if (candidate.role == Role.ASSASSIN || candidate.role == Role.CARRY) flexRaw -= 1
                if (candidate.has("tanky") || candidate.has("cc")) { flexRaw += 1; why.add("Blind pick ปลอดภัย") }
            }

            // ── 7. Lane Score ─────────────────────────────────────────────
            var laneRaw = 0
            if (myLanes.isNotEmpty()) {
                if (candidate.lanes.any { it in myLanes }) { laneRaw += 3; why.add("ตรงเลนที่คุณเล่น") }
                else { laneRaw -= 2; avoid.add("ไม่ตรงเลนที่คุณเล่น") }
            }

            // ── Weighted Final Score ──────────────────────────────────────
            val cC  = counterRaw.coerceIn(-8, 20)
            val cS  = synergyRaw.coerceIn(-6, 10)
            val cCo = compRaw.coerceIn(-4, 12)
            val cT  = threatRaw.coerceIn(-4, 10)
            val cF  = futureSafety.coerceIn(0, 10)
            val cFl = flexRaw.coerceIn(-3, 7)
            val cL  = laneRaw.coerceIn(-2, 3)

            val raw = (cC  * w.counter  +
                       cS  * w.synergy  +
                       cCo * w.composition +
                       cT  * w.threat   +
                       cF  * w.future   +
                       cFl * w.flex     +
                       cL  * w.lane)

            // normalize เป็น 0–100
            val finalScore = (((raw + 15f) / 30f) * 100f).toInt().coerceIn(0, 100)

            val label = when {
                cC >= 8 && cC >= cS + cCo -> "Best Counter"
                cS >= 6 && cS >= cC       -> "Best Synergy"
                tier >= 4 && finalScore >= 70 -> "Top Tier Pick"
                phase == DraftPhase.BLIND  -> "Safe Blind Pick"
                else                       -> "Best Overall"
            }

            val hs = HeroScores(
                counterScore     = cC,
                synergyScore     = cS,
                compositionScore = cCo,
                threatScore      = cT,
                futureSafety     = cF,
                flexScore        = cFl,
                laneScore        = cL,
                finalScore       = finalScore,
                label            = label,
                confidence       = conf
            )

            results.add(Triple(candidate, hs, Pair(why.toList(), avoid.toList())))
        }

        // ── Sort & Pick Top ───────────────────────────────────────────────
        val sorted = results
            .filter { it.second.finalScore > 0 }
            .sortedWith(
                compareByDescending<Triple<Hero, HeroScores, Pair<List<String>, List<String>>>> { it.second.finalScore }
                    .thenByDescending { it.first.tierValue() }
                    .thenBy { it.first.name }
            )

        // ให้มีแต่ละ label ถ้าเป็นไปได้
        val picked  = ArrayList<Recommendation>()
        val usedIds = HashSet<String>()

        for (label in listOf("Best Counter", "Best Synergy", "Top Tier Pick", "Safe Blind Pick", "Best Overall")) {
            if (picked.size >= top) break
            val best = sorted.firstOrNull { it.second.label == label && it.first.id !in usedIds }
            if (best != null) {
                picked.add(Recommendation(best.first, best.second, best.third.first, best.third.second))
                usedIds.add(best.first.id)
            }
        }

        // เติมที่เหลือตาม score
        for (entry in sorted) {
            if (picked.size >= top) break
            if (entry.first.id in usedIds) continue
            picked.add(Recommendation(entry.first, entry.second, entry.third.first, entry.third.second))
            usedIds.add(entry.first.id)
        }

        return picked.take(top)
    }
}

// ─── Hero Extension ───────────────────────────────────────────────────────────

fun Hero.tierValue(): Int = when (tier?.uppercase()) {
    "S+" -> 5; "S" -> 4; "A" -> 3; "B" -> 2; else -> 1
}
