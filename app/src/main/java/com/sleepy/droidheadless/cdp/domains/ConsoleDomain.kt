// File: app/src/main/java/com/sleepy/droidheadless/cdp/domains/ConsoleDomain.kt
package com.sleepy.droidheadless.cdp.domains

import org.json.JSONObject

/**
 * Implements the Chrome DevTools Protocol "Console" domain.
 *
 * This domain is relatively simple - it mostly just needs to be enabled,
 * and then Console.messageAdded events are emitted by the WebViewManager
 * when WebChromeClient.onConsoleMessage() fires.
 *
 * Supported methods:
 * - Console.enable: Start receiving console messages
 * - Console.disable: Stop receiving console messages
 * - Console.clearMessages: Clear the console
 *
 * @see https://chromedevtools.github.io/devtools-protocol/tot/Console/
 */
class ConsoleDomain {

    var isEnabled: Boolean = false
        private set

    /**
     * Routes a CDP method call to the appropriate handler.
     */
    fun handleMethod(method: String, params: JSONObject): JSONObject {
        return when (method) {
            "Console.enable" -> handleEnable()
            "Console.disable" -> handleDisable()
            "Console.clearMessages" -> handleClearMessages()
            else -> JSONObject()
        }
    }

    private fun handleEnable(): JSONObject {
        isEnabled = true
        return JSONObject()
    }

    private fun handleDisable(): JSONObject {
        isEnabled = false
        return JSONObject()
    }

    private fun handleClearMessages(): JSONObject {
        // No stored messages to clear in our implementation
        return JSONObject()
    }
}
