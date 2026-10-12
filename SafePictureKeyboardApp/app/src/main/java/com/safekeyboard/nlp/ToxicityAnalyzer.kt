package com.safekeyboard.nlp

import android.content.Context
import java.util.Locale

/**
 * ToxicityAnalyzer - On-device NLP for detecting harmful content
 *
 * PHASE 1: Rule-based + Regex + Lexicon
 * - Sentence-level scoring
 * - Pattern matching
 * - Context-aware analysis
 *
 * PHASE 2 (Future): TensorFlow Lite with DistilBERT/ALBERT
 *
 * NON-NEGOTIABLES:
 * - No cloud inference
 * - No message upload
 * - No logging of text
 * - Works offline
 *
 * Output Format:
 * {
 *   "toxicity_score": 0.0 – 1.0,
 *   "category": "harassment | threats | hate_speech | sexual_content | self_harm | none",
 *   "severity": "low | medium | high"
 * }
 */
class ToxicityAnalyzer(private val context: Context) {

    companion object {
        // Canonical 5 risk categories (mirrors parental-dashboard/lib/schema.ts).
        const val CATEGORY_HARASSMENT = "harassment"
        const val CATEGORY_THREATS = "threats"
        const val CATEGORY_HATE_SPEECH = "hate_speech"
        const val CATEGORY_SEXUAL_CONTENT = "sexual_content"
        const val CATEGORY_SELF_HARM = "self_harm"
        const val CATEGORY_NONE = "none"

        val CANONICAL_CATEGORIES: List<String> = listOf(
            CATEGORY_HARASSMENT,
            CATEGORY_THREATS,
            CATEGORY_HATE_SPEECH,
            CATEGORY_SEXUAL_CONTENT,
            CATEGORY_SELF_HARM
        )
    }

    data class AnalysisResult(
        val toxicityScore: Float,
        val category: String,
        val severity: String
    )

    // Toxicity lexicons by category
    private val harassmentTerms = setOf(
        // Harassment patterns / common English profanity
        "loser", "idiot", "stupid", "dumb", "pathetic", "worthless",
        "waste", "failure", "ugly", "fat", "disgusting", "shut up",
        "nobody likes", "everyone hates", "kill yourself", "kys",
        "die", "disappear", "nobody cares", "hate you", "retard", "retarded",
        // Profanity / slurs commonly used in personal attacks
        "bitch", "bastard", "asshole", "ass hole", "motherfucker", "mother fucker",
        "fuck", "fucker", "fucking", "shit", "piece of shit", "damn", "dumbass",
        "jackass", "prick", "dick", "dickhead", "cunt", "twat",
        "slut", "whore", "hoe", "skank"
    )

    private val hateTerms = setOf(
        // Hate speech (slurs and discriminatory language)
        // Note: Using mild examples for demonstration
        "racist", "sexist", "homophobic", "transphobic",
        "discriminate", "inferior", "subhuman", "degenerate"
    )

    private val threatTerms = setOf(
        // Threats and violence
        "kill", "murder", "hurt", "harm", "beat", "attack",
        "shoot", "stab", "punch", "destroy", "threaten",
        "going to hurt", "will kill", "find you", "coming for",
        "better watch", "you're dead", "gonna get"
    )

    private val sexualTerms = setOf(
        // Sexual harassment
        "send nudes", "send pics", "sexy", "hot body",
        "wanna hookup", "dtf", "netflix and chill"
    )

    private val selfHarmTerms = setOf(
        // Self-harm / suicidal ideation (first-person expressions)
        "kill myself", "kms", "end my life", "end it all",
        "want to die", "wanna die", "suicide", "suicidal",
        "cut myself", "hurt myself", "self harm", "self-harm",
        "no reason to live", "better off dead", "hate myself",
        "not worth living", "cant go on", "can't go on"
    )

    // Intensity multipliers
    private val intensifiers = setOf(
        "really", "very", "extremely", "so", "absolutely",
        "totally", "completely", "fucking", "damn"
    )

    // Negation words (reduce toxicity score)
    private val negations = setOf(
        "not", "no", "never", "don't", "doesn't", "didn't",
        "won't", "wouldn't", "can't", "couldn't"
    )

    /**
     * Analyzes a message for toxic content
     */
    fun analyzeMessage(message: String): AnalysisResult {
        val normalizedMessage = message.lowercase(Locale.getDefault())
        val words = normalizedMessage.split(Regex("\\s+"))

        // Calculate scores for each category
        val harassmentScore = calculateCategoryScore(normalizedMessage, words, harassmentTerms)
        val hateScore = calculateCategoryScore(normalizedMessage, words, hateTerms)
        val threatScore = calculateCategoryScore(normalizedMessage, words, threatTerms)
        val sexualScore = calculateCategoryScore(normalizedMessage, words, sexualTerms)
        val selfHarmScore = calculateCategoryScore(normalizedMessage, words, selfHarmTerms)

        // Determine dominant category (canonical 5 taxonomy)
        val categoryScores = mapOf(
            CATEGORY_HARASSMENT to harassmentScore,
            CATEGORY_HATE_SPEECH to hateScore,
            CATEGORY_THREATS to threatScore,
            CATEGORY_SEXUAL_CONTENT to sexualScore,
            CATEGORY_SELF_HARM to selfHarmScore
        )

        val maxCategory = categoryScores.maxByOrNull { it.value }
        val dominantCategory = maxCategory?.key ?: CATEGORY_NONE
        val maxScore = maxCategory?.value ?: 0f

        // Apply context modifiers
        val adjustedScore = applyContextModifiers(normalizedMessage, words, maxScore)

        // Determine severity
        val severity = when {
            adjustedScore >= 0.75f -> "high"
            adjustedScore >= 0.45f -> "medium"
            adjustedScore >= 0.25f -> "low"
            else -> "none"
        }

        val finalCategory = if (adjustedScore < 0.25f) CATEGORY_NONE else dominantCategory

        return AnalysisResult(
            toxicityScore = adjustedScore,
            category = finalCategory,
            severity = severity
        )
    }

    /**
     * Calculates score for a specific category
     */
    private fun calculateCategoryScore(
        message: String,
        words: List<String>,
        lexicon: Set<String>
    ): Float {
        var score = 0f
        var matchCount = 0

        // Check for exact word matches
        for (term in lexicon) {
            if (term in words) {
                score += 1f
                matchCount++
            }
            // Check for substring matches (e.g., "stupid" in "stupidity")
            else if (message.contains(term)) {
                score += 0.7f
                matchCount++
            }
        }

        // Check for threat patterns (e.g., "I will kill you")
        score += detectThreatPatterns(message)

        // Normalize score (0.0 to 1.0)
        return minOf(score / 3f, 1f)
    }

    /**
     * Detects specific threat patterns
     */
    private fun detectThreatPatterns(message: String): Float {
        var patternScore = 0f

        val threatPatterns = listOf(
            Regex("(will|gonna|going to)\\s+(kill|hurt|harm|beat|attack)"),
            Regex("(i|i'll|im)\\s+(kill|hurt|harm|destroy)\\s+(you|u)"),
            Regex("(you|u)\\s+(will|gonna|going to)\\s+(die|regret|pay)"),
            Regex("(find|get|come for)\\s+(you|u)"),
            Regex("(watch|look)\\s+(out|your back)")
        )

        for (pattern in threatPatterns) {
            if (pattern.containsMatchIn(message)) {
                patternScore += 0.5f
            }
        }

        return patternScore
    }

    /**
     * Applies context modifiers to the score
     */
    private fun applyContextModifiers(message: String, words: List<String>, baseScore: Float): Float {
        var adjustedScore = baseScore

        // Check for intensifiers
        val hasIntensifier = words.any { it in intensifiers }
        if (hasIntensifier) {
            adjustedScore *= 1.3f
        }

        // Check for negations (reduce score)
        val hasNegation = words.any { it in negations }
        if (hasNegation) {
            adjustedScore *= 0.6f
        }

        // Check for multiple exclamation marks (indicates aggression)
        val exclamationCount = message.count { it == '!' }
        if (exclamationCount >= 2) {
            adjustedScore *= 1.2f
        }

        // Check for ALL CAPS (indicates shouting/aggression)
        val capsRatio = message.count { it.isUpperCase() }.toFloat() / message.length
        if (capsRatio > 0.5f && message.length > 10) {
            adjustedScore *= 1.2f
        }

        // Check for repetition (e.g., "stupid stupid stupid")
        if (hasRepetition(words)) {
            adjustedScore *= 1.15f
        }

        // Cap at 1.0
        return minOf(adjustedScore, 1f)
    }

    /**
     * Detects word repetition
     */
    private fun hasRepetition(words: List<String>): Boolean {
        if (words.size < 2) return false

        for (i in 0 until words.size - 1) {
            if (words[i] == words[i + 1] && words[i].length > 3) {
                return true
            }
        }
        return false
    }

    /**
     * Gets explanation for why a message was flagged (for debugging)
     */
    fun getAnalysisExplanation(message: String): String {
        val result = analyzeMessage(message)
        return "Score: ${result.toxicityScore}, Category: ${result.category}, Severity: ${result.severity}"
    }
}
