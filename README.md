# DroidHeadless 🤖

> A production-ready Android app that runs a headless Chromium-based browser with full **Chrome DevTools Protocol (CDP)** support — using the device's built-in WebView. No custom Chromium build required.

[![Min SDK](https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-brightgreen?logo=android)](https://developer.android.com/about/versions/oreo)
[![Target SDK](https://img.shields.io/badge/Target%20SDK-34-blue)](https://developer.android.com/about/versions/14)
[![Language](https://img.shields.io/badge/Language-Kotlin-purple?logo=kotlin)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)

---

## Features

- ✅ Full CDP server — HTTP on port `9222` + WebSocket on port `9223` (always `port+1`)
- ✅ Compatible with **Puppeteer**, **Playwright**, and any raw CDP client
- ✅ Multi-tab support — tabs managed via the CDP `Target` domain
- ✅ Network traffic interception with full request/response event emission
- ✅ Screenshots — base64 PNG or JPEG via `Page.captureScreenshot`
- ✅ JavaScript evaluation — synchronous and async/Promise via `Runtime.evaluate`
- ✅ Cookie management
- ✅ Console message capture — `Console.messageAdded` events
- ✅ User-agent override via `Emulation.setUserAgentOverride`
- ✅ Input simulation — mouse clicks and keyboard events via `Input` domain
- ✅ Auto-start on device boot
- ✅ Foreground service with live stats notification
- ✅ Configurable port (1024–65534) via the in-app Settings screen
- ✅ Stub implementations for CSS, Log, Debugger, Performance, Security, ServiceWorker (broad client compatibility)

---

## Architecture

| Component | Description |
|---|---|
| `HeadlessBrowserService` | Android foreground `Service` that orchestrates the entire stack |
| `WebViewManager` | Manages multiple `WebView` instances (tabs/pages); each is a CDP target. Default viewport: **1280×720** |
| `NetworkInterceptor` | Intercepts all HTTP traffic via `shouldInterceptRequest`, re-issues via `HttpURLConnection`, and emits CDP `Network` domain events |
| `CDPServer` | HTTP server (NanoHTTPD on port N) + WebSocket server (Java-WebSocket on port N+1) |
| `CDPHandler` | Routes CDP JSON-RPC messages to domain-specific handlers |
| Domain Handlers | `PageDomain`, `RuntimeDomain`, `NetworkDomain`, `ConsoleDomain` — plus stubs for broader compatibility |

---

## Requirements

- **Android Studio** Hedgehog or newer
- **JDK 17**
- **Android SDK 34**
- A physical device or emulator running **Android 8.0+ (API 26+)**

### Permissions used

| Permission | Purpose |
|---|---|
| `INTERNET` | CDP server & WebView networking |
| `FOREGROUND_SERVICE` | Keep the browser service alive |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Required on Android 14+ for special-use foreground services |
| `RECEIVE_BOOT_COMPLETED` | Auto-start on device boot |
| `POST_NOTIFICATIONS` | Show foreground service notification |
| `WAKE_LOCK` | Prevent CPU sleep during active sessions |

---

## Build & Install

```bash
# Clone the repository
git clone https://github.com/imsleepy69x/DroidHeadless.git
cd DroidHeadless

# Build a debug APK
./gradlew assembleDebug

# Install directly onto a connected device/emulator
./gradlew installDebug
```

> **Tip:** After install, open the app once to configure the port (default `9222`) and grant notification permissions, then tap **Start** to launch the foreground service.

### Key dependencies

| Library | Version |
|---|---|
| `org.nanohttpd:nanohttpd` | `2.3.1` |
| `org.java-websocket:Java-WebSocket` | `1.5.6` |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | `1.7.3` |
| `androidx.webkit:webkit` | `1.9.0` |

---

## ADB Port Forwarding

DroidHeadless runs entirely on-device. To reach it from your development machine, forward the ports over ADB:

```bash
adb forward tcp:9222 tcp:9222   # HTTP (CDP REST API)
adb forward tcp:9223 tcp:9223   # WebSocket (CDP commands)
```

To remove the forwards when done:

```bash
adb forward --remove tcp:9222
adb forward --remove tcp:9223
```

---

## HTTP Endpoints

All endpoints are served at `http://127.0.0.1:9222` (or whichever port you configured).

| Method | Path | Description |
|---|---|---|
| `GET` | `/` | HTML status page |
| `GET` | `/json/version` | Browser version info |
| `GET` | `/json/list` or `/json` | List all open pages/targets |
| `PUT` | `/json/new?url=<url>` | Create a new page (optionally navigate to URL) |
| `GET` | `/json/activate/<id>` | Bring a page to the foreground |
| `GET` | `/json/close/<id>` | Close a page/target |
| `GET` | `/json/protocol` | Full CDP protocol descriptor |

**WebSocket endpoint:**

```
ws://127.0.0.1:9223/devtools/page/<targetId>
```

---

## Quick Start

### Puppeteer (Node.js)

> **Important:** Because the WebSocket runs on a different port than HTTP, use `browserURL` instead of `browserWSEndpoint`.

```js
const puppeteer = require('puppeteer-core');

(async () => {
  // Connect via the HTTP endpoint — Puppeteer will discover the WS URL automatically
  const browser = await puppeteer.connect({
    browserURL: 'http://127.0.0.1:9222',
  });

  const page = await browser.newPage();
  await page.goto('https://example.com');

  const title = await page.title();
  console.log('Page title:', title);

  const screenshot = await page.screenshot({ encoding: 'base64' });
  console.log('Screenshot (base64 length):', screenshot.length);

  await browser.disconnect();
})();
```

### Raw CDP Client (Node.js)

```js
const CDP = require('chrome-remote-interface');

(async () => {
  // List available targets
  const targets = await CDP.List({ host: '127.0.0.1', port: 9222 });
  console.log('Targets:', targets);

  const client = await CDP({
    host: '127.0.0.1',
    port: 9222,          // HTTP port for target discovery
    target: targets[0],  // Connect to the first available page
  });

  const { Page, Runtime, Network } = client;

  await Network.enable();
  await Page.enable();
  await Page.navigate({ url: 'https://example.com' });
  await Page.loadEventFired();

  const { result } = await Runtime.evaluate({ expression: 'document.title' });
  console.log('Title:', result.value);

  const { data } = await Page.captureScreenshot({ format: 'png' });
  require('fs').writeFileSync('screenshot.png', Buffer.from(data, 'base64'));

  await client.close();
})();
```

---

## Supported CDP Domains

| Domain | Status | Key Methods |
|---|---|---|
| **Page** | ✅ Implemented | `enable`, `navigate`, `reload`, `captureScreenshot`, `getFrameTree`, `setLifecycleEventsEnabled`, `addScriptToEvaluateOnNewDocument`, `createIsolatedWorld`, `getNavigationHistory`, `stopLoading`, `bringToFront` |
| **Runtime** | ✅ Implemented | `enable`, `evaluate` (sync + async/Promise), `callFunctionOn`, `getProperties`, `releaseObject`, `releaseObjectGroup`, `runIfWaitingForDebugger` |
| **Network** | ✅ Implemented | `enable`, `disable` — events: `requestWillBeSent`, `responseReceived`, `loadingFinished`, `loadingFailed` |
| **Console** | ✅ Implemented | `enable`, `disable`, `clearMessages` — event: `messageAdded` |
| **Emulation** | ✅ Implemented | `setUserAgentOverride`; `setDeviceMetricsOverride`, `setEmulatedMedia` (stubs) |
| **Target** | ✅ Implemented | `getTargetInfo`, `createTarget`, `closeTarget`, `attachToTarget`, `setDiscoverTargets`, `setAutoAttach` |
| **Browser** | ✅ Implemented | `getVersion`, `close` |
| **DOM** | ⚠️ Minimal | `enable`, `disable`, `getDocument` |
| **Input** | ✅ Implemented | `dispatchMouseEvent` (click via JS), `dispatchKeyEvent`, `insertText` |
| **CSS** | 🔲 Stub | No-op — present for client compatibility |
| **Log** | 🔲 Stub | No-op — present for client compatibility |
| **Debugger** | 🔲 Stub | No-op — present for client compatibility |
| **Performance** | 🔲 Stub | No-op — present for client compatibility |
| **Security** | 🔲 Stub | No-op — present for client compatibility |
| **ServiceWorker** | 🔲 Stub | No-op — present for client compatibility |

---

## Known Limitations

- **Separate WebSocket port** — The WebSocket server runs on `port+1` (e.g. `9223`), not the same port as the HTTP server. Always connect using `browserURL` (not `browserWSEndpoint`) with Puppeteer to allow auto-discovery.
- **No native multi-tab UI** — Tabs are created and managed exclusively via the CDP `Target` domain.
- **JS-based input simulation** — `Input.dispatchMouseEvent` and `Input.dispatchKeyEvent` are implemented via JavaScript injection, not native Android touch/key events. Behaviour may differ from a real browser.
- **Network request body not captured** — `NetworkInterceptor` re-issues requests via `HttpURLConnection`; POST body capture is not currently supported.
- **ADB forwarding required** — There is no built-in remote access mechanism. Use `adb forward` to expose ports to your host machine (see [ADB Port Forwarding](#adb-port-forwarding)).
- **WebView limitations** — Some advanced CDP features (e.g. full JS debugger, precise DOM inspection) are constrained by what Android's system WebView exposes.

---

## License

This project is licensed under the [MIT License](LICENSE).
