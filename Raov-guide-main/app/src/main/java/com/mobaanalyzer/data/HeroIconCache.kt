package com.mobaanalyzer.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Hero icon cache — โหลดจาก rovmeta.com ครั้งแรก แล้ว cache ไว้ใน internal storage
 *
 * กลยุทธ์:
 * - icon แต่ละตัวบันทึกเป็น <heroId>.jpg
 * - meta.json เก็บ { heroId: { url, etag, lastModified } } สำหรับ HTTP conditional request
 * - ทุกครั้งที่โหลด ใช้ If-None-Match / If-Modified-Since → server ตอบ 304 = ไม่ต้องโหลดใหม่
 * - ถ้า server ตอบ 200 = มี icon ใหม่ → ทับของเก่า
 * - ถ้าเน็ตไม่มี / timeout → ใช้ cache เดิม
 * - ไม่มีการลบไฟล์เก่าโดยไม่จำเป็น — icon ที่ไม่มีใน heroes.json จะถูก prune ตอน init
 */
object HeroIconCache {

    private const val TAG = "HeroIconCache"
    private const val CACHE_DIR = "hero_icons"
    private const val META_FILE = "meta.json"
    private const val CONNECT_TIMEOUT = 8_000
    private const val READ_TIMEOUT = 12_000

    private lateinit var iconDir: File
    private lateinit var metaFile: File

    // heroId -> { url, etag?, lastModified? }
    private var meta: MutableMap<String, IconMeta> = mutableMapOf()
    // heroId -> iconUrl (จาก assets/hero_icons.json)
    private var urlMap: Map<String, String> = emptyMap()

    private data class IconMeta(
        val url: String,
        val etag: String? = null,
        val lastModified: String? = null
    )

    /** เรียกครั้งเดียวตอนแอปเริ่ม — sync, เร็วมาก (แค่อ่านไฟล์) */
    fun init(context: Context, knownHeroIds: Set<String>) {
        iconDir = File(context.filesDir, CACHE_DIR).also { it.mkdirs() }
        metaFile = File(iconDir, META_FILE)
        urlMap = loadUrlMap(context)
        meta = loadMeta()
        pruneOrphans(knownHeroIds)
    }

    /**
     * ดึง Bitmap ของ heroId
     * - ถ้ามี cache + server บอก 304 → คืน cache ทันที
     * - ถ้า server บอก 200 → ทับ cache แล้วคืนรูปใหม่
     * - ถ้าเน็ตใช้ไม่ได้ → คืน cache เดิม (ถ้ามี) หรือ null
     */
    suspend fun getIcon(heroId: String): Bitmap? = withContext(Dispatchers.IO) {
        val url = urlMap[heroId] ?: return@withContext loadCached(heroId)
        val cacheFile = File(iconDir, "$heroId.jpg")
        val existing = meta[heroId]

        return@withContext try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
                setRequestProperty("Referer", "https://rovmeta.com/")
                // conditional request — ถามว่าเปลี่ยนไหม ไม่ต้องโหลดทั้งไฟล์ถ้าเหมือนเดิม
                existing?.etag?.let { setRequestProperty("If-None-Match", it) }
                existing?.lastModified?.let { setRequestProperty("If-Modified-Since", it) }
            }

            when (conn.responseCode) {
                304 -> {
                    // ไม่มีการเปลี่ยนแปลง — ใช้ cache เดิม
                    loadCached(heroId)
                }
                200 -> {
                    // มีรูปใหม่ — ทับของเก่า
                    val bitmap = conn.inputStream.use { BitmapFactory.decodeStream(it) }
                    if (bitmap != null) {
                        val scaled = Bitmap.createScaledBitmap(bitmap, 128, 128, true)
                        bitmap.recycle()
                        cacheFile.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                        // อัปเดต meta
                        meta[heroId] = IconMeta(
                            url = url,
                            etag = conn.getHeaderField("ETag"),
                            lastModified = conn.getHeaderField("Last-Modified")
                        )
                        saveMeta()
                        Log.d(TAG, "Updated icon: $heroId")
                        scaled
                    } else {
                        loadCached(heroId)
                    }
                }
                else -> {
                    Log.w(TAG, "HTTP ${conn.responseCode} for $heroId")
                    loadCached(heroId)
                }
            }
        } catch (e: Exception) {
            // เน็ตล้มเหลว — ใช้ cache เดิม
            Log.d(TAG, "Network unavailable for $heroId, using cache: ${e.message}")
            loadCached(heroId)
        }
    }

    /** โหลด icon ทั้งหมด (background prefetch) */
    suspend fun prefetchAll(heroIds: List<String>) {
        heroIds.forEach { getIcon(it) }
        Log.d(TAG, "Prefetch done: ${cachedCount()}/${heroIds.size} icons cached")
    }

    fun isCached(heroId: String) = File(iconDir, "$heroId.jpg").let { it.exists() && it.length() > 0 }
    fun cachedCount() = iconDir.listFiles { f -> f.name.endsWith(".jpg") }?.size ?: 0

    // ── private ────────────────────────────────────────────────────────────────

    private fun loadCached(heroId: String): Bitmap? {
        val f = File(iconDir, "$heroId.jpg")
        return if (f.exists() && f.length() > 0) BitmapFactory.decodeFile(f.absolutePath) else null
    }

    /** โหลด URL map จาก assets/hero_icons.json */
    private fun loadUrlMap(context: Context): Map<String, String> {
        return try {
            val text = context.assets.open("hero_icons.json")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            val heroes = JSONObject(text).getJSONObject("heroes")
            buildMap { for (key in heroes.keys()) heroes.getJSONObject(key).optString("icon_url").takeIf { it.isNotEmpty() }?.let { put(key, it) } }
        } catch (e: Exception) {
            Log.w(TAG, "hero_icons.json โหลดไม่ได้: ${e.message}")
            emptyMap()
        }
    }

    /** โหลด meta จากดิสก์ */
    private fun loadMeta(): MutableMap<String, IconMeta> {
        if (!metaFile.exists()) return mutableMapOf()
        return try {
            val json = JSONObject(metaFile.readText())
            buildMap {
                for (key in json.keys()) {
                    val o = json.getJSONObject(key)
                    put(key, IconMeta(o.getString("url"), o.optString("etag").ifEmpty { null }, o.optString("lm").ifEmpty { null }))
                }
            }.toMutableMap()
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    /** บันทึก meta ลงดิสก์ */
    @Synchronized
    private fun saveMeta() {
        try {
            val json = JSONObject()
            for ((id, m) in meta) {
                json.put(id, JSONObject().apply {
                    put("url", m.url)
                    m.etag?.let { put("etag", it) }
                    m.lastModified?.let { put("lm", it) }
                })
            }
            metaFile.writeText(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "saveMeta failed: ${e.message}")
        }
    }

    /** ลบ icon ของ hero ที่ไม่มีในเกมแล้ว (เช่น hero ถูกลบออกจาก heroes.json) */
    private fun pruneOrphans(knownIds: Set<String>) {
        iconDir.listFiles { f -> f.name.endsWith(".jpg") }?.forEach { f ->
            val id = f.nameWithoutExtension
            if (id !in knownIds) {
                f.delete()
                meta.remove(id)
                Log.d(TAG, "Pruned orphan: $id")
            }
        }
    }
}
