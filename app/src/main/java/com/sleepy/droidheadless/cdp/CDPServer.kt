// File: app/src/main/java/com/sleepy/droidheadless/cdp/CDPServer.kt
package com.sleepy.droidheadless.cdp

import android.util.Log
import com.sleepy.droidheadless.browser.WebViewManager
import fi.iki.elonen.NanoHTTPD
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The CDP (Chrome DevTools Protocol) server.
 *
 * This class runs TWO servers:
 * 1. HTTP server (NanoHTTPD) on the configured port for /json/[*] endpoints
 * 2. WebSocket server on port+1 for ws://localhost:{port+1}/devtools/page/{id}
 *
 * The HTTP server handles:
 *   GET /json/version  → Browser version info
 *   GET /json/list     → List of open pages/targets
 *   GET /json or /json/ → Same as /json/list
 *   PUT /json/new?url=  → Create a new page
 *   GET /json/activate/{id} → Activate a page (no-op for headless)
 *   GET /json/close/{id} → Close a page
 *   GET /json/protocol  → Protocol schema (minimal)
 *
 * The WebSocket server handles:
 *   ws://localhost:{port}/devtools/page/{id} → CDP session for a page
 *
 * NOTE: We bind both servers to 127.0.0.1 only for security.
 *
 * ARCHITECTURE NOTE: NanoHTTPD runs the HTTP server, and we embed a WebSocket
 * upgrade handler into it so that both HTTP and WebSocket run on the SAME port.
 * This is critical for Puppeteer/Playwright compatibility - they expect WebSocket
 * URLs to be on the same port as the HTTP JSON endpoints.
 */
class CDPServer(
    private val port: Int,
    private val webViewManager: WebViewManager,
    private val cdpHandler: CDPHandler
) {

    companion object {
        private const val TAG = "CDPServer"
        private const val LOCALHOST = "127.0.0.1"
    }

    // HTTP server for /json/* endpoints
    private var httpServer: CDPHttpServer? = null

    // WebSocket server for devtools connections
    private var wsServer: CDPWebSocketServer? = null

    // Map WebSocket connections to session IDs
    private val wsSessionMap = ConcurrentHashMap<WebSocket, String>()

    /**
     * Starts both the HTTP and WebSocket servers.
     */
    fun start() {
        try {
            // Start HTTP server on configured port
            httpServer = CDPHttpServer(LOCALHOST, port).also { it.start() }
            Log.i(TAG, "HTTP server started on $LOCALHOST:$port")

            // Start WebSocket server on port+1
            // We use a separate port for WebSocket to avoid complexity,
            // but the HTTP /json/list endpoint returns URLs pointing to this port.
            // Actually, for Puppeteer compatibility, we'll make HTTP endpoint
            // return ws URLs on the WS port.
            val wsPort = port + 1
            wsServer = CDPWebSocketServer(InetSocketAddress(LOCALHOST, wsPort)).also {
                it.isReuseAddr = true
                it.start()
            }
            Log.i(TAG, "WebSocket server started on $LOCALHOST:$wsPort")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start CDP server", e)
            throw e
        }
    }

    /**
     * Stops both servers and cleans up.
     */
    fun stop() {
        try {
            httpServer?.stop()
            httpServer = null
            Log.i(TAG, "HTTP server stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping HTTP server", e)
        }

        try {
            wsServer?.stop(1000)
            wsServer = null
            Log.i(TAG, "WebSocket server stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping WebSocket server", e)
        }

        wsSessionMap.clear()
    }

    // ================================================================
    // HTTP Server (NanoHTTPD) - handles /json/* endpoints
    // ================================================================

    /**
     * HTTP server that implements the CDP HTTP endpoints.
     * These endpoints allow Puppeteer/Playwright to discover pages and connect.
     */
    inner class CDPHttpServer(hostname: String, port: Int) : NanoHTTPD(hostname, port) {

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri ?: "/"
            val method = session.method

            Log.d(TAG, "HTTP ${method.name} $uri")

            return try {
                when {
                    // /json/version - Browser version information
                    uri == "/json/version" -> serveVersion()

                    // /json/list or /json or /json/ - List of open pages
                    uri == "/json/list" || uri == "/json" || uri == "/json/" -> serveList()

                    // /json/new - Create a new page
                    uri.startsWith("/json/new") -> serveNew(session)

                    // /json/activate/{id} - Activate a page
                    uri.startsWith("/json/activate/") -> serveActivate(uri)

                    // /json/close/{id} - Close a page
                    uri.startsWith("/json/close/") -> serveClose(uri)

                    // /json/protocol - Protocol descriptor
                    uri == "/json/protocol" -> serveProtocol()

                    // Root - simple status page
                    uri == "/" -> serveStatusPage()

                    else -> {
                        newFixedLengthResponse(
                            Response.Status.NOT_FOUND,
                            "application/json",
                            """{"error": "Not found: $uri"}"""
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error serving $uri", e)
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "application/json",
                    """{"error": "${e.message}"}"""
                )
            }
        }

        /**
         * GET /json/version
         * Returns browser version info matching Chrome's format exactly.
         */
        private fun serveVersion(): Response {
            val wsPort = port + 1
            val version = JSONObject().apply {
                put("Browser", "DroidHeadless/1.0")
                put("Protocol-Version", "1.3")
                put("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) DroidHeadless/1.0")
                put("V8-Version", "")
                put("WebKit-Version", "537.36")
                // This is the key URL Puppeteer uses to connect
                put("webSocketDebuggerUrl", "ws://$LOCALHOST:$wsPort/devtools/browser")
            }
            return jsonResponse(version.toString())
        }

        /**
         * GET /json/list
         * Returns list of all open pages/targets.
         * This is what Puppeteer uses to discover pages after connecting.
         */
        private fun serveList(): Response {
            val wsPort = port + 1
            val pages = webViewManager.getAllPages()
            val list = JSONArray()

            pages.forEach { page ->
                list.put(JSONObject().apply {
                    put("description", "")
                    put("devtoolsFrontendUrl", "")
                    put("id", page.id)
                    put("title", page.title)
                    put("type", "page")
                    put("url", page.currentUrl)
                    put("webSocketDebuggerUrl", "ws://$LOCALHOST:$wsPort/devtools/page/${page.id}")
                })
            }

            return jsonResponse(list.toString())
        }

        /**
         * PUT /json/new?url={url}
         * Creates a new page and returns its info.
         */
        private fun serveNew(session: IHTTPSession): Response {
            val url = session.parms?.get("url") ?: "about:blank"
            val wsPort = port + 1

            val pageId = kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(10_000) {
                    webViewManager.createPage(url).await()
                }
            }

            if (pageId == null) {
                return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "application/json",
                    """{"error": "Failed to create page"}"""
                )
            }

            val response = JSONObject().apply {
                put("description", "")
                put("devtoolsFrontendUrl", "")
                put("id", pageId)
                put("title", "")
                put("type", "page")
                put("url", url)
                put("webSocketDebuggerUrl", "ws://$LOCALHOST:$wsPort/devtools/page/$pageId")
            }

            return jsonResponse(response.toString())
        }

        /**
         * GET /json/activate/{id}
         * "Activates" a page (no-op for headless, but return success).
         */
        private fun serveActivate(uri: String): Response {
            val pageId = uri.removePrefix("/json/activate/")
            val page = webViewManager.getPageInfo(pageId)

            return if (page != null) {
                newFixedLengthResponse(
                    Response.Status.OK,
                    "text/plain",
                    "Target activated"
                )
            } else {
                newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "text/plain",
                    "No such target id: $pageId"
                )
            }
        }

        /**
         * GET /json/close/{id}
         * Closes a page.
         */
        private fun serveClose(uri: String): Response {
            val pageId = uri.removePrefix("/json/close/")

            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(5_000) {
                    webViewManager.closePage(pageId).await()
                }
            }

            return newFixedLengthResponse(
                Response.Status.OK,
                "text/plain",
                "Target is closing"
            )
        }

        /**
         * GET /json/protocol
         * Returns a minimal protocol descriptor.
         */
        private fun serveProtocol(): Response {
            val protocol = JSONObject().apply {
                put("version", JSONObject().apply {
                    put("major", "1")
                    put("minor", "3")
                })
                put("domains", JSONArray().apply {
                    arrayOf("Page", "Network", "Runtime", "Console", "DOM", 
                            "Emulation", "Target", "Browser", "Input", "CSS",
                            "Log", "Performance", "Security").forEach { domain ->
                        put(JSONObject().apply {
                            put("domain", domain)
                            put("experimental", false)
                        })
                    }
                })
            }
            return jsonResponse(protocol.toString())
        }

        /**
         * GET /
         * Simple HTML status page.
         */
        private fun serveStatusPage(): Response {
            val html = """
                <!DOCTYPE html>
                <html>
                <head><title>DroidHeadless</title></head>
                <body style="font-family: sans-serif; padding: 2em;">
                    <h1>🤖 DroidHeadless</h1>
                    <p>Chrome DevTools Protocol server is running.</p>
                    <ul>
                        <li><a href="/json/version">/json/version</a></li>
                        <li><a href="/json/list">/json/list</a></li>
                        <li><a href="/json/protocol">/json/protocol</a></li>
                    </ul>
                    <h3>Connect with Puppeteer:</h3>
                    <pre>const browser = await puppeteer.connect({
    browserURL: 'http://127.0.0.1:${port}'
});</pre>
                </body>
                </html>
            """.trimIndent()
            return newFixedLengthResponse(Response.Status.OK, "text/html", html)
        }

        /**
         * Helper to create a JSON response with proper headers.
         */
        private fun jsonResponse(json: String): Response {
            return newFixedLengthResponse(Response.Status.OK, "application/json", json).also {
                it.addHeader("Access-Control-Allow-Origin", "*")
                it.addHeader("Cache-Control", "no-cache")
            }
        }
    }

    // ================================================================
    // WebSocket Server - handles CDP sessions
    // ================================================================

    /**
     * WebSocket server that handles CDP protocol communication.
     * Each WebSocket connection represents a CDP session attached to a page.
     */
    inner class CDPWebSocketServer(address: InetSocketAddress) : WebSocketServer(address) {

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            val path = handshake.resourceDescriptor ?: ""
            Log.i(TAG, "WebSocket connected: $path from ${conn.remoteSocketAddress}")

            // Parse the page ID from the WebSocket path
            // Format: /devtools/page/{pageId} or /devtools/browser
            val pageId = when {
                path.startsWith("/devtools/page/") -> {
                    path.removePrefix("/devtools/page/")
                }
                path.startsWith("/devtools/browser") -> {
                    // Browser-level connection - attach to first page
                    webViewManager.getAllPages().firstOrNull()?.id ?: ""
                }
                else -> {
                    Log.w(TAG, "Unknown WebSocket path: $path")
                    webViewManager.getAllPages().firstOrNull()?.id ?: ""
                }
            }

            if (pageId.isEmpty()) {
                Log.w(TAG, "No page found for WebSocket connection")
                conn.close(1008, "No pages available")
                return
            }

            // Create a session ID for this connection
            val sessionId = UUID.randomUUID().toString()
            wsSessionMap[conn] = sessionId

            // Register the session with the CDP handler
            cdpHandler.registerSession(sessionId, pageId) { message ->
                try {
                    if (conn.isOpen) {
                        conn.send(message)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send WS message", e)
                }
            }

            Log.i(TAG, "CDP session started: $sessionId → page $pageId")
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
            val sessionId = wsSessionMap.remove(conn)
            if (sessionId != null) {
                cdpHandler.unregisterSession(sessionId)
                Log.i(TAG, "CDP session ended: $sessionId (code=$code, reason=$reason)")
            }
        }

        override fun onMessage(conn: WebSocket, message: String) {
            val sessionId = wsSessionMap[conn]
            if (sessionId == null) {
                Log.w(TAG, "Message from unknown connection")
                return
            }

            // Route the message to the CDP handler
            cdpHandler.handleMessage(sessionId, message)
        }

        override fun onError(conn: WebSocket?, ex: Exception) {
            Log.e(TAG, "WebSocket error: ${ex.message}", ex)
            if (conn != null) {
                val sessionId = wsSessionMap.remove(conn)
                if (sessionId != null) {
                    cdpHandler.unregisterSession(sessionId)
                }
            }
        }

        override fun onStart() {
            Log.i(TAG, "WebSocket server started")
            // Set connection lost timeout
            connectionLostTimeout = 60
        }
    }
}
