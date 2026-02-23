// File: app/src/main/java/com/sleepy/droidheadless/browser/NetworkInterceptor.kt
package com.sleepy.droidheadless.browser

import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Intercepts all network traffic flowing through WebView instances.
 * 
 * This is the heart of the Network domain - it captures every request and response,
 * extracts headers, status codes, timing info, and body data. All captured events
 * are forwarded to registered listeners (which are CDP WebSocket sessions).
 *
 * ARCHITECTURE:
 * WebView → shouldInterceptRequest() → NetworkInterceptor → re-issues request via HttpURLConnection
 *   → captures full response → emits CDP events → returns response to WebView
 *
 * This approach gives us full visibility into network traffic that WebView normally hides.
 */
class NetworkInterceptor {

    companion object {
        private const val TAG = "NetworkInterceptor"
        // Timeout for intercepted requests (don't hang forever)
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 30_000
    }

    // Coroutine scope for non-blocking event emission
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Live traffic counters (thread-safe)
    val requestCount = AtomicInteger(0)
    val bytesTransferred = AtomicLong(0)

    // Request ID generator - monotonically increasing, unique per session
    private val requestIdCounter = AtomicInteger(0)

    // Store pending request data for correlation (requestId → request info)
    private val pendingRequests = ConcurrentHashMap<String, RequestInfo>()

    /**
     * Listener interface for CDP event consumers.
     * Each connected WebSocket session implements this to receive network events.
     */
    interface NetworkEventListener {
        fun onNetworkEvent(method: String, params: JSONObject)
    }

    // All active CDP session listeners
    private val listeners = ConcurrentHashMap.newKeySet<NetworkEventListener>()

    fun addListener(listener: NetworkEventListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: NetworkEventListener) {
        listeners.remove(listener)
    }

    /**
     * Called from WebViewClient.shouldInterceptRequest().
     * 
     * This is the main interception point. We:
     * 1. Emit Network.requestWillBeSent
     * 2. Re-issue the request ourselves via HttpURLConnection
     * 3. Capture the full response (headers, status, body)
     * 4. Emit Network.responseReceived and Network.loadingFinished
     * 5. Return the response to WebView so the page loads normally
     *
     * @param pageId The CDP page/target ID this request belongs to
     * @param request The WebView request to intercept
     * @return WebResourceResponse to feed back to WebView, or null to let WebView handle it
     */
    fun interceptRequest(
        pageId: String,
        request: WebResourceRequest
    ): WebResourceResponse? {
        val url = request.url?.toString() ?: return null

        // Skip data: URIs and blob: URIs - these aren't real network requests
        if (url.startsWith("data:") || url.startsWith("blob:") || url.startsWith("about:")) {
            return null
        }

        val requestId = "req-${requestIdCounter.incrementAndGet()}"
        val method = request.method ?: "GET"
        val timestamp = System.currentTimeMillis() / 1000.0
        val count = requestCount.incrementAndGet()

        // Build request headers JSON
        val requestHeaders = JSONObject()
        request.requestHeaders?.forEach { (key, value) ->
            requestHeaders.put(key, value)
        }

        // Store request info for later correlation
        val info = RequestInfo(
            requestId = requestId,
            url = url,
            method = method,
            headers = requestHeaders,
            timestamp = timestamp,
            pageId = pageId
        )
        pendingRequests[requestId] = info

        // Emit: Network.requestWillBeSent
        emitEvent("Network.requestWillBeSent", JSONObject().apply {
            put("requestId", requestId)
            put("loaderId", pageId)
            put("documentURL", url)
            put("timestamp", timestamp)
            put("wallTime", timestamp)
            put("type", guessResourceType(url))
            put("request", JSONObject().apply {
                put("url", url)
                put("method", method)
                put("headers", requestHeaders)
                put("initialPriority", "Medium")
                put("referrerPolicy", "no-referrer-when-downgrade")
            })
            put("initiator", JSONObject().apply {
                put("type", "other")
            })
        })

        // Now actually make the request ourselves
        return try {
            val result = executeRequest(url, method, request.requestHeaders)

            val responseTimestamp = System.currentTimeMillis() / 1000.0
            val bodySize = result.body?.size?.toLong() ?: 0L
            bytesTransferred.addAndGet(bodySize)

            // Emit: Network.responseReceived
            emitEvent("Network.responseReceived", JSONObject().apply {
                put("requestId", requestId)
                put("loaderId", pageId)
                put("timestamp", responseTimestamp)
                put("type", guessResourceType(url))
                put("response", JSONObject().apply {
                    put("url", url)
                    put("status", result.statusCode)
                    put("statusText", result.statusText)
                    put("headers", result.responseHeaders)
                    put("mimeType", result.mimeType)
                    put("connectionReused", false)
                    put("connectionId", requestId.hashCode())
                    put("encodedDataLength", bodySize)
                    put("protocol", "http/1.1")
                })
            })

            // Emit: Network.loadingFinished
            emitEvent("Network.loadingFinished", JSONObject().apply {
                put("requestId", requestId)
                put("timestamp", responseTimestamp)
                put("encodedDataLength", bodySize)
            })

            pendingRequests.remove(requestId)

            // Return the response to WebView
            WebResourceResponse(
                result.mimeType,
                result.encoding,
                result.statusCode,
                result.statusText,
                result.responseHeadersMap,
                result.body?.let { ByteArrayInputStream(it) }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Request failed: $url", e)

            // Emit: Network.loadingFailed
            emitEvent("Network.loadingFailed", JSONObject().apply {
                put("requestId", requestId)
                put("timestamp", System.currentTimeMillis() / 1000.0)
                put("type", guessResourceType(url))
                put("errorText", e.message ?: "Unknown error")
                put("canceled", false)
            })

            pendingRequests.remove(requestId)

            // Return null to let WebView try its own handling
            null
        }
    }

    /**
     * Actually executes an HTTP request using HttpURLConnection.
     * This gives us full control over the request/response cycle.
     */
    private fun executeRequest(
        url: String,
        method: String,
        headers: Map<String, String>?
    ): InterceptedResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true

        // Forward all original headers
        headers?.forEach { (key, value) ->
            connection.setRequestProperty(key, value)
        }

        try {
            connection.connect()

            val statusCode = connection.responseCode
            val statusText = connection.responseMessage ?: "OK"

            // Collect response headers
            val responseHeaders = JSONObject()
            val responseHeadersMap = mutableMapOf<String, String>()
            connection.headerFields?.forEach { (key, values) ->
                if (key != null && values.isNotEmpty()) {
                    val value = values.joinToString(", ")
                    responseHeaders.put(key, value)
                    responseHeadersMap[key] = value
                }
            }

            // Read response body
            val inputStream = if (statusCode in 200..399) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val body = inputStream?.use { stream ->
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                var bytesRead: Int
                while (stream.read(chunk).also { bytesRead = it } != -1) {
                    buffer.write(chunk, 0, bytesRead)
                }
                buffer.toByteArray()
            }

            // Determine MIME type
            val contentType = connection.contentType ?: "application/octet-stream"
            val mimeType = contentType.split(";").first().trim()
            val encoding = if (contentType.contains("charset=")) {
                contentType.substringAfter("charset=").trim()
            } else {
                "utf-8"
            }

            return InterceptedResponse(
                statusCode = statusCode,
                statusText = statusText,
                mimeType = mimeType,
                encoding = encoding,
                responseHeaders = responseHeaders,
                responseHeadersMap = responseHeadersMap,
                body = body
            )
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Broadcasts a CDP network event to all registered listeners.
     * Uses coroutines to avoid blocking the interception thread.
     */
    private fun emitEvent(method: String, params: JSONObject) {
        scope.launch {
            listeners.forEach { listener ->
                try {
                    listener.onNetworkEvent(method, params)
                } catch (e: Exception) {
                    Log.w(TAG, "Error emitting $method to listener", e)
                }
            }
        }
    }

    /**
     * Guesses the CDP resource type from a URL.
     * This maps to Chrome's ResourceType enum used in CDP.
     */
    private fun guessResourceType(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.endsWith(".html") || lower.endsWith(".htm") -> "Document"
            lower.endsWith(".js") || lower.contains(".js?") -> "Script"
            lower.endsWith(".css") || lower.contains(".css?") -> "Stylesheet"
            lower.endsWith(".png") || lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") || lower.endsWith(".gif") ||
            lower.endsWith(".webp") || lower.endsWith(".svg") ||
            lower.endsWith(".ico") -> "Image"
            lower.endsWith(".woff") || lower.endsWith(".woff2") ||
            lower.endsWith(".ttf") || lower.endsWith(".otf") -> "Font"
            lower.endsWith(".json") || lower.contains("/api/") -> "XHR"
            lower.endsWith(".mp4") || lower.endsWith(".webm") -> "Media"
            lower.endsWith(".wasm") -> "Other"
            else -> "Other"
        }
    }

    /**
     * Clean up resources when the service is destroyed.
     */
    fun destroy() {
        listeners.clear()
        pendingRequests.clear()
    }

    // Internal data classes for request/response tracking
    data class RequestInfo(
        val requestId: String,
        val url: String,
        val method: String,
        val headers: JSONObject,
        val timestamp: Double,
        val pageId: String
    )

    data class InterceptedResponse(
        val statusCode: Int,
        val statusText: String,
        val mimeType: String,
        val encoding: String,
        val responseHeaders: JSONObject,
        val responseHeadersMap: Map<String, String>,
        val body: ByteArray?
    )
}
