package com.safekeyboard.media

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * NsfwImageClassifier - On-device NSFW/nudity image classifier
 *
 * Runs a MobileNetV2-based 5-class NSFW classifier entirely on-device
 * (no cloud inference, no image upload) to flag sensitive images before
 * they are sent from the keyboard.
 *
 * Model: compatible with the open-source GantMan/nsfw_model (Keras,
 * MobileNetV2 backbone) converted to TensorFlow Lite. See
 * assets/models/MODEL_CARD_NSFW.md for conversion instructions and the
 * expected tensor shapes. The actual `.tflite` binary is NOT bundled in
 * this repo (see point 4 below / the model card).
 *
 * Classes (softmax order expected by this code):
 *   0 = drawings, 1 = hentai, 2 = neutral, 3 = porn, 4 = sexy
 *
 * NON-NEGOTIABLES (mirrors ToxicityAnalyzer / EnhancedToxicityAnalyzer):
 * - No cloud inference
 * - No image upload
 * - No logging of image content/bytes
 * - Works offline
 * - FAIL-OPEN: if the model asset is missing, fails to load, or inference
 *   throws for any reason, we never block the user — we log a warning and
 *   return a "not sensitive" result.
 */
class NsfwImageClassifier(private val context: Context) {

    companion object {
        private const val TAG = "NsfwImageClassifier"

        private const val MODEL_ASSET_PATH = "models/nsfw_classifier.tflite"

        private const val INPUT_IMAGE_SIZE = 224
        private const val INPUT_CHANNELS = 3
        private const val PIXEL_MAX_VALUE = 255.0f

        // Index order matches the GantMan/nsfw_model softmax output.
        private const val LABEL_DRAWINGS = "drawings"
        private const val LABEL_HENTAI = "hentai"
        private const val LABEL_NEUTRAL = "neutral"
        private const val LABEL_PORN = "porn"
        private const val LABEL_SEXY = "sexy"

        val LABELS: List<String> = listOf(
            LABEL_DRAWINGS,
            LABEL_HENTAI,
            LABEL_NEUTRAL,
            LABEL_PORN,
            LABEL_SEXY
        )

        // Sensitive if porn + hentai + sexy probabilities sum >= this threshold.
        const val DEFAULT_SENSITIVE_THRESHOLD = 0.5f

        // Reported category for sensitive results (matches canonical schema style).
        const val CATEGORY_SENSITIVE_IMAGE = "sensitive_image"

        private const val LABEL_UNAVAILABLE = "unavailable"
    }

    /**
     * @param isSensitive true if the combined porn+hentai+sexy probability mass
     *   meets or exceeds the configured threshold.
     * @param confidence probability of the winning [label] (0.0 - 1.0).
     * @param category always "sensitive_image" for this classifier's findings.
     * @param label highest-probability class name, or "unavailable" if the
     *   classifier failed open.
     */
    data class ClassificationResult(
        val isSensitive: Boolean,
        val confidence: Float,
        val category: String = CATEGORY_SENSITIVE_IMAGE,
        val label: String
    )

    // Lazily loaded so a missing/corrupt model asset doesn't crash construction —
    // failure is caught and surfaced only when classify() is actually called.
    private val interpreter: Interpreter? by lazy {
        try {
            val model = loadModelFile(MODEL_ASSET_PATH)
            Interpreter(model)
        } catch (t: Throwable) {
            Log.w(TAG, "NSFW model failed to load from $MODEL_ASSET_PATH: ${t.message}")
            null
        }
    }

    /**
     * Classifies a bitmap as sensitive/non-sensitive using the on-device
     * NSFW model. Runs on [Dispatchers.Default].
     *
     * FAIL-OPEN: any failure (missing model, bad input, inference error)
     * results in a non-sensitive "unavailable" result — this classifier
     * never blocks the user due to an internal error.
     *
     * @param sensitiveThreshold combined porn+hentai+sexy probability
     *   threshold above which the image is flagged sensitive.
     */
    suspend fun classify(
        bitmap: Bitmap,
        sensitiveThreshold: Float = DEFAULT_SENSITIVE_THRESHOLD
    ): ClassificationResult = withContext(Dispatchers.Default) {
        try {
            val clf = interpreter ?: run {
                Log.w(TAG, "NSFW classifier unavailable (model not loaded) — failing open")
                return@withContext unavailableResult()
            }

            val input = preprocess(bitmap)
            val output = Array(1) { FloatArray(LABELS.size) }
            clf.run(input, output)

            val probabilities = output[0]
            postprocess(probabilities, sensitiveThreshold)
        } catch (t: Throwable) {
            Log.w(TAG, "NSFW classification failed, failing open: ${t.message}", t)
            unavailableResult()
        }
    }

    /**
     * Releases the TFLite interpreter. Safe to call multiple times.
     */
    fun close() {
        try {
            interpreter?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "Error closing NSFW interpreter: ${t.message}")
        }
    }

    /**
     * Resizes the bitmap to [INPUT_IMAGE_SIZE]x[INPUT_IMAGE_SIZE] and packs it
     * into a float32 NHWC buffer normalized to [0, 1], matching the
     * GantMan/nsfw_model MobileNetV2 preprocessing.
     */
    private fun preprocess(bitmap: Bitmap): ByteBuffer {
        val resized = if (bitmap.width == INPUT_IMAGE_SIZE && bitmap.height == INPUT_IMAGE_SIZE) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, INPUT_IMAGE_SIZE, INPUT_IMAGE_SIZE, true)
        }

        val byteBuffer = ByteBuffer.allocateDirect(
            4 * INPUT_IMAGE_SIZE * INPUT_IMAGE_SIZE * INPUT_CHANNELS
        )
        byteBuffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(INPUT_IMAGE_SIZE * INPUT_IMAGE_SIZE)
        resized.getPixels(pixels, 0, INPUT_IMAGE_SIZE, 0, 0, INPUT_IMAGE_SIZE, INPUT_IMAGE_SIZE)

        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            byteBuffer.putFloat(r / PIXEL_MAX_VALUE)
            byteBuffer.putFloat(g / PIXEL_MAX_VALUE)
            byteBuffer.putFloat(b / PIXEL_MAX_VALUE)
        }

        if (resized !== bitmap) {
            resized.recycle()
        }

        byteBuffer.rewind()
        return byteBuffer
    }

    /**
     * Interprets the softmax output: sums porn+hentai+sexy mass for the
     * sensitivity decision, and reports the single highest-probability
     * label/confidence.
     */
    private fun postprocess(probabilities: FloatArray, sensitiveThreshold: Float): ClassificationResult {
        val hentaiIdx = LABELS.indexOf(LABEL_HENTAI)
        val pornIdx = LABELS.indexOf(LABEL_PORN)
        val sexyIdx = LABELS.indexOf(LABEL_SEXY)

        val sensitiveMass = (probabilities.getOrNull(hentaiIdx) ?: 0f) +
            (probabilities.getOrNull(pornIdx) ?: 0f) +
            (probabilities.getOrNull(sexyIdx) ?: 0f)

        var topIdx = 0
        for (i in probabilities.indices) {
            if (probabilities[i] > probabilities[topIdx]) topIdx = i
        }
        val topLabel = LABELS.getOrElse(topIdx) { LABEL_NEUTRAL }
        val topConfidence = probabilities.getOrElse(topIdx) { 0f }

        val isSensitive = sensitiveMass >= sensitiveThreshold

        return ClassificationResult(
            isSensitive = isSensitive,
            confidence = topConfidence,
            label = topLabel
        )
    }

    private fun unavailableResult(): ClassificationResult = ClassificationResult(
        isSensitive = false,
        confidence = 0f,
        label = LABEL_UNAVAILABLE
    )

    /**
     * Loads the `.tflite` model from assets as a memory-mapped buffer.
     * Throws if the asset is missing/unreadable — caller handles fail-open.
     */
    private fun loadModelFile(assetPath: String): MappedByteBuffer {
        context.assets.openFd(assetPath).use { assetFileDescriptor ->
            FileInputStream(assetFileDescriptor.fileDescriptor).use { inputStream ->
                val fileChannel = inputStream.channel
                val startOffset = assetFileDescriptor.startOffset
                val declaredLength = assetFileDescriptor.declaredLength
                return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
            }
        }
    }
}
