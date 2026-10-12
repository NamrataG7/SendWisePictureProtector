package com.safekeyboard.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * UserIdGenerator - Generates privacy-preserving anonymous user IDs
 *
 * IDENTITY RULES (NON-NEGOTIABLE):
 * - No login, no phone number, no email, no Android account
 *
 * IMPLEMENTATION:
 * - Hash(AndroidID + PerDeviceRandomSalt)
 * - SHA-256, one-way, stable across sessions on the same install
 *
 * SECURITY MODEL (revised):
 * - The salt is a 32-byte value from SecureRandom, generated on first launch
 *   and persisted in EncryptedSharedPreferences (AES-256 GCM, key in Android Keystore).
 * - The salt is NEVER compiled into the APK. An attacker with the APK cannot
 *   reverse SHA-256(AndroidID + salt) because the salt is unknown and unique
 *   per install.
 * - Survives app updates (EncryptedSharedPreferences is regular app-private storage).
 * - Regenerates on "Clear Data" / reinstall (which is correct — that user is a new
 *   pseudonymous actor from the dashboard's perspective).
 *
 * FALLBACK:
 * - If EncryptedSharedPreferences init fails (rare — e.g. Keystore corruption),
 *   we fall back to a regular SharedPreferences file and log a WARNING. This is
 *   "security-degraded mode": the salt is still per-device-random (better than a
 *   compiled-in constant) but not encrypted at rest.
 */
object UserIdGenerator {

    private const val TAG = "UserIdGenerator"

    // File names
    private const val ENCRYPTED_PREFS_FILE = "safekeyboard_secure_id"
    private const val FALLBACK_PREFS_FILE = "safekeyboard_secure_id_fallback"

    // Keys
    private const val KEY_DEVICE_SALT = "device_salt"           // base64 of 32 random bytes
    private const val KEY_LEGACY_ROTATED = "legacy_salt_rotated" // one-shot log guard
    private const val KEY_SCHEMA_VERSION = "salt_schema_version"

    private const val CURRENT_SCHEMA_VERSION = 2 // v1 = compiled-in APP_SALT (deprecated)
    private const val SALT_LENGTH_BYTES = 32

    /**
     * Generates an anonymous user ID hash: SHA-256(AndroidID + per-device-salt).
     *
     * @return 64-char lowercase hex string
     */
    @SuppressLint("HardwareIds")
    fun getAnonymousUserId(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "UNKNOWN"

        val saltBytes = getOrCreateDeviceSalt(context)
        val androidIdBytes = androidId.toByteArray(Charsets.UTF_8)

        val combined = ByteArray(androidIdBytes.size + saltBytes.size)
        System.arraycopy(androidIdBytes, 0, combined, 0, androidIdBytes.size)
        System.arraycopy(saltBytes, 0, combined, androidIdBytes.size, saltBytes.size)

        return sha256Hex(combined)
    }

    /**
     * Alias for [getAnonymousUserId] — used by callers that prefer the "hash" naming.
     */
    fun getUserIdHash(context: Context): String = getAnonymousUserId(context)

    /**
     * Force-generates a new device salt. Intended for testing / support flows
     * (e.g. "reset pseudonym"). Callers should be aware that this invalidates
     * any pairing on the dashboard side — the device will look like a brand-new
     * pseudonymous actor after rotation.
     */
    @JvmStatic
    fun rotateSalt(context: Context) {
        val prefs = openPrefs(context)
        val newSalt = ByteArray(SALT_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(KEY_DEVICE_SALT, Base64.encodeToString(newSalt, Base64.NO_WRAP))
            .putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
            .apply()
        Log.i(TAG, "Device salt rotated (schema v$CURRENT_SCHEMA_VERSION)")
    }

    fun isValidUserId(userId: String): Boolean =
        userId.matches(Regex("^[a-f0-9]{64}$"))

    fun getShortenedUserId(userId: String): String {
        if (userId.length < 12) return userId
        return "${userId.substring(0, 8)}...${userId.substring(userId.length - 4)}"
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private fun getOrCreateDeviceSalt(context: Context): ByteArray {
        val prefs = openPrefs(context)

        // Migration: if we're upgrading from the old compiled-salt scheme,
        // there will be no KEY_DEVICE_SALT yet. Log once and generate.
        val storedSchema = prefs.getInt(KEY_SCHEMA_VERSION, 1)
        val existing = prefs.getString(KEY_DEVICE_SALT, null)

        if (existing != null) {
            return try {
                Base64.decode(existing, Base64.NO_WRAP)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Stored salt corrupted; regenerating", e)
                generateAndPersistSalt(prefs)
            }
        }

        if (storedSchema < CURRENT_SCHEMA_VERSION && !prefs.getBoolean(KEY_LEGACY_ROTATED, false)) {
            Log.i(TAG, "Rotating device hash for stronger pseudonymity")
            prefs.edit().putBoolean(KEY_LEGACY_ROTATED, true).apply()
        }

        return generateAndPersistSalt(prefs)
    }

    private fun generateAndPersistSalt(prefs: SharedPreferences): ByteArray {
        val salt = ByteArray(SALT_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(KEY_DEVICE_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
            .apply()
        return salt
    }

    private fun openPrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context.applicationContext,
                ENCRYPTED_PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "EncryptedSharedPreferences init failed; falling back to plain SharedPreferences " +
                    "(SECURITY-DEGRADED MODE — salt is per-device-random but not encrypted at rest)",
                t
            )
            context.applicationContext.getSharedPreferences(
                FALLBACK_PREFS_FILE,
                Context.MODE_PRIVATE
            )
        }
    }

    private fun sha256Hex(input: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
