package com.safekeyboard.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.safekeyboard.R

/**
 * ImageWarningOverlayManager - sibling to [WarningOverlayManager], adapted
 * for the image-attach-and-protect flow.
 *
 * Shows a blocking overlay with a blurred preview and a 3-way decision
 * (Keep Blurred / Send Original / Discard) before a picked image is
 * inserted into the focused input field.
 *
 * Same system-overlay / fallback-Toast structure as WarningOverlayManager:
 * REQUIREMENTS mirrored from that class (system overlay, not dialog;
 * emotionally neutral wording; no shaming language).
 */
class ImageWarningOverlayManager(private val context: Context) {

    enum class ImageDecision { KEEP_BLURRED, DISCARD, SEND_ORIGINAL }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var isShowing = false

    /** Whether the "Send Original" button is enabled for this session
     *  (moderation / parental settings may disallow overriding the blur). */
    var allowSendOriginal: Boolean = true

    // Callback for the user's 3-way decision.
    var onUserDecision: ((decision: ImageDecision) -> Unit)? = null

    // Callback invoked when the overlay is dismissed without a user decision
    // (IME finished, service destroyed, tap outside). Mirrors
    // WarningOverlayManager so the caller can log action="cancelled".
    var onDismissedWithoutDecision: (() -> Unit)? = null

    // Tracks whether the current warning was resolved by an explicit user tap.
    private var userMadeDecision = false

    init {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    /**
     * Shows the image warning overlay.
     *
     * @param category e.g. result.label from NsfwImageClassifier
     * @param severity one of "high", "medium", "low"
     * @param previewBitmap the already-blurred preview bitmap to display
     */
    fun showImageWarning(category: String, severity: String, previewBitmap: Bitmap) {
        if (!hasOverlayPermission()) {
            Toast.makeText(
                context,
                R.string.toast_overlay_permission_needed,
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (isShowing) return

        userMadeDecision = false

        val inflater = LayoutInflater.from(context)
        overlayView = inflater.inflate(R.layout.image_warning_overlay, null)

        setupOverlayView(category, severity, previewBitmap)

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
            android.util.Log.i("ImageWarningOverlay", "Overlay attached successfully")
        } catch (e: Exception) {
            android.util.Log.w("ImageWarningOverlay", "addView FAILED - falling back to Toast", e)
            e.printStackTrace()
            // MIUI / OEM overlays are often blocked even when
            // Settings.canDrawOverlays returns true. Fall back to discarding
            // the image with a Toast notice, since there is no overlay to
            // present the 3-way choice on.
            showFallbackWarning(category, severity)
        }
    }

    /**
     * Fallback when TYPE_APPLICATION_OVERLAY is silently blocked
     * (MIUI, some Samsung skins, work-profile devices). Since there is no
     * overlay to present Keep Blurred / Send Original / Discard choices on,
     * we fail safe and discard the image, only informing the user.
     */
    private fun showFallbackWarning(category: String, severity: String) {
        val displayCat = category.replaceFirstChar { it.titlecase() }
        val message = "\u26A0\uFE0F SafeKeyboard: $displayCat (${severity.uppercase()}) detected in image — image was not sent."
        try {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            android.util.Log.w("ImageWarningOverlay", "Toast fallback also failed", t)
        }
        userMadeDecision = true
        try {
            onUserDecision?.invoke(ImageDecision.DISCARD)
        } catch (t: Throwable) {
            android.util.Log.w("ImageWarningOverlay", "onUserDecision callback failed", t)
        }
    }

    /**
     * Wires up dynamic content: preview image, category/severity chips,
     * and the three action rows.
     */
    private fun setupOverlayView(category: String, severity: String, previewBitmap: Bitmap) {
        overlayView?.let { view ->
            view.findViewById<ImageView>(R.id.image_preview).setImageBitmap(previewBitmap)

            val categoryValue = view.findViewById<TextView>(R.id.image_category_badge)
            categoryValue.text = category.replaceFirstChar { it.titlecase() }

            val severityValue = view.findViewById<TextView>(R.id.image_severity_value)
            val normalizedSeverity = severity.trim().replaceFirstChar { it.titlecase() }
            severityValue.text = normalizedSeverity

            val severityColorRes = when (severity.trim().lowercase()) {
                "high", "severe", "critical" -> R.color.sendwise_severity_high
                "low", "mild"                -> R.color.sendwise_severity_low
                else                          -> R.color.sendwise_severity_medium
            }
            severityValue.setTextColor(ContextCompat.getColor(context, severityColorRes))

            // Keep Blurred (filled) - primary, always allowed.
            view.findViewById<View>(R.id.button_keep_blurred).setOnClickListener {
                userMadeDecision = true
                dismissOverlay()
                onUserDecision?.invoke(ImageDecision.KEEP_BLURRED)
            }

            // Send Original (outlined) - only enabled if moderation/parental
            // settings allow overriding the blur.
            val sendOriginalView = view.findViewById<View>(R.id.button_send_original)
            sendOriginalView.isEnabled = allowSendOriginal
            sendOriginalView.alpha = if (allowSendOriginal) 1.0f else 0.4f
            if (allowSendOriginal) {
                sendOriginalView.setOnClickListener {
                    userMadeDecision = true
                    dismissOverlay()
                    onUserDecision?.invoke(ImageDecision.SEND_ORIGINAL)
                }
            } else {
                sendOriginalView.setOnClickListener(null)
            }

            // Discard - cancel, insert nothing.
            view.findViewById<View>(R.id.button_discard).setOnClickListener {
                userMadeDecision = true
                dismissOverlay()
                onUserDecision?.invoke(ImageDecision.DISCARD)
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
        userMadeDecision = false
    }

    fun isOverlayShowing(): Boolean = isShowing
}
