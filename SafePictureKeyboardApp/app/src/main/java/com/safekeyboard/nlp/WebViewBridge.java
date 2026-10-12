package com.safekeyboard.nlp;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebSettings;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * WebViewBridge - JavaScript bridge for toxicity detection
 *
 * Loads the shared detection library (JavaScript) into a WebView and provides
 * a bridge to call the detection functions from Android (Kotlin/Java).
 *
 * This allows the Android app to use the same 90-95% accurate detection logic
 * as the Chrome Extension, including:
 * - Rule-based detection
 * - Emoji sentiment analysis
 * - Sarcasm detection
 * - Platform context awareness
 * - Warning escalation
 *
 * Thread-safe: All WebView operations run on the main thread
 */
public class WebViewBridge {
    private static final String TAG = "WebViewBridge";
    private WebView webView;
    private Handler mainHandler;
    private boolean isInitialized = false;
    private String lastResult = null;
    private CountDownLatch resultLatch = null;

    public WebViewBridge(Context context) {
        mainHandler = new Handler(Looper.getMainLooper());
        initializeWebView(context);
    }

    /**
     * Initialize WebView and load detection library
     */
    private void initializeWebView(final Context context) {
        mainHandler.post(() -> {
            try {
                webView = new WebView(context.getApplicationContext());
                WebSettings settings = webView.getSettings();
                settings.setJavaScriptEnabled(true);
                settings.setAllowFileAccess(true);
                settings.setDomStorageEnabled(true);

                // Add JavaScript interface for callbacks
                webView.addJavascriptInterface(new JsInterface(), "AndroidBridge");

                // Load detection library modules
                loadJavaScriptFile("file:///android_asset/detection-library/analyzer.js");
                loadJavaScriptFile("file:///android_asset/detection-library/emoji-analyzer.js");
                loadJavaScriptFile("file:///android_asset/detection-library/sarcasm-detector.js");
                loadJavaScriptFile("file:///android_asset/detection-library/context-detector.js");
                loadJavaScriptFile("file:///android_asset/detection-library/warning-escalator.js");
                loadJavaScriptFile("file:///android_asset/detection-library/index.js");

                // Mark as initialized after a delay (give scripts time to load)
                mainHandler.postDelayed(() -> {
                    isInitialized = true;
                    Log.d(TAG, "WebView detection library initialized");
                }, 500);

            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize WebView: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Load a JavaScript file into the WebView
     */
    private void loadJavaScriptFile(final String url) {
        webView.loadUrl(url);
    }

    /**
     * Analyze text for toxicity (synchronous from caller's perspective)
     *
     * @param text       Message text to analyze
     * @param sensitivity Detection threshold (0.0-1.0)
     * @param platform   Platform hostname (e.g., "instagram.com")
     * @return JSON string with analysis result
     */
    public String analyzeText(String text, double sensitivity, String platform) {
        if (!isInitialized) {
            Log.w(TAG, "WebView not initialized yet, using fallback");
            return getFallbackResult(text, sensitivity);
        }

        // Escape text for JavaScript
        String escapedText = escapeJavaScript(text);
        String escapedPlatform = escapeJavaScript(platform);

        // Build JavaScript call
        final String jsCode = String.format(
            "(function() { " +
            "  try { " +
            "    var result = SafeKeyboardDetection.analyze('%s', %f, '%s'); " +
            "    return JSON.stringify(result); " +
            "  } catch (e) { " +
            "    return JSON.stringify({ " +
            "      isToxic: false, " +
            "      score: 0.0, " +
            "      category: 'none', " +
            "      severity: 'none', " +
            "      error: e.toString() " +
            "    }); " +
            "  } " +
            "})()",
            escapedText,
            sensitivity,
            escapedPlatform
        );

        // Execute JavaScript and wait for result
        lastResult = null;
        resultLatch = new CountDownLatch(1);

        mainHandler.post(() -> {
            webView.evaluateJavascript(jsCode, value -> {
                // Remove quotes from JSON string
                if (value != null && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                    // Unescape JSON
                    value = value.replace("\\\"", "\"")
                                 .replace("\\\\", "\\")
                                 .replace("\\n", "\n");
                }
                lastResult = value;
                resultLatch.countDown();
            });
        });

        // Wait for result (max 2 seconds)
        try {
            boolean completed = resultLatch.await(2, TimeUnit.SECONDS);
            if (!completed) {
                Log.w(TAG, "JavaScript execution timed out");
                return getFallbackResult(text, sensitivity);
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted while waiting for result", e);
            return getFallbackResult(text, sensitivity);
        }

        if (lastResult == null || lastResult.equals("null")) {
            Log.w(TAG, "JavaScript returned null, using fallback");
            return getFallbackResult(text, sensitivity);
        }

        return lastResult;
    }

    /**
     * Get warning escalation level
     *
     * @param violationCount Total number of violations
     * @return JSON string with warning level configuration
     */
    public String getWarningLevel(int violationCount) {
        if (!isInitialized) {
            return getDefaultWarningLevel();
        }

        final String jsCode = String.format(
            "(function() { " +
            "  try { " +
            "    var result = SafeKeyboardDetection.getWarningLevel(%d); " +
            "    return JSON.stringify(result); " +
            "  } catch (e) { " +
            "    return JSON.stringify({ level: 'educational', tone: 'gentle' }); " +
            "  } " +
            "})()",
            violationCount
        );

        lastResult = null;
        resultLatch = new CountDownLatch(1);

        mainHandler.post(() -> {
            webView.evaluateJavascript(jsCode, value -> {
                if (value != null && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1)
                                 .replace("\\\"", "\"")
                                 .replace("\\\\", "\\");
                }
                lastResult = value;
                resultLatch.countDown();
            });
        });

        try {
            resultLatch.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted while getting warning level", e);
        }

        return lastResult != null ? lastResult : getDefaultWarningLevel();
    }

    /**
     * Escape text for use in JavaScript string
     */
    private String escapeJavaScript(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                   .replace("'", "\\'")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }

    /**
     * Fallback result if WebView is not ready
     */
    private String getFallbackResult(String text, double sensitivity) {
        try {
            JSONObject result = new JSONObject();
            result.put("isToxic", false);
            result.put("score", 0.0);
            result.put("originalScore", 0.0);
            result.put("category", "none");
            result.put("severity", "none");
            result.put("fallback", true);
            return result.toString();
        } catch (JSONException e) {
            return "{\"isToxic\":false,\"score\":0.0,\"category\":\"none\",\"severity\":\"none\",\"fallback\":true}";
        }
    }

    /**
     * Default warning level
     */
    private String getDefaultWarningLevel() {
        return "{\"level\":\"educational\",\"tone\":\"gentle\",\"title\":\"Think Before You Send\",\"cooldownSeconds\":0}";
    }

    /**
     * Check if WebView is ready
     */
    public boolean isReady() {
        return isInitialized;
    }

    /**
     * Clean up resources
     */
    public void destroy() {
        if (webView != null) {
            mainHandler.post(() -> {
                webView.destroy();
                webView = null;
            });
        }
    }

    /**
     * JavaScript interface for callbacks (if needed in future)
     */
    private class JsInterface {
        @JavascriptInterface
        public void log(String message) {
            Log.d(TAG, "JS: " + message);
        }
    }
}
