package com.safekeyboard.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * PairingApiService - Retrofit interface for the parental-linking pairing flow.
 *
 * API CONTRACT:
 * POST /api/pairing/generate
 *   Request:  { "user_id_hash": "<sha256>" }
 *   Response: { "code": "123456", "expires_at": "<ISO 8601 string>" }
 *
 * The 6-digit code is short-lived (paper spec: 15 min TTL) and must be entered
 * in the parent-facing SendWise dashboard to link this device.
 *
 * PRIVACY:
 * - Only the anonymous user_id_hash is transmitted.
 * - No PII, no message content, no recipient data.
 */
interface PairingApiService {

    @POST("/api/pairing/generate")
    suspend fun generatePairingCode(
        @Body request: PairingGenerateRequest
    ): Response<PairingGenerateResponse>
}

/** Request body for POST /api/pairing/generate */
data class PairingGenerateRequest(
    val user_id_hash: String
)

/**
 * Response body for POST /api/pairing/generate.
 * Server returns:
 *   { "code": "123456", "expires_at": "2026-08-25T17:45:11.242Z" }
 * expires_at is an ISO 8601 UTC string — NOT a Long epoch.
 */
data class PairingGenerateResponse(
    val code: String? = null,
    val expires_at: String? = null,
    val error: String? = null
)
