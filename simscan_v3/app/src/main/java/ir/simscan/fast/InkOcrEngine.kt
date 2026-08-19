package ir.simscan.fast

import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

class InkOcrEngine {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private data class Variant(val bitmap: Bitmap, val owned: Boolean)

    fun recognize(source: Bitmap, baseRotation: Int, callback: (String?) -> Unit) {
        val finished = AtomicBoolean(false)
        val variants = ArrayList<Variant>()
        makeInkMask(source, Ink.RED)?.let { variants += Variant(it, true) }
        makeInkMask(source, Ink.BLUE)?.let { variants += Variant(it, true) }
        variants += Variant(source, false)

        val rotations = intArrayOf(
            normalizeRotation(baseRotation),
            normalizeRotation(baseRotation + 90),
            normalizeRotation(baseRotation + 180),
            normalizeRotation(baseRotation + 270)
        ).distinct().toIntArray()

        fun cleanup() {
            variants.filter { it.owned }.forEach {
                if (!it.bitmap.isRecycled) it.bitmap.recycle()
            }
            if (!source.isRecycled) source.recycle()
        }

        fun finish(value: String?) {
            if (finished.compareAndSet(false, true)) {
                cleanup()
                callback(value)
            }
        }

        fun runAttempt(variantIndex: Int, rotationIndex: Int) {
            if (finished.get()) return
            if (variantIndex >= variants.size) {
                finish(null)
                return
            }
            if (rotationIndex >= rotations.size) {
                runAttempt(variantIndex + 1, 0)
                return
            }

            val variant = variants[variantIndex]
            val image = InputImage.fromBitmap(variant.bitmap, rotations[rotationIndex])
            recognizer.process(image)
                .addOnSuccessListener { text ->
                    val candidate = bestCandidate(text)
                    if (candidate != null) finish(candidate)
                    else runAttempt(variantIndex, rotationIndex + 1)
                }
                .addOnFailureListener {
                    runAttempt(variantIndex, rotationIndex + 1)
                }
        }

        runAttempt(0, 0)
    }

    fun close() = recognizer.close()

    private fun bestCandidate(text: Text): String? {
        data class Scored(val phone: String, val score: Int)
        var best: Scored? = null

        fun consider(raw: String, sizeScore: Int = 0) {
            val phone = PhoneNormalizer.validMobile(raw) ?: return
            var score = 100 + sizeScore
            if (raw.contains('۰') || raw.contains('٠')) score += 3
            if (phone.startsWith("09")) score += 5
            if (best == null || score > best!!.score) best = Scored(phone, score)
        }

        for (block in text.textBlocks) {
            for (line in block.lines) {
                val b = line.boundingBox
                consider(line.text, if (b != null) min(30, max(b.width(), b.height()) / 10) else 0)
                for (element in line.elements) {
                    val e = element.boundingBox
                    consider(element.text, if (e != null) min(30, max(e.width(), e.height()) / 10) else 0)
                }
            }
        }
        consider(text.text)
        return best?.phone
    }

    private enum class Ink { RED, BLUE }

    private fun makeInkMask(source: Bitmap, ink: Ink): Bitmap? {
        val w = source.width
        val h = source.height
        if (w <= 0 || h <= 0) return null
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        var count = 0

        fun matches(c: Int): Boolean {
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            return when (ink) {
                Ink.RED -> r >= 105 && r - g >= 22 && r - b >= 4 && (r + b) - (g * 2) >= 25
                Ink.BLUE -> b >= 95 && b - r >= 18 && b - g >= 10
            }
        }

        var y = 0
        while (y < h) {
            var x = 0
            val row = y * w
            while (x < w) {
                if (matches(pixels[row + x])) {
                    count++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
                x++
            }
            y++
        }

        if (count < 120 || maxX <= minX || maxY <= minY) return null

        val margin = 36
        minX = max(0, minX - margin)
        minY = max(0, minY - margin)
        maxX = min(w - 1, maxX + margin)
        maxY = min(h - 1, maxY + margin)
        val cw = maxX - minX + 1
        val ch = maxY - minY + 1
        if (cw < 40 || ch < 40) return null

        val outPixels = IntArray(cw * ch) { Color.WHITE }
        for (yy in 0 until ch) {
            val srcRow = (minY + yy) * w
            val dstRow = yy * cw
            for (xx in 0 until cw) {
                if (matches(pixels[srcRow + minX + xx])) {
                    outPixels[dstRow + xx] = Color.BLACK
                }
            }
        }
        var mask = Bitmap.createBitmap(outPixels, cw, ch, Bitmap.Config.ARGB_8888)

        val longest = max(cw, ch)
        val factor = when {
            longest < 700 -> 2.5f
            longest < 1200 -> 1.8f
            else -> 1.25f
        }
        val nw = min(2400, (cw * factor).toInt().coerceAtLeast(cw))
        val nh = min(2400, (ch * factor).toInt().coerceAtLeast(ch))
        if (nw != cw || nh != ch) {
            val scaled = Bitmap.createScaledBitmap(mask, nw, nh, true)
            mask.recycle()
            mask = scaled
        }
        return mask
    }

    private fun normalizeRotation(value: Int): Int {
        val v = ((value % 360) + 360) % 360
        return when {
            v < 45 || v >= 315 -> 0
            v < 135 -> 90
            v < 225 -> 180
            else -> 270
        }
    }
}
