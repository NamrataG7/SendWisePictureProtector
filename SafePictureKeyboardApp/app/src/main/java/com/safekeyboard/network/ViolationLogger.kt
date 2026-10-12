package com.safekeyboard.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.safekeyboard.utils.PreferencesManager
import com.safekeyboard.utils.UserIdGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Response
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * ViolationLogger - POSTs violation metadata to /api/violations.
 *
 * Payload matches parental-dashboard/lib/schema.ts ViolationIngestSchema:
 *   { user_id_hash, timestamp (ISO 8601), category, severity, action, session_id }
 *
 * RULES:
 * - NEVER include text / message / content (server rejects strictly).
 * - session_id is a per-app-launch UUID (not persisted).
 * - Category is mapped from internal analyzer names to the 5 API enum values.
 * - Severity is normalized to low | medium | high.
 * - HTTPS only, TLS 1.3 preferred (see RetrofitClient).
 * - 429 rate-limit: log + exponential backoff, max 3 attempts.
 * - Offline: queue count-only, sync when online.
 */
class ViolationLogger(private val context: Context) {

    companion object {
        // Per-app-launch UUID (companion => shared across ViolationLogger instances in this process).
        // Not persisted; regenerated on process restart, per paper's session_id definition.
        val SESSION_ID: String = UUID.randomUUID().toString()

        private const val MAX_RETRY_ATTEMPTS = 3
        private const val INITIAL_BACKOFF_MS = 500L

        // Canonical 6 risk categories accepted by the API
        // (mirrors parental-dashboard/lib/schema.ts IncidentCategoryEnum).
        // "sensitive_image" represents on-device NSFW/nudity detection in
        // images attached via the keyboard's image picker (separate from
        // text toxicity detection).
        val CANONICAL_CATEGORIES: Set<String> = setOf(
            "harassment",
            "threats",
            "hate_speech",
            "sexual_content",
            "self_harm",
            "sensitive_image"
        )

        // Safety-net fallback when analyzer emits an unknown / legacy value.
        // Analyzers now emit canonical names directly, so this is only a
        // defense-in-depth guard against future drift.
        private const val DEFAULT_CATEGORY = "harassment"

        /**
         * Pass-through identity mapping to the API's canonical taxonomy.
         *
         * The on-device analyzers (ToxicityAnalyzer / EnhancedToxicityAnalyzer)
         * emit one of the 6 canonical category strings directly. If a value
         * outside the canonical set is ever received, we fall back to
         * [DEFAULT_CATEGORY] so the ingest still succeeds schema validation.
         */
        fun mapCategory(internal: String): String {
            val key = internal.trim().lowercase()
            return if (key in CANONICAL_CATEGORIES) key else DEFAULT_CATEGORY
        }

        fun normalizeSeverity(severity: String): String {
            return when (severity.trim().lowercase()) {
                "high", "severe", "critical" -> "high"
                "low", "mild", "minor"       -> "low"
                else                          -> "medium"
            }
        }

        private val ISO_FORMAT: SimpleDateFormat by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }

        fun isoNow(): String = ISO_FORMAT.format(Date())
    }

    private val apiService = RetrofitClient.apiService
    private val preferencesManager = PreferencesManager(context)
    private val queueFile = File(context.filesDir, "violation_queue.json")

    /**
     * Logs a violation to the server. If offline, queues for later sync.
     */
    suspend fun logViolation(
        category: String,
        severity: String,
        action: String
    ) = withContext(Dispatchers.IO) {
        try {
            val userId = UserIdGenerator.getAnonymousUserId(context)

            val request = ViolationLogRequest(
                user_id_hash = userId,
                timestamp = isoNow(),
                category = mapCategory(category),
                severity = normalizeSeverity(severity),
                action = action,
                session_id = SESSION_ID
            )

            if (isNetworkAvailable()) {
                sendViolationWithRetry(request)
                syncQueuedViolations()
            } else {
                queueViolation(request)
            }

            updateLocalStatistics(action, request.category)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Sends a violation, retrying on 429 with exponential backoff.
     */
    private suspend fun sendViolationWithRetry(request: ViolationLogRequest) {
        var attempt = 0
        var backoff = INITIAL_BACKOFF_MS
        while (attempt < MAX_RETRY_ATTEMPTS) {
            attempt++
            try {
                val response: Response<ViolationLogResponse> = apiService.logViolation(request)

                if (response.isSuccessful) {
                    response.body()?.escalation_flag?.let { flagged ->
                        if (flagged) handleEscalationFlag(response.body()?.current_count ?: 0)
                    }
                    return
                }

                if (response.code() == 429) {
                    println("ViolationLogger: 429 rate limit, attempt=$attempt, backoff=${backoff}ms")
                    if (attempt < MAX_RETRY_ATTEMPTS) {
                        delay(backoff)
                        backoff *= 2
                        continue
                    } else {
                        queueViolation(request)
                        return
                    }
                }

                // Non-retryable error
                queueViolation(request)
                return
            } catch (e: Exception) {
                e.printStackTrace()
                if (attempt >= MAX_RETRY_ATTEMPTS) {
                    queueViolation(request)
                    return
                }
                delay(backoff)
                backoff *= 2
            }
        }
    }

    private fun queueViolation(request: ViolationLogRequest) {
        try {
            val queue = loadQueue()
            queue.put(createQueueItem(request))
            saveQueue(queue)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun syncQueuedViolations() = withContext(Dispatchers.IO) {
        try {
            if (!isNetworkAvailable()) return@withContext

            val queue = loadQueue()
            if (queue.length() == 0) return@withContext

            val successfulIndices = mutableListOf<Int>()

            for (i in 0 until queue.length()) {
                try {
                    val item = queue.getJSONObject(i)
                    val request = ViolationLogRequest(
                        user_id_hash = item.getString("user_id_hash"),
                        timestamp = item.optString("timestamp_iso", isoNow()),
                        category = item.getString("category"),
                        severity = item.getString("severity"),
                        action = item.getString("action"),
                        session_id = item.optString("session_id", SESSION_ID)
                    )

                    val response = apiService.logViolation(request)
                    if (response.isSuccessful) {
                        successfulIndices.add(i)
                    } else if (response.code() == 429) {
                        // Stop syncing this pass; try again next opportunity.
                        break
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            if (successfulIndices.isNotEmpty()) {
                val newQueue = JSONArray()
                for (i in 0 until queue.length()) {
                    if (i !in successfulIndices) {
                        newQueue.put(queue.getJSONObject(i))
                    }
                }
                saveQueue(newQueue)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createQueueItem(request: ViolationLogRequest): JSONObject {
        return JSONObject().apply {
            put("user_id_hash", request.user_id_hash)
            put("category", request.category)
            put("severity", request.severity)
            put("action", request.action)
            put("timestamp_iso", request.timestamp)
            put("session_id", request.session_id)
            put("timestamp", System.currentTimeMillis())
        }
    }

    private fun loadQueue(): JSONArray {
        return try {
            if (queueFile.exists()) JSONArray(queueFile.readText()) else JSONArray()
        } catch (e: Exception) {
            e.printStackTrace()
            JSONArray()
        }
    }

    private fun saveQueue(queue: JSONArray) {
        try {
            queueFile.writeText(queue.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateLocalStatistics(action: String, category: String) {
        when (action) {
            "sent_anyway" -> preferencesManager.incrementViolationCount(2)
            "warning_only" -> preferencesManager.incrementWarningCount(1)
        }
        preferencesManager.setLastCategory(category)
    }

    private fun handleEscalationFlag(count: Int) {
        println("Escalation flag received. Total count: $count")
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            val info = cm.activeNetworkInfo
            @Suppress("DEPRECATION")
            info?.isConnected == true
        }
    }

    fun getQueueSize(): Int = try { loadQueue().length() } catch (e: Exception) { 0 }

    fun clearQueue() {
        try { queueFile.delete() } catch (e: Exception) { e.printStackTrace() }
    }
}
