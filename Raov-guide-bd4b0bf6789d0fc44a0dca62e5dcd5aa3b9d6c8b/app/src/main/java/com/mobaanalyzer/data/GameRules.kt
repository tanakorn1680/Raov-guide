package com.mobaanalyzer.data

import android.content.Context
import org.json.JSONObject

data class Objective(val id: String, val label: String, val firstSpawnSec: Int, val warnBeforeSec: Int)

/** Tunable numbers loaded from assets/game_rules.json so patches don't need code changes. */
data class GameRules(
    val earlyEndSec: Int = 300,
    val midEndSec: Int = 780,
    val behindKills: Int = 4,
    val aheadKills: Int = 4,
    val deathWarn: Int = 4,
    val objectives: List<Objective> = emptyList()
) {
    companion object {
        fun load(context: Context, asset: String = "game_rules.json"): GameRules {
            val text = context.assets.open(asset).bufferedReader(Charsets.UTF_8).use { it.readText() }
            return fromJson(text)
        }

        fun fromJson(text: String): GameRules {
            val root = JSONObject(text)
            val phases = root.optJSONObject("phases")
            val swing = root.optJSONObject("scoreSwing")
            val objs = ArrayList<Objective>()
            val arr = root.optJSONArray("objectives")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    objs.add(
                        Objective(
                            o.getString("id"),
                            o.getString("label"),
                            o.getInt("firstSpawnSec"),
                            o.optInt("warnBeforeSec", 30)
                        )
                    )
                }
            }
            val d = GameRules()
            return GameRules(
                earlyEndSec = phases?.optInt("earlyEndSec", d.earlyEndSec) ?: d.earlyEndSec,
                midEndSec = phases?.optInt("midEndSec", d.midEndSec) ?: d.midEndSec,
                behindKills = swing?.optInt("behindKills", d.behindKills) ?: d.behindKills,
                aheadKills = swing?.optInt("aheadKills", d.aheadKills) ?: d.aheadKills,
                deathWarn = root.optInt("deathWarn", d.deathWarn),
                objectives = objs
            )
        }
    }
}
