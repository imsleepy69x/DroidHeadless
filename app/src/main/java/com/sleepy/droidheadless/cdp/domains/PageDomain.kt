// File: app/src/main/java/com/sleepy/droidheadless/cdp/domains/PageDomain.kt
package com.sleepy.droidheadless.cdp.domains

import com.sleepy.droidheadless.browser.WebViewManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Implements the Chrome DevTools Protocol "Page" domain.
 *
 * Supported methods:
 * - Page.enable: Starts receiving Page domain events
 * - Page.navigate: Navigates the page to a URL
 * - Page.reload: Reloads the page
 * - Page.captureScreenshot: Takes a screenshot as base64 PNG/JPEG
 * - Page.getFrameTree: Returns the frame tree structure
 * - Page.setLifecycleEventsEnabled: Enables lifecycle events
 *
 * @see https://chromedevtools.github.io/devtools-protocol/tot/Page/
 */
class PageDomain(private val webViewManager: WebViewManager) {

    /**
     * Routes a CDP method call to the appropriate handler.
     *
     * @param method The full CDP method name (e.g., "Page.navigate")
     * @param params The parameters JSON object
     * @param pageId The target page ID
     * @return Result JSON object to send back to the client
     */
    fun handleMethod(method: String, params: JSONObject, pageId: String): JSONObject {
        return when (method) {
            "Page.enable" -> handleEnable()
            "Page.disable" -> handleDisable()
            "Page.navigate" -> handleNavigate(params, pageId)
            "Page.reload" -> handleReload(params, pageId)
            "Page.captureScreenshot" -> handleCaptureScreenshot(params, pageId)
            "Page.getFrameTree" -> handleGetFrameTree(pageId)
            "Page.setLifecycleEventsEnabled" -> handleSetLifecycleEventsEnabled(params)
            "Page.bringToFront" -> JSONObject() // No-op for headless
            "Page.setAdBlockingEnabled" -> JSONObject() // No-op
            "Page.addScriptToEvaluateOnNewDocument" -> handleAddScriptToEvaluateOnNewDocument(params)
            "Page.createIsolatedWorld" -> handleCreateIsolatedWorld(params, pageId)
            "Page.getNavigationHistory" -> handleGetNavigationHistory(pageId)
            "Page.stopLoading" -> handleStopLoading(pageId)
            else -> {
                JSONObject().put("error", "Method not implemented: $method")
            }
        }
    }

    private fun handleEnable(): JSONObject {
        // Page domain is always enabled in our implementation
        return JSONObject()
    }

    private fun handleDisable(): JSONObject {
        return JSONObject()
    }

    /**
     * Page.navigate - Navigates to a URL.
     * 
     * Params:
     *   url (string): URL to navigate to
     *   referrer (string, optional): Referrer URL
     *   transitionType (string, optional): Intended transition type
     *
     * Returns:
     *   frameId (string): Frame ID of the navigated frame
     *   loaderId (string, optional): Loader ID
     */
    private fun handleNavigate(params: JSONObject, pageId: String): JSONObject {
        val url = params.optString("url", "")
        if (url.isEmpty()) {
            return JSONObject().apply {
                put("error", JSONObject().apply {
                    put("code", -32602)
                    put("message", "Missing required parameter: url")
                })
            }
        }

        return try {
            runBlocking {
                withTimeoutOrNull(10_000) {
                    webViewManager.navigate(pageId, url).await()
                }
            }
            JSONObject().apply {
                put("frameId", pageId)
                put("loaderId", pageId)
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("frameId", pageId)
                put("errorText", e.message ?: "Navigation failed")
            }
        }
    }

    /**
     * Page.reload - Reloads the page.
     *
     * Params:
     *   ignoreCache (boolean, optional): If true, clears cache before reload
     */
    private fun handleReload(params: JSONObject, pageId: String): JSONObject {
        val ignoreCache = params.optBoolean("ignoreCache", false)
        return try {
            runBlocking {
                withTimeoutOrNull(5_000) {
                    webViewManager.reloadPage(pageId, ignoreCache).await()
                }
            }
            JSONObject()
        } catch (e: Exception) {
            JSONObject()
        }
    }

    /**
     * Page.captureScreenshot - Captures a screenshot.
     *
     * Params:
     *   format (string, optional): "png" or "jpeg" (default: "png")
     *   quality (integer, optional): JPEG quality 0-100 (default: 100)
     *   clip (object, optional): Viewport clipping rect
     *
     * Returns:
     *   data (string): Base64-encoded image data
     */
    private fun handleCaptureScreenshot(params: JSONObject, pageId: String): JSONObject {
        val format = params.optString("format", "png")
        val quality = params.optInt("quality", 100)

        return try {
            val base64 = runBlocking {
                withTimeoutOrNull(10_000) {
                    webViewManager.captureScreenshot(pageId, quality, format).await()
                }
            }
            JSONObject().put("data", base64 ?: "")
        } catch (e: Exception) {
            JSONObject().apply {
                put("error", JSONObject().apply {
                    put("code", -32000)
                    put("message", "Screenshot failed: ${e.message}")
                })
            }
        }
    }

    /**
     * Page.getFrameTree - Returns the frame hierarchy.
     * We report a single main frame since WebView doesn't expose iframe details easily.
     */
    private fun handleGetFrameTree(pageId: String): JSONObject {
        val url = webViewManager.getPageUrl(pageId)
        val title = runBlocking {
            withTimeoutOrNull(3_000) {
                webViewManager.getPageTitle(pageId).await()
            } ?: ""
        }

        return JSONObject().apply {
            put("frameTree", JSONObject().apply {
                put("frame", JSONObject().apply {
                    put("id", pageId)
                    put("loaderId", pageId)
                    put("url", url)
                    put("domainAndRegistry", "")
                    put("securityOrigin", url)
                    put("mimeType", "text/html")
                    put("name", title)
                })
                put("childFrames", org.json.JSONArray())
            })
        }
    }

    private fun handleSetLifecycleEventsEnabled(params: JSONObject): JSONObject {
        // We emit lifecycle events regardless, but acknowledge the command
        return JSONObject()
    }

    private fun handleAddScriptToEvaluateOnNewDocument(params: JSONObject): JSONObject {
        // Return a fake identifier - full implementation would inject script on every page load
        return JSONObject().put("identifier", "1")
    }

    private fun handleCreateIsolatedWorld(params: JSONObject, pageId: String): JSONObject {
        return JSONObject().put("executionContextId", 2)
    }

    private fun handleGetNavigationHistory(pageId: String): JSONObject {
        val url = webViewManager.getPageUrl(pageId)
        return JSONObject().apply {
            put("currentIndex", 0)
            put("entries", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("id", 0)
                    put("url", url)
                    put("userTypedURL", url)
                    put("title", "")
                    put("transitionType", "typed")
                })
            })
        }
    }

    private fun handleStopLoading(pageId: String): JSONObject {
        // We could stop loading via WebView, but just acknowledge
        return JSONObject()
    }
}
