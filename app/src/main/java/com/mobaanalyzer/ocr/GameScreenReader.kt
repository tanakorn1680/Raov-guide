package com.mobaanalyzer.ocr

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.mobaanalyzer.model.GameState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * GameScreenReader
 *
 * รับ Bitmap จาก MediaProjection → ส่งเข้า ML Kit OCR → คืน raw text
 * แล้วส่งให้ GameStateParser แปลงเป็น GameState
 *
 * ใช้ Latin recognizer เพราะ ROV ใช้ตัวเลขและอักษรภาษาอังกฤษเป็นหลัก
 * (ชื่อฮีโร่, ตัวเลข HP, timer, score)
 */
class GameScreenReader {

    companion object {
        private const val TAG = "GameScreenReader"
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val parser = GameStateParser()

    /**
     * อ่าน Bitmap → GameState
     * suspend function — เรียกใน coroutine
     */
    suspend fun read(bitmap: Bitmap): GameState = withContext(Dispatchers.Default) {
        val rawText = recognizeText(bitmap)
        Log.d(TAG, "OCR raw:\n$rawText")
        parser.parse(rawText)
    }

    /**
     * อ่าน เฉพาะ region ที่ต้องการ (crop ก่อน OCR → เร็วขึ้น)
     *
     * @param bitmap  ภาพเต็มหน้าจอ
     * @param xRatio  สัดส่วนซ้าย  (0.0–1.0)
     * @param yRatio  สัดส่วนบน   (0.0–1.0)
     * @param wRatio  สัดส่วนกว้าง (0.0–1.0)
     * @param hRatio  สัดส่วนสูง   (0.0–1.0)
     */
    suspend fun readRegion(
        bitmap: Bitmap,
        xRatio: Float, yRatio: Float,
        wRatio: Float, hRatio: Float
    ): String = withContext(Dispatchers.Default) {
        val x = (bitmap.width  * xRatio).toInt().coerceIn(0, bitmap.width  - 1)
        val y = (bitmap.height * yRatio).toInt().coerceIn(0, bitmap.height - 1)
        val w = (bitmap.width  * wRatio).toInt().coerceIn(1, bitmap.width  - x)
        val h = (bitmap.height * hRatio).toInt().coerceIn(1, bitmap.height - y)

        val crop = Bitmap.createBitmap(bitmap, x, y, w, h)
        recognizeText(crop).also { crop.recycle() }
    }

    /**
     * ML Kit text recognition — suspend wrapper
     */
    private suspend fun recognizeText(bitmap: Bitmap): String =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val text = result.textBlocks
                        .joinToString("\n") { block ->
                            block.lines.joinToString(" ") { it.text }
                        }
                    cont.resume(text)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "OCR failed: ${e.message}")
                    cont.resumeWithException(e)
                }
        }

    fun close() {
        recognizer.close()
    }
}
