package com.safekeyboard.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * ApiService - Retrofit interface for backend communication
 *
 * API CONTRACT (matches parental-dashboard/lib/schema.ts ViolationIngestSchema):
 * POST /api/violations
 * {
 *   "user_id_hash": "<64 hex, SHA-256>",
 *   "timestamp":    "<ISO 8601>",
 *   "category":     "harassment | threats | hate_speech | sexual_content | self_harm",
 *   "severity":     "low | medium | high",
 *   "action":       "edited | sent_anyway | blocked | cancelled",
 *   "session_id":   "<per-app-launch UUID>"
 * }
 *
 * ABSOLUTELY FORBIDDEN (server rejects strictly):
 * - text / message / content
 * - recipient info
 * - app / package name
 */
interface ApiService {

    @POST("/api/violations")
    suspend fun logViolation(
        @Body request: ViolationLogRequest
    ): Response<ViolationLogResponse>
}

/**
 * Request model — field names MUST match ViolationIngestSchema exactly.
 */
data class ViolationLogRequest(
    val user_id_hash: String,
    val timestamp: String,
    val category: String,
    val severity: String,
    val action: String,
    val session_id: String
)

/**
 * Response model for violation logging.
 * Server response is currently loose; escalation fields are optional/tolerated.
 */
data class ViolationLogResponse(
    val success: Boolean = true,
    val message: String? = null,
    val current_count: Int? = null,
    val escalation_flag: Boolean? = null
)
