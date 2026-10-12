package com.safekeyboard.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.safekeyboard.R
import com.safekeyboard.nlp.ToxicityAnalyzer

/**
 * WarningOverlayManager - Manages the pre-send blocking popup (SendWise Fig 2 style).
 *
 * REQUIREMENTS:
 * - System overlay (not dialog)
 * - Blocks interaction temporarily
 * - Emotionally neutral wording
 * - No shaming language
 *
 * Buttons:
 * 1. Edit Message - Dismiss overlay, return to keyboard, no server call
 * 2. Continue    - Allow send, log violation metadata
 */
class WarningOverlayManager(private val context: Context) {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var isShowing = false

    // Callback for user decision
    var onUserDecision: ((sendAnyway: Boolean) -> Unit)? = null

    // Callback invoked when the overlay is dismissed without a user decision
    // (e.g. IME finished, service destroyed, tap outside). Used to log
    // action="cancelled" so the Fig 3 "Edited vs Sent Unchanged" donut has
    // an accurate denominator.
    var onDismissedWithoutDecision: (() -> Unit)? = null

    // Tracks whether the current warning was resolved by an explicit user tap
    // (Edit or Continue). If false when cleanup()/dismissOverlay() runs, we
    // treat it as a cancellation.
    private var userMadeDecision = false

    // Store current analysis result for logging
    var currentAnalysisResult: ToxicityAnalyzer.AnalysisResult? = null

    init {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    /**
     * Shows the warning overlay.
     *
     * @param category e.g. "Harassment", "Hate", "Threat", "Sexual"
     * @param severity one of "High", "Medium", "Low" (case-insensitive)
     */
    fun showWarning(category: String, severity: String) {
        if (!hasOverlayPermission()) {
            Toast.makeText(
                context,
                R.string.toast_overlay_permission_needed,
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (isShowing) return

        // Reset decision tracker for this new warning cycle
        userMadeDecision = false

        val inflater = LayoutInflater.from(context)
        overlayView = inflater.inflate(R.layout.warning_overlay, null)

        setupOverlayView(category, severity)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.CENTER

        try {
            windowManager?.addView(overlayView, params)
            isShowing = true
            android.util.Log.i("WarningOverlay", "Overlay attached successfully")
        } catch (e: Exception) {
            android.util.Log.w("WarningOverlay", "addView FAILED - falling back to Toast + Notification", e)
            e.printStackTrace()
            // MIUI / OEM overlays are often blocked even when
            // Settings.canDrawOverlays returns true. Fall back to a
            // long Toast + a heads-up notification so the user is
            // still warned even without the overlay window.
            showFallbackWarning(category, severity)
        }
    }

    /**
     * Fallback when TYPE_APPLICATION_OVERLAY is silently blocked
     * (MIUI, some Samsung skins, work-profile devices).
     * Shows a persistent Toast and a heads-up notification instead.
     */
    private fun showFallbackWarning(category: String, severity: String) {
        val displayCat = category.replaceFirstChar { it.titlecase() }
        val message = "\u26A0\uFE0F SendWise: $displayCat (${severity.uppercase()}) — potentially harmful text detected. Consider editing before sending."
        try {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            android.util.Log.w("WarningOverlay", "Toast fallback also failed", t)
        }
        // Trigger onUserDecision(true) so the IME state machine
        // doesn't stay stuck waiting for a decision the user can never
        // give (there's no overlay to tap Edit/Continue on).
        userMadeDecision = true
        try {
            onUserDecision?.invoke(false) // treat as "edited/cancelled" for logging
        } catch (t: Throwable) {
            android.util.Log.w("WarningOverlay", "onUserDecision callback failed", t)
        }
    }

    /**
     * Wires up dynamic content: category chip, severity chip (+ severity color),
     * and the two action buttons.
     */
    private fun setupOverlayView(category: String, severity: String) {
        overlayView?.let { view ->
            // Category value (title-case for display, e.g. "Harassment")
            val categoryValue = view.findViewById<TextView>(R.id.category_badge)
            categoryValue.text = category.replaceFirstChar { it.titlecase() }
            categoryValue.visibility = View.VISIBLE

            // Severity value + dynamic color
            val severityValue = view.findViewById<TextView>(R.id.severity_value)
            val normalizedSeverity = severity.trim().replaceFirstChar { it.titlecase() }
            severityValue.text = normalizedSeverity

            val severityColorRes = when (severity.trim().lowercase()) {
                "high", "severe", "critical" -> R.color.sendwise_severity_high
                "low", "mild"                -> R.color.sendwise_severity_low
                else                          -> R.color.sendwise_severity_medium
            }
            severityValue.setTextColor(ContextCompat.getColor(context, severityColorRes))

            // Edit Message (outlined) - dismiss, no send
            view.findViewById<View>(R.id.button_edit).setOnClickListener {
                userMadeDecision = true
                dismissOverlay()
                onUserDecision?.invoke(false)
            }

            // Continue / Send Anyway (filled) - allow send
            view.findViewById<View>(R.id.button_send_anyway).setOnClickListener {
                userMadeDecision = true
                dismissOverlay()
                onUserDecision?.invoke(true)
            }
        }
    }

    private fun dismissOverlay() {
        try {
            if (overlayView != null && isShowing) {
                windowManager?.removeView(overlayView)
                overlayView = null
                isShowing = false
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun cleanup() {
        // If the overlay was up and the user never explicitly chose Edit or
        // Continue, treat this as a cancellation so ViolationLogger can log
        // action="cancelled".
        val wasShowingWithoutDecision = isShowing && !userMadeDecision
        dismissOverlay()
        if (wasShowingWithoutDecision) {
            try {
                onDismissedWithoutDecision?.invoke()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        onUserDecision = null
        onDismissedWithoutDecision = null
        currentAnalysisResult = null
        userMadeDecision = false
    }

    fun isOverlayShowing(): Boolean = isShowing
}
