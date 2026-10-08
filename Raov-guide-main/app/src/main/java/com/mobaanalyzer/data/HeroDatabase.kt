package com.mobaanalyzer.data

import android.content.Context
import org.json.JSONObject

enum class Role { TANK, FIGHTER, ASSASSIN, MAGE, CARRY, SUPPORT, UNKNOWN }
enum class DamageType { PHYSICAL, MAGIC, MIXED }

data class Hero(
    val id: String,
    val name: String,
    val aliases: List<String>,
    val role: Role,
    val lanes: List<String>,
    val damage: DamageType,
    val tags: Set<String>,
    val counters: Set<String> = emptySet(),
    val weakTo: Set<String> = emptySet(),
    val tier: String? = null
) {
    fun has(tag: String): Boolean = tag in tags

    /** false for name-only heroes whose role/tags are not filled in yet. */
    val hasData: Boolean get() = role != Role.UNKNOWN
}

/** Hero list loaded from assets/heroes.json + fuzzy matching for noisy OCR text. */
class HeroDatabase(val heroes: List<Hero>) {

    private val index: List<Pair<String, Hero>> = heroes
        .flatMap { h -> (listOf(h.name) + h.aliases).map { normalize(it) to h } }
        .filter { it.first.length >= 3 }

    /** Best hero for one OCR string, or null if nothing is close enough. */
    fun match(raw: String): Hero? {
        val q = normalize(raw)
        if (q.length < 3) return null
        var best: Hero? = null
        var bestDist = Int.MAX_VALUE
        for ((key, hero) in index) {
            if (kotlin.math.abs(key.length - q.length) > 2) continue
            val d = editDistance(q, key)
            if (d <= allowedErrors(key.length) && d < bestDist) {
                bestDist = d
                best = hero
            }
        }
        return best
    }

    /** Scan OCR lines (whole line, each word, and adjacent word pairs) and return distinct heroes. */
    fun findIn(lines: List<String>): List<Hero> {
        val found = LinkedHashSet<Hero>()
        for (line in lines) {
            val tokens = line.split(Regex("\\s+")).filter { it.isNotBlank() }
            val candidates = ArrayList<String>()
            candidates.add(line)
            candidates.addAll(tokens)
            for (i in 0 until tokens.size - 1) candidates.add(tokens[i] + tokens[i + 1])
            for (c in candidates) match(c)?.let { found.add(it) }
        }
        return found.toList()
    }

    companion object {
        fun load(context: Context, asset: String = "heroes.json"): HeroDatabase {
            val text = context.assets.open(asset).bufferedReader(Charsets.UTF_8).use { it.readText() }
            return fromJson(text)
        }

        fun fromJson(text: String): HeroDatabase {
            val arr = JSONObject(text).getJSONArray("heroes")
            val list = ArrayList<Hero>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Hero(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        aliases = o.optJSONArray("aliases").toStrings(),
                        role = Role.valueOf(o.optString("role", "UNKNOWN")),
                        lanes = o.optJSONArray("lanes").toStrings(),
                        damage = DamageType.valueOf(o.optString("damage", "MIXED")),
                        tags = o.optJSONArray("tags").toStrings().toSet(),
                        counters = o.optJSONArray("counters").toStrings().toSet(),
                        weakTo = o.optJSONArray("weakTo").toStrings().toSet(),
                        tier = o.optString("tier").takeIf { it.isNotEmpty() }
                    )
                )
            }
            return HeroDatabase(list)
        }

        /** Lowercase, drop punctuation/spaces, and fold common OCR digit/letter mixups. */
        fun normalize(s: String): String {
            val sb = StringBuilder()
            for (ch in s.lowercase()) {
                if (!ch.isLetterOrDigit()) continue
                sb.append(
                    when (ch) {
                        '0' -> 'o'
                        '1' -> 'l'
                        '5' -> 's'
                        else -> ch
                    }
                )
            }
            return sb.toString()
        }

        private fun allowedErrors(len: Int): Int = if (len <= 4) 0 else if (len <= 7) 1 else 2

        private fun editDistance(a: String, b: String): Int {
            var prev = IntArray(b.length + 1) { it }
            var cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                }
                val t = prev; prev = cur; cur = t
            }
            return prev[b.length]
        }
    }
}

private fun org.json.JSONArray?.toStrings(): List<String> {
    if (this == null) return emptyList()
    val out = ArrayList<String>()
    for (i in 0 until length()) out.add(getString(i))
    return out
}
