package com.mobaanalyzer.ocr

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.mobaanalyzer.data.HeroDatabase
import com.mobaanalyzer.engine.NormRect
import com.mobaanalyzer.engine.OcrLine
import com.mobaanalyzer.engine.ScreenAnalyzer
import com.mobaanalyzer.engine.ScreenReading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ReadResult(
    val reading: ScreenReading,
    val rawText: String      // OCR text ดิบ สำหรับ debug
)

class GameScreenReader(private val db: HeroDatabase) {

    companion object {
        private const val TAG = "GameScreenReader"
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val analyzer   = ScreenAnalyzer(db)

    suspend fun read(bitmap: Bitmap, ignore: List<NormRect> = emptyList()): ReadResult = withContext(Dispatchers.Default) {
        val (lines, rawText) = recognizeWithPosition(bitmap)

        Log.d(TAG, "=== OCR: ${lines.size} blocks ===")
        lines.forEach { Log.d(TAG, "  [${it.cx.fmt()}x${it.cy.fmt()}] \"${it.text}\"") }

        val reading = analyzer.analyze(lines, ignore)
        Log.d(TAG, "state=${reading.state} allies=${reading.allies.map{it.name}} enemies=${reading.enemies.map{it.name}}")

        ReadResult(reading, rawText)
    }

    private suspend fun recognizeWithPosition(bitmap: Bitmap): Pair<List<OcrLine>, String> =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val w = bitmap.width.toFloat()
                    val h = bitmap.height.toFloat()
                    val lines = mutableListOf<OcrLine>()
                    val rawBuilder = StringBuilder()

                    for (block in result.textBlocks) {
                        for (line in block.lines) {
                            val box = line.boundingBox ?: continue
                            val cx = (box.left + box.right) / 2f / w
                            val cy = (box.top + box.bottom) / 2f / h
                            lines.add(OcrLine(line.text, cx, cy))
                            rawBuilder.append(line.text).append("\n")
                        }
                    }
                    cont.resume(Pair(lines, rawBuilder.toString().trim()))
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "OCR failed: ${e.message}")
                    cont.resumeWithException(e)
                }
        }

    fun close() = recognizer.close()

    private fun Float.fmt() = "%.2f".format(this)
}
