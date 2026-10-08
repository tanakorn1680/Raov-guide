package com.mobaanalyzer.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mobaanalyzer.R
import com.mobaanalyzer.data.AppState
import com.mobaanalyzer.data.Hero
import com.mobaanalyzer.data.HeroDatabase
import com.mobaanalyzer.data.HeroIconCache
import com.mobaanalyzer.data.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class HeroDbActivity : AppCompatActivity() {

    private lateinit var rvHeroes: RecyclerView
    private lateinit var etSearch: EditText
    private lateinit var chipGroup: LinearLayout
    private lateinit var tvPatchBadge: TextView
    private lateinit var bannerUpdate: View
    private lateinit var tvUpdateMsg: TextView
    private lateinit var tvUpdateAction: TextView

    private var allHeroes: List<Hero> = emptyList()
    private var activeRole: Role? = null
    private var searchQuery: String = ""
    private val adapter = HeroAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hero_db)

        rvHeroes       = findViewById(R.id.rvHeroes)
        etSearch       = findViewById(R.id.etSearch)
        chipGroup      = findViewById(R.id.chipGroup)
        tvPatchBadge   = findViewById(R.id.tvPatchBadge)
        bannerUpdate   = findViewById(R.id.bannerUpdate)
        tvUpdateMsg    = findViewById(R.id.tvUpdateMsg)
        tvUpdateAction = findViewById(R.id.tvUpdateAction)

        rvHeroes.layoutManager = LinearLayoutManager(this)
        rvHeroes.adapter = adapter

        val db = AppState.heroDb ?: HeroDatabase.load(this)
        allHeroes = db.heroes.sortedBy { it.name }

        val localPatch = loadLocalPatch()
        tvPatchBadge.text = "แพทช์ $localPatch"

        buildRoleChips()
        applyFilter()

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString()?.trim() ?: ""
                applyFilter()
            }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })

        checkPatchUpdate(localPatch)
    }

    // ── Patch ──────────────────────────────────────────────────────────────────

    private fun loadLocalPatch(): String = try {
        val text = assets.open("heroes.json").bufferedReader().use { it.readText() }
        JSONObject(text).optString("patch", "?")
    } catch (e: Exception) { "?" }

    private fun checkPatchUpdate(localPatch: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val remotePatch = fetchRemotePatch() ?: return@launch
            if (remotePatch != localPatch) {
                withContext(Dispatchers.Main) {
                    tvUpdateMsg.text = "มีแพทช์ใหม่: $remotePatch  (ของเรา: $localPatch)"
                    bannerUpdate.visibility = View.VISIBLE
                    tvUpdateAction.setOnClickListener {
                        startActivity(Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://rovmeta.com/th/heroes")))
                    }
                }
            }
        }
    }

    private fun fetchRemotePatch(): String? = try {
        val conn = URL("https://rovmeta.com/th/heroes").openConnection() as HttpURLConnection
        conn.connectTimeout = 6_000
        conn.readTimeout = 8_000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        val html = conn.inputStream.bufferedReader().use { it.readText() }
        Regex("""(?:แพทช์|[Pp]atch)\s*([\d.]+)""").find(html)?.groupValues?.get(1)
    } catch (e: Exception) { null }

    // ── Role chips ─────────────────────────────────────────────────────────────

    private fun buildRoleChips() {
        val roles = listOf(null) + listOf(
            Role.TANK, Role.FIGHTER, Role.ASSASSIN,
            Role.MAGE, Role.CARRY, Role.SUPPORT
        )
        chipGroup.removeAllViews()
        roles.forEach { role ->
            val chip = TextView(this).apply {
                text = roleLabel(role)
                textSize = 12f
                isSelected = (role == activeRole)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setTextColor(if (isSelected) 0xFFFFDD44.toInt() else 0xFF888888.toInt())
                background = if (isSelected)
                    resources.getDrawable(R.drawable.bg_chip_active, null)
                else
                    resources.getDrawable(R.drawable.bg_chip, null)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(6) }
                layoutParams = lp
                setOnClickListener {
                    activeRole = role
                    buildRoleChips()
                    applyFilter()
                }
            }
            chipGroup.addView(chip)
        }
    }

    private fun roleLabel(role: Role?) = when (role) {
        null -> "ทั้งหมด"
        Role.TANK -> "แทงค์"
        Role.FIGHTER -> "วอริเออร์"
        Role.ASSASSIN -> "อัสซาซิน"
        Role.MAGE -> "เมจ"
        Role.CARRY -> "มาร์คแมน"
        Role.SUPPORT -> "ซัพพอร์ต"
        else -> role.name
    }

    // ── Filter ─────────────────────────────────────────────────────────────────

    private fun applyFilter() {
        val q = searchQuery.lowercase()
        val filtered = allHeroes.filter { h ->
            val matchRole = activeRole == null || h.role == activeRole
            val matchQ = q.isEmpty() || h.name.lowercase().contains(q) || h.id.contains(q)
            matchRole && matchQ
        }.sortedWith(compareBy({ tierOrder(it.tier) }, { it.name }))
        adapter.submitList(filtered)
    }

    private fun tierOrder(tier: String?) = when (tier) {
        "S+" -> 0; "S" -> 1; "A" -> 2; "B" -> 3; "C" -> 4; "D" -> 5; else -> 6
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    // ── Adapter ────────────────────────────────────────────────────────────────

    inner class HeroAdapter : RecyclerView.Adapter<HeroAdapter.VH>() {
        private var list: List<Hero> = emptyList()

        fun submitList(l: List<Hero>) {
            list = l
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context).inflate(R.layout.item_hero_card, parent, false)
        )

        override fun getItemCount() = list.size
        override fun onBindViewHolder(h: VH, pos: Int) = h.bind(list[pos])

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val ivIcon  = v.findViewById<ImageView>(R.id.ivHeroIcon)
            private val tvName  = v.findViewById<TextView>(R.id.tvHeroName)
            private val tvTier  = v.findViewById<TextView>(R.id.tvTier)
            private val tvRole  = v.findViewById<TextView>(R.id.tvRoleLane)
            private val tvTags  = v.findViewById<TextView>(R.id.tvTags)

            private val laneLabel = mapOf(
                "JUNGLE" to "ป่า", "MID" to "กลาง",
                "ABYSSAL" to "ล่าง", "DARK_SLAYER" to "บน"
            )
            private val tierColor = mapOf(
                "S+" to 0xFFFF4444.toInt(), "S" to 0xFFFF8844.toInt(),
                "A"  to 0xFFFFDD44.toInt(), "B" to 0xFF88DD44.toInt(),
                "C"  to 0xFF44AAFF.toInt(), "D" to 0xFF888888.toInt()
            )

            fun bind(hero: Hero) {
                tvName.text = hero.name
                tvRole.text = buildString {
                    append(roleLabel(hero.role))
                    val lanes = hero.lanes.mapNotNull { laneLabel[it] }
                    if (lanes.isNotEmpty()) append(" · ${lanes.joinToString()}")
                }
                tvTags.text = hero.tags.joinToString(" · ")

                if (hero.tier != null) {
                    tvTier.text = hero.tier
                    tvTier.setTextColor(tierColor[hero.tier] ?: 0xFF888888.toInt())
                    tvTier.visibility = View.VISIBLE
                } else {
                    tvTier.visibility = View.GONE
                }

                ivIcon.setImageDrawable(null)
                lifecycleScope.launch {
                    HeroIconCache.getIcon(hero.id)?.let { ivIcon.setImageBitmap(it) }
                }

                itemView.setOnClickListener { /* detail screen — เพิ่มทีหลัง */ }
            }
        }
    }
}
