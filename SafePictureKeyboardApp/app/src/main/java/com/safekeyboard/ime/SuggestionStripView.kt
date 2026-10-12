package com.safekeyboard.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.safekeyboard.R

/**
 * SuggestionStripView
 *
 * A lightweight, self-contained suggestion strip for the SendWise IME.
 * - Renders up to 3 word suggestions horizontally.
 * - Middle suggestion is highlighted (blue text).
 * - Tap fires [onSuggestionTapped] with the chosen word.
 * - Uses a HARDCODED small dictionary (~200 common English words) below.
 *   No external asset, no Trie — prefix scan + sort is fine at this size.
 *
 * Frequency = order in DICTIONARY (earlier = more common).
 * Ranking on prefix match:
 *   1. lower index (higher frequency) first
 *   2. then alphabetical (as a stable tiebreaker)
 * Then we cap at 3 suggestions.
 */
class SuggestionStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Callback: user tapped a suggestion. */
    var onSuggestionTapped: ((String) -> Unit)? = null

    private var suggestions: List<String> = emptyList()

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 16f * resources.displayMetrics.scaledDensity
        isFakeBoldText = false
    }

    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.suggestion_divider)
        strokeWidth = 1f
    }

    private val bounds = RectF()

    private val colorText: Int by lazy {
        ContextCompat.getColor(context, R.color.suggestion_text)
    }
    private val colorHighlight: Int by lazy {
        ContextCompat.getColor(context, R.color.suggestion_text_highlight)
    }

    /**
     * Update the strip based on the current partial word.
     * Empty / whitespace -> clears strip.
     */
    fun updateForPrefix(prefix: String) {
        val p = prefix.trim().lowercase()
        suggestions = if (p.isEmpty()) {
            emptyList()
        } else {
            computeSuggestions(p)
        }
        invalidate()
    }

    fun clear() {
        suggestions = emptyList()
        bannerText = null
        invalidate()
    }

    /**
     * Diagnostic banner — replaces suggestions with a coloured status
     * message drawn INSIDE the keyboard bar (guaranteed to render, no
     * overlay/notification/Toast permissions required).
     *
     *  color=0 -> gray/info,  1 -> red/risk,  2 -> orange/warn,  3 -> green/ok
     */
    fun showBanner(text: String, color: Int) {
        bannerText = text
        bannerColor = color
        invalidate()
    }

    private var bannerText: String? = null
    private var bannerColor: Int = 0

    private fun computeSuggestions(prefix: String): List<String> {
        // Skip exact matches — no value suggesting the word already typed.
        val matches = mutableListOf<Pair<Int, String>>()
        for ((idx, word) in DICTIONARY.withIndex()) {
            if (word.length > prefix.length && word.startsWith(prefix)) {
                matches.add(idx to word)
            }
        }
        // Sort: by frequency (index asc), then alphabetical
        matches.sortWith(compareBy({ it.first }, { it.second }))
        return matches.take(3).map { it.second }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Diagnostic banner takes over the whole strip. Guaranteed to render.
        val b = bannerText
        if (b != null) {
            val w = width.toFloat()
            val h = height.toFloat()
            val bg = when (bannerColor) {
                1 -> 0xFFFEE2E2.toInt()   // red-tinted
                2 -> 0xFFFEF3C7.toInt()   // orange-tinted
                3 -> 0xFFD1FAE5.toInt()   // green-tinted
                else -> 0xFFE5E7EB.toInt() // gray
            }
            val fg = when (bannerColor) {
                1 -> 0xFF991B1B.toInt()
                2 -> 0xFF92400E.toInt()
                3 -> 0xFF065F46.toInt()
                else -> 0xFF374151.toInt()
            }
            val bgPaint = android.graphics.Paint().apply { color = bg }
            canvas.drawRect(0f, 0f, w, h, bgPaint)
            val prevColor = textPaint.color
            val prevBold = textPaint.isFakeBoldText
            textPaint.color = fg
            textPaint.isFakeBoldText = true
            val baseY = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(b, w / 2f, baseY, textPaint)
            textPaint.color = prevColor
            textPaint.isFakeBoldText = prevBold
            return
        }

        if (suggestions.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()
        val slotW = w / suggestions.size
        val baseY = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f

        // Middle index gets highlighted. For 1 suggestion, no highlight.
        // For 3, middle = 1. For 2, we highlight index 0 (leftmost) — but
        // spec says "middle suggestion highlighted", so with <3 items we
        // simply don't highlight.
        val highlightIdx = if (suggestions.size == 3) 1 else -1

        for (i in suggestions.indices) {
            textPaint.color = if (i == highlightIdx) colorHighlight else colorText
            textPaint.isFakeBoldText = (i == highlightIdx)
            val cx = slotW * i + slotW / 2f
            canvas.drawText(suggestions[i], cx, baseY, textPaint)

            // Divider between slots
            if (i < suggestions.size - 1) {
                val x = slotW * (i + 1)
                val pad = h * 0.25f
                canvas.drawLine(x, pad, x, h - pad, dividerPaint)
            }
        }
        // Silence unused-warning on bounds (kept for potential press-state ext).
        bounds.setEmpty()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (suggestions.isEmpty()) return false
        if (event.action == MotionEvent.ACTION_UP) {
            val slotW = width.toFloat() / suggestions.size
            val idx = (event.x / slotW).toInt().coerceIn(0, suggestions.size - 1)
            onSuggestionTapped?.invoke(suggestions[idx])
            performClick()
            return true
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    companion object {
        /**
         * Hardcoded ~200-word frequency-ordered dictionary.
         * Order = frequency descending. Prefix matches sorted by this order.
         */
        private val DICTIONARY: List<String> = listOf(
            "the", "and", "for", "are", "but", "not", "you", "all", "can", "had",
            "her", "was", "one", "our", "out", "day", "get", "has", "him", "his",
            "how", "man", "new", "now", "old", "see", "two", "way", "who", "boy",
            "did", "its", "let", "put", "say", "she", "too", "use", "dad", "mom",
            "big", "box", "cat", "dog", "eat", "end", "far", "fun", "got", "hot",
            "hey", "ice", "ill", "job", "kid", "law", "lot", "mad", "may", "met",
            "mid", "mud", "non", "own", "pen", "pet", "pop", "red", "run", "sad",
            "sat", "set", "sit", "six", "sun", "tag", "top", "try", "wet", "why",
            "win", "yes", "ask", "bad", "bed", "bag", "bit", "cut", "dry", "ear",
            "egg", "few", "fit", "gas", "gun", "hit", "hop", "ink", "key", "keys",
            "kit", "log", "low", "mix", "net", "oil", "pay", "pig", "ram", "sir",
            "sky", "tap", "ten", "tie", "tin", "van", "war", "wax", "web", "zip",
            "would", "could", "should", "about", "above", "after", "again", "along",
            "always", "another", "any", "around", "being", "below", "between",
            "both", "call", "came", "come", "does", "down", "each", "even", "every",
            "find", "first", "from", "going", "good", "great", "have", "help",
            "here", "home", "into", "just", "know", "last", "life", "like", "little",
            "long", "look", "made", "make", "many", "more", "most", "much", "must",
            "name", "need", "next", "nice", "only", "open", "over", "part", "place",
            "right", "said", "same", "some", "such", "take", "than", "that", "them",
            "then", "there", "they", "thing", "think", "this", "those", "three",
            "time", "together", "tomorrow", "took", "turn", "under", "until", "upon",
            "very", "want", "water", "well", "went", "were", "what", "when", "where",
            "which", "while", "will", "with", "work", "year", "your"
        )
    }
}
