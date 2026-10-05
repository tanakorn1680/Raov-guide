package com.mobaanalyzer.ocr

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.mobaanalyzer.data.HeroDatabase
import com.mobaanalyzer.engine.OcrLine
import com.mobaanalyzer.engine.ScreenAnalyzer
import com.mobaanalyzer.engine.ScreenReading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class GameScreenReader(private val db: HeroDatabase) {

    companion object {
        private const val TAG = "GameScreenReader"
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val analyzer = ScreenAnalyzer(db)

    /**
     * อ่าน Bitmap → ScreenReading (มี allies, enemies, grid, timer, score)
     * ใช้ position x,y ของแต่ละ text block เพื่อแยกซ้าย/ขวา = เรา/ศัตรู
     */
    suspend fun read(bitmap: Bitmap): ScreenReading = withContext(Dispatchers.Default) {
        val lines = recognizeWithPosition(bitmap)

        // log ทุก text block ที่เห็น เพื่อ debug
        Log.d(TAG, "=== OCR found ${lines.size} text blocks ===")
        lines.forEach { line ->
            Log.d(TAG, "  [x=${line.cx.format(2)} y=${line.cy.format(2)}] \"${line.text}\"")
        }

        val reading = analyzer.analyze(lines)
        Log.d(TAG, "=== ScreenReading: state=${reading.state} allies=${reading.allies.map{it.name}} enemies=${reading.enemies.map{it.name}} grid=${reading.grid.map{it.name}} ===")
        reading
    }

    /**
     * OCR แบบเก็บตำแหน่ง x,y ของแต่ละ block
     * normalised เป็น 0.0–1.0 เทียบกับขนาดภาพ
     */
    private suspend fun recognizeWithPosition(bitmap: Bitmap): List<OcrLine> =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val w = bitmap.width.toFloat()
                    val h = bitmap.height.toFloat()
                    val lines = mutableListOf<OcrLine>()

                    for (block in result.textBlocks) {
                        for (line in block.lines) {
                            val box = line.boundingBox ?: continue
                            val cx = (box.left + box.right) / 2f / w
                            val cy = (box.top + box.bottom) / 2f / h
                            lines.add(OcrLine(line.text, cx, cy))
                        }
                    }
                    cont.resume(lines)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "OCR failed: ${e.message}")
                    cont.resumeWithException(e)
                }
        }

    fun close() = recognizer.close()

    private fun Float.format(digits: Int) = "%.${digits}f".format(this)
}
