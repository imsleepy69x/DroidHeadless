// File: app/src/main/java/com/sleepy/droidheadless/cdp/domains/NetworkDomain.kt
package com.sleepy.droidheadless.cdp.domains

import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Implements the Chrome DevTools Protocol "Network" domain.
 *
 * Supported methods:
 * - Network.enable: Start receiving network events
 * - Network.disable: Stop receiving network events
 * - Network.getCookies: Get all cookies (optionally filtered by URL)
 * - Network.setCookie: Set a cookie
 * - Network.deleteCookies: Delete cookies matching criteria
 * - Network.clearBrowserCookies: Delete all cookies
 * - Network.setUserAgentOverride: Override user agent (also in Emulation domain)
 * - Network.setExtraHTTPHeaders: Set extra headers for requests
 *
 * Network events (requestWillBeSent, responseReceived, etc.) are emitted
 * by the NetworkInterceptor and forwarded through the CDPHandler.
 *
 * @see https://chromedevtools.github.io/devtools-protocol/tot/Network/
 */
class NetworkDomain {

    // Android's global cookie manager (shared across all WebViews)
    private val cookieManager: CookieManager = CookieManager.getInstance()

    // Extra headers to send with every request
    private var extraHeaders: Map<String, String> = emptyMap()

    /**
     * Routes a CDP method call to the appropriate handler.
     */
    fun handleMethod(method: String, params: JSONObject): JSONObject {
        return when (method) {
            "Network.enable" -> handleEnable(params)
            "Network.disable" -> handleDisable()
            "Network.getCookies" -> handleGetCookies(params)
            "Network.setCookie" -> handleSetCookie(params)
            "Network.deleteCookies" -> handleDeleteCookies(params)
            "Network.clearBrowserCookies" -> handleClearBrowserCookies()
            "Network.setExtraHTTPHeaders" -> handleSetExtraHTTPHeaders(params)
            "Network.getResponseBody" -> handleGetResponseBody(params)
            "Network.setRequestInterception" -> JSONObject() // Acknowledged but not fully implemented
            "Network.setCacheDisabled" -> JSONObject() // Acknowledged
            "Network.emulateNetworkConditions" -> JSONObject() // Acknowledged
            else -> JSONObject()
        }
    }

    private fun handleEnable(params: JSONObject): JSONObject {
        // Network monitoring is always active through our interceptor
        return JSONObject()
    }

    private fun handleDisable(): JSONObject {
        return JSONObject()
    }

    /**
     * Network.getCookies - Returns cookies for the given URLs.
     *
     * Params:
     *   urls (array of string, optional): List of URLs to get cookies for
     *
     * Returns:
     *   cookies (array): Array of cookie objects
     */
    private fun handleGetCookies(params: JSONObject): JSONObject {
        val urls = params.optJSONArray("urls")
        val cookies = JSONArray()

        if (urls != null && urls.length() > 0) {
            // Get cookies for specific URLs
            for (i in 0 until urls.length()) {
                val url = urls.getString(i)
                parseCookiesForUrl(url, cookies)
            }
        } else {
            // Without URLs, we can't easily enumerate all cookies from CookieManager
            // Return empty - clients should specify URLs
        }

        return JSONObject().put("cookies", cookies)
    }

    /**
     * Parses the cookie string returned by CookieManager into CDP cookie objects.
     */
    private fun parseCookiesForUrl(url: String, cookies: JSONArray) {
        val cookieString = cookieManager.getCookie(url) ?: return

        cookieString.split(";").forEach { cookie ->
            val trimmed = cookie.trim()
            val eqIndex = trimmed.indexOf('=')
            if (eqIndex > 0) {
                val name = trimmed.substring(0, eqIndex).trim()
                val value = trimmed.substring(eqIndex + 1).trim()

                cookies.put(JSONObject().apply {
                    put("name", name)
                    put("value", value)
                    put("domain", extractDomain(url))
                    put("path", "/")
                    put("expires", -1) // Session cookie (no expiry info from CookieManager)
                    put("size", name.length + value.length)
                    put("httpOnly", false)
                    put("secure", url.startsWith("https"))
                    put("session", true)
                    put("sameParty", false)
                    put("sourceScheme", if (url.startsWith("https")) "Secure" else "NonSecure")
                    put("sourcePort", if (url.startsWith("https")) 443 else 80)
                })
            }
        }
    }

    /**
     * Network.setCookie - Sets a cookie.
     *
     * Params:
     *   name (string): Cookie name
     *   value (string): Cookie value
     *   url (string, optional): URL to associate with
     *   domain (string, optional): Cookie domain
     *   path (string, optional): Cookie path
     *   secure (boolean, optional): Secure flag
     *   httpOnly (boolean, optional): HttpOnly flag
     *   expires (number, optional): Expiration timestamp
     */
    private fun handleSetCookie(params: JSONObject): JSONObject {
        val name = params.optString("name", "")
        val value = params.optString("value", "")
        val url = params.optString("url", "")
        val domain = params.optString("domain", "")
        val path = params.optString("path", "/")
        val secure = params.optBoolean("secure", false)
        val httpOnly = params.optBoolean("httpOnly", false)
        val expires = params.optDouble("expires", -1.0)

        if (name.isEmpty()) {
            return JSONObject().put("success", false)
        }

        // Build cookie string in the format CookieManager expects
        val cookieBuilder = StringBuilder()
        cookieBuilder.append("$name=$value")
        if (domain.isNotEmpty()) cookieBuilder.append("; domain=$domain")
        cookieBuilder.append("; path=$path")
        if (secure) cookieBuilder.append("; secure")
        if (httpOnly) cookieBuilder.append("; httponly")
        if (expires > 0) {
            // Convert Unix timestamp to HTTP date format
            val date = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("GMT")
            }.format(java.util.Date((expires * 1000).toLong()))
            cookieBuilder.append("; expires=$date")
        }

        // Determine the URL to set the cookie for
        val targetUrl = when {
            url.isNotEmpty() -> url
            domain.isNotEmpty() -> "https://$domain"
            else -> return JSONObject().put("success", false)
        }

        cookieManager.setCookie(targetUrl, cookieBuilder.toString())
        cookieManager.flush()

        return JSONObject().put("success", true)
    }

    /**
     * Network.deleteCookies - Deletes cookies matching the given criteria.
     *
     * Note: Android's CookieManager doesn't support granular deletion,
     * so we do our best by removing and re-setting remaining cookies.
     */
    private fun handleDeleteCookies(params: JSONObject): JSONObject {
        val name = params.optString("name", "")
        val url = params.optString("url", "")
        val domain = params.optString("domain", "")

        val targetUrl = when {
            url.isNotEmpty() -> url
            domain.isNotEmpty() -> "https://$domain"
            else -> return JSONObject()
        }

        // Get existing cookies
        val existingCookies = cookieManager.getCookie(targetUrl) ?: return JSONObject()

        // Remove all cookies for this URL first
        cookieManager.setCookie(targetUrl, "")

        // Re-set cookies that don't match the deletion criteria
        if (name.isNotEmpty()) {
            existingCookies.split(";").forEach { cookie ->
                val trimmed = cookie.trim()
                val eqIndex = trimmed.indexOf('=')
                if (eqIndex > 0) {
                    val cookieName = trimmed.substring(0, eqIndex).trim()
                    if (cookieName != name) {
                        cookieManager.setCookie(targetUrl, trimmed)
                    }
                }
            }
        }

        cookieManager.flush()
        return JSONObject()
    }

    /**
     * Network.clearBrowserCookies - Removes all cookies.
     */
    private fun handleClearBrowserCookies(): JSONObject {
        cookieManager.removeAllCookies(null)
        cookieManager.flush()
        return JSONObject()
    }

    /**
     * Network.setExtraHTTPHeaders - Set extra headers sent with every request.
     */
    private fun handleSetExtraHTTPHeaders(params: JSONObject): JSONObject {
        val headers = params.optJSONObject("headers")
        if (headers != null) {
            val map = mutableMapOf<String, String>()
            headers.keys().forEach { key ->
                map[key] = headers.getString(key)
            }
            extraHeaders = map
        }
        return JSONObject()
    }

    /**
     * Network.getResponseBody - Returns body content for a request.
     * We don't store response bodies currently, so return empty.
     */
    private fun handleGetResponseBody(params: JSONObject): JSONObject {
        return JSONObject().apply {
            put("body", "")
            put("base64Encoded", false)
        }
    }

    fun getExtraHeaders(): Map<String, String> = extraHeaders

    /**
     * Extracts domain from a URL string.
     */
    private fun extractDomain(url: String): String {
        return try {
            java.net.URL(url).host
        } catch (e: Exception) {
            url
        }
    }
}
