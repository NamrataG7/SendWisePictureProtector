package com.safekeyboard.ui

import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.safekeyboard.R
import com.safekeyboard.network.PairingGenerateRequest
import com.safekeyboard.network.RetrofitClient
import com.safekeyboard.utils.PreferencesManager
import com.safekeyboard.utils.UserIdGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * PairingActivity
 *
 * Lets a child's device generate a short-lived 6-digit pairing code that a
 * parent enters into the SendWise dashboard to link the two.
 *
 * States (mutually exclusive containers in activity_pairing.xml):
 *   1. GENERATE - no unexpired code cached: show "Generate Code" CTA.
 *   2. CODE     - a live code is cached: show code + countdown to expiry.
 *   3. PAIRED   - device already linked: show "Unlink" CTA.
 *
 * Network:
 *   POST /api/pairing/generate  { user_id_hash } -> { code, expires_at }
 *   Failures surface via Snackbar with a retry action.
 *
 * Privacy:
 *   Only the anonymous SHA-256 user_id_hash is transmitted. No PII.
 */
class PairingActivity : AppCompatActivity() {

    private lateinit var prefs: PreferencesManager

    // Views (bound in onCreate; nullable state after finish() to avoid leaks
    // via the CountDownTimer callback).
    private var stateGenerate: View? = null
    private var stateCode: View? = null
    private var statePaired: View? = null
    private var progressContainer: View? = null
    private var btnGenerate: Button? = null
    private var btnRegenerate: Button? = null
    private var btnUnlink: Button? = null
    private var tvCode: TextView? = null
    private var tvCountdown: TextView? = null
    private var rootView: View? = null

    private var countdownTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pairing)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.pairing_title)

        prefs = PreferencesManager(this)

        rootView = findViewById(android.R.id.content)
        stateGenerate = findViewById(R.id.state_generate)
        stateCode = findViewById(R.id.state_code)
        statePaired = findViewById(R.id.state_paired)
        progressContainer = findViewById(R.id.progress_container)
        btnGenerate = findViewById(R.id.btn_generate)
        btnRegenerate = findViewById(R.id.btn_regenerate)
        btnUnlink = findViewById(R.id.btn_unlink)
        tvCode = findViewById(R.id.tv_code)
        tvCountdown = findViewById(R.id.tv_countdown)

        btnGenerate?.setOnClickListener { requestPairingCode() }
        btnRegenerate?.setOnClickListener { requestPairingCode() }
        btnUnlink?.setOnClickListener { confirmUnlink() }

        renderInitialState()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        countdownTimer?.cancel()
        countdownTimer = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // State rendering
    // ---------------------------------------------------------------------

    private fun renderInitialState() {
        when {
            prefs.isPaired() -> showPairedState()
            prefs.hasUnexpiredPairingCode() -> {
                val code = prefs.getPairingCode()
                val expiresAt = prefs.getPairingExpiresAt()
                if (code != null) {
                    showCodeState(code, expiresAt)
                } else {
                    showGenerateState()
                }
            }
            else -> showGenerateState()
        }
    }

    private fun showGenerateState() {
        countdownTimer?.cancel()
        countdownTimer = null
        stateGenerate?.visibility = View.VISIBLE
        stateCode?.visibility = View.GONE
        statePaired?.visibility = View.GONE
        progressContainer?.visibility = View.GONE
        btnGenerate?.isEnabled = true
    }

    private fun showCodeState(code: String, expiresAtEpochMs: Long) {
        stateGenerate?.visibility = View.GONE
        stateCode?.visibility = View.VISIBLE
        statePaired?.visibility = View.GONE

        tvCode?.text = code
        startCountdown(expiresAtEpochMs)
    }

    private fun showPairedState() {
        countdownTimer?.cancel()
        countdownTimer = null
        stateGenerate?.visibility = View.GONE
        stateCode?.visibility = View.GONE
        statePaired?.visibility = View.VISIBLE
    }

    private fun startCountdown(expiresAtEpochMs: Long) {
        countdownTimer?.cancel()
        val remainingMs = expiresAtEpochMs - System.currentTimeMillis()
        if (remainingMs <= 0L) {
            handleCodeExpired()
            return
        }
        tvCountdown?.text = buildCountdownLabel(remainingMs)
        countdownTimer = object : CountDownTimer(remainingMs, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                tvCountdown?.text = buildCountdownLabel(millisUntilFinished)
            }

            override fun onFinish() {
                handleCodeExpired()
            }
        }.also { it.start() }
    }

    private fun buildCountdownLabel(remainingMs: Long): String {
        val totalSeconds = (remainingMs / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        val time = String.format("%02d:%02d:%02d", hours, minutes, seconds)
        return getString(R.string.pairing_expires_prefix) + time
    }

    private fun handleCodeExpired() {
        prefs.clearPairingCode()
        showGenerateState()
        val root = rootView ?: return
        Snackbar.make(root, R.string.pairing_expired, Snackbar.LENGTH_LONG).show()
    }

    // ---------------------------------------------------------------------
    // Network
    // ---------------------------------------------------------------------

    private fun requestPairingCode() {
        // UI: disable the trigger, show inline progress.
        btnGenerate?.isEnabled = false
        btnRegenerate?.isEnabled = false
        progressContainer?.visibility = View.VISIBLE

        val userIdHash = UserIdGenerator.getAnonymousUserId(applicationContext)

        lifecycleScope.launch {
            val outcome: PairingOutcome = try {
                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.pairingApiService.generatePairingCode(
                        PairingGenerateRequest(user_id_hash = userIdHash)
                    )
                }
                val body = response.body()
                val expiresAtMs = body?.expires_at?.let { parseIso8601ToMillis(it) }
                if (response.isSuccessful && body != null &&
                    !body.code.isNullOrBlank() && expiresAtMs != null &&
                    expiresAtMs > System.currentTimeMillis()
                ) {
                    PairingOutcome.Success(body.code, expiresAtMs)
                } else {
                    android.util.Log.w(
                        "PairingActivity",
                        "Pairing failed: http=${response.code()} code=${body?.code} " +
                            "expires_at=${body?.expires_at} parsed=$expiresAtMs error=${body?.error}"
                    )
                    PairingOutcome.Failure(getString(R.string.pairing_error_generic))
                }
            } catch (io: IOException) {
                android.util.Log.w("PairingActivity", "Pairing network error", io)
                PairingOutcome.Failure(getString(R.string.pairing_error_network))
            } catch (t: Throwable) {
                android.util.Log.w("PairingActivity", "Pairing unexpected error", t)
                PairingOutcome.Failure(getString(R.string.pairing_error_generic))
            }

            // UI restore
            progressContainer?.visibility = View.GONE
            btnGenerate?.isEnabled = true
            btnRegenerate?.isEnabled = true

            when (outcome) {
                is PairingOutcome.Success -> {
                    prefs.setPairingCode(outcome.code, outcome.expiresAt)
                    showCodeState(outcome.code, outcome.expiresAt)
                }
                is PairingOutcome.Failure -> showErrorSnackbar(outcome.message)
            }
        }
    }

    private fun showErrorSnackbar(message: String) {
        val root = rootView ?: return
        Snackbar.make(root, message, Snackbar.LENGTH_INDEFINITE)
            .setAction(R.string.pairing_retry) { requestPairingCode() }
            .show()
    }

    // ---------------------------------------------------------------------
    // Unlink
    // ---------------------------------------------------------------------

    private fun confirmUnlink() {
        AlertDialog.Builder(this)
            .setTitle(R.string.pairing_unlink_confirm_title)
            .setMessage(R.string.pairing_unlink_confirm_body)
            .setPositiveButton(R.string.pairing_unlink) { _, _ ->
                prefs.setPaired(false)
                showGenerateState()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private sealed class PairingOutcome {
        data class Success(val code: String, val expiresAt: Long) : PairingOutcome()
        data class Failure(val message: String) : PairingOutcome()
    }

    /**
     * Parse an ISO 8601 UTC string like "2026-08-25T17:45:11.242Z" into
     * epoch millis. Returns null on any parse failure so the caller can
     * surface a generic error instead of crashing.
     */
    private fun parseIso8601ToMillis(iso: String): Long? = try {
        val instant = java.time.Instant.parse(iso)
        instant.toEpochMilli()
    } catch (t: Throwable) {
        android.util.Log.w("PairingActivity", "Failed to parse expires_at: $iso", t)
        null
    }
}
