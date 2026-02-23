// File: app/src/main/java/com/sleepy/droidheadless/cdp/domains/RuntimeDomain.kt
package com.sleepy.droidheadless.cdp.domains

import com.sleepy.droidheadless.browser.WebViewManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Implements the Chrome DevTools Protocol "Runtime" domain.
 *
 * Supported methods:
 * - Runtime.enable: Enable runtime events
 * - Runtime.evaluate: Evaluate JavaScript expression
 * - Runtime.callFunctionOn: Call a function on a remote object
 * - Runtime.getProperties: Get properties of an object
 * - Runtime.releaseObject: Release a remote object reference
 *
 * @see https://chromedevtools.github.io/devtools-protocol/tot/Runtime/
 */
class RuntimeDomain(private val webViewManager: WebViewManager) {

    // Execution context counter
    private var executionContextId = 1

    /**
     * Routes a CDP method call to the appropriate handler.
     */
    fun handleMethod(method: String, params: JSONObject, pageId: String): JSONObject {
        return when (method) {
            "Runtime.enable" -> handleEnable(pageId)
            "Runtime.disable" -> JSONObject()
            "Runtime.evaluate" -> handleEvaluate(params, pageId)
            "Runtime.callFunctionOn" -> handleCallFunctionOn(params, pageId)
            "Runtime.getProperties" -> handleGetProperties(params)
            "Runtime.releaseObject" -> JSONObject()
            "Runtime.releaseObjectGroup" -> JSONObject()
            "Runtime.runIfWaitingForDebugger" -> JSONObject()
            "Runtime.discardConsoleEntries" -> JSONObject()
            "Runtime.setAsyncCallStackDepth" -> JSONObject()
            "Runtime.addBinding" -> JSONObject()
            else -> JSONObject()
        }
    }

    /**
     * Runtime.enable - Enables the Runtime domain.
     * 
     * Sends an executionContextCreated event to inform the client
     * about the current JavaScript execution context.
     */
    private fun handleEnable(pageId: String): JSONObject {
        // The executionContextCreated event will be sent via the handler
        return JSONObject()
    }

    /**
     * Creates the executionContextCreated event payload.
     * This is sent when Runtime.enable is called and when a page navigates.
     */
    fun createExecutionContextEvent(pageId: String): JSONObject {
        val url = webViewManager.getPageUrl(pageId)
        return JSONObject().apply {
            put("context", JSONObject().apply {
                put("id", executionContextId)
                put("origin", url)
                put("name", "")
                put("uniqueId", "$executionContextId.$pageId")
                put("auxData", JSONObject().apply {
                    put("isDefault", true)
                    put("type", "default")
                    put("frameId", pageId)
                })
            })
        }
    }

    /**
     * Runtime.evaluate - Evaluates a JavaScript expression.
     *
     * Params:
     *   expression (string): JavaScript expression to evaluate
     *   returnByValue (boolean, optional): Whether to return by value
     *   awaitPromise (boolean, optional): Whether to await Promise
     *   generatePreview (boolean, optional): Whether to generate preview
     *   userGesture (boolean, optional): Whether to treat as user gesture
     *
     * Returns:
     *   result (RemoteObject): Evaluation result
     *   exceptionDetails (ExceptionDetails, optional): Exception if thrown
     */
    private fun handleEvaluate(params: JSONObject, pageId: String): JSONObject {
        val expression = params.optString("expression", "")
        val awaitPromise = params.optBoolean("awaitPromise", false)
        val returnByValue = params.optBoolean("returnByValue", false)

        if (expression.isEmpty()) {
            return JSONObject().apply {
                put("result", JSONObject().apply {
                    put("type", "undefined")
                })
            }
        }

        return try {
            val jsResult = runBlocking {
                withTimeoutOrNull(30_000) {
                    webViewManager.evaluateJavaScript(pageId, expression, awaitPromise).await()
                }
            }

            if (jsResult == null) {
                return JSONObject().apply {
                    put("result", JSONObject().apply {
                        put("type", "undefined")
                    })
                    put("exceptionDetails", JSONObject().apply {
                        put("exceptionId", 1)
                        put("text", "Evaluation timed out")
                        put("lineNumber", 0)
                        put("columnNumber", 0)
                    })
                }
            }

            val type = jsResult.optString("type", "undefined")

            // Check if it was an error
            if (type == "error") {
                return JSONObject().apply {
                    put("result", JSONObject().apply {
                        put("type", "object")
                        put("subtype", "error")
                        put("className", "Error")
                        put("description", jsResult.optString("value", "Error"))
                    })
                    put("exceptionDetails", JSONObject().apply {
                        put("exceptionId", 1)
                        put("text", "Uncaught")
                        put("lineNumber", 0)
                        put("columnNumber", 0)
                        put("exception", JSONObject().apply {
                            put("type", "object")
                            put("subtype", "error")
                            put("description", jsResult.optString("value", "Error"))
                        })
                    })
                }
            }

            // Build the RemoteObject response
            val remoteObject = JSONObject().apply {
                put("type", type)
                when (type) {
                    "string" -> {
                        put("value", jsResult.opt("value"))
                    }
                    "number" -> {
                        val value = jsResult.opt("value")
                        put("value", value)
                        put("description", value?.toString() ?: "NaN")
                    }
                    "boolean" -> {
                        put("value", jsResult.opt("value"))
                    }
                    "undefined" -> {
                        // No value for undefined
                    }
                    "object" -> {
                        val value = jsResult.opt("value")
                        if (value == null || value == JSONObject.NULL) {
                            put("subtype", "null")
                            put("value", JSONObject.NULL)
                        } else {
                            put("value", value)
                            put("description", value.toString())
                            put("objectId", "{\"injectedScriptId\":1,\"id\":1}")
                        }
                    }
                    else -> {
                        put("value", jsResult.opt("value"))
                    }
                }
            }

            JSONObject().put("result", remoteObject)

        } catch (e: Exception) {
            JSONObject().apply {
                put("result", JSONObject().apply {
                    put("type", "undefined")
                })
                put("exceptionDetails", JSONObject().apply {
                    put("exceptionId", 1)
                    put("text", e.message ?: "Evaluation failed")
                    put("lineNumber", 0)
                    put("columnNumber", 0)
                })
            }
        }
    }

    /**
     * Runtime.callFunctionOn - Calls a function with given arguments on a remote object.
     *
     * We implement this by wrapping the function call as a regular evaluate,
     * since we don't maintain real remote object references.
     */
    private fun handleCallFunctionOn(params: JSONObject, pageId: String): JSONObject {
        val functionDeclaration = params.optString("functionDeclaration", "")
        val arguments = params.optJSONArray("arguments")
        val returnByValue = params.optBoolean("returnByValue", false)
        val awaitPromise = params.optBoolean("awaitPromise", false)

        // Build argument list for the function call
        val argList = StringBuilder()
        if (arguments != null) {
            for (i in 0 until arguments.length()) {
                if (i > 0) argList.append(", ")
                val arg = arguments.getJSONObject(i)
                when {
                    arg.has("value") -> {
                        val value = arg.get("value")
                        when (value) {
                            is String -> argList.append("\"${value.replace("\"", "\\\"")}\"")
                            is JSONObject -> argList.append(value.toString())
                            else -> argList.append(value)
                        }
                    }
                    arg.has("unserializableValue") -> {
                        argList.append(arg.getString("unserializableValue"))
                    }
                    else -> argList.append("undefined")
                }
            }
        }

        // Execute the function
        val expression = "($functionDeclaration)($argList)"
        val evaluateParams = JSONObject().apply {
            put("expression", expression)
            put("returnByValue", returnByValue)
            put("awaitPromise", awaitPromise)
        }

        return handleEvaluate(evaluateParams, pageId)
    }

    /**
     * Runtime.getProperties - Returns properties of an object.
     * We return a minimal response since we don't track real remote objects.
     */
    private fun handleGetProperties(params: JSONObject): JSONObject {
        return JSONObject().apply {
            put("result", org.json.JSONArray())
        }
    }
}
