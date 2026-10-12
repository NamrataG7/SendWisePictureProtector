package com.safekeyboard.nlp

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream
import kotlin.math.ln

/**
 * RandomForestTextClassifier
 *
 * Loads a TF-IDF + RandomForest model exported by
 * `model_training/export_to_kotlin_json.py` (gzip-JSON in assets/models/).
 *
 * Schema (see the exporter's docstring):
 *   model_name, version, task ("binary"|"multiclass"), n_features, n_classes,
 *   threshold, classes[], vocabulary{term:idx}, idf[], trees[]
 *
 * Each tree carries:
 *   feature[], threshold[], children_left[], children_right[], value[][]
 * Leaves have children_left[i] == -1; value[i] holds raw class weights/counts
 * which we normalise on the fly to a probability vector.
 *
 * This class is intentionally dependency-free (no JSON lib besides org.json,
 * which is bundled with Android). It is thread-safe for prediction after
 * `load()` completes.
 */
class RandomForestTextClassifier private constructor(
    val modelName: String,
    val version: String,
    val task: String,
    val classes: List<String>,
    val threshold: Double,
    private val nFeatures: Int,
    private val vocabulary: Map<String, Int>,
    private val idf: DoubleArray,
    private val trees: Array<Tree>,
) {

    private class Tree(
        val feature: IntArray,
        val threshold: DoubleArray,
        val left: IntArray,
        val right: IntArray,
        /** value[node][class] — raw class weights at that node (leaves are what matter). */
        val value: Array<DoubleArray>,
    ) {
        /** Return normalised class-probability vector from the leaf reached by `x`. */
        fun predictProba(x: DoubleArray, out: DoubleArray) {
            var node = 0
            while (left[node] != -1) {
                val f = feature[node]
                val v = if (f >= 0 && f < x.size) x[f] else 0.0
                node = if (v <= threshold[node]) left[node] else right[node]
            }
            val row = value[node]
            var sum = 0.0
            for (v in row) sum += v
            if (sum <= 0.0) {
                val uniform = 1.0 / out.size
                for (i in out.indices) out[i] = uniform
            } else {
                for (i in out.indices) out[i] = row[i] / sum
            }
        }
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    data class Prediction(
        val label: String,
        val probabilities: Map<String, Double>,
        val topProbability: Double,
    )

    /** Vectorise `text` to a TF-IDF vector of length nFeatures (dense). */
    fun vectorize(text: String): DoubleArray {
        val vec = DoubleArray(nFeatures)
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return vec

        val termCounts = HashMap<Int, Int>()
        // unigrams
        for (tok in tokens) {
            val idx = vocabulary[tok] ?: continue
            termCounts[idx] = (termCounts[idx] ?: 0) + 1
        }
        // bigrams
        if (tokens.size >= 2) {
            for (i in 0 until tokens.size - 1) {
                val bg = tokens[i] + " " + tokens[i + 1]
                val idx = vocabulary[bg] ?: continue
                termCounts[idx] = (termCounts[idx] ?: 0) + 1
            }
        }
        if (termCounts.isEmpty()) return vec

        // TF-IDF (sklearn default): tf * idf, then L2-normalise
        var sqSum = 0.0
        for ((idx, count) in termCounts) {
            val w = count.toDouble() * idf[idx]
            vec[idx] = w
            sqSum += w * w
        }
        if (sqSum > 0.0) {
            val norm = Math.sqrt(sqSum)
            for ((idx, _) in termCounts) vec[idx] = vec[idx] / norm
        }
        return vec
    }

    /** Average per-tree class probabilities. */
    fun predictProba(text: String): DoubleArray {
        val x = vectorize(text)
        val agg = DoubleArray(classes.size)
        val tmp = DoubleArray(classes.size)
        for (tree in trees) {
            tree.predictProba(x, tmp)
            for (i in agg.indices) agg[i] += tmp[i]
        }
        val n = trees.size.toDouble()
        for (i in agg.indices) agg[i] /= n
        return agg
    }

    /** Classify with the exporter's decision threshold (binary) or argmax (multiclass). */
    fun predict(text: String): Prediction {
        val probs = predictProba(text)
        val probMap = LinkedHashMap<String, Double>(classes.size)
        for (i in classes.indices) probMap[classes[i]] = probs[i]

        val label: String
        val top: Double
        if (task == "binary" && classes.size == 2) {
            // Use exporter threshold on positive class (index 1)
            val pos = probs[1]
            label = if (pos >= threshold) classes[1] else classes[0]
            top = if (pos >= threshold) pos else 1.0 - pos
        } else {
            var best = 0
            for (i in 1 until probs.size) if (probs[i] > probs[best]) best = i
            label = classes[best]
            top = probs[best]
        }
        return Prediction(label, probMap, top)
    }

    // ------------------------------------------------------------------
    // Tokenisation — mirrors sklearn TfidfVectorizer defaults
    //   token_pattern = r"(?u)\b\w\w+\b", lowercase=True, strip_accents='unicode'
    // ------------------------------------------------------------------
    private fun tokenize(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val stripped = stripAccents(text.lowercase())
        val tokens = ArrayList<String>()
        val m = TOKEN_RE.matcher(stripped)
        while (m.find()) tokens.add(m.group())
        return tokens
    }

    private fun stripAccents(s: String): String {
        val nfd = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        val sb = StringBuilder(nfd.length)
        for (c in nfd) if (Character.getType(c).toByte() != Character.NON_SPACING_MARK) sb.append(c)
        return sb.toString()
    }

    companion object {
        private const val TAG = "RandomForestTC"
        private val TOKEN_RE = java.util.regex.Pattern.compile("\\b\\w\\w+\\b", java.util.regex.Pattern.UNICODE_CHARACTER_CLASS)

        /** Load a model from `assets/models/<assetName>` (must be gzip-JSON). */
        fun load(context: Context, assetName: String): RandomForestTextClassifier {
            val t0 = System.currentTimeMillis()
            val jsonText = context.assets.open("models/$assetName").use { input ->
                GZIPInputStream(input).use { gz ->
                    BufferedReader(InputStreamReader(gz, Charsets.UTF_8)).readText()
                }
            }
            val root = JSONObject(jsonText)

            val modelName = root.optString("model_name", assetName)
            val version = root.optString("version", "unknown")
            val task = root.optString("task", "binary")
            val nFeatures = root.getInt("n_features")
            val nClasses = root.getInt("n_classes")
            val threshold = root.optDouble("threshold", 0.5)

            val classesArr = root.getJSONArray("classes")
            val classes = ArrayList<String>(classesArr.length())
            for (i in 0 until classesArr.length()) classes.add(classesArr.getString(i))
            require(classes.size == nClasses) { "classes length != n_classes" }

            val vocabObj = root.getJSONObject("vocabulary")
            val vocab = HashMap<String, Int>(vocabObj.length() * 2)
            val vkeys = vocabObj.keys()
            while (vkeys.hasNext()) {
                val k = vkeys.next()
                vocab[k] = vocabObj.getInt(k)
            }

            val idfArr = root.getJSONArray("idf")
            require(idfArr.length() == nFeatures) { "idf length != n_features" }
            val idf = DoubleArray(nFeatures)
            for (i in 0 until nFeatures) idf[i] = idfArr.getDouble(i)

            val treesArr = root.getJSONArray("trees")
            val trees = Array(treesArr.length()) { i -> parseTree(treesArr.getJSONObject(i)) }

            val ms = System.currentTimeMillis() - t0
            Log.i(TAG, "loaded $assetName v$version task=$task trees=${trees.size} " +
                "features=$nFeatures classes=$classes in ${ms}ms")
            return RandomForestTextClassifier(
                modelName, version, task, classes, threshold,
                nFeatures, vocab, idf, trees,
            )
        }

        private fun parseTree(o: JSONObject): Tree {
            val fArr = o.getJSONArray("feature")
            val tArr = o.getJSONArray("threshold")
            val lArr = o.getJSONArray("children_left")
            val rArr = o.getJSONArray("children_right")
            val vArr = o.getJSONArray("value")
            val n = fArr.length()
            // Widen to Long then narrow to Int so we accept JSON numbers that
            // the parser boxed as Long (>2^31) or Double (e.g. -1.0 for leaf
            // sentinels emitted by some sklearn exporters).
            val feature = IntArray(n) { safeInt(fArr.get(it)) }
            val threshold = DoubleArray(n) { safeDouble(tArr.get(it)) }
            val left = IntArray(n) { safeInt(lArr.get(it)) }
            val right = IntArray(n) { safeInt(rArr.get(it)) }
            val value = Array(n) { i ->
                val row = vArr.getJSONArray(i)
                DoubleArray(row.length()) { j -> safeDouble(row.get(j)) }
            }
            return Tree(feature, threshold, left, right, value)
        }

        /**
         * Robust number->Int coercion. JSON parser may box small integers
         * as Integer, larger as Long, and floats as Double. We accept all.
         */
        private fun safeInt(v: Any?): Int = when (v) {
            is Int -> v
            is Long -> v.toInt()
            is Double -> v.toInt()
            is Number -> v.toInt()
            is String -> v.toInt()
            else -> throw ClassCastException("cannot cast ${v?.javaClass?.name} to Int")
        }

        private fun safeDouble(v: Any?): Double = when (v) {
            is Double -> v
            is Int -> v.toDouble()
            is Long -> v.toDouble()
            is Number -> v.toDouble()
            is String -> v.toDouble()
            else -> throw ClassCastException("cannot cast ${v?.javaClass?.name} to Double")
        }

        // Silence unused-import warning if ln() ever gets removed above.
        @Suppress("unused") private val _ln = ln(2.0)
    }
}
