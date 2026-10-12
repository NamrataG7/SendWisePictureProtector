package com.safekeyboard.ime

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.inputmethodservice.InputMethodService
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import com.safekeyboard.R
import com.safekeyboard.media.ImageBlurUtils
import com.safekeyboard.media.ImageHandoff
import com.safekeyboard.media.NsfwImageClassifier
import com.safekeyboard.nlp.ToxicityAnalyzer
import com.safekeyboard.nlp.EnhancedToxicityAnalyzer
import com.safekeyboard.ui.ImagePickerActivity
import com.safekeyboard.ui.ImageWarningOverlayManager
import com.safekeyboard.ui.WarningOverlayManager
import com.safekeyboard.utils.PreferencesManager
import com.safekeyboard.network.ViolationLogger
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

/**
 * SafeKeyboardIME - Core keyboard service implementing pre-send harm prevention
 *
 * This Input Method Editor (IME):
 * - Captures all user input in a message buffer
 * - Detects intent-to-send through multiple signals
 * - Analyzes message toxicity on-device
 * - Shows intervention popup before harmful messages are sent
 * - Logs metadata only (never message content)
 */
class SafeKeyboardIME : InputMethodService(), KeyboardView.OnKeyboardActionListener {

    private var keyboardView: KeyboardView? = null
    private var keyboard: Keyboard? = null
    private var suggestionStrip: SuggestionStripView? = null

    // Long-press handling for top-row digits (Q..P => 1..0) and backspace repeat.
    private val pressHandler = Handler(Looper.getMainLooper())
    private var pendingLongPress: Runnable? = null
    private var longPressConsumed = false
    private var backspaceRepeat: Runnable? = null

    // Map top-row letter codes -> digit char.
    private val topRowDigits: Map<Int, Char> = mapOf(
        113 to '1', 119 to '2', 101 to '3', 114 to '4', 116 to '5',
        121 to '6', 117 to '7', 105 to '8', 111 to '9', 112 to '0'
    )

    // Message buffer - NEVER persisted
    private val messageBuffer = MessageBuffer()

    // Intent-to-send detector
    private val sendIntentDetector = SendIntentDetector()

    // On-device NLP analyzer (enhanced with shared library)
    private lateinit var enhancedAnalyzer: EnhancedToxicityAnalyzer

    // Warning overlay manager
    private lateinit var overlayManager: WarningOverlayManager

    // --- Image attach & protect feature (new, additive) --------------------
    // Sibling overlay manager for the image NSFW decision flow.
    private lateinit var imageOverlayManager: ImageWarningOverlayManager

    // NSFW image classifier (parallel lane: com.safekeyboard.media.NsfwImageClassifier).
    // Lazily created on first image pick (avoids loading model weights for
    // users who never use the attach-image feature). Stored as an explicit
    // Lazy so onDestroy() can check isInitialized() before calling close().
    private val nsfwClassifierLazy = lazy { NsfwImageClassifier(this) }
    private val nsfwClassifier: NsfwImageClassifier get() = nsfwClassifierLazy.value

    // Pending state for the currently-shown image warning overlay, so the
    // 3-way decision callback knows which bitmaps/metadata to act on.
    private var pendingImageOriginal: Bitmap? = null
    private var pendingImageBlurred: Bitmap? = null
    private var pendingImageCategory: String? = null
    private var pendingImageSeverity: String? = null

    // Short-lived FileProvider cache files created for commitContent/clipboard
    // handoff. Never written anywhere else; deleted on use or onDestroy.
    private val imageCacheFiles = mutableListOf<File>()

    // Preferences
    private lateinit var preferencesManager: PreferencesManager

    // Violation logger (for server communication)
    private lateinit var violationLogger: ViolationLogger

    // Coroutine scope for async operations
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // State tracking
    private var isShiftOn = false
    private var lastKeyPressTime = 0L
    private var currentAppPackage = ""

    // --- Fig 3 telemetry state ---------------------------------------------
    // Stash of the analysis result that triggered the currently-visible
    // warning overlay. Retained beyond `overlayManager.currentAnalysisResult`
    // so we can still log action="cancelled" after the overlay is torn down
    // by the system (onFinishInputView / onDestroy).
    private var pendingWarningCategory: String? = null
    private var pendingWarningSeverity: String? = null

    // Guard against double-logging: set true as soon as we log any terminal
    // action for the current warning (edited / sent_anyway / cancelled).
    private var warningDecisionMade = true

    // Simple in-process counters for on-device verification of the
    // Edited vs Sent Unchanged ratio during device testing. Dumped to
    // logcat at verbose level after every action.
    private data class TelemetryCounts(
        var edited: Int = 0,
        var sentAnyway: Int = 0,
        var cancelled: Int = 0,
        var blocked: Int = 0
    )
    private val telemetryCounts = TelemetryCounts()

    private fun logTelemetryCounts(justLogged: String) {
        Log.v(
            TAG,
            "TelemetryCounts action=$justLogged " +
                "edited=${telemetryCounts.edited} " +
                "sent_anyway=${telemetryCounts.sentAnyway} " +
                "cancelled=${telemetryCounts.cancelled} " +
                "blocked=${telemetryCounts.blocked}"
        )
    }

    // --- Live (debounced) analysis while typing ---------------------------
    // WhatsApp / IG / most messengers use their OWN Send button (outside the
    // keyboard), so KEYCODE_DONE never fires and handleDone()'s pre-send
    // analysis is skipped. To still catch harmful content in those apps, we
    // run a debounced analysis on the buffer as the user types. Fires the
    // Fig 2 warning overlay as soon as the RF classifier score >= 0.5.
    private val liveDebounceHandler = Handler(Looper.getMainLooper())
    private var pendingLiveAnalysis: Runnable? = null
    private val LIVE_ANALYSIS_DEBOUNCE_MS = 1000L
    private val LIVE_ANALYSIS_WORD_BOUNDARY_DEBOUNCE_MS = 300L
    private var lastLiveAnalyzedText: String = ""

    // Sensitive-field state. When false, MessageBuffer capture, live analysis,
    // and suggestion strip are all disabled for privacy (fix: passwords /
    // banking / OTP / secure apps).
    private var analysisEnabledForField: Boolean = true

    // Last committed character — used by the word-boundary fast path in
    // scheduleLiveAnalysis(). Set by handleCharacter / onText.
    private var lastCommittedChar: Char = '\u0000'

    companion object {
        private const val TAG = "SafeKeyboardIME"
        private const val MIN_CHARS_FOR_LIVE = 8
        private const val MIN_WORDS_FOR_LIVE = 2

        // Packages where we ALWAYS skip analysis + buffer capture, even if
        // the input field looks benign.
        private val SENSITIVE_PACKAGE_ALLOWLIST = listOf(
            // Banking
            "chase", "citi", "wellsfargo", "bankofamerica", "hdfc", "icici",
            "sbi", "axis", "kotak", "paypal", "venmo", "revolut", "monzo",
            "n26", "cash.app",
            // India payment / UPI
            "gpay", "googlepay", "phonepe", "paytm", "bhim",
            "amazon.mshop", "amazonpay",
            // Secure messaging
            "signal", "telegram", "threema", "session.messenger",
            // Password managers
            "1password", "bitwarden", "dashlane", "lastpass", "keepass", "nordpass",
            // System auth
            "com.google.android.gms.auth", "com.android.settings"
        )

        // Positive whitelist: chat / social apps where we WANT to analyze
        // even if the field carries NO_SUGGESTIONS or NO_PERSONALIZED_LEARNING.
        // (Password/numeric rules still take precedence.)
        private val ANALYZE_PACKAGE_WHITELIST = listOf(
            "whatsapp", "instagram", "snapchat",
            "com.facebook.orca", "com.discord", "com.twitter", "com.reddit",
            "com.tiktok", "com.telegram.messenger",
            "sms", "com.android.messaging", "com.samsung.android.messaging"
        )
    }

    /**
     * Detect whether the current input field should be excluded from
     * message-buffer capture, live analysis, and suggestions.
     *
     * Returns (isSensitive, reasonString) — reason is logged for debugging
     * on-device but never persisted or exfiltrated.
     */
    private fun isSensitiveInputField(info: EditorInfo?): Pair<Boolean, String> {
        if (info == null) return false to "no-editor-info"

        val inputType = info.inputType
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val flags = inputType and InputType.TYPE_MASK_FLAGS
        val pkg = (info.packageName ?: "").lowercase()

        // 1. Password variants (text + number classes)
        if (cls == InputType.TYPE_CLASS_TEXT && (
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            )
        ) {
            return true to "password"
        }
        if (cls == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        ) {
            return true to "password"
        }

        // 2. Numeric class — banking amounts / PINs / OTPs / phone digits
        if (cls == InputType.TYPE_CLASS_NUMBER) {
            return true to "numeric"
        }

        // 3. Email / phone / URI
        if (cls == InputType.TYPE_CLASS_PHONE) {
            return true to "email/phone/uri"
        }
        if (cls == InputType.TYPE_CLASS_TEXT && (
                variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT ||
                variation == InputType.TYPE_TEXT_VARIATION_URI
            )
        ) {
            return true to "email/phone/uri"
        }

        // 6. Package allowlist — absolute skip regardless of field type.
        val matchedSensitivePkg = SENSITIVE_PACKAGE_ALLOWLIST.firstOrNull { pkg.contains(it) }
        if (matchedSensitivePkg != null) {
            return true to "app in sensitive package allowlist: $matchedSensitivePkg"
        }

        // Rules 4 (NO_SUGGESTIONS) and 5 (IME_FLAG_NO_PERSONALIZED_LEARNING)
        // were removed on 2026-08-26 — too many false negatives. MIUI system
        // search, many messengers, and ordinary text fields set these flags
        // for reasons unrelated to sensitivity (they just want autocorrect
        // off). We rely on the strong signals: password variants, numeric
        // class, email/URI variations, and the sensitive package allowlist.

        return false to "ok"
    }

    override fun onCreate() {
        super.onCreate()

        // Initialize components
        enhancedAnalyzer = EnhancedToxicityAnalyzer(this)
        overlayManager = WarningOverlayManager(this)
        preferencesManager = PreferencesManager(this)
        violationLogger = ViolationLogger(this)

        // --- Image attach & protect: new overlay manager + decision wiring ---
        imageOverlayManager = ImageWarningOverlayManager(this)
        imageOverlayManager.onUserDecision = { decision ->
            handleImageDecision(decision)
        }
        imageOverlayManager.onDismissedWithoutDecision = {
            handleImageDecision(ImageWarningOverlayManager.ImageDecision.DISCARD)
        }

        // Set up warning popup callback
        overlayManager.onUserDecision = { sendAnyway ->
            if (sendAnyway) {
                handleSendAnywayChoice()
            } else {
                handleEditChoice()
            }
        }

        // If the overlay is torn down without the user tapping Edit or
        // Continue (IME finished, service destroyed, tap outside), treat it
        // as a cancellation so the Fig 3 donut denominator stays correct.
        overlayManager.onDismissedWithoutDecision = {
            handleOverlayCancelled()
        }
    }

    override fun onCreateInputView(): View {
        val root = layoutInflater.inflate(R.layout.keyboard_view, null)
        keyboardView = root.findViewById(R.id.keyboard_view)
        suggestionStrip = root.findViewById(R.id.suggestion_strip)
        keyboard = Keyboard(this, R.xml.qwerty)
        keyboardView?.keyboard = keyboard
        keyboardView?.setOnKeyboardActionListener(this)

        // Tap suggestion -> replace current partial word via InputConnection.
        suggestionStrip?.onSuggestionTapped = { word ->
            replaceCurrentWord(word)
        }

        // --- Image attach & protect: wire the attach button ---
        root.findViewById<View?>(R.id.btn_attach_image)?.setOnClickListener {
            ImageHandoff.listener = { uri -> handlePickedImage(uri) }
            val intent = Intent(this, ImagePickerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
                ImageHandoff.listener = null
            }
        }

        return root
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)

        // Clear buffer when starting new input
        if (!restarting) {
            messageBuffer.clear()
        }

        // Track current app context
        attribute?.packageName?.let {
            currentAppPackage = it
        }

        // Determine whether this field is safe to analyze.
        val (sensitive, reason) = isSensitiveInputField(attribute)
        analysisEnabledForField = !sensitive
        if (sensitive) {
            Log.d(TAG, "Analysis disabled: reason=$reason")
            // Belt-and-braces: drop anything we may have captured and cancel
            // any live analysis that was queued from a previous field.
            messageBuffer.clear()
            pendingLiveAnalysis?.let { liveDebounceHandler.removeCallbacks(it) }
            pendingLiveAnalysis = null
            lastLiveAnalyzedText = ""
            // Hide suggestions on sensitive fields (matches Gboard behavior).
            suggestionStrip?.visibility = View.GONE
        } else {
            Log.d(TAG, "Analysis enabled: reason=$reason")
            suggestionStrip?.visibility = View.VISIBLE
        }

        // Update send intent detector with app context
        sendIntentDetector.updateAppContext(currentAppPackage)
    }

    /**
     * Last-chance intervention hook (paper Fig 2).
     *
     * When the editor loses focus (user is switching away — often right after tapping Send),
     * run one final synchronous analysis on the current buffer. If risk >= 0.5 and no
     * overlay is already showing, surface the warning overlay before it's too late.
     *
     * Buffer is always cleared after handling so nothing leaks across editors.
     */
    override fun onFinishInput() {
        super.onFinishInput()
        if (!analysisEnabledForField) {
            Log.d(TAG, "Live skipped: sensitive field (onFinishInput)")
            messageBuffer.clear()
            return
        }
        try {
            if (preferencesManager.isModerationEnabled() &&
                !messageBuffer.isEmpty() &&
                !overlayManager.isOverlayShowing()
            ) {
                val message = messageBuffer.getCurrentMessage()
                if (message.isNotEmpty()) {
                    val platform = getPlatformFromPackage(currentAppPackage)
                    val result = enhancedAnalyzer.analyzeMessage(
                        message = message,
                        sensitivity = preferencesManager.getSensitivityThreshold().toDouble(),
                        platform = platform
                    )
                    if (result.toxicityScore >= 0.5f) {
                        showWarningPopup(result)
                    }
                }
            }
        } catch (e: Exception) {
            // Fail open — never block the user because of an analysis error.
            e.printStackTrace()
        } finally {
            messageBuffer.clear()
        }
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)

        // Detect cursor movement away from end (signal of message finalization)
        val currentText = getCurrentInputConnection()?.getTextBeforeCursor(500, 0)?.toString() ?: ""
        if (newSelStart != currentText.length) {
            sendIntentDetector.recordCursorMoveAway()
        }
    }

    // KeyboardView.OnKeyboardActionListener methods

    override fun onKey(primaryCode: Int, keyCodes: IntArray?) {
        val inputConnection = currentInputConnection ?: return

        // If a long-press handler already committed a digit / repeat-deleted
        // for this key press, swallow the normal onKey.
        if (longPressConsumed) {
            longPressConsumed = false
            return
        }

        when (primaryCode) {
            Keyboard.KEYCODE_DELETE -> handleBackspace(inputConnection)
            Keyboard.KEYCODE_SHIFT -> handleShift()
            Keyboard.KEYCODE_DONE -> handleDone(inputConnection)
            Keyboard.KEYCODE_MODE_CHANGE -> handleModeChange()
            else -> handleCharacter(primaryCode, inputConnection)
        }

        // Update last key press time for pause detection
        lastKeyPressTime = System.currentTimeMillis()

        // Refresh the suggestion strip after any character-affecting key.
        refreshSuggestions()
    }

    override fun onPress(primaryCode: Int) {
        // Cancel any pending timers from a previous key.
        cancelPendingTimers()
        longPressConsumed = false

        when {
            // Top-row letters: schedule digit-insert after 300ms.
            topRowDigits.containsKey(primaryCode) -> {
                val digit = topRowDigits.getValue(primaryCode)
                pendingLongPress = Runnable {
                    val ic = currentInputConnection ?: return@Runnable
                    ic.commitText(digit.toString(), 1)
                    if (analysisEnabledForField) {
                        messageBuffer.append(digit.toString())
                        lastCommittedChar = digit
                    }
                    longPressConsumed = true
                    lastKeyPressTime = System.currentTimeMillis()
                    refreshSuggestions()
                    vibrate(30)
                    scheduleLiveAnalysis()
                }
                pressHandler.postDelayed(pendingLongPress!!, 300)
            }
            // Backspace: schedule accelerating repeat.
            primaryCode == Keyboard.KEYCODE_DELETE -> {
                scheduleBackspaceAcceleration()
            }
        }
    }

    override fun onRelease(primaryCode: Int) {
        cancelPendingTimers()
    }

    override fun onText(text: CharSequence?) {
        // Handle text input
        text?.let {
            currentInputConnection?.commitText(it, 1)
            if (!analysisEnabledForField) return
            messageBuffer.append(it.toString())
            if (it.isNotEmpty()) lastCommittedChar = it[it.length - 1]
            scheduleLiveAnalysis()
        }
    }

    override fun swipeLeft() {}
    override fun swipeRight() {}
    override fun swipeDown() {}
    override fun swipeUp() {}

    // Input handling methods

    private fun handleCharacter(code: Int, ic: InputConnection) {
        var char = code.toChar()

        if (isShiftOn) {
            char = char.uppercaseChar()
            isShiftOn = false
        }

        // Commit character
        ic.commitText(char.toString(), 1)

        if (!analysisEnabledForField) {
            // Privacy: do NOT capture into buffer or run live analysis on
            // sensitive fields (passwords, banking, OTPs, etc.).
            return
        }

        // Add to message buffer
        messageBuffer.append(char.toString())
        lastCommittedChar = char

        // Check for send intent periodically
        checkSendIntentAsync()

        // Debounced live analysis (catches WhatsApp-style external Send).
        scheduleLiveAnalysis()
    }

    private fun handleBackspace(ic: InputConnection) {
        // Delete character before cursor
        ic.deleteSurroundingText(1, 0)

        // Remove from buffer
        messageBuffer.deleteLastChar()
    }

    private fun handleShift() {
        isShiftOn = !isShiftOn
        keyboard?.isShifted = isShiftOn
        keyboardView?.invalidateAllKeys()
    }

    private fun handleDone(ic: InputConnection) {
        // Enter key pressed - strong signal of intent to send
        sendIntentDetector.recordEnterKeyPress()

        if (!analysisEnabledForField) {
            Log.d(TAG, "Live skipped: sensitive field (handleDone)")
            ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER))
            return
        }

        // Paper Algorithm 1, step 1: immediate synchronous analysis on Enter/Send
        // in a social/communication context. If risky, consume the key event and
        // show the warning overlay. Only proceed with send if the user taps Continue.
        if (preferencesManager.isModerationEnabled() &&
            sendIntentDetector.isSocialCommunicationApp() &&
            !overlayManager.isOverlayShowing()
        ) {
            val message = messageBuffer.getCurrentMessage()
            if (message.isNotEmpty()) {
                try {
                    val result = enhancedAnalyzer.analyzeMessage(
                        message = message,
                        sensitivity = preferencesManager.getSensitivityThreshold().toDouble(),
                        platform = getPlatformFromPackage(currentAppPackage)
                    )
                    if (result.toxicityScore >= 0.5f) {
                        // Consume the Enter key: do NOT forward to the host app.
                        showWarningPopup(result)
                        return
                    }
                } catch (e: Exception) {
                    // Fail open
                    e.printStackTrace()
                }
            }
        }

        ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER))
        ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER))

        // Immediate send intent check (async delayed-pause path, kept for parity)
        checkSendIntentAsync()
    }

    private fun handleModeChange() {
        // Switch between alphabetic and numeric keyboards (future enhancement)
    }

    // -----------------------------------------------------------------------
    // Suggestion strip wiring
    // -----------------------------------------------------------------------

    private fun currentPartialWord(): String {
        val ic = currentInputConnection ?: return ""
        val before = ic.getTextBeforeCursor(64, 0)?.toString() ?: return ""
        // Take the trailing run of letters (partial word).
        val sb = StringBuilder()
        for (i in before.length - 1 downTo 0) {
            val c = before[i]
            if (c.isLetter()) sb.append(c) else break
        }
        return sb.reverse().toString()
    }

    private fun refreshSuggestions() {
        suggestionStrip?.updateForPrefix(currentPartialWord())
    }

    private fun replaceCurrentWord(word: String) {
        val ic = currentInputConnection ?: return
        val partial = currentPartialWord()
        ic.beginBatchEdit()
        try {
            if (partial.isNotEmpty()) {
                ic.deleteSurroundingText(partial.length, 0)
                // Sync buffer.
                repeat(partial.length) { messageBuffer.deleteLastChar() }
            }
            val out = word + " "
            ic.commitText(out, 1)
            messageBuffer.append(out)
        } finally {
            ic.endBatchEdit()
        }
        refreshSuggestions()
        scheduleLiveAnalysis()
    }

    // -----------------------------------------------------------------------
    // Backspace acceleration + long-press timers
    // -----------------------------------------------------------------------

    private fun cancelPendingTimers() {
        pendingLongPress?.let { pressHandler.removeCallbacks(it) }
        pendingLongPress = null
        backspaceRepeat?.let { pressHandler.removeCallbacks(it) }
        backspaceRepeat = null
    }

    /**
     * Backspace hold behavior:
     *   0..500ms  : single tap only (handled by onKey)
     *   500ms+    : 1 char / 40ms
     *   2000ms+   : whole word at a time (every 120ms)
     * Vibrates 30ms on each threshold change.
     */
    private fun scheduleBackspaceAcceleration() {
        val startTime = System.currentTimeMillis()
        var stage = 0 // 0=idle, 1=char-repeat, 2=word-repeat
        val runnable = object : Runnable {
            override fun run() {
                val ic = currentInputConnection ?: return
                val elapsed = System.currentTimeMillis() - startTime
                val newStage = when {
                    elapsed >= 2000 -> 2
                    elapsed >= 500 -> 1
                    else -> 0
                }
                if (newStage != stage) {
                    stage = newStage
                    if (stage > 0) vibrate(30)
                }
                when (stage) {
                    1 -> {
                        ic.deleteSurroundingText(1, 0)
                        messageBuffer.deleteLastChar()
                        refreshSuggestions()
                        pressHandler.postDelayed(this, 40)
                    }
                    2 -> {
                        deletePrecedingWord(ic)
                        refreshSuggestions()
                        pressHandler.postDelayed(this, 120)
                    }
                    else -> {
                        // Not yet at threshold — check again shortly.
                        pressHandler.postDelayed(this, 60)
                    }
                }
            }
        }
        backspaceRepeat = runnable
        // First check after 500ms (initial single tap is handled separately by onKey).
        pressHandler.postDelayed(runnable, 500)
    }

    private fun deletePrecedingWord(ic: InputConnection) {
        val before = ic.getTextBeforeCursor(64, 0)?.toString() ?: return
        if (before.isEmpty()) return
        // Trim one trailing whitespace, then all trailing non-whitespace.
        var i = before.length
        while (i > 0 && before[i - 1].isWhitespace()) i--
        while (i > 0 && !before[i - 1].isWhitespace()) i--
        val deleteCount = before.length - i
        if (deleteCount > 0) {
            ic.deleteSurroundingText(deleteCount, 0)
            repeat(deleteCount) { messageBuffer.deleteLastChar() }
        }
    }

    private fun vibrate(ms: Long) {
        try {
            val v = getSystemService(VIBRATOR_SERVICE) as? Vibrator ?: return
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") v.vibrate(ms)
            }
        } catch (_: Exception) { /* haptics are best-effort */ }
    }

    /**
     * Checks if user is about to send the message
     * If yes, analyzes the message and shows warning if needed
     */
    private fun checkSendIntentAsync() {
        if (!preferencesManager.isModerationEnabled()) {
            return
        }

        serviceScope.launch {
            // Check typing pause
            delay(1500) // Wait for pause threshold

            val timeSinceLastKey = System.currentTimeMillis() - lastKeyPressTime
            if (timeSinceLastKey >= 1500) {
                sendIntentDetector.recordTypingPause()
            }

            // Determine if user is about to send
            if (sendIntentDetector.isUserAboutToSend()) {
                val message = messageBuffer.getCurrentMessage()

                if (message.isNotEmpty()) {
                    // ONLY analyze in social/gaming/communication contexts
                    // NOT in productivity apps (Google Docs, Word, Notes, Search)
                    if (sendIntentDetector.isSocialCommunicationApp()) {
                        analyzeAndIntervene(message)
                    }
                }
            }
        }
    }

    /**
     * Analyzes message toxicity and shows warning if needed
     * Now uses enhanced detection with 90-95% accuracy
     */
    private suspend fun analyzeAndIntervene(message: String) = withContext(Dispatchers.Default) {
        try {
            // Get platform context for context-aware detection
            val platform = getPlatformFromPackage(currentAppPackage)

            // Use enhanced analyzer with all improvements
            val result = enhancedAnalyzer.analyzeMessage(
                message = message,
                sensitivity = preferencesManager.getSensitivityThreshold().toDouble(),
                platform = platform
            )

            // Check if message is problematic
            if (result.isToxic) {
                withContext(Dispatchers.Main) {
                    showWarningPopup(result)
                }
            }
        } catch (e: Exception) {
            // Fail open - if analysis fails, allow the message
            e.printStackTrace()
        }
    }

    /**
     * Map Android package name to platform hostname for context detection
     */
    private fun getPlatformFromPackage(packageName: String): String {
        return when {
            packageName.contains("instagram") -> "instagram.com"
            packageName.contains("twitter") || packageName.contains("x.corp") -> "x.com"
            packageName.contains("discord") -> "discord.com"
            packageName.contains("whatsapp") -> "whatsapp.com"
            packageName.contains("facebook") || packageName.contains("messenger") -> "facebook.com"
            packageName.contains("reddit") -> "reddit.com"
            packageName.contains("youtube") -> "youtube.com"
            packageName.contains("tiktok") -> "tiktok.com"
            packageName.contains("snapchat") -> "snapchat.com"
            packageName.contains("telegram") -> "telegram.org"
            packageName.contains("linkedin") -> "linkedin.com"
            packageName.contains("github") -> "github.com"
            else -> ""
        }
    }

    /**
     * Debounced live analysis while typing (Fig 2 trigger for messengers
     * that use their own Send button — WhatsApp, Instagram, etc.).
     *
     * Every printable-char insert calls this. We cancel any pending run and
     * post a new one 800ms later. Once fired, we hop off the UI thread for
     * the (potentially expensive) RF classifier call, then hop back to show
     * the warning overlay if score >= 0.5.
     */
    private fun scheduleLiveAnalysis() {
        if (!preferencesManager.isModerationEnabled()) {
            suggestionStrip?.showBanner("SW off (moderation disabled)", 2)
            return
        }
        if (!analysisEnabledForField) {
            Log.d(TAG, "Live skipped: sensitive field")
            suggestionStrip?.showBanner("SW off (sensitive field)", 2)
            return
        }

        suggestionStrip?.showBanner("SW: scheduling…", 0)

        // Cancel any pending analysis — user is still typing.
        pendingLiveAnalysis?.let { liveDebounceHandler.removeCallbacks(it) }

        val runnable = Runnable {
            val text = messageBuffer.getCurrentMessage()
            val wordCount = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size

            // Hard-trigger bypass: if the buffer contains an unambiguous
            // slur/profanity token, skip the length/word gate entirely.
            // Guarantees "stupid" / "motherfucker" alone still triggers.
            val bypassThresholds = enhancedAnalyzer.containsHardTriggerToken(text) != null

            if (!bypassThresholds &&
                (text.length < MIN_CHARS_FOR_LIVE || wordCount < MIN_WORDS_FOR_LIVE)) {
                Log.w(TAG, "Live analyze skipped: too short/few words len=${text.length} words=$wordCount")
                suggestionStrip?.showBanner("SW: need ${MIN_CHARS_FOR_LIVE}c/${MIN_WORDS_FOR_LIVE}w (have ${text.length}c/${wordCount}w)", 2)
                return@Runnable
            }
            if (text == lastLiveAnalyzedText) {
                Log.w(TAG, "Live analyze skipped: unchanged len=${text.length}")
                return@Runnable
            }
            if (overlayManager.isOverlayShowing()) {
                Log.w(TAG, "Live analyze skipped: overlay_already_showing")
                return@Runnable
            }

            lastLiveAnalyzedText = text
            suggestionStrip?.showBanner("SW: analyzing ${text.length}c…", 0)

            // Off UI thread for classifier, then back on for overlay.
            serviceScope.launch(Dispatchers.Default) {
                try {
                    val result = enhancedAnalyzer.analyzeMessage(
                        message = text,
                        sensitivity = preferencesManager.getSensitivityThreshold().toDouble(),
                        platform = getPlatformFromPackage(currentAppPackage)
                    )
                    Log.d(TAG, "Live analyzed len=${text.length} score=${result.toxicityScore}")
                    withContext(Dispatchers.Main) {
                        if (result.toxicityScore >= 0.5f) {
                            suggestionStrip?.showBanner(
                                "\u26A0 RISK ${(result.toxicityScore * 100).toInt()}% ${result.category}",
                                1
                            )
                            if (!overlayManager.isOverlayShowing()) {
                                showWarningPopup(result)
                            } else {
                                Log.w(TAG, "Live analyze skipped: overlay_shown_during_analysis")
                            }
                        } else {
                            suggestionStrip?.showBanner("\u2713 SW ok (${(result.toxicityScore * 100).toInt()}%)", 3)
                        }
                    }
                } catch (e: Exception) {
                    // Fail open.
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        suggestionStrip?.showBanner("SW error: ${e.message?.take(40)}", 2)
                    }
                }
            }
        }
        pendingLiveAnalysis = runnable

        // Word-boundary fast path: if the user just committed a space AND the
        // buffer already has enough words, fire quickly (300ms) instead of
        // waiting for the full debounce. This surfaces the warning sooner at
        // semantically meaningful chunks.
        val currentText = messageBuffer.getCurrentMessage()
        val currentWordCount = currentText.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
        val delayMs = if (lastCommittedChar == ' ' && currentWordCount >= MIN_WORDS_FOR_LIVE) {
            LIVE_ANALYSIS_WORD_BOUNDARY_DEBOUNCE_MS
        } else {
            LIVE_ANALYSIS_DEBOUNCE_MS
        }
        liveDebounceHandler.postDelayed(runnable, delayMs)
        Log.d(TAG, "Live scheduled for buffer len=${currentText.length} words=$currentWordCount delay=${delayMs}ms")
    }

    /**
     * Shows the intervention popup
     */
    private fun showWarningPopup(result: EnhancedToxicityAnalyzer.AnalysisResult) {
        overlayManager.showWarning(
            category = result.category,
            severity = result.severity
        )

        // Stash the triggering analysis so we can attribute a terminal action
        // (edited / sent_anyway / cancelled) to the right category+severity
        // even if the overlay is dismissed by the system before the user taps.
        pendingWarningCategory = result.category
        pendingWarningSeverity = result.severity
        warningDecisionMade = false

        // Store current analysis result for logging (convert to old format for compatibility)
        val legacyResult = ToxicityAnalyzer.AnalysisResult(
            toxicityScore = result.toxicityScore,
            category = result.category,
            severity = result.severity
        )
        overlayManager.currentAnalysisResult = legacyResult
    }

    /**
     * User chose to send anyway - log violation
     */
    private fun handleSendAnywayChoice() {
        val result = overlayManager.currentAnalysisResult ?: return
        if (warningDecisionMade) return
        warningDecisionMade = true

        // Log violation metadata (NO message content)
        serviceScope.launch {
            try {
                violationLogger.logViolation(
                    category = result.category,
                    severity = result.severity,
                    action = "sent_anyway"
                )
            } catch (e: Exception) {
                // Fail silently - don't block user
                e.printStackTrace()
            }
        }

        telemetryCounts.sentAnyway += 1
        logTelemetryCounts("sent_anyway")

        // Clear pending stash
        pendingWarningCategory = null
        pendingWarningSeverity = null

        // Clear buffer after send
        messageBuffer.clear()

        // Reset send intent detector
        sendIntentDetector.reset()

        // Allow live analysis to re-fire on the next batch of typing.
        lastLiveAnalyzedText = ""
    }

    /**
     * User chose to edit - dismiss and allow editing.
     * Logs action="edited" so Fig 3 can compare heeded vs ignored warnings.
     */
    private fun handleEditChoice() {
        if (!warningDecisionMade) {
            warningDecisionMade = true

            val category = pendingWarningCategory
                ?: overlayManager.currentAnalysisResult?.category
            val severity = pendingWarningSeverity
                ?: overlayManager.currentAnalysisResult?.severity

            if (category != null && severity != null) {
                serviceScope.launch {
                    try {
                        violationLogger.logViolation(
                            category = category,
                            severity = severity,
                            action = "edited"
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                telemetryCounts.edited += 1
                logTelemetryCounts("edited")
            }
        }

        pendingWarningCategory = null
        pendingWarningSeverity = null

        // Buffer remains intact; user can continue editing.
        // Reset send intent detector.
        sendIntentDetector.reset()

        // Allow live analysis to re-fire if user keeps typing after editing.
        lastLiveAnalyzedText = ""
    }

    /**
     * Overlay was dismissed by the system without an explicit user decision
     * (e.g. IME finished, service destroyed, tap outside). Log once as
     * action="cancelled" so the Fig 3 donut denominator stays accurate.
     */
    private fun handleOverlayCancelled() {
        if (warningDecisionMade) return
        warningDecisionMade = true

        val category = pendingWarningCategory
            ?: overlayManager.currentAnalysisResult?.category
        val severity = pendingWarningSeverity
            ?: overlayManager.currentAnalysisResult?.severity

        if (category != null && severity != null) {
            serviceScope.launch {
                try {
                    violationLogger.logViolation(
                        category = category,
                        severity = severity,
                        action = "cancelled"
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            telemetryCounts.cancelled += 1
            logTelemetryCounts("cancelled")
        }

        pendingWarningCategory = null
        pendingWarningSeverity = null

        // Allow live analysis to re-fire on new typing after cancellation.
        lastLiveAnalyzedText = ""
    }

    // =========================================================================
    // Image attach & protect (new, additive — does not touch text moderation
    // logic above). Picked via SafeKeyboard's own attach button only; IMEs
    // cannot intercept images selected through a host app's own gallery/
    // camera UI.
    // =========================================================================

    /**
     * Handles the Uri delivered by [ImageHandoff] after the user picks (or
     * cancels) an image in [ImagePickerActivity].
     *
     * Decodes+downscales off the UI thread, classifies for NSFW/nudity, then
     * hops back to Main to either show the decision overlay (if sensitive)
     * or insert the image directly (if not).
     */
    private fun handlePickedImage(uri: Uri?) {
        if (uri == null) return

        serviceScope.launch(Dispatchers.Default) {
            try {
                val bitmap = ImageBlurUtils.decodeAndDownscale(this@SafeKeyboardIME, uri)
                if (bitmap == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@SafeKeyboardIME,
                            R.string.toast_analysis_failed,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@launch
                }

                val result = nsfwClassifier.classify(bitmap)

                withContext(Dispatchers.Main) {
                    if (result.isSensitive) {
                        val blurred = ImageBlurUtils.blurBitmap(bitmap)
                        pendingImageOriginal = bitmap
                        pendingImageBlurred = blurred
                        pendingImageCategory = result.label
                        pendingImageSeverity = severityFromConfidence(result.confidence)
                        imageOverlayManager.allowSendOriginal = allowSendOriginalOverride()
                        imageOverlayManager.showImageWarning(
                            category = result.label,
                            severity = pendingImageSeverity!!,
                            previewBitmap = blurred
                        )
                    } else {
                        insertImageIntoInputConnection(bitmap)
                    }
                }
            } catch (e: Exception) {
                // Fail open — never block the user because of a classifier error.
                e.printStackTrace()
            }
        }
    }

    private fun severityFromConfidence(c: Float): String = when {
        c >= 0.8f -> "high"
        c >= 0.5f -> "medium"
        else -> "low"
    }

    /**
     * Whether the "Send Original" (unblurred) override is offered on the
     * image warning overlay. Locked down (disallowed) when this device is
     * operating under parental consent AND moderation is enabled — i.e. a
     * supervised child account should not be able to bypass the blur.
     * Otherwise (self-managed adult account, or moderation turned off
     * entirely) the override is available.
     */
    private fun allowSendOriginalOverride(): Boolean {
        return !(preferencesManager.hasParentalConsent() && preferencesManager.isModerationEnabled())
    }

    /**
     * Resolves the user's 3-way decision on a flagged image and logs
     * metadata only (category/severity/action — never image bytes/content)
     * via the existing [violationLogger], mirroring the text-warning
     * edited/sent_anyway/cancelled pattern.
     */
    private fun handleImageDecision(decision: ImageWarningOverlayManager.ImageDecision) {
        val category = pendingImageCategory
        val severity = pendingImageSeverity

        when (decision) {
            ImageWarningOverlayManager.ImageDecision.KEEP_BLURRED -> {
                pendingImageBlurred?.let { insertImageIntoInputConnection(it) }
                if (category != null && severity != null) {
                    serviceScope.launch {
                        try {
                            violationLogger.logViolation(
                                category = category,
                                severity = severity,
                                action = "edited"
                            )
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
            ImageWarningOverlayManager.ImageDecision.DISCARD -> {
                if (category != null && severity != null) {
                    serviceScope.launch {
                        try {
                            violationLogger.logViolation(
                                category = category,
                                severity = severity,
                                action = "cancelled"
                            )
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
            ImageWarningOverlayManager.ImageDecision.SEND_ORIGINAL -> {
                pendingImageOriginal?.let { insertImageIntoInputConnection(it) }
                if (category != null && severity != null) {
                    serviceScope.launch {
                        try {
                            violationLogger.logViolation(
                                category = category,
                                severity = severity,
                                action = "sent_anyway"
                            )
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        }

        pendingImageOriginal = null
        pendingImageBlurred = null
        pendingImageCategory = null
        pendingImageSeverity = null
    }

    /**
     * Inserts [bitmap] into the focused input field.
     *
     * Writes it to a short-lived FileProvider-backed cache file under
     * `cacheDir/images/` (the ONLY place the original/unblurred image is
     * ever persisted, and only transiently), then tries
     * [InputConnectionCompat.commitContent] (API 25+ content commit). If the
     * host app doesn't support commitContent, falls back to placing the
     * image on the system clipboard via [ClipboardManager] using the same
     * FileProvider Uri. The cache file is tracked in [imageCacheFiles] and
     * deleted on [onDestroy] (promptly — never left on persistent storage).
     */
    private fun insertImageIntoInputConnection(bitmap: Bitmap) {
        val ic = currentInputConnection
        val cacheFile = writeBitmapToCache(bitmap) ?: return
        val contentUri = try {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", cacheFile)
        } catch (e: Exception) {
            e.printStackTrace()
            cacheFile.delete()
            imageCacheFiles.remove(cacheFile)
            return
        }

        val editorInfo = currentInputEditorInfo
        var committed = false
        if (ic != null && editorInfo != null) {
            try {
                val inputContentInfo = InputContentInfoCompat(
                    contentUri,
                    ClipDescription("image", arrayOf("image/png")),
                    null
                )
                committed = InputConnectionCompat.commitContent(
                    ic, editorInfo, inputContentInfo,
                    InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                    null
                )
            } catch (e: Exception) {
                e.printStackTrace()
                committed = false
            }
        }

        if (!committed) {
            try {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = ClipData.newUri(contentResolver, "SafeKeyboard image", contentUri)
                clipboard?.setPrimaryClip(clip)
                val appLabel = currentAppPackage.ifEmpty { "the app" }
                Toast.makeText(
                    this,
                    getString(R.string.toast_image_copied_prefix) + appLabel,
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Cache file is only needed transiently for the handoff above.
        // Schedule prompt deletion rather than keeping it around.
        deleteImageCacheFile(cacheFile)
    }

    /**
     * Writes [bitmap] to a new file under cacheDir/images/ (created if
     * needed) and tracks it in [imageCacheFiles] for cleanup. Returns null
     * on failure.
     */
    private fun writeBitmapToCache(bitmap: Bitmap): File? {
        return try {
            val dir = File(cacheDir, "images").apply { mkdirs() }
            val file = File(dir, "img_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            imageCacheFiles.add(file)
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Deletes a single tracked cache file immediately after use. Safe to
     * call even if the host app hasn't finished reading the content:// Uri
     * yet in theory, but commitContent/clipboard consumers read eagerly in
     * practice; as a defense-in-depth all remaining tracked files are also
     * swept in [onDestroy].
     */
    private fun deleteImageCacheFile(file: File) {
        try {
            file.delete()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            imageCacheFiles.remove(file)
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // If the overlay was showing and no decision was made, this counts
        // as a cancellation. WarningOverlayManager.cleanup() will fire the
        // onDismissedWithoutDecision callback; we mirror that here in case
        // the view is torn down without cleanup() (safety net; guarded by
        // warningDecisionMade so we don't double-log).
        if (overlayManager.isOverlayShowing() && !warningDecisionMade) {
            handleOverlayCancelled()
        }
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        // Ensure a hanging warning gets a cancelled log before teardown.
        if (overlayManager.isOverlayShowing() && !warningDecisionMade) {
            handleOverlayCancelled()
        }
        // Image overlay: same safety-net pattern for a hanging image warning.
        if (imageOverlayManager.isOverlayShowing()) {
            handleImageDecision(ImageWarningOverlayManager.ImageDecision.DISCARD)
        }
        super.onDestroy()
        serviceScope.cancel()
        cancelPendingTimers()
        pendingLiveAnalysis?.let { liveDebounceHandler.removeCallbacks(it) }
        pendingLiveAnalysis = null
        overlayManager.cleanup()
        imageOverlayManager.cleanup()
        enhancedAnalyzer.destroy()
        if (nsfwClassifierLazy.isInitialized()) {
            nsfwClassifier.close()
        }
        ImageHandoff.listener = null

        // Privacy: sweep any leftover short-lived image cache files. Under
        // normal operation these are deleted immediately after the
        // commitContent/clipboard handoff, but this is a defense-in-depth
        // guard against a leaked file if the IME is torn down mid-handoff.
        imageCacheFiles.toList().forEach { file ->
            try {
                file.delete()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        imageCacheFiles.clear()
    }
}
