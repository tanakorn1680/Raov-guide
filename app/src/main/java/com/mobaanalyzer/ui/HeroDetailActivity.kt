package com.mobaanalyzer.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mobaanalyzer.R
import com.mobaanalyzer.data.AppState
import com.mobaanalyzer.data.Hero
import com.mobaanalyzer.data.HeroDatabase
import com.mobaanalyzer.data.HeroIconCache
import com.mobaanalyzer.data.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class HeroDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_HERO_ID = "hero_id"
        private const val PICK_IMAGE = 1001
    }

    private lateinit var hero: Hero

    private lateinit var ivIcon: ImageView
    private lateinit var tvName: TextView
    private lateinit var tvTier: TextView
    private lateinit var tvRole: TextView
    private lateinit var tvTags: TextView
    private lateinit var rowStats: View
    private lateinit var tvWin: TextView
    private lateinit var tvPick: TextView
    private lateinit var tvBan: TextView
    private lateinit var sectionCounters: View
    private lateinit var tvCounters: TextView
    private lateinit var sectionWeakTo: View
    private lateinit var tvWeakTo: TextView
    private lateinit var panelChangeIcon: View
    private lateinit var etIconUrl: EditText
    private lateinit var btnPickFromGallery: Button
    private lateinit var btnSaveIcon: Button
    private lateinit var btnResetIcon: Button
    private lateinit var btnChangeIcon: TextView

    private val tierColor = mapOf(
        "S+" to 0xFFFF4444.toInt(), "S" to 0xFFFF8844.toInt(),
        "A"  to 0xFFFFDD44.toInt(), "B" to 0xFF88DD44.toInt(),
        "C"  to 0xFF44AAFF.toInt(), "D" to 0xFF888888.toInt()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hero_detail)

        val heroId = intent.getStringExtra(EXTRA_HERO_ID) ?: run { finish(); return }
        val db = AppState.heroDb ?: HeroDatabase.load(this)
        hero = db.heroes.find { it.id == heroId } ?: run { finish(); return }

        HeroIconCache.initIfNeeded(applicationContext, db.heroes.map { it.id }.toSet())

        bindViews()
        populateHero()
        loadIcon()
    }

    private fun bindViews() {
        ivIcon           = findViewById(R.id.ivDetailIcon)
        tvName           = findViewById(R.id.tvDetailName)
        tvTier           = findViewById(R.id.tvDetailTier)
        tvRole           = findViewById(R.id.tvDetailRole)
        tvTags           = findViewById(R.id.tvDetailTags)
        rowStats         = findViewById(R.id.rowStats)
        tvWin            = findViewById(R.id.tvWin)
        tvPick           = findViewById(R.id.tvPick)
        tvBan            = findViewById(R.id.tvBan)
        sectionCounters  = findViewById(R.id.sectionCounters)
        tvCounters       = findViewById(R.id.tvCounters)
        sectionWeakTo    = findViewById(R.id.sectionWeakTo)
        tvWeakTo         = findViewById(R.id.tvWeakTo)
        panelChangeIcon  = findViewById(R.id.panelChangeIcon)
        etIconUrl        = findViewById(R.id.etIconUrl)
        btnPickFromGallery = findViewById(R.id.btnPickFromGallery)
        btnSaveIcon      = findViewById(R.id.btnSaveIcon)
        btnResetIcon     = findViewById(R.id.btnResetIcon)
        btnChangeIcon    = findViewById(R.id.btnChangeIcon)

        btnChangeIcon.setOnClickListener {
            panelChangeIcon.visibility =
                if (panelChangeIcon.visibility == View.GONE) View.VISIBLE else View.GONE
        }

        btnPickFromGallery.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK).apply { type = "image/*" }
            startActivityForResult(intent, PICK_IMAGE)
        }

        btnSaveIcon.setOnClickListener { saveIconFromUrl() }

        btnResetIcon.setOnClickListener {
            HeroIconCache.clearCustomIcon(hero.id)
            loadIcon()
            panelChangeIcon.visibility = View.GONE
            Toast.makeText(this, "รีเซ็ต icon แล้ว", Toast.LENGTH_SHORT).show()
        }
    }

    private fun populateHero() {
        supportActionBar?.title = hero.name
        tvName.text = hero.name

        if (hero.tier != null) {
            tvTier.text = hero.tier
            tvTier.setTextColor(tierColor[hero.tier] ?: 0xFF888888.toInt())
            tvTier.visibility = View.VISIBLE
        } else {
            tvTier.visibility = View.GONE
        }

        val laneLabel = mapOf(
            "JUNGLE" to "ป่า", "MID" to "กลาง",
            "ABYSSAL" to "ล่าง", "DARK_SLAYER" to "บน"
        )
        val roleLabel = when (hero.role) {
            Role.TANK -> "แทงค์"; Role.FIGHTER -> "วอริเออร์"
            Role.ASSASSIN -> "อัสซาซิน"; Role.MAGE -> "เมจ"
            Role.CARRY -> "มาร์คแมน"; Role.SUPPORT -> "ซัพพอร์ต"
            else -> hero.role.name
        }
        val lanes = hero.lanes.mapNotNull { laneLabel[it] }.joinToString(" / ")
        tvRole.text = if (lanes.isNotEmpty()) "$roleLabel · $lanes" else roleLabel
        tvTags.text = hero.tags.joinToString(" · ")

        // counters
        if (hero.counters.isNotEmpty()) {
            val db = AppState.heroDb ?: HeroDatabase.load(this)
            tvCounters.text = hero.counters
                .mapNotNull { id -> db.heroes.find { it.id == id }?.name ?: id }
                .joinToString("  ·  ")
            sectionCounters.visibility = View.VISIBLE
        }

        if (hero.weakTo.isNotEmpty()) {
            val db = AppState.heroDb ?: HeroDatabase.load(this)
            tvWeakTo.text = hero.weakTo
                .mapNotNull { id -> db.heroes.find { it.id == id }?.name ?: id }
                .joinToString("  ·  ")
            sectionWeakTo.visibility = View.VISIBLE
        }
    }

    private fun loadIcon() {
        lifecycleScope.launch {
            val bmp = HeroIconCache.getIcon(hero.id)
            if (bmp != null) ivIcon.setImageBitmap(bmp)
            else ivIcon.setImageResource(android.R.drawable.ic_menu_gallery)
        }
    }

    // ── เปลี่ยน icon ─────────────────────────────────────────────────────────

    private fun saveIconFromUrl() {
        val url = etIconUrl.text.toString().trim()
        if (url.isEmpty()) { Toast.makeText(this, "กรอก URL ก่อน", Toast.LENGTH_SHORT).show(); return }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 8_000; conn.readTimeout = 10_000
                val bmp = conn.inputStream.use { BitmapFactory.decodeStream(it) }
                if (bmp != null) {
                    HeroIconCache.saveCustomIcon(hero.id, bmp)
                    withContext(Dispatchers.Main) {
                        ivIcon.setImageBitmap(bmp)
                        panelChangeIcon.visibility = View.GONE
                        Toast.makeText(this@HeroDetailActivity, "บันทึก icon แล้ว", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@HeroDetailActivity, "โหลดรูปไม่ได้", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@HeroDetailActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_IMAGE && resultCode == Activity.RESULT_OK) {
            val uri: Uri = data?.data ?: return
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val stream = contentResolver.openInputStream(uri) ?: return@launch
                    val bmp = BitmapFactory.decodeStream(stream)
                    if (bmp != null) {
                        HeroIconCache.saveCustomIcon(hero.id, bmp)
                        withContext(Dispatchers.Main) {
                            ivIcon.setImageBitmap(bmp)
                            panelChangeIcon.visibility = View.GONE
                            Toast.makeText(this@HeroDetailActivity, "บันทึก icon แล้ว", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@HeroDetailActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}
