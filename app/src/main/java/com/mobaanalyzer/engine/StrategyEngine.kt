package com.mobaanalyzer.engine

import com.mobaanalyzer.data.DamageType
import com.mobaanalyzer.data.GameRules
import com.mobaanalyzer.data.Hero
import com.mobaanalyzer.data.Role

/** Everything the engine needs. Fill what OCR could read; leave the rest null/empty. */
data class Situation(
    val seconds: Int,
    val allyKills: Int? = null,
    val enemyKills: Int? = null,
    val myDeaths: Int? = null,
    val myHero: Hero? = null,
    val allies: List<Hero> = emptyList(),
    val enemies: List<Hero> = emptyList()
)

data class Tip(val priority: Int, val text: String)

enum class Phase { EARLY, MID, LATE }

/** Fully offline rule-based advisor. No network, no API. */
class StrategyEngine(private val rules: GameRules = GameRules()) {

    fun phaseOf(seconds: Int): Phase = when {
        seconds < rules.earlyEndSec -> Phase.EARLY
        seconds < rules.midEndSec -> Phase.MID
        else -> Phase.LATE
    }

    fun analyze(s: Situation, maxTips: Int = 3): List<Tip> {
        val tips = ArrayList<Tip>()
        tips.addAll(objectiveTips(s))
        tips.addAll(scoreTips(s))
        tips.addAll(deathTips(s))
        tips.addAll(compositionTips(s))
        tips.addAll(roleTips(s))
        return tips.sortedByDescending { it.priority }.distinctBy { it.text }.take(maxTips)
    }

    private fun objectiveTips(s: Situation): List<Tip> {
        val out = ArrayList<Tip>()
        for (o in rules.objectives) {
            val left = o.firstSpawnSec - s.seconds
            if (left in 1..o.warnBeforeSec) {
                out.add(Tip(90, "อีก $left วิ ${o.label} จะเกิด — รวมทีมและปักวิชั่นที่ตำแหน่ง"))
            } else if (left in -20..0) {
                out.add(Tip(95, "${o.label} เกิดแล้ว — เช็กตำแหน่งศัตรูก่อนตัดสินใจสู้หรือสละ"))
            }
        }
        return out
    }

    private fun scoreTips(s: Situation): List<Tip> {
        val a = s.allyKills ?: return emptyList()
        val e = s.enemyKills ?: return emptyList()
        val diff = a - e
        val phase = phaseOf(s.seconds)
        val out = ArrayList<Tip>()
        if (diff <= -rules.behindKills) {
            out.add(Tip(70, "ตามอยู่ ${-diff} ฆ่า — เลี่ยงสู้กลุ่ม ฟาร์มใต้ป้อมและรอจังหวะ"))
            if (phase == Phase.LATE) out.add(Tip(75, "ช่วงท้ายเกมตามอยู่ — อยู่รวมกลุ่มป้องกันฐาน อย่าแยกกัน"))
        } else if (diff >= rules.aheadKills) {
            if (phase == Phase.EARLY) {
                out.add(Tip(65, "นำอยู่ $diff ฆ่า — กดดันป้อมแรกและแย่งป่าฝั่งศัตรู"))
            } else {
                out.add(Tip(72, "นำอยู่ $diff ฆ่า — บีบป้อม/มังกรก่อนศัตรูฟื้นตัว"))
            }
        }
        return out
    }

    private fun deathTips(s: Situation): List<Tip> {
        val d = s.myDeaths ?: return emptyList()
        return if (d >= rules.deathWarn && phaseOf(s.seconds) != Phase.LATE) {
            listOf(Tip(68, "คุณตาย $d ครั้งแล้ว — เล่นเซฟ อยู่หลังทีม รอให้ศัตรูใช้สกิลก่อนเข้าสู้"))
        } else emptyList()
    }

    private fun compositionTips(s: Situation): List<Tip> {
        val out = ArrayList<Tip>()
        val en = s.enemies
        if (en.size >= 3) {
            val phys = en.count { it.damage == DamageType.PHYSICAL }
            val magic = en.count { it.damage == DamageType.MAGIC }
            if (phys.toDouble() / en.size >= 0.6) out.add(Tip(60, "ศัตรูส่วนใหญ่ดาเมจกายภาพ — เสริมเกราะ"))
            if (magic.toDouble() / en.size >= 0.6) out.add(Tip(60, "ศัตรูส่วนใหญ่ดาเมจเวท — เสริมต้านเวท"))
            val assassins = en.count { it.role == Role.ASSASSIN }
            if (assassins >= 2) out.add(Tip(58, "ศัตรูมีนักฆ่า $assassins ตัว — อย่าเดินเดี่ยว คุมวิชั่นพุ่มและป่า"))
            if (en.any { it.has("heal") }) out.add(Tip(55, "ศัตรูมีสายฮีล — พิจารณาของลดการฟื้นฟู"))
            if (en.count { it.has("tanky") } >= 2) out.add(Tip(50, "ศัตรูตัวถึกหลายตัว — ใช้ดาเมจทะลุเกราะ/เปอร์เซ็นต์เลือด"))
            val carries = en.count { it.role == Role.CARRY }
            if (carries >= 2) out.add(Tip(45, "ศัตรูมีแครี่ $carries ตัว — มองหาจังหวะบุกใส่แครี่ก่อน"))
        }
        val al = s.allies
        if (al.size >= 3 && al.none { it.role == Role.TANK } && al.none { it.has("cc") }) {
            out.add(Tip(48, "ทีมเราไม่มีแทงค์/สกิลคุมฝูง — เล่นเป็นกลุ่ม เลี่ยงการปะทะหน้า"))
        }
        return out
    }

    private fun roleTips(s: Situation): List<Tip> {
        val hero = s.myHero ?: return emptyList()
        val early = phaseOf(s.seconds) == Phase.EARLY
        val text = when (hero.role) {
            Role.ASSASSIN ->
                if (early) "ฟาร์มป่าให้ครบ แล้วเข้าช่วยเลนที่ศัตรูดันเข้าป้อมเรา"
                else "ซุ่มจับตัวที่แยกออกมา อย่าเปิดสู้ก่อนทีม"
            Role.CARRY ->
                if (early) "ฟาร์มให้เลเวลสูง อยู่ใกล้ซัพพอร์ต"
                else "ยืนหลังทีม ตีตัวที่ใกล้และปลอดภัยที่สุด"
            Role.MAGE -> "ใช้สกิลระยะไกลสร้างแรงกดดัน อยู่หลังแทงค์"
            Role.TANK, Role.SUPPORT -> "ตามประกบแครี่ ปักวิชั่นรอบ objective"
            Role.FIGHTER -> "คุมเลนและดันป้อม แล้วรวมทีมตอน objective"
        }
        return listOf(Tip(30, text))
    }
}
