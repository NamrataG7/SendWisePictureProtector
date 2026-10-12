package com.safekeyboard.network

import android.util.Log
import com.safekeyboard.BuildConfig
import okhttp3.CertificatePinner
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * RetrofitClient - Singleton Retrofit instance.
 *
 * Security posture (matches paper §Security/Transport):
 *   - HTTPS only (cleartext disabled via network_security_config.xml)
 *   - TLS 1.3 preferred, TLS 1.2 fallback (ConnectionSpec)
 *   - Certificate pinning via SPKI SHA-256 (CertificatePinner)
 *   - Timeouts: 15s connect / 30s read / 30s write
 *   - HTTP body logging enabled only in DEBUG builds
 *
 * API endpoints:
 *   - /api/logViolation (log violations)
 *   - /api/getStats     (retrieve user stats)
 */
object RetrofitClient {

    private const val TAG = "RetrofitClient"
    private const val PIN_PLACEHOLDER = "PLACEHOLDER_UPDATE_AFTER_DEPLOY"

    private val BASE_URL: String = BuildConfig.API_BASE_URL

    /** Extract host portion from BASE_URL for pinning. */
    private val apiHost: String = runCatching { URI(BASE_URL).host }
        .getOrNull()
        ?: "sendwise-lac.vercel.app"

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.BODY
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
    }

    /** Restrict handshake to modern TLS versions: 1.3 preferred, 1.2 fallback. */
    private val modernTlsSpec: ConnectionSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
        .build()

    private val certificatePinner: CertificatePinner? = run {
        val pin = BuildConfig.CERT_PIN_SHA256
        if (pin.isBlank() || pin == PIN_PLACEHOLDER) {
            Log.w(
                TAG,
                "CERT_PIN_SHA256 is a placeholder — certificate pinning DISABLED. " +
                    "Update build.gradle with the real SPKI SHA-256 pin before release. " +
                    "See SafeKeyboardApp/CERT_PINNING.md."
            )
            null
        } else {
            CertificatePinner.Builder()
                .add(apiHost, "sha256/$pin")
                .build()
        }
    }

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectionSpecs(listOf(modernTlsSpec))
        .apply { certificatePinner?.let { certificatePinner(it) } }
        .addInterceptor(loggingInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val apiService: ApiService = retrofit.create(ApiService::class.java)
    val pairingApiService: PairingApiService = retrofit.create(PairingApiService::class.java)
}
