package com.safekeyboard.ui

import android.os.Bundle
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import com.safekeyboard.R
import com.safekeyboard.utils.PreferencesManager
import com.safekeyboard.utils.UserIdGenerator

/**
 * SettingsActivity - User preferences and settings
 *
 * MUST INCLUDE:
 * - Toggle: Enable / Disable moderation
 * - Explanation screen
 * - Reset counters (local)
 * - Opt-out always available
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        private lateinit var preferencesManager: PreferencesManager

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            preferencesManager = PreferencesManager(requireContext())

            setupPreferences()
        }

        private fun setupPreferences() {
            // Moderation toggle
            val moderationSwitch = findPreference<SwitchPreferenceCompat>("moderation_enabled")
            moderationSwitch?.setOnPreferenceChangeListener { _, newValue ->
                preferencesManager.setModerationEnabled(newValue as Boolean)
                true
            }

            // Sensitivity threshold
            val sensitivitySeekBar = findPreference<SeekBarPreference>("sensitivity_threshold")
            sensitivitySeekBar?.setOnPreferenceChangeListener { _, newValue ->
                val threshold = (newValue as Int) / 100f
                preferencesManager.setSensitivityThreshold(threshold)
                true
            }

            // Display current statistics
            updateStatistics()

            // Reset counters
            val resetCounters = findPreference<Preference>("reset_counters")
            resetCounters?.setOnPreferenceClickListener {
                showResetConfirmation()
                true
            }

            // Parental Link -> opens PairingActivity
            val parentalLink = findPreference<Preference>("parental_link")
            parentalLink?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), PairingActivity::class.java))
                true
            }

            // Display user ID
            val userIdPref = findPreference<Preference>("user_id")
            val userId = UserIdGenerator.getAnonymousUserId(requireContext())
            userIdPref?.summary = UserIdGenerator.getShortenedUserId(userId)

            // Privacy information
            val aboutPrivacy = findPreference<Preference>("about_privacy")
            aboutPrivacy?.setOnPreferenceClickListener {
                showPrivacyDialog()
                true
            }
        }

        private fun updateStatistics() {
            val violationCount = preferencesManager.getViolationCount()
            val warningCount = preferencesManager.getWarningCount()

            findPreference<Preference>("violation_count")?.summary = violationCount.toString()
            findPreference<Preference>("warning_count")?.summary = warningCount.toString()
        }

        private fun showResetConfirmation() {
            AlertDialog.Builder(requireContext())
                .setTitle("Reset Statistics")
                .setMessage("Are you sure you want to reset all local statistics? This cannot be undone.")
                .setPositiveButton("Reset") { _, _ ->
                    preferencesManager.resetStatistics()
                    updateStatistics()
                    Toast.makeText(
                        requireContext(),
                        R.string.toast_counters_reset,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        private fun showPrivacyDialog() {
            val message = getString(R.string.privacy_what_analyzed) + "\n\n" +
                    getString(R.string.privacy_what_not_stored) + "\n\n" +
                    getString(R.string.privacy_what_sent)

            AlertDialog.Builder(requireContext())
                .setTitle(R.string.privacy_title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show()
        }
    }
}
