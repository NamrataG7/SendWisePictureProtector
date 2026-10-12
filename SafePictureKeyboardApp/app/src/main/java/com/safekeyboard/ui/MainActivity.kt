package com.safekeyboard.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.safekeyboard.R
import com.safekeyboard.utils.PreferencesManager

/**
 * MainActivity - Main entry point of the app
 *
 * Provides:
 * - Enable keyboard instructions
 * - Settings access
 * - Privacy information
 */
class MainActivity : AppCompatActivity() {

    private lateinit var buttonEnableKeyboard: Button
    private lateinit var buttonSettings: Button
    private lateinit var buttonPrivacy: Button
    private lateinit var statusCard: CardView
    private lateinit var prefsManager: PreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize PreferencesManager
        prefsManager = PreferencesManager(this)

        // Check ToS and age verification on first run
        checkFirstRunRequirements()

        // Initialize views
        buttonEnableKeyboard = findViewById(R.id.button_enable_keyboard)
        buttonSettings = findViewById(R.id.button_settings)
        buttonPrivacy = findViewById(R.id.button_privacy)
        statusCard = findViewById(R.id.status_card)

        // Set up click listeners
        buttonEnableKeyboard.setOnClickListener {
            openKeyboardSettings()
        }

        buttonSettings.setOnClickListener {
            openSettings()
        }

        buttonPrivacy.setOnClickListener {
            showPrivacyDialog()
        }
    }

    /**
     * Checks if user has accepted ToS and verified age (first run only)
     */
    private fun checkFirstRunRequirements() {
        if (!prefsManager.isToSAccepted()) {
            showAgeVerificationDialog()
        }
    }

    /**
     * Shows age verification dialog (COPPA compliance)
     */
    private fun showAgeVerificationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Age Verification")
            .setMessage("SafeKeyboard is designed to promote online safety.\n\nAre you 13 years of age or older?")
            .setPositiveButton("Yes, I'm 13+") { _, _ ->
                prefsManager.setAgeVerified(true)
                showToSDialog()
            }
            .setNegativeButton("No, I'm under 13") { _, _ ->
                showParentalConsentDialog()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Shows parental consent dialog for users under 13
     */
    private fun showParentalConsentDialog() {
        AlertDialog.Builder(this)
            .setTitle("Parental Consent Required")
            .setMessage("Users under 13 require parental permission to use this app.\n\n" +
                    "Please have a parent or guardian review the Terms of Service and Privacy Policy, " +
                    "then tap \"Parent: I Consent\" below.")
            .setPositiveButton("Parent: I Consent") { _, _ ->
                prefsManager.setAgeVerified(true)
                prefsManager.setParentalConsent(true)
                showToSDialog()
            }
            .setNegativeButton("Exit") { _, _ ->
                Toast.makeText(this,
                    "Parental consent is required to use SafeKeyboard",
                    Toast.LENGTH_LONG).show()
                finish()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Shows Terms of Service acceptance dialog
     */
    private fun showToSDialog() {
        AlertDialog.Builder(this)
            .setTitle("Terms of Service")
            .setMessage("By using SafeKeyboard, you agree to:\n\n" +
                    "• Monitoring of keyboard input for safety purposes\n" +
                    "• Collection of anonymous violation statistics\n" +
                    "• No guarantee of 100% accuracy (false positives/negatives possible)\n" +
                    "• \"AS IS\" provision - no liability for missed detections\n" +
                    "• You are ultimately responsible for your messages\n\n" +
                    "Full Terms of Service and Privacy Policy available in Settings.\n\n" +
                    "Do you agree to these terms?")
            .setPositiveButton("I Agree") { _, _ ->
                prefsManager.setToSAccepted(true)
                Toast.makeText(this,
                    "Welcome to SafeKeyboard! Enable it in Settings below.",
                    Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Decline") { _, _ ->
                Toast.makeText(this,
                    "You must accept the Terms of Service to use SafeKeyboard",
                    Toast.LENGTH_LONG).show()
                finish()
            }
            .setCancelable(false)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // Check keyboard status
        updateKeyboardStatus()
    }

    /**
     * Opens the system keyboard settings
     */
    private fun openKeyboardSettings() {
        startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
    }

    /**
     * Opens the app settings
     */
    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    /**
     * Shows privacy information dialog
     */
    private fun showPrivacyDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.privacy_title)
            .setMessage(buildPrivacyMessage())
            .setPositiveButton("OK", null)
            .show()
    }

    /**
     * Builds the privacy message
     */
    private fun buildPrivacyMessage(): String {
        return getString(R.string.privacy_what_analyzed) + "\n\n" +
                getString(R.string.privacy_what_not_stored) + "\n\n" +
                getString(R.string.privacy_what_sent)
    }

    /**
     * Updates the keyboard status display
     */
    private fun updateKeyboardStatus() {
        if (isKeyboardEnabled()) {
            statusCard.visibility = View.VISIBLE
        } else {
            statusCard.visibility = View.GONE
        }
    }

    /**
     * Checks if SafeKeyboard is enabled in system settings
     */
    private fun isKeyboardEnabled(): Boolean {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabledInputMethods = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_INPUT_METHODS
        ) ?: ""

        return enabledInputMethods.contains(packageName)
    }
}
