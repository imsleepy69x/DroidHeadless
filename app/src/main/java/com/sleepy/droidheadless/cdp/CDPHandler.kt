// File: app/src/main/java/com/sleepy/droidheadless/cdp/CDPHandler.kt
package com.sleepy.droidheadless.cdp

import android.util.Log
import com.sleepy.droidheadless.browser.NetworkInterceptor
import com.sleepy.droidheadless.browser.WebViewManager
import com.sleepy.droidheadless.cdp.domains.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Central message router for the Chrome DevTools Protocol.
 *
 * This class:
 * 1. Receives JSON-RPC messages from WebSocket clients
 * 2. Routes them to the appropriate domain handler (Page, Network, Runtime, etc.)
 * 3. Returns the result as a JSON-RPC response
 * 4. Forwards domain events (like Network.requestWillBeSent) to subscribed clients
 *
 * CDP uses JSON-RPC 2.0 over WebSocket:
 * - Client sends: { "id": 1, "method": "Page.navigate", "params": { "url": "..." } }
 * - Server responds: { "id": 1, "result": { "frameId": "..." } }
 * - Server pushes events: { "method": "Network.requestWillBeSent", "params": { ... } }
 */
class CDPHandler(
    private val webViewManager: WebViewManager,
    private val networkInterceptor: NetworkInterceptor
) : NetworkInterceptor.NetworkEventListener, WebViewManager.PageEventListener {

    companion object {
        private const val TAG = "CDPHandler"
    }

    // Domain handlers
    private val pageDomain = PageDomain(webViewManager)
    private val networkDomain = NetworkDomain()
    private val runtimeDomain = RuntimeDomain(webViewManager)
    private val consoleDomain = ConsoleDomain()

    // Active WebSocket sessions: sessionId → event callback
    // When a client connects via WebSocket, we register their callback here
    // so we can push events to them
    private val sessions = ConcurrentHashMap<String, (String) -> Unit>()

    // Track which page each session is connected to
    private val sessionPageMap = ConcurrentHashMap<String, String>()

    // Track enabled domains per session
    private val enabledDomains = ConcurrentHashMap<String, MutableSet<String>>()

    // Coroutine scope for async event handling
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        // Register ourselves as listeners for network and page events
        networkInterceptor.addListener(this)
        webViewManager.addEventListener(this)
    }

    /**
     * Registers a new WebSocket session.
     *
     * @param sessionId Unique identifier for this session
     * @param pageId The page this session is connected to
     * @param sendCallback Function to send a message to the client
     */
    fun registerSession(sessionId: String, pageId: String, sendCallback: (String) -> Unit) {
        sessions[sessionId] = sendCallback
        sessionPageMap[sessionId] = pageId
        enabledDomains[sessionId] = ConcurrentHashMap.newKeySet()
        Log.i(TAG, "Session registered: $sessionId → page $pageId")
    }

    /**
     * Unregisters a WebSocket session (client disconnected).
     */
    fun unregisterSession(sessionId: String) {
        sessions.remove(sessionId)
        sessionPageMap.remove(sessionId)
        enabledDomains.remove(sessionId)
        Log.i(TAG, "Session unregistered: $sessionId")
    }

    /**
     * Handles an incoming CDP message from a WebSocket client.
     *
     * @param sessionId The session that sent the message
     * @param message Raw JSON string of the CDP command
     */
    fun handleMessage(sessionId: String, message: String) {
        scope.launch {
            try {
                val json = JSONObject(message)
                val id = json.optInt("id", -1)
                val method = json.optString("method", "")
                val params = json.optJSONObject("params") ?: JSONObject()

                if (method.isEmpty()) {
                    sendError(sessionId, id, -32600, "Invalid Request: missing method")
                    return@launch
                }

                val pageId = sessionPageMap[sessionId] ?: run {
                    sendError(sessionId, id, -32000, "No page associated with session")
                    return@launch
                }

                Log.d(TAG, "← [$sessionId] $method (id=$id)")

                // Track enabled domains
                if (method.endsWith(".enable")) {
                    val domain = method.substringBefore(".")
                    enabledDomains[sessionId]?.add(domain)
                } else if (method.endsWith(".disable")) {
                    val domain = method.substringBefore(".")
                    enabledDomains[sessionId]?.remove(domain)
                }

                // Route to appropriate domain handler
                val result = routeMethod(method, params, pageId, sessionId)

                // Send response
                val response = JSONObject().apply {
                    put("id", id)
                    put("result", result)
                }

                sendToSession(sessionId, response.toString())

                // Send follow-up events for certain enable methods
                handlePostEnable(sessionId, method, pageId)

            } catch (e: Exception) {
                Log.e(TAG, "Error handling message: ${e.message}", e)
                try {
                    val json = JSONObject(message)
                    val id = json.optInt("id", -1)
                    sendError(sessionId, id, -32603, "Internal error: ${e.message}")
                } catch (_: Exception) {
                    // Can't even parse the message, nothing to do
                }
            }
        }
    }

    /**
     * Routes a CDP method to the correct domain handler.
     */
    private fun routeMethod(
        method: String,
        params: JSONObject,
        pageId: String,
        sessionId: String
    ): JSONObject {
        val domain = method.substringBefore(".")

        return when (domain) {
            "Page" -> pageDomain.handleMethod(method, params, pageId)
            "Network" -> networkDomain.handleMethod(method, params)
            "Runtime" -> runtimeDomain.handleMethod(method, params, pageId)
            "Console" -> consoleDomain.handleMethod(method, params)
            "Emulation" -> handleEmulation(method, params, pageId)
            "Target" -> handleTarget(method, params, pageId)
            "Browser" -> handleBrowser(method, params)
            "DOM" -> handleDOM(method, params, pageId)
            "CSS" -> handleCSS(method, params)
            "Log" -> handleLog(method, params)
            "Inspector" -> JSONObject()
            "Debugger" -> handleDebugger(method, params)
            "Profiler" -> JSONObject()
            "HeapProfiler" -> JSONObject()
            "Performance" -> handlePerformance(method, params)
            "Security" -> JSONObject()
            "ServiceWorker" -> JSONObject()
            "Input" -> handleInput(method, params, pageId)
            "Fetch" -> JSONObject()
            "Overlay" -> JSONObject()
            "Accessibility" -> JSONObject()
            else -> {
                Log.w(TAG, "Unhandled domain: $domain (method: $method)")
                JSONObject()
            }
        }
    }

    /**
     * Sends follow-up events that certain .enable methods require.
     * For example, Runtime.enable should send executionContextCreated.
     */
    private fun handlePostEnable(sessionId: String, method: String, pageId: String) {
        when (method) {
            "Runtime.enable" -> {
                // Send executionContextCreated event
                val event = JSONObject().apply {
                    put("method", "Runtime.executionContextCreated")
                    put("params", runtimeDomain.createExecutionContextEvent(pageId))
                }
                sendToSession(sessionId, event.toString())
            }
            "Page.enable" -> {
                // Send frameAttached event for the main frame
                val event = JSONObject().apply {
                    put("method", "Page.frameAttached")
                    put("params", JSONObject().apply {
                        put("frameId", pageId)
                        put("parentFrameId", "")
                    })
                }
                sendToSession(sessionId, event.toString())
            }
        }
    }

    // ================================================================
    // Additional domain handlers (simplified implementations)
    // ================================================================

    /**
     * Emulation domain - handles user agent override and viewport settings.
     */
    private fun handleEmulation(method: String, params: JSONObject, pageId: String): JSONObject {
        return when (method) {
            "Emulation.setUserAgentOverride" -> {
                val ua = params.optString("userAgent", "")
                if (ua.isNotEmpty()) {
                    runBlocking {
                        withTimeoutOrNull(5_000) {
                            webViewManager.setUserAgent(pageId, ua).await()
                        }
                    }
                }
                JSONObject()
            }
            "Emulation.setDeviceMetricsOverride" -> JSONObject()
            "Emulation.clearDeviceMetricsOverride" -> JSONObject()
            "Emulation.setTouchEmulationEnabled" -> JSONObject()
            "Emulation.setEmulatedMedia" -> JSONObject()
            "Emulation.setScrollbarsHidden" -> JSONObject()
            "Emulation.setDocumentCookieDisabled" -> JSONObject()
            "Emulation.setAutoDarkModeOverride" -> JSONObject()
            else -> JSONObject()
        }
    }

    /**
     * Target domain - handles target/session management.
     */
    private fun handleTarget(method: String, params: JSONObject, pageId: String): JSONObject {
        return when (method) {
            "Target.setDiscoverTargets" -> JSONObject()
            "Target.setAutoAttach" -> JSONObject()
            "Target.getTargetInfo" -> {
                val url = webViewManager.getPageUrl(pageId)
                JSONObject().apply {
                    put("targetInfo", JSONObject().apply {
                        put("targetId", pageId)
                        put("type", "page")
                        put("title", "")
                        put("url", url)
                        put("attached", true)
                        put("browserContextId", "default")
                    })
                }
            }
            "Target.createTarget" -> {
                val url = params.optString("url", "about:blank")
                val newPageId = runBlocking {
                    withTimeoutOrNull(10_000) {
                        webViewManager.createPage(url).await()
                    }
                } ?: ""
                JSONObject().put("targetId", newPageId)
            }
            "Target.closeTarget" -> {
                val targetId = params.optString("targetId", pageId)
                runBlocking {
                    withTimeoutOrNull(5_000) {
                        webViewManager.closePage(targetId).await()
                    }
                }
                JSONObject().put("success", true)
            }
            "Target.attachToTarget" -> {
                JSONObject().put("sessionId", sessionPageMap.entries.find { 
                    it.value == params.optString("targetId", pageId)
                }?.key ?: "session-1")
            }
            else -> JSONObject()
        }
    }

    /**
     * Browser domain - returns browser-level information.
     */
    private fun handleBrowser(method: String, params: JSONObject): JSONObject {
        return when (method) {
            "Browser.getVersion" -> {
                JSONObject().apply {
                    put("protocolVersion", "1.3")
                    put("product", "DroidHeadless/1.0")
                    put("revision", "@1")
                    put("userAgent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) DroidHeadless/1.0")
                    put("jsVersion", "")
                }
            }
            "Browser.close" -> JSONObject()
            else -> JSONObject()
        }
    }

    /**
     * DOM domain - minimal implementation for compatibility.
     */
    private fun handleDOM(method: String, params: JSONObject, pageId: String): JSONObject {
        return when (method) {
            "DOM.enable" -> JSONObject()
            "DOM.disable" -> JSONObject()
            "DOM.getDocument" -> {
                JSONObject().apply {
                    put("root", JSONObject().apply {
                        put("nodeId", 1)
                        put("backendNodeId", 1)
                        put("nodeType", 9) // DOCUMENT_NODE
                        put("nodeName", "#document")
                        put("localName", "")
                        put("nodeValue", "")
                        put("childNodeCount", 1)
                        put("documentURL", webViewManager.getPageUrl(pageId))
                        put("baseURL", webViewManager.getPageUrl(pageId))
                        put("xmlVersion", "")
                    })
                }
            }
            else -> JSONObject()
        }
    }

    private fun handleCSS(method: String, params: JSONObject): JSONObject {
        return when (method) {
            "CSS.enable" -> JSONObject()
            "CSS.disable" -> JSONObject()
            else -> JSONObject()
        }
    }

    private fun handleLog(method: String, params: JSONObject): JSONObject {
        return when (method) {
            "Log.enable" -> JSONObject()
            "Log.disable" -> JSONObject()
            "Log.clear" -> JSONObject()
            else -> JSONObject()
        }
    }

    private fun handleDebugger(method: String, params: JSONObject): JSONObject {
        return when (method) {
            "Debugger.enable" -> {
                JSONObject().put("debuggerId", "debugger-1")
            }
            "Debugger.disable" -> JSONObject()
            "Debugger.setAsyncCallStackDepth" -> JSONObject()
            "Debugger.setBlackboxPatterns" -> JSONObject()
            "Debugger.setPauseOnExceptions" -> JSONObject()
            else -> JSONObject()
        }
    }

    private fun handlePerformance(method: String, params: JSONObject): JSONObject {
        return when (method) {
            "Performance.enable" -> JSONObject()
            "Performance.disable" -> JSONObject()
            "Performance.getMetrics" -> {
                JSONObject().put("metrics", org.json.JSONArray())
            }
            else -> JSONObject()
        }
    }

    /**
     * Input domain - handles input events (click, type, etc.)
     */
    private fun handleInput(method: String, params: JSONObject, pageId: String): JSONObject {
        return when (method) {
            "Input.dispatchMouseEvent" -> {
                val type = params.optString("type", "")
                val x = params.optDouble("x", 0.0)
                val y = params.optDouble("y", 0.0)
                if (type == "mousePressed" || type == "mouseReleased") {
                    // Simulate click via JavaScript
                    val clickScript = """
                        var el = document.elementFromPoint($x, $y);
                        if (el) {
                            var event = new MouseEvent('${if (type == "mousePressed") "mousedown" else "mouseup"}', {
                                bubbles: true, cancelable: true, clientX: $x, clientY: $y
                            });
                            el.dispatchEvent(event);
                            ${if (type == "mouseReleased") "el.click();" else ""}
                        }
                    """.trimIndent()
                    runBlocking {
                        withTimeoutOrNull(5_000) {
                            webViewManager.evaluateJavaScript(pageId, clickScript).await()
                        }
                    }
                }
                JSONObject()
            }
            "Input.dispatchKeyEvent" -> {
                val text = params.optString("text", "")
                if (text.isNotEmpty()) {
                    val keyScript = """
                        var el = document.activeElement;
                        if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable)) {
                            el.value = (el.value || '') + '$text';
                            el.dispatchEvent(new Event('input', { bubbles: true }));
                        }
                    """.trimIndent()
                    runBlocking {
                        withTimeoutOrNull(5_000) {
                            webViewManager.evaluateJavaScript(pageId, keyScript).await()
                        }
                    }
                }
                JSONObject()
            }
            "Input.insertText" -> {
                val text = params.optString("text", "")
                if (text.isNotEmpty()) {
                    val insertScript = """
                        var el = document.activeElement;
                        if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA')) {
                            el.value = (el.value || '') + '${text.replace("'", "\\'")}';
                            el.dispatchEvent(new Event('input', { bubbles: true }));
                            el.dispatchEvent(new Event('change', { bubbles: true }));
                        }
                    """.trimIndent()
                    runBlocking {
                        withTimeoutOrNull(5_000) {
                            webViewManager.evaluateJavaScript(pageId, insertScript).await()
                        }
                    }
                }
                JSONObject()
            }
            else -> JSONObject()
        }
    }

    // ================================================================
    // Event forwarding from NetworkInterceptor and WebViewManager
    // ================================================================

    /**
     * Called by NetworkInterceptor when a network event occurs.
     * Forwards to all sessions that have the Network domain enabled.
     */
    override fun onNetworkEvent(method: String, params: JSONObject) {
        val event = JSONObject().apply {
            put("method", method)
            put("params", params)
        }
        val eventStr = event.toString()

        sessions.forEach { (sessionId, _) ->
            if (enabledDomains[sessionId]?.contains("Network") == true) {
                sendToSession(sessionId, eventStr)
            }
        }
    }

    /**
     * Called by WebViewManager when a page event occurs.
     * Forwards to sessions connected to that specific page.
     */
    override fun onPageEvent(pageId: String, method: String, params: JSONObject) {
        val event = JSONObject().apply {
            put("method", method)
            put("params", params)
        }
        val eventStr = event.toString()

        // Find sessions connected to this page
        sessionPageMap.forEach { (sessionId, connectedPageId) ->
            if (connectedPageId == pageId) {
                val domain = method.substringBefore(".")
                // Send if the domain is enabled, or always for certain critical events
                if (enabledDomains[sessionId]?.contains(domain) == true ||
                    domain == "Page" || domain == "Runtime" || domain == "Console"
                ) {
                    sendToSession(sessionId, eventStr)
                }
            }
        }
    }

    /**
     * Sends a message to a specific WebSocket session.
     */
    private fun sendToSession(sessionId: String, message: String) {
        try {
            sessions[sessionId]?.invoke(message)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send to session $sessionId: ${e.message}")
        }
    }

    /**
     * Sends a JSON-RPC error response.
     */
    private fun sendError(sessionId: String, id: Int, code: Int, message: String) {
        val error = JSONObject().apply {
            put("id", id)
            put("error", JSONObject().apply {
                put("code", code)
                put("message", message)
            })
        }
        sendToSession(sessionId, error.toString())
    }

    /**
     * Cleanup when the handler is destroyed.
     */
    fun destroy() {
        networkInterceptor.removeListener(this)
        webViewManager.removeEventListener(this)
        sessions.clear()
        sessionPageMap.clear()
        enabledDomains.clear()
        scope.cancel()
    }
}
