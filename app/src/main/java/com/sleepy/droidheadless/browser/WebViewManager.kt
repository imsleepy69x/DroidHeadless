// File: app/src/main/java/com/sleepy/droidheadless/browser/WebViewManager.kt
package com.sleepy.droidheadless.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages all WebView instances (tabs/pages) for the headless browser.
 *
 * Each WebView represents a CDP "target" or "page". This class handles:
 * - Creating and destroying WebView instances
 * - Navigation (Page.navigate)
 * - Screenshots (Page.captureScreenshot)
 * - JavaScript evaluation (Runtime.evaluate)
 * - Console message capture
 * - User-agent override
 *
 * IMPORTANT: All WebView operations MUST run on the main thread.
 * We use Handler(Looper.getMainLooper()) to ensure this.
 */
class WebViewManager(
    private val context: Context,
    private val networkInterceptor: NetworkInterceptor
) {

    companion object {
        private const val TAG = "WebViewManager"
        private const val DEFAULT_VIEWPORT_WIDTH = 1280
        private const val DEFAULT_VIEWPORT_HEIGHT = 720
    }

    // All active pages/tabs: pageId → PageInfo
    private val pages = ConcurrentHashMap<String, PageInfo>()

    // Main thread handler - WebView requires main thread for all operations
    private val mainHandler = Handler(Looper.getMainLooper())

    // Coroutine scope for async operations
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Listener for page events that need to be forwarded to CDP sessions.
     */
    interface PageEventListener {
        fun onPageEvent(pageId: String, method: String, params: JSONObject)
    }

    private val eventListeners = ConcurrentHashMap.newKeySet<PageEventListener>()

    fun addEventListener(listener: PageEventListener) {
        eventListeners.add(listener)
    }

    fun removeEventListener(listener: PageEventListener) {
        eventListeners.remove(listener)
    }

    /**
     * Creates a new WebView (page/tab) and returns its unique ID.
     * The WebView is fully configured for headless operation.
     *
     * @param url Initial URL to load (default: about:blank)
     * @return The unique page ID
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun createPage(url: String = "about:blank"): CompletableDeferred<String> {
        val deferred = CompletableDeferred<String>()
        val pageId = UUID.randomUUID().toString().replace("-", "").take(32)

        mainHandler.post {
            try {
                val webView = WebView(context).apply {
                    // Configure WebView settings for headless operation
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        setSupportMultipleWindows(false)
                        javaScriptCanOpenWindowsAutomatically = true
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        allowFileAccess = false
                        allowContentAccess = false
                        mediaPlaybackRequiresUserGesture = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        cacheMode = WebSettings.LOAD_DEFAULT
                        // Set default viewport dimensions
                        setSupportZoom(false)
                        builtInZoomControls = false
                    }

                    // Set layout dimensions (offscreen rendering)
                    layout(0, 0, DEFAULT_VIEWPORT_WIDTH, DEFAULT_VIEWPORT_HEIGHT)
                    measure(
                        android.view.View.MeasureSpec.makeMeasureSpec(DEFAULT_VIEWPORT_WIDTH, android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(DEFAULT_VIEWPORT_HEIGHT, android.view.View.MeasureSpec.EXACTLY)
                    )
                    layout(0, 0, DEFAULT_VIEWPORT_WIDTH, DEFAULT_VIEWPORT_HEIGHT)
                }

                // Set up WebViewClient for navigation events and network interception
                webView.webViewClient = object : WebViewClient() {

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        if (request == null) return null
                        // Delegate to NetworkInterceptor for full traffic capture
                        return networkInterceptor.interceptRequest(pageId, request)
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        emitPageEvent(pageId, "Page.frameStartedLoading", JSONObject().apply {
                            put("frameId", pageId)
                        })
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        // Update stored URL
                        pages[pageId]?.currentUrl = url ?: "about:blank"

                        emitPageEvent(pageId, "Page.loadEventFired", JSONObject().apply {
                            put("timestamp", System.currentTimeMillis() / 1000.0)
                        })
                        emitPageEvent(pageId, "Page.frameStoppedLoading", JSONObject().apply {
                            put("frameId", pageId)
                        })
                        emitPageEvent(pageId, "Page.frameNavigated", JSONObject().apply {
                            put("frame", JSONObject().apply {
                                put("id", pageId)
                                put("loaderId", pageId)
                                put("url", url ?: "about:blank")
                                put("domainAndRegistry", "")
                                put("securityOrigin", url ?: "about:blank")
                                put("mimeType", "text/html")
                            })
                        })
                        // Also emit DOM content event
                        emitPageEvent(pageId, "Page.domContentEventFired", JSONObject().apply {
                            put("timestamp", System.currentTimeMillis() / 1000.0)
                        })
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        super.onReceivedError(view, request, error)
                        if (request?.isForMainFrame == true) {
                            Log.w(TAG, "Page error: ${error?.description} for ${request.url}")
                        }
                    }
                }

                // Set up WebChromeClient for console messages and other browser events
                webView.webChromeClient = object : WebChromeClient() {

                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        consoleMessage?.let { msg ->
                            val level = when (msg.messageLevel()) {
                                ConsoleMessage.MessageLevel.LOG -> "log"
                                ConsoleMessage.MessageLevel.WARNING -> "warning"
                                ConsoleMessage.MessageLevel.ERROR -> "error"
                                ConsoleMessage.MessageLevel.DEBUG -> "debug"
                                ConsoleMessage.MessageLevel.TIP -> "info"
                                else -> "log"
                            }

                            emitPageEvent(pageId, "Console.messageAdded", JSONObject().apply {
                                put("message", JSONObject().apply {
                                    put("source", "console-api")
                                    put("level", level)
                                    put("text", msg.message())
                                    put("line", msg.lineNumber())
                                    put("url", msg.sourceId() ?: "")
                                })
                            })

                            // Also emit Runtime.consoleAPICalled for better compatibility
                            emitPageEvent(pageId, "Runtime.consoleAPICalled", JSONObject().apply {
                                put("type", level)
                                put("args", org.json.JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("type", "string")
                                        put("value", msg.message())
                                    })
                                })
                                put("executionContextId", 1)
                                put("timestamp", System.currentTimeMillis().toDouble())
                            })
                        }
                        return true
                    }

                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        super.onProgressChanged(view, newProgress)
                        pages[pageId]?.loadProgress = newProgress
                    }
                }

                // Store the page
                val pageInfo = PageInfo(
                    id = pageId,
                    webView = webView,
                    currentUrl = url,
                    title = ""
                )
                pages[pageId] = pageInfo

                // Load the initial URL
                if (url != "about:blank") {
                    webView.loadUrl(url)
                }

                Log.i(TAG, "Created page: $pageId with URL: $url")
                deferred.complete(pageId)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to create page", e)
                deferred.completeExceptionally(e)
            }
        }

        return deferred
    }

    /**
     * Navigates an existing page to a new URL.
     * The deferred completes only after onPageFinished fires, so callers
     * can be sure the page is actually loaded before evaluating JS.
     */
    fun navigate(pageId: String, url: String): CompletableDeferred<Boolean> {
        val deferred = CompletableDeferred<Boolean>()

        val page = pages[pageId]
        if (page == null) {
            deferred.completeExceptionally(IllegalArgumentException("Page not found: $pageId"))
            return deferred
        }

        mainHandler.post {
            try {
                // Install a one-shot WebViewClient that completes the deferred
                // when the page finishes loading, then restores normal behavior.
                val originalClient = page.webView.webViewClient
                page.webView.webViewClient = object : WebViewClient() {

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        if (request == null) return null
                        return networkInterceptor.interceptRequest(pageId, request)
                    }

                    override fun onPageStarted(view: WebView?, u: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, u, favicon)
                        emitPageEvent(pageId, "Page.frameStartedLoading", JSONObject().apply {
                            put("frameId", pageId)
                        })
                    }

                    override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                        super.onPageFinished(view, finishedUrl)
                        // Update stored URL now that the page is actually loaded
                        pages[pageId]?.currentUrl = finishedUrl ?: url

                        emitPageEvent(pageId, "Page.loadEventFired", JSONObject().apply {
                            put("timestamp", System.currentTimeMillis() / 1000.0)
                        })
                        emitPageEvent(pageId, "Page.frameStoppedLoading", JSONObject().apply {
                            put("frameId", pageId)
                        })
                        emitPageEvent(pageId, "Page.frameNavigated", JSONObject().apply {
                            put("frame", JSONObject().apply {
                                put("id", pageId)
                                put("loaderId", pageId)
                                put("url", finishedUrl ?: url)
                                put("domainAndRegistry", "")
                                put("securityOrigin", finishedUrl ?: url)
                                put("mimeType", "text/html")
                            })
                        })
                        emitPageEvent(pageId, "Page.domContentEventFired", JSONObject().apply {
                            put("timestamp", System.currentTimeMillis() / 1000.0)
                        })

                        // Restore original client and signal completion
                        page.webView.webViewClient = originalClient
                        if (!deferred.isCompleted) deferred.complete(true)
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        super.onReceivedError(view, request, error)
                        if (request?.isForMainFrame == true) {
                            Log.w(TAG, "Page error: ${error?.description} for ${request.url}")
                            pages[pageId]?.currentUrl = url
                            page.webView.webViewClient = originalClient
                            if (!deferred.isCompleted) deferred.complete(false)
                        }
                    }
                }

                page.webView.loadUrl(url)

            } catch (e: Exception) {
                Log.e(TAG, "Navigation failed", e)
                deferred.completeExceptionally(e)
            }
        }

        return deferred
    }

    /**
     * Captures a screenshot of the specified page as a base64-encoded PNG.
     *
     * This works by drawing the WebView's content to a Canvas/Bitmap,
     * then encoding it as PNG.
     */
    fun captureScreenshot(
        pageId: String,
        quality: Int = 100,
        format: String = "png"
    ): CompletableDeferred<String> {
        val deferred = CompletableDeferred<String>()

        val page = pages[pageId]
        if (page == null) {
            deferred.completeExceptionally(IllegalArgumentException("Page not found: $pageId"))
            return deferred
        }

        mainHandler.post {
            try {
                val webView = page.webView
                val width = if (webView.width > 0) webView.width else DEFAULT_VIEWPORT_WIDTH
                val height = if (webView.height > 0) webView.height else DEFAULT_VIEWPORT_HEIGHT

                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                webView.draw(canvas)

                val outputStream = ByteArrayOutputStream()
                val compressFormat = if (format.lowercase() == "jpeg") {
                    Bitmap.CompressFormat.JPEG
                } else {
                    Bitmap.CompressFormat.PNG
                }
                bitmap.compress(compressFormat, quality, outputStream)
                bitmap.recycle()

                val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                deferred.complete(base64)

            } catch (e: Exception) {
                Log.e(TAG, "Screenshot capture failed", e)
                deferred.completeExceptionally(e)
            }
        }

        return deferred
    }

    /**
     * Evaluates JavaScript in the context of a page.
     * Supports both synchronous and asynchronous evaluation.
     *
     * @param pageId The page to evaluate in
     * @param expression The JavaScript expression to evaluate
     * @param awaitPromise If true, waits for Promise to resolve
     * @return The result as a JSONObject with type and value
     */
    fun evaluateJavaScript(
        pageId: String,
        expression: String,
        awaitPromise: Boolean = false
    ): CompletableDeferred<JSONObject> {
        val deferred = CompletableDeferred<JSONObject>()

        val page = pages[pageId]
        if (page == null) {
            deferred.completeExceptionally(IllegalArgumentException("Page not found: $pageId"))
            return deferred
        }

        mainHandler.post {
            try {
                // Wrap expression to capture both sync and async results properly
                val wrappedExpression = if (awaitPromise) {
                    """
                    (async function() {
                        try {
                            var __result = await ($expression);
                            return JSON.stringify({type: typeof __result, value: __result});
                        } catch(e) {
                            return JSON.stringify({type: 'error', value: e.toString()});
                        }
                    })()
                    """.trimIndent()
                } else {
                    """
                    (function() {
                        try {
                            var __result = $expression;
                            if (__result === undefined) return JSON.stringify({type: 'undefined', value: null});
                            if (__result === null) return JSON.stringify({type: 'object', value: null});
                            return JSON.stringify({type: typeof __result, value: __result});
                        } catch(e) {
                            return JSON.stringify({type: 'error', value: e.toString()});
                        }
                    })()
                    """.trimIndent()
                }

                page.webView.evaluateJavascript(wrappedExpression) { rawResult ->
                    try {
                        // WebView returns the result as a JSON-encoded string
                        // It may be double-quoted, so we need to parse carefully
                        val cleaned = if (rawResult.startsWith("\"") && rawResult.endsWith("\"")) {
                            // Unescape the JSON string
                            rawResult
                                .substring(1, rawResult.length - 1)
                                .replace("\\\"", "\"")
                                .replace("\\\\", "\\")
                                .replace("\\n", "\n")
                                .replace("\\t", "\t")
                        } else {
                            rawResult
                        }

                        if (cleaned == "null" || cleaned.isBlank()) {
                            deferred.complete(JSONObject().apply {
                                put("type", "undefined")
                            })
                        } else {
                            val parsed = JSONObject(cleaned)
                            val result = JSONObject().apply {
                                put("type", parsed.optString("type", "undefined"))
                                if (parsed.has("value")) {
                                    put("value", parsed.get("value"))
                                }
                                // Add description for Chrome compatibility
                                put("description", parsed.opt("value")?.toString() ?: "undefined")
                            }
                            deferred.complete(result)
                        }
                    } catch (e: Exception) {
                        // If parsing fails, return raw value
                        deferred.complete(JSONObject().apply {
                            put("type", "string")
                            put("value", rawResult)
                            put("description", rawResult)
                        })
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "JS evaluation failed", e)
                deferred.completeExceptionally(e)
            }
        }

        return deferred
    }

    /**
     * Sets a custom user agent string for the specified page.
     * Persists across page loads as requested.
     */
    fun setUserAgent(pageId: String, userAgent: String): CompletableDeferred<Boolean> {
        val deferred = CompletableDeferred<Boolean>()

        val page = pages[pageId]
        if (page == null) {
            deferred.completeExceptionally(IllegalArgumentException("Page not found: $pageId"))
            return deferred
        }

        mainHandler.post {
            try {
                page.webView.settings.userAgentString = userAgent
                page.userAgent = userAgent
                deferred.complete(true)
            } catch (e: Exception) {
                deferred.completeExceptionally(e)
            }
        }

        return deferred
    }

    /**
     * Closes and destroys a page (tab).
     */
    fun closePage(pageId: String): CompletableDeferred<Boolean> {
        val deferred = CompletableDeferred<Boolean>()

        val page = pages.remove(pageId)
        if (page == null) {
            deferred.complete(false)
            return deferred
        }

        mainHandler.post {
            try {
                page.webView.stopLoading()
                page.webView.loadUrl("about:blank")
                page.webView.clearHistory()
                page.webView.clearCache(true)
                page.webView.destroy()
                Log.i(TAG, "Closed page: $pageId")
                deferred.complete(true)
            } catch (e: Exception) {
                Log.e(TAG, "Error closing page", e)
                deferred.complete(true) // Still consider it closed
            }
        }

        return deferred
    }

    /**
     * Returns info about a specific page for CDP /json/list endpoint.
     */
    fun getPageInfo(pageId: String): PageInfo? = pages[pageId]

    /**
     * Returns all active pages for CDP /json/list endpoint.
     */
    fun getAllPages(): List<PageInfo> = pages.values.toList()

    /**
     * Gets the current URL of a page.
     */
    fun getPageUrl(pageId: String): String {
        return pages[pageId]?.currentUrl ?: "about:blank"
    }

    /**
     * Gets the current title of a page.
     */
    fun getPageTitle(pageId: String): CompletableDeferred<String> {
        val deferred = CompletableDeferred<String>()

        val page = pages[pageId]
        if (page == null) {
            deferred.complete("")
            return deferred
        }

        mainHandler.post {
            try {
                deferred.complete(page.webView.title ?: "")
            } catch (e: Exception) {
                deferred.complete("")
            }
        }

        return deferred
    }

    /**
     * Reloads a page.
     */
    fun reloadPage(pageId: String, ignoreCache: Boolean = false): CompletableDeferred<Boolean> {
        val deferred = CompletableDeferred<Boolean>()
        val page = pages[pageId]
        if (page == null) {
            deferred.completeExceptionally(IllegalArgumentException("Page not found: $pageId"))
            return deferred
        }
        mainHandler.post {
            try {
                if (ignoreCache) {
                    page.webView.clearCache(true)
                }
                page.webView.reload()
                deferred.complete(true)
            } catch (e: Exception) {
                deferred.completeExceptionally(e)
            }
        }
        return deferred
    }

    /**
     * Gets the page's HTML content.
     */
    fun getPageContent(pageId: String): CompletableDeferred<String> {
        return evaluateJavaScript(pageId, "document.documentElement.outerHTML").let { jsDeferred ->
            val deferred = CompletableDeferred<String>()
            scope.launch {
                try {
                    val result = jsDeferred.await()
                    deferred.complete(result.optString("value", ""))
                } catch (e: Exception) {
                    deferred.complete("")
                }
            }
            deferred
        }
    }

    /**
     * Emits a page event to all registered listeners.
     */
    private fun emitPageEvent(pageId: String, method: String, params: JSONObject) {
        eventListeners.forEach { listener ->
            try {
                listener.onPageEvent(pageId, method, params)
            } catch (e: Exception) {
                Log.w(TAG, "Error emitting page event", e)
            }
        }
    }

    /**
     * Destroys all pages and releases resources.
     */
    fun destroyAll() {
        mainHandler.post {
            pages.values.forEach { page ->
                try {
                    page.webView.stopLoading()
                    page.webView.loadUrl("about:blank")
                    page.webView.destroy()
                } catch (e: Exception) {
                    Log.w(TAG, "Error destroying WebView", e)
                }
            }
            pages.clear()
        }
        scope.cancel()
    }

    /**
     * Holds information about a single page/tab.
     */
    data class PageInfo(
        val id: String,
        val webView: WebView,
        var currentUrl: String = "about:blank",
        var title: String = "",
        var userAgent: String? = null,
        var loadProgress: Int = 0
    )
}
