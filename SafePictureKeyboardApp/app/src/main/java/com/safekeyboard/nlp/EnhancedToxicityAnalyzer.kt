package com.safekeyboard.nlp

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * EnhancedToxicityAnalyzer - Uses shared detection library via WebView
 *
 * This analyzer provides 90-95% accuracy by using the same advanced detection
 * logic as the Chrome Extension:
 * - Rule-based detection (75-80% base)
 * - Emoji sentiment analysis (reduces false positives by 5-10%)
 * - Sarcasm detection (reduces false positives by 5-10%)
 * - Platform context awareness (gaming, professional, technical contexts)
 * - Progressive warning escalation (4 levels)
 *
 * Falls back to the basic ToxicityAnalyzer if WebView is not ready.
 *
 * Thread-safe: Can be called from any thread
 */
class EnhancedToxicityAnalyzer(private val context: Context) {

    companion object {
        private const val TAG = "EnhancedToxicityAnalyzer"
        private const val DETECT_TAG = "SendWiseDetect"
        private const val DEFAULT_SENSITIVITY = 0.5
    }

    data class AnalysisResult(
        val toxicityScore: Float,
        val originalScore: Float,
        val category: String,
        val severity: String,
        val isToxic: Boolean,
        val adjustments: Adjustments? = null,
        val usingEnhanced: Boolean = false
    )

    data class Adjustments(
        val emoji: Boolean = false,
        val sarcasm: Boolean = false,
        val context: Boolean = false
    )

    data class WarningLevel(
        val level: String,
        val tone: String,
        val title: String,
        val subtitle: String? = null,
        val cooldownSeconds: Int = 0,
        val showViolationCount: Boolean = false,
        val showConsequences: Boolean = false
    )

    private val webViewBridge: WebViewBridge = WebViewBridge(context)
    private val fallbackAnalyzer: ToxicityAnalyzer = ToxicityAnalyzer(context)

    /**
     * Hard-trigger short-circuit list. Any occurrence of these tokens as a
     * standalone word (case-insensitive, punctuation-tolerant) fires the
     * warning overlay immediately with score=1.0 — no RF, no lexicon, no
     * thresholds, no debounce dependency. Guarantees the detection path
     * is functional even if RF fails to load or lexicon regresses.
     */
    /**
     * Hard-trigger short-circuit list, mapped by category.
     * Any occurrence of these tokens as a standalone word (case-insensitive,
     * punctuation-tolerant) fires the warning overlay immediately with
     * score=1.0 and the correct category — no RF, no lexicon, no thresholds,
     * no debounce dependency.
     */
    private val HARD_TRIGGERS: List<Pair<String, String>> = buildList {
        // SELF-HARM RISK - suicidal ideation, self-injury
        listOf(
            "kill myself", "end my life", "end it all", "want to die",
            "wanna die", "wish i was dead", "wish i were dead",
            "no reason to live", "cant go on", "can't go on",
            "jump off", "hang myself", "cut myself", "cutting myself",
            "self harm", "self-harm", "suicidal", "suicide",
            "kys", "kill yourself", "kys yourself"
        ).forEach { add(it to ToxicityAnalyzer.CATEGORY_SELF_HARM) }

        // THREATS - violence, intimidation
        listOf(
            "kill you", "gonna kill", "going to kill", "murder you",
            "beat you up", "beat the shit", "beat you down",
            "hurt you", "gonna hurt", "going to hurt",
            "watch your back", "you're dead", "youre dead",
            "gonna find you", "going to find you", "coming for you",
            "shoot you", "stab you", "break your"
        ).forEach { add(it to ToxicityAnalyzer.CATEGORY_THREATS) }

        // SEXUAL CONTENT - unwanted sexual references
        listOf(
            "have sex", "want to fuck", "wanna fuck", "fuck you",
            "send nudes", "send nude", "send pics", "send pic",
            "show your", "show me your", "your body",
            "get naked", "take off your", "strip for",
            "hookup", "hook up with", "porn", "pornography",
            "sex chat", "dick pic", "boobs", "tits", "titties"
        ).forEach { add(it to ToxicityAnalyzer.CATEGORY_SEXUAL_CONTENT) }

        // HATE SPEECH - slurs targeting protected groups
        listOf(
            "faggot", "nigger", "chink", "kike", "spic", "tranny",
            "retard", "retarded"
        ).forEach { add(it to ToxicityAnalyzer.CATEGORY_HATE_SPEECH) }

        // HARASSMENT - personal insults, profanity (default catch-all)
        listOf(
            "stupid", "idiot", "moron", "loser", "ugly", "worthless",
            "bitch", "bastard", "asshole", "motherfucker", "fuck",
            "fucker", "fucking", "fucked", "shit", "damn", "prick",
            "dick", "dickhead", "cunt", "slut", "whore", "hoe",
            "dumbass", "pathetic", "nobody likes you", "hate you"
        ).forEach { add(it to ToxicityAnalyzer.CATEGORY_HARASSMENT) }
    }

    /**
     * Public wrapper for use by IME callers who want to bypass length/word
     * thresholds when the buffer clearly contains an unambiguous slur.
     * Returns the matched token or null.
     */
    fun containsHardTriggerToken(message: String): String? =
        try { detectHardProfanity(message)?.first } catch (_: Throwable) { null }

    /**
     * Returns (matchedToken, category) or null.
     *
     * Priority: iterates HARD_TRIGGERS in order (self-harm > threats >
     * sexual > hate > harassment) so that "i want to kill myself"
     * matches self-harm, not the "kill" substring of threats.
     */
    private fun detectHardProfanity(message: String): Pair<String, String>? {
        val lc = message.lowercase()
        for ((word, cat) in HARD_TRIGGERS) {
            if (word.contains(' ')) {
                // multi-word phrase — substring match
                if (lc.contains(word)) return word to cat
            } else {
                // single word — bounded by non-letter or start/end
                val re = Regex("(^|[^a-z])${Regex.escape(word)}([^a-z]|$)")
                if (re.containsMatchIn(lc)) return word to cat
            }
        }
        return null
    }

    // Paper's actual method: TF-IDF + RandomForest. Loaded lazily on first use
    // (which happens on Dispatchers.Default from the IME, so this won't ANR
    // the input-method main thread on service start).
    // See assets/models/sendwise_rf_v1.json.gz (binary risk) and
    // sendwise_category_v1.json.gz (5-way category over risk-positive rows).
    private val rfBinary: RandomForestTextClassifier? by lazy {
        try {
            val clf = RandomForestTextClassifier.load(context, "sendwise_rf_v1.json.gz")
            Log.i(DETECT_TAG, "RF binary loaded: ${clf.modelName} v${clf.version} " +
                "task=${clf.task} classes=${clf.classes} threshold=${clf.threshold}")
            clf
        } catch (t: Throwable) {
            Log.w(DETECT_TAG, "RF binary failed to load: ${t.message}", t)
            null
        }
    }

    private val rfCategory: RandomForestTextClassifier? by lazy {
        try {
            val clf = RandomForestTextClassifier.load(context, "sendwise_category_v1.json.gz")
            Log.i(DETECT_TAG, "RF category loaded: ${clf.modelName} v${clf.version} " +
                "task=${clf.task} classes=${clf.classes}")
            clf
        } catch (t: Throwable) {
            Log.w(DETECT_TAG, "RF category failed to load: ${t.message}", t)
            null
        }
    }

    /**
     * Analyze a message for toxicity
     *
     * @param message Message text to analyze
     * @param sensitivity Detection threshold (0.0-1.0, default 0.5)
     * @param platform Optional platform hostname for context awareness
     * @return Analysis result with all enhancements applied
     */
    fun analyzeMessage(
        message: String,
        sensitivity: Double = DEFAULT_SENSITIVITY,
        platform: String = ""
    ): AnalysisResult {
        Log.v(DETECT_TAG, "analyzeMessage text.len=${message.length} sensitivity=$sensitivity")

        // Priority 0: Hard-trigger short-circuit — guarantees the popup
        // fires for unambiguously abusive words regardless of RF / lexicon /
        // threshold state. Isolates keyboard-lifecycle bugs from analyzer bugs.
        val hardHit = try { detectHardProfanity(message) } catch (t: Throwable) {
            Log.w(DETECT_TAG, "detectHardProfanity threw: ${t.message}", t); null
        }
        if (hardHit != null) {
            val (matchedToken, matchedCategory) = hardHit
            Log.d(DETECT_TAG, "HARD-TRIGGER hit=\"$matchedToken\" cat=$matchedCategory len=${message.length}")
            // Self-harm + threats are inherently high severity;
            // hate-speech / sexual-content are high; harassment defaults medium.
            val sev = when (matchedCategory) {
                ToxicityAnalyzer.CATEGORY_SELF_HARM,
                ToxicityAnalyzer.CATEGORY_THREATS,
                ToxicityAnalyzer.CATEGORY_HATE_SPEECH,
                ToxicityAnalyzer.CATEGORY_SEXUAL_CONTENT -> "high"
                else -> "medium"
            }
            return AnalysisResult(
                toxicityScore = 1.0f,
                originalScore = 1.0f,
                category = matchedCategory,
                severity = sev,
                isToxic = true,
                usingEnhanced = false
            )
        }

        // Priority 1: RandomForest binary risk classifier (the paper's actual
        // method). Wrapped in an OUTER try/catch to defend against exceptions
        // thrown from the `by lazy` initializer (JSON parse errors, ClassCast,
        // etc.) which bypass the inner try/catch in tryRandomForest.
        val rfResult = try {
            tryRandomForest(message, sensitivity)
        } catch (t: Throwable) {
            Log.w(DETECT_TAG, "RF outer catch: ${t.javaClass.simpleName}: ${t.message}", t)
            null
        }
        if (rfResult != null) {
            val enriched = try {
                maybeAttachWebViewCategory(rfResult, message, sensitivity, platform)
            } catch (t: Throwable) {
                Log.w(DETECT_TAG, "WebView enrich threw, using RF result as-is: ${t.message}")
                rfResult
            }
            Log.d(DETECT_TAG, "Final toxicityScore=${enriched.toxicityScore} " +
                "isToxic=${enriched.isToxic} category=${enriched.category} source=RF")
            return enriched
        }

        // Priority 2: Deterministic lexicon fallback. Guaranteed to run even if
        // the analyzer itself throws — worst case we return a "clean" result.
        val fallback = try {
            useFallbackAnalyzer(message, sensitivity)
        } catch (t: Throwable) {
            Log.w(DETECT_TAG, "Lexicon fallback threw: ${t.message}", t)
            AnalysisResult(
                toxicityScore = 0.0f,
                originalScore = 0.0f,
                category = ToxicityAnalyzer.CATEGORY_NONE,
                severity = "none",
                isToxic = false,
                usingEnhanced = false
            )
        }
        Log.d(DETECT_TAG, "Final toxicityScore=${fallback.toxicityScore} " +
            "isToxic=${fallback.isToxic} category=${fallback.category} source=LEXICON")
        return fallback
    }

    /**
     * Run the RandomForest binary risk classifier. Returns null if unavailable or
     * if it produced a non-finite score (in which case caller falls back to lexicon).
     */
    private fun tryRandomForest(message: String, sensitivity: Double): AnalysisResult? {
        val clf = rfBinary ?: run {
            Log.v(DETECT_TAG, "RF unavailable — skipping")
            return null
        }
        return try {
            val probs = clf.predictProba(message)
            // Binary model: risk-positive index. Prefer explicit "risk"-like class,
            // else fall back to index 1 (exporter convention: [negative, positive]).
            val posIdx = clf.classes.indexOfFirst { c ->
                val lc = c.lowercase()
                lc.contains("risk") && !lc.contains("non") || lc == "1" || lc == "true" ||
                    lc == "toxic" || lc == "positive"
            }.let { if (it >= 0) it else (clf.classes.size - 1).coerceAtLeast(0) }
            val riskScore = probs.getOrNull(posIdx) ?: Double.NaN
            if (riskScore.isNaN() || riskScore.isInfinite()) {
                Log.w(DETECT_TAG, "RF failed: non-finite score")
                return null
            }
            Log.v(DETECT_TAG, "RF returned score=$riskScore (posClass=${clf.classes.getOrNull(posIdx)})")

            val threshold = minOf(clf.threshold, sensitivity)
            val isToxic = riskScore >= threshold

            // Category: try RF category model first, else derive from lexicon.
            val category = classifyCategory(message) ?: ToxicityAnalyzer.CATEGORY_NONE
            val severity = when {
                riskScore >= 0.75 -> "high"
                riskScore >= 0.45 -> "medium"
                riskScore >= 0.25 -> "low"
                else -> "none"
            }
            AnalysisResult(
                toxicityScore = riskScore.toFloat(),
                originalScore = riskScore.toFloat(),
                category = if (isToxic) category else ToxicityAnalyzer.CATEGORY_NONE,
                severity = if (isToxic) severity else "none",
                isToxic = isToxic,
                usingEnhanced = true
            )
        } catch (t: Throwable) {
            Log.w(DETECT_TAG, "RF failed: ${t.message}", t)
            null
        }
    }

    /**
     * Classify the risk category using the RF category model, if available.
     * Falls back to lexicon-derived category. Returns null on failure.
     */
    private fun classifyCategory(message: String): String? {
        val cat = rfCategory
        if (cat != null) {
            try {
                val pred = cat.predict(message)
                Log.v(DETECT_TAG, "RF category=${pred.label} p=${pred.topProbability}")
                // Normalise "self_harm_risk" → "self_harm" to match canonical schema.
                val label = pred.label.lowercase()
                return when {
                    label.contains("self") && label.contains("harm") -> ToxicityAnalyzer.CATEGORY_SELF_HARM
                    label in ToxicityAnalyzer.CANONICAL_CATEGORIES -> label
                    else -> label
                }
            } catch (t: Throwable) {
                Log.w(DETECT_TAG, "RF category failed: ${t.message}")
            }
        }
        // Derive from lexicon
        val lex = fallbackAnalyzer.analyzeMessage(message)
        return if (lex.category != ToxicityAnalyzer.CATEGORY_NONE) lex.category else null
    }

    /**
     * Optionally overwrite the category from a WebView analysis, but only when the
     * WebView returned a real signal (no error, non-fallback, non-zero score).
     * Score/toxicity are NEVER trusted from the WebView here — see trace report.
     */
    private fun maybeAttachWebViewCategory(
        base: AnalysisResult,
        message: String,
        sensitivity: Double,
        platform: String,
    ): AnalysisResult {
        if (!webViewBridge.isReady()) return base
        return try {
            val jsonResult = webViewBridge.analyzeText(message, sensitivity, platform)
            val json = JSONObject(jsonResult)
            if (json.optBoolean("fallback", false) || json.has("error")) {
                if (json.has("error")) {
                    Log.v(DETECT_TAG, "WebView JS error (ignored): ${json.optString("error")}")
                }
                return base
            }
            val wvCategory = json.optString("category", "").takeIf { it.isNotBlank() && it != "none" }
            if (wvCategory != null && base.isToxic && base.category == ToxicityAnalyzer.CATEGORY_NONE) {
                base.copy(category = wvCategory)
            } else base
        } catch (e: Exception) {
            Log.v(DETECT_TAG, "WebView category enrichment skipped: ${e.message}")
            base
        }
    }

    /**
     * Parse JSON result from WebView
     */
    private fun parseEnhancedResult(jsonString: String): AnalysisResult {
        try {
            val json = JSONObject(jsonString)

            // Treat any error field OR explicit fallback flag as "not really
            // enhanced" — the JS IIFE's catch block returns error without
            // setting fallback:true (see DETECTION_TRACE_REPORT.md §1).
            if (json.optBoolean("fallback", false) || json.has("error")) {
                if (json.has("error")) {
                    Log.w(TAG, "JS error in WebView payload: ${json.optString("error")}")
                }
                Log.w(TAG, "Received fallback/error result from WebView")
                return AnalysisResult(
                    toxicityScore = 0f,
                    originalScore = 0f,
                    category = "none",
                    severity = "none",
                    isToxic = false,
                    usingEnhanced = false
                )
            }

            // Parse adjustments if present
            val adjustments = if (json.has("adjustments")) {
                val adj = json.getJSONObject("adjustments")
                Adjustments(
                    emoji = adj.optBoolean("emoji", false),
                    sarcasm = adj.optBoolean("sarcasm", false),
                    context = adj.optBoolean("context", false)
                )
            } else {
                null
            }

            return AnalysisResult(
                toxicityScore = json.getDouble("score").toFloat(),
                originalScore = json.optDouble("originalScore", json.getDouble("score")).toFloat(),
                category = json.getString("category"),
                severity = json.getString("severity"),
                isToxic = json.getBoolean("isToxic"),
                adjustments = adjustments,
                usingEnhanced = true
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse JSON result: ${e.message}", e)
            Log.e(TAG, "JSON was: $jsonString")

            // Return safe fallback
            return AnalysisResult(
                toxicityScore = 0f,
                originalScore = 0f,
                category = "none",
                severity = "none",
                isToxic = false,
                usingEnhanced = false
            )
        }
    }

    /**
     * Use fallback analyzer (basic rule-based)
     */
    private fun useFallbackAnalyzer(message: String, sensitivity: Double): AnalysisResult {
        val basicResult = fallbackAnalyzer.analyzeMessage(message)
        val isToxic = basicResult.toxicityScore >= sensitivity
        Log.v(DETECT_TAG, "Lexicon returned score=${basicResult.toxicityScore} " +
            "category=${basicResult.category} severity=${basicResult.severity}")

        return AnalysisResult(
            toxicityScore = basicResult.toxicityScore,
            originalScore = basicResult.toxicityScore,
            category = basicResult.category,
            severity = basicResult.severity,
            isToxic = isToxic,
            usingEnhanced = false
        )
    }

    /**
     * Get warning escalation level based on violation count
     *
     * @param violationCount Total number of violations by user
     * @return Warning level configuration
     */
    fun getWarningLevel(violationCount: Int): WarningLevel {
        if (webViewBridge.isReady()) {
            try {
                val jsonResult = webViewBridge.getWarningLevel(violationCount)
                return parseWarningLevel(jsonResult)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get warning level: ${e.message}", e)
            }
        }

        // Fallback to basic escalation
        return getBasicWarningLevel(violationCount)
    }

    /**
     * Parse warning level from JSON
     */
    private fun parseWarningLevel(jsonString: String): WarningLevel {
        try {
            val json = JSONObject(jsonString)

            return WarningLevel(
                level = json.getString("level"),
                tone = json.getString("tone"),
                title = json.getString("title"),
                subtitle = json.optString("subtitle", null),
                cooldownSeconds = json.optInt("cooldownSeconds", 0),
                showViolationCount = json.optBoolean("showViolationCount", false),
                showConsequences = json.optBoolean("showConsequences", false)
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse warning level: ${e.message}", e)
            return getBasicWarningLevel(0)
        }
    }

    /**
     * Basic warning level (fallback)
     */
    private fun getBasicWarningLevel(violationCount: Int): WarningLevel {
        return when {
            violationCount <= 3 -> WarningLevel(
                level = "educational",
                tone = "gentle",
                title = "Think Before You Send",
                subtitle = "This message might hurt someone's feelings",
                cooldownSeconds = 0
            )
            violationCount <= 10 -> WarningLevel(
                level = "reminder",
                tone = "firm",
                title = "Reminder: Be Kind Online",
                subtitle = "You've sent $violationCount messages that may be harmful",
                cooldownSeconds = 5
            )
            violationCount <= 20 -> WarningLevel(
                level = "strong",
                tone = "serious",
                title = "Serious Warning",
                subtitle = "You've sent $violationCount potentially harmful messages",
                cooldownSeconds = 10,
                showViolationCount = true,
                showConsequences = true
            )
            else -> WarningLevel(
                level = "escalation",
                tone = "critical",
                title = "Critical: Repeated Violations",
                subtitle = "Your account has been flagged for review",
                cooldownSeconds = 15,
                showViolationCount = true,
                showConsequences = true
            )
        }
    }

    /**
     * Get explanation for analysis (debugging)
     */
    fun getAnalysisExplanation(message: String): String {
        val result = analyzeMessage(message)

        val enhancedStatus = if (result.usingEnhanced) "ENHANCED" else "BASIC"
        val adjustmentsStr = result.adjustments?.let { adj ->
            val applied = mutableListOf<String>()
            if (adj.emoji) applied.add("emoji")
            if (adj.sarcasm) applied.add("sarcasm")
            if (adj.context) applied.add("context")
            if (applied.isNotEmpty()) " [Adjustments: ${applied.joinToString(", ")}]" else ""
        } ?: ""

        return "[$enhancedStatus] Score: ${result.toxicityScore} (original: ${result.originalScore}), " +
               "Category: ${result.category}, Severity: ${result.severity}$adjustmentsStr"
    }

    /**
     * Check if enhanced detection is available
     */
    fun isEnhancedAvailable(): Boolean {
        return webViewBridge.isReady()
    }

    /**
     * Clean up resources
     */
    fun destroy() {
        webViewBridge.destroy()
    }
}
