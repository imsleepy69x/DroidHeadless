# DroidHeadless Usage Guide

> A practical guide for developers using DroidHeadless — an Android app that runs a headless browser with Chrome DevTools Protocol (CDP) support via the device's built-in WebView.

---

## Table of Contents

1. [Overview](#1-overview)
2. [Installation](#2-installation)
3. [App Setup](#3-app-setup)
4. [ADB Port Forwarding](#4-adb-port-forwarding)
5. [Verifying the Server](#5-verifying-the-server)
6. [Connecting with Puppeteer](#6-connecting-with-puppeteer)
7. [Connecting with Playwright](#7-connecting-with-playwright)
8. [Raw CDP over WebSocket](#8-raw-cdp-over-websocket)
9. [CDP API Reference](#9-cdp-api-reference)
10. [Taking Screenshots](#10-taking-screenshots)
11. [Evaluating JavaScript](#11-evaluating-javascript)
12. [Network Monitoring](#12-network-monitoring)
13. [Multi-tab / Multiple Pages](#13-multi-tab--multiple-pages)
14. [User Agent Override](#14-user-agent-override)
15. [Console Capture](#15-console-capture)
16. [Input Simulation](#16-input-simulation)
17. [Auto-start on Boot](#17-auto-start-on-boot)
18. [Troubleshooting](#18-troubleshooting)
19. [Known Limitations](#19-known-limitations)

---

## 1. Overview

DroidHeadless turns any Android device (Android 8.0 / API 26+) into a headless browser server. It runs a WebView in the background as a foreground service and exposes a **Chrome DevTools Protocol (CDP)** interface, making it compatible with standard browser automation tools like Puppeteer and Playwright.

**Key characteristics:**

| Property | Value |
|---|---|
| Minimum Android version | Android 8.0 (API 26) |
| Browser engine | Device built-in WebView (Chromium-based) |
| HTTP API port | 9222 (default, configurable) |
| WebSocket CDP port | HTTP port + 1 (default: 9223) |
| Service type | Foreground service |
| Boot auto-start | Supported (opt-in) |

**Why two ports?** The HTTP REST API (for listing pages, getting version info, etc.) runs on port 9222, while the actual WebSocket CDP connection runs on port 9223 (always `HTTP port + 1`). This is important when configuring Puppeteer — see [Section 6](#6-connecting-with-puppeteer).

---

## 2. Installation

### Prerequisites

- Android device or emulator running Android 8.0+ (API 26+)
- ADB installed and device connected (`adb devices` should list your device)
- Java/Android SDK for building from source (optional if you have a pre-built APK)

### Build from Source

```bash
# Clone the repository
git clone https://github.com/your-org/DroidHeadless.git
cd DroidHeadless

# Build the debug APK
./gradlew assembleDebug
```

The APK will be output to `app/build/outputs/apk/debug/app-debug.apk`.

### Install the APK

**Option A — Install via ADB manually:**

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

**Option B — Build and install in one step:**

```bash
./gradlew installDebug
```

Both options install the app on the connected Android device. Confirm the installation succeeded with:

```bash
adb shell pm list packages | grep droidheadless
```

---

## 3. App Setup

### First Launch

1. Open the **DroidHeadless** app on your Android device.
2. Tap the **Start** button to launch the headless browser service.
3. A persistent foreground notification will appear confirming the service is running.

> **Android 13+ note:** You must grant the notification permission when prompted, or the foreground service will not display its notification (though it may still run).

### Settings

Open the Settings screen within the app to configure:

| Setting | Description | Default |
|---|---|---|
| **Port** | HTTP API port (1024–65534). WebSocket is always this port + 1. | 9222 |
| **Auto-start on Boot** | Automatically start the headless service when the device boots. | Off |

**Changing the port:**

1. Open Settings in the app.
2. Enter a new port number (must be between 1024 and 65534).
3. Save the setting.
4. **Restart the service** (tap Stop, then Start) for the new port to take effect.
5. Re-run `adb forward` with the new port numbers.

---

## 4. ADB Port Forwarding

Since the app runs on your Android device, you need to forward the device's ports to your development machine using ADB. This must be done each time you reconnect your device (or after a reboot, unless you script it).

```bash
# Forward HTTP API port
adb forward tcp:9222 tcp:9222

# Forward WebSocket CDP port
adb forward tcp:9223 tcp:9223
```

After forwarding, both ports are accessible on `127.0.0.1` (localhost) from your development machine.

**If you changed the default port**, forward your custom port and port+1:

```bash
# Example: custom port 9000
adb forward tcp:9000 tcp:9000
adb forward tcp:9001 tcp:9001
```

**List active port forwards:**

```bash
adb forward --list
```

**Remove all forwards (cleanup):**

```bash
adb forward --remove-all
```

> **Multiple devices:** If you have multiple ADB devices connected, specify the device serial: `adb -s <serial> forward tcp:9222 tcp:9222`

---

## 5. Verifying the Server

After starting the service and setting up port forwarding, verify everything is working:

**Check the HTTP API:**

```bash
curl http://127.0.0.1:9222/json/version
```

Expected response:

```json
{
  "Browser": "DroidHeadless/1.0",
  "Protocol-Version": "1.3",
  "webSocketDebuggerUrl": "ws://127.0.0.1:9223/devtools/browser"
}
```

**List active pages:**

```bash
curl http://127.0.0.1:9222/json/list
```

**View the HTML status page:**

```bash
curl http://127.0.0.1:9222/
# Or open http://127.0.0.1:9222 in your browser
```

**Create a new page:**

```bash
curl -X PUT "http://127.0.0.1:9222/json/new?url=https://example.com"
```

If `curl` returns a valid JSON response, your setup is complete and ready for automation.

---

## 6. Connecting with Puppeteer

### Important: Use `browserURL`, Not `browserWSEndpoint`

Because DroidHeadless uses **two separate ports** (HTTP on 9222, WebSocket on 9223), you **must** use `browserURL` instead of `browserWSEndpoint`. Using `browserURL` causes Puppeteer to:

1. Call `GET /json/version` on port 9222
2. Read the `webSocketDebuggerUrl` from the response (which points to port 9223)
3. Connect to the WebSocket on port 9223

Using `browserWSEndpoint` pointing to port 9222 directly will fail.

### Installation

```bash
pnpm add puppeteer-core
# or
npm install puppeteer-core
```

### Basic Connection

```js
const puppeteer = require('puppeteer-core');

async function main() {
  // Connect using browserURL — Puppeteer fetches /json/version to get the WS URL
  const browser = await puppeteer.connect({
    browserURL: 'http://127.0.0.1:9222',
  });

  console.log('Connected to DroidHeadless');

  const page = await browser.newPage();
  await page.goto('https://example.com');

  const title = await page.title();
  console.log('Page title:', title);

  await browser.disconnect();
}

main().catch(console.error);
```

### Full Working Example

```js
const puppeteer = require('puppeteer-core');

async function automateWithPuppeteer() {
  const browser = await puppeteer.connect({
    browserURL: 'http://127.0.0.1:9222',
  });

  try {
    const page = await browser.newPage();

    // Navigate and wait for page load
    await page.goto('https://example.com', { waitUntil: 'networkidle0' });

    // Get page title
    const title = await page.title();
    console.log('Title:', title);

    // Evaluate JavaScript on the page
    const bodyText = await page.evaluate(() => document.body.innerText);
    console.log('Body text:', bodyText.substring(0, 200));

    // Take a screenshot
    await page.screenshot({ path: 'screenshot.png' });
    console.log('Screenshot saved to screenshot.png');

    // Close the page
    await page.close();
  } finally {
    await browser.disconnect();
  }
}

automateWithPuppeteer().catch(console.error);
```

### Getting All Open Pages

```js
const browser = await puppeteer.connect({ browserURL: 'http://127.0.0.1:9222' });
const pages = await browser.pages();
console.log(`Open pages: ${pages.length}`);
for (const page of pages) {
  console.log(' -', await page.url());
}
await browser.disconnect();
```

---

## 7. Connecting with Playwright

### Installation

```bash
pnpm add playwright
# or
npm install playwright
```

### Basic Connection

```js
const { chromium } = require('playwright');

async function main() {
  // connectOverCDP takes the HTTP URL — Playwright fetches /json/version internally
  const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');

  console.log('Connected to DroidHeadless via Playwright');

  const context = browser.contexts()[0] || await browser.newContext();
  const page = await context.newPage();

  await page.goto('https://example.com');
  console.log('Title:', await page.title());

  await page.screenshot({ path: 'screenshot.png' });
  console.log('Screenshot saved');

  await browser.close();
}

main().catch(console.error);
```

### Full Working Example

```js
const { chromium } = require('playwright');

async function automateWithPlaywright() {
  const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');

  try {
    const context = browser.contexts()[0] || await browser.newContext();
    const page = await context.newPage();

    // Navigate
    await page.goto('https://news.ycombinator.com', { waitUntil: 'domcontentloaded' });

    // Extract data
    const headlines = await page.$$eval('.titleline > a', (els) =>
      els.slice(0, 5).map((el) => ({ text: el.innerText, href: el.href }))
    );

    console.log('Top HN headlines:');
    headlines.forEach((h, i) => console.log(`${i + 1}. ${h.text}`));

    await page.screenshot({ path: 'hn.png' });
  } finally {
    await browser.close();
  }
}

automateWithPlaywright().catch(console.error);
```

---

## 8. Raw CDP over WebSocket

For lower-level control or when not using Puppeteer/Playwright, you can connect directly over WebSocket using the CDP JSON-RPC protocol.

### How CDP Works

- Every message is a JSON object with an `id`, `method`, and optional `params`
- Responses have the same `id` plus a `result` or `error` field
- Events are asynchronous messages with a `method` and `params` but no `id`

### Installation

```bash
pnpm add ws
```

### Full Raw CDP Example

```js
const WebSocket = require('ws');
const http = require('http');

async function getPageList() {
  return new Promise((resolve, reject) => {
    http.get('http://127.0.0.1:9222/json/list', (res) => {
      let data = '';
      res.on('data', (chunk) => (data += chunk));
      res.on('end', () => resolve(JSON.parse(data)));
    }).on('error', reject);
  });
}

async function main() {
  // 1. Get the list of available pages
  const pages = await getPageList();
  if (pages.length === 0) {
    // Create a new page if none exist
    await new Promise((resolve, reject) => {
      http.get('http://127.0.0.1:9222/json/new?url=about:blank', (res) => {
        let d = '';
        res.on('data', (c) => (d += c));
        res.on('end', resolve);
      }).on('error', reject);
    });
    pages.push(...(await getPageList()));
  }

  const pageId = pages[0].id;
  console.log('Connecting to page:', pageId);

  // 2. Connect to the WebSocket CDP endpoint
  const ws = new WebSocket(`ws://127.0.0.1:9223/devtools/page/${pageId}`);
  let msgId = 1;
  const pending = new Map();

  function send(method, params = {}) {
    return new Promise((resolve) => {
      const id = msgId++;
      pending.set(id, resolve);
      ws.send(JSON.stringify({ id, method, params }));
    });
  }

  ws.on('message', (data) => {
    const msg = JSON.parse(data);
    if (msg.id && pending.has(msg.id)) {
      pending.get(msg.id)(msg.result);
      pending.delete(msg.id);
    } else if (msg.method) {
      // Async event
      console.log('Event:', msg.method, JSON.stringify(msg.params).substring(0, 100));
    }
  });

  await new Promise((resolve) => ws.on('open', resolve));
  console.log('WebSocket connected');

  // 3. Enable Page events
  await send('Page.enable');

  // 4. Navigate to a URL
  console.log('Navigating...');
  await send('Page.navigate', { url: 'https://example.com' });

  // Wait for page load
  await new Promise((resolve) => setTimeout(resolve, 2000));

  // 5. Evaluate JavaScript
  const result = await send('Runtime.evaluate', {
    expression: 'document.title',
    returnByValue: true,
  });
  console.log('Page title:', result.result.value);

  // 6. Take a screenshot
  const screenshot = await send('Page.captureScreenshot', { format: 'png' });
  const fs = require('fs');
  fs.writeFileSync('screenshot.png', Buffer.from(screenshot.data, 'base64'));
  console.log('Screenshot saved to screenshot.png');

  ws.close();
}

main().catch(console.error);
```

---

## 9. CDP API Reference

All communication uses the Chrome DevTools Protocol JSON-RPC format. Messages sent to the WebSocket look like:

```json
{ "id": 1, "method": "Domain.method", "params": { "key": "value" } }
```

Responses:

```json
{ "id": 1, "result": { ... } }
```

Events (no `id`):

```json
{ "method": "Domain.eventName", "params": { ... } }
```

---

### Page Domain

| Method | Parameters | Description |
|---|---|---|
| `Page.enable` | — | Enable Page domain events |
| `Page.disable` | — | Disable Page domain events |
| `Page.navigate` | `url` (string), `referrer` (string, optional) | Navigate to a URL |
| `Page.reload` | `ignoreCache` (boolean) | Reload the current page |
| `Page.captureScreenshot` | `format` (`png`/`jpeg`), `quality` (0–100, jpeg only) | Capture a screenshot; returns `data` as base64 |
| `Page.getFrameTree` | — | Get the frame tree structure |
| `Page.getNavigationHistory` | — | Get navigation history entries |
| `Page.stopLoading` | — | Stop page load |
| `Page.addScriptToEvaluateOnNewDocument` | `source` (string) | Inject script on each new document (stub — limited support) |
| `Page.createIsolatedWorld` | `frameId` (string) | Create an isolated JS world |
| `Page.setLifecycleEventsEnabled` | `enabled` (boolean) | Enable/disable lifecycle events |

**Page Events:**

| Event | Description |
|---|---|
| `Page.frameStartedLoading` | Frame began loading |
| `Page.frameNavigated` | Frame navigated to a new URL |
| `Page.domContentEventFired` | DOMContentLoaded fired |
| `Page.loadEventFired` | Load event fired |
| `Page.frameStoppedLoading` | Frame finished loading |

---

### Runtime Domain

| Method | Parameters | Description |
|---|---|---|
| `Runtime.enable` | — | Enable Runtime domain events |
| `Runtime.evaluate` | `expression` (string), `awaitPromise` (boolean), `returnByValue` (boolean) | Evaluate JavaScript expression; returns `RemoteObject` |
| `Runtime.callFunctionOn` | `functionDeclaration`, `objectId`, `arguments`, `returnByValue` | Call a function on a remote object |
| `Runtime.getProperties` | `objectId` (string) | Get properties of a remote object |
| `Runtime.releaseObject` | `objectId` (string) | Release a remote object reference |

**Runtime Events:**

| Event | Description |
|---|---|
| `Runtime.executionContextCreated` | A new execution context was created |
| `Runtime.consoleAPICalled` | A console API was called (log, warn, error, etc.) |

---

### Network Domain

| Method | Parameters | Description |
|---|---|---|
| `Network.enable` | — | Enable network monitoring |
| `Network.disable` | — | Disable network monitoring |

**Network Events:**

| Event | Key Params | Description |
|---|---|---|
| `Network.requestWillBeSent` | `requestId`, `request.url`, `request.method` | A request is about to be sent |
| `Network.responseReceived` | `requestId`, `response.url`, `response.status` | A response was received |
| `Network.loadingFinished` | `requestId`, `encodedDataLength` | Request completed |
| `Network.loadingFailed` | `requestId`, `errorText` | Request failed |

> **Note:** Request body capture is not supported. Only GET-style re-issued requests are available.

---

### Console Domain

| Method | Parameters | Description |
|---|---|---|
| `Console.enable` | — | Enable console message collection |
| `Console.disable` | — | Disable console message collection |
| `Console.clearMessages` | — | Clear all collected console messages |

**Console Events:**

| Event | Key Params | Description |
|---|---|---|
| `Console.messageAdded` | `source`, `level`, `text`, `line`, `url` | A console message was added |

---

### Emulation Domain

| Method | Parameters | Description |
|---|---|---|
| `Emulation.setUserAgentOverride` | `userAgent` (string) | Override the User-Agent string |
| `Emulation.setDeviceMetricsOverride` | `width`, `height`, `deviceScaleFactor`, `mobile` | Set device metrics (stub implementation) |

---

### Target Domain

| Method | Parameters | Description |
|---|---|---|
| `Target.createTarget` | `url` (string) | Create a new page/target |
| `Target.closeTarget` | `targetId` (string) | Close a target/page |
| `Target.getTargetInfo` | `targetId` (string) | Get info about a specific target |
| `Target.attachToTarget` | `targetId` (string) | Attach to a target |

---

### Input Domain

| Method | Parameters | Description |
|---|---|---|
| `Input.dispatchMouseEvent` | `type` (`mousePressed`/`mouseReleased`), `x`, `y` | Simulate a mouse event (JS-simulated, not native) |
| `Input.dispatchKeyEvent` | `text` (string) | Dispatch a key event |
| `Input.insertText` | `text` (string) | Insert text at current focus |

> **Note:** Input events are JavaScript-simulated, not native Android touch/key events.

---

### DOM Domain

| Method | Parameters | Description |
|---|---|---|
| `DOM.getDocument` | — | Get the root DOM node (minimal: `nodeId`, `backendNodeId`, `documentURL`) |

---

### Browser Domain

| Method | Parameters | Description |
|---|---|---|
| `Browser.getVersion` | — | Get browser version information |

---

### HTTP API Endpoints

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/` | HTML status page |
| `GET` | `/json/version` | Browser version and WebSocket debugger URL |
| `GET` | `/json/list` | Array of all open page objects |
| `GET` | `/json` or `/json/` | Same as `/json/list` |
| `PUT` | `/json/new?url=<url>` | Create a new page; returns page object |
| `GET` | `/json/activate/<id>` | Activate a page (no-op for headless) |
| `GET` | `/json/close/<id>` | Close a page |
| `GET` | `/json/protocol` | CDP protocol descriptor |

**Page object structure** (returned by `/json/list` and `/json/new`):

```json
{
  "id": "abc123",
  "title": "Example Domain",
  "url": "https://example.com",
  "type": "page",
  "webSocketDebuggerUrl": "ws://127.0.0.1:9223/devtools/page/abc123"
}
```

---

## 10. Taking Screenshots

### Via Puppeteer

```js
const puppeteer = require('puppeteer-core');

const browser = await puppeteer.connect({ browserURL: 'http://127.0.0.1:9222' });
const page = await browser.newPage();

await page.goto('https://example.com', { waitUntil: 'networkidle0' });
await page.screenshot({ path: 'screenshot.png' });

// JPEG with quality setting
await page.screenshot({ path: 'screenshot.jpg', type: 'jpeg', quality: 80 });

// Full-page screenshot
await page.screenshot({ path: 'fullpage.png', fullPage: true });

await browser.disconnect();
```

### Via Raw CDP

```js
const screenshot = await send('Page.captureScreenshot', {
  format: 'png', // or 'jpeg'
  quality: 90,   // only applies to jpeg
});

const fs = require('fs');
fs.writeFileSync('screenshot.png', Buffer.from(screenshot.data, 'base64'));
```

### Troubleshooting Black Screenshots

If your screenshot is all black, the page has not finished rendering. Solutions:

```js
// Option 1: Wait for network idle
await page.goto(url, { waitUntil: 'networkidle0' });

// Option 2: Wait for load event
await page.goto(url, { waitUntil: 'load' });

// Option 3: Add explicit delay after navigation
await page.goto(url);
await new Promise(resolve => setTimeout(resolve, 2000));
await page.screenshot({ path: 'screenshot.png' });

// Option 4 (raw CDP): Listen for Page.loadEventFired before screenshotting
```

---

## 11. Evaluating JavaScript

### Via Puppeteer

```js
const browser = await puppeteer.connect({ browserURL: 'http://127.0.0.1:9222' });
const page = await browser.newPage();
await page.goto('https://example.com');

// Evaluate an expression and return a value
const title = await page.evaluate(() => document.title);
console.log('Title:', title);

// Pass arguments into the page context
const text = await page.evaluate((selector) => {
  return document.querySelector(selector)?.innerText;
}, 'h1');
console.log('H1 text:', text);

// Complex return values
const links = await page.evaluate(() => {
  return Array.from(document.querySelectorAll('a')).map(a => ({
    text: a.innerText,
    href: a.href,
  }));
});
console.log('Links:', links);

await browser.disconnect();
```

### Via Raw CDP (Runtime.evaluate)

```js
// Simple value (returnByValue: true returns the JS value directly)
const result = await send('Runtime.evaluate', {
  expression: 'document.title',
  returnByValue: true,
});
console.log('Title:', result.result.value);

// Await a Promise
const asyncResult = await send('Runtime.evaluate', {
  expression: 'fetch("https://api.example.com/data").then(r => r.text())',
  awaitPromise: true,
  returnByValue: true,
});
console.log('Fetched data:', asyncResult.result.value);

// RemoteObject (for objects — use objectId for further inspection)
const objResult = await send('Runtime.evaluate', {
  expression: '({ a: 1, b: 2 })',
  returnByValue: false, // returns RemoteObject with objectId
});
console.log('Object type:', objResult.result.type);

// Get properties of a remote object
const props = await send('Runtime.getProperties', {
  objectId: objResult.result.objectId,
});
console.log('Properties:', props.result.map(p => p.name));
```

---

## 12. Network Monitoring

Enable the Network domain to receive events for all HTTP requests made by the page.

### Via Puppeteer

```js
const browser = await puppeteer.connect({ browserURL: 'http://127.0.0.1:9222' });
const page = await browser.newPage();

// Listen for requests
page.on('request', (req) => {
  console.log('→ Request:', req.method(), req.url());
});

// Listen for responses
page.on('response', (res) => {
  console.log('← Response:', res.status(), res.url());
});

// Listen for failed requests
page.on('requestfailed', (req) => {
  console.log('✗ Failed:', req.url(), req.failure()?.errorText);
});

await page.goto('https://example.com');
await browser.disconnect();
```

### Via Raw CDP

```js
// Enable network monitoring
await send('Network.enable');

// Handle events in the message handler
ws.on('message', (data) => {
  const msg = JSON.parse(data);
  if (!msg.method) return;

  switch (msg.method) {
    case 'Network.requestWillBeSent':
      console.log('→', msg.params.request.method, msg.params.request.url);
      break;
    case 'Network.responseReceived':
      console.log('←', msg.params.response.status, msg.params.response.url);
      break;
    case 'Network.loadingFinished':
      console.log('✓ Done:', msg.params.requestId, msg.params.encodedDataLength, 'bytes');
      break;
    case 'Network.loadingFailed':
      console.log('✗ Failed:', msg.params.requestId, msg.params.errorText);
      break;
  }
});

await send('Page.navigate', { url: 'https://example.com' });
```

---

## 13. Multi-tab / Multiple Pages

DroidHeadless supports multiple simultaneous pages (tabs) via the Target domain and the HTTP API.

### Creating and Managing Pages via HTTP API

```bash
# Create a new page
curl -X PUT "http://127.0.0.1:9222/json/new?url=https://example.com"

# List all pages
curl http://127.0.0.1:9222/json/list

# Close a page by ID
curl http://127.0.0.1:9222/json/close/<id>
```

### Creating Pages via Puppeteer

```js
const browser = await puppeteer.connect({ browserURL: 'http://127.0.0.1:9222' });

// Open multiple pages
const page1 = await browser.newPage();
const page2 = await browser.newPage();

await page1.goto('https://example.com');
await page2.goto('https://github.com');

const [title1, title2] = await Promise.all([page1.title(), page2.title()]);
console.log('Page 1:', title1);
console.log('Page 2:', title2);

await Promise.all([page1.close(), page2.close()]);
await browser.disconnect();
```

### Using the Target Domain via Raw CDP

```js
// Create a new target
const { targetId } = await send('Target.createTarget', {
  url: 'https://example.com',
});
console.log('New target ID:', targetId);

// Get info about a target
const { targetInfo } = await send('Target.getTargetInfo', { targetId });
console.log('Target URL:', targetInfo.url);

// Close a target
await send('Target.closeTarget', { targetId });
```

> **Note:** There is no persistent UI for tabs in headless mode. Tabs exist only programmatically.

---

## 14. User Agent Override

Override the User-Agent string sent with requests. Useful for testing mobile sites or bypassing UA-based detection.

### Via Puppeteer

```js
const browser = await puppeteer.connect({ browserURL: 'http://127.0.0.1:9222' });
const page = await browser.newPage();

// Set a custom User-Agent
await page.setUserAgent(
  'Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) ' +
  'AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1'
);

await page.goto('https://example.com');
console.log('Navigated with mobile UA');
await browser.disconnect();
```

### Via Raw CDP

```js
await send('Emulation.setUserAgentOverride', {
  userAgent: 'MyCustomBot/1.0 (+https://mybot.example.com)',
});

await send('Page.navigate', { url: 'https://httpbin.org/user-agent' });
await new Promise(resolve => setTimeout(resolve, 1500));

const result = await send('Runtime.evaluate', {
  expression: 'document.body.innerText',
  returnByValue: true,
});
console.log('Server saw UA:', result.result.value);
```

---

## 15. Console Capture

Capture `console.log`, `console.warn`, `console.error`, and other console output from the page.

### Via Puppeteer

```js
const browser = await puppeteer.connect({ browserURL: 'http://127.0.0.1:9222' });
const page = await browser.newPage();

// Listen for console messages
page.on('console', (msg) => {
  const type = msg.type(); // log, warn, error, info, debug, etc.
  const text = msg.text();
  console.log(`[page:${type}] ${text}`);
});

await page.goto('https://example.com');

// Trigger console output
await page.evaluate(() => {
  console.log('Hello from page!');
  console.warn('This is a warning');
  console.error('This is an error');
});

await browser.disconnect();
```

### Via Raw CDP

```js
// Enable Console domain
await send('Console.enable');

// Handle Console.messageAdded events
ws.on('message', (data) => {
  const msg = JSON.parse(data);
  if (msg.method === 'Console.messageAdded') {
    const { source, level, text, line, url } = msg.params.message;
    console.log(`[${level}] ${text} (${url}:${line})`);
  }
});

// Or use Runtime domain for richer console events
await send('Runtime.enable');
ws.on('message', (data) => {
  const msg = JSON.parse(data);
  if (msg.method === 'Runtime.consoleAPICalled') {
    const { type, args } = msg.params;
    const values = args.map(a => a.value ?? a.description ?? a.type).join(' ');
    console.log(`[runtime:${type}] ${values}`);
  }
});
```

---

## 16. Input Simulation

Simulate mouse clicks and keyboard input. Note that these are JavaScript-simulated events, not native Android touch events.

### Mouse Click via Raw CDP

```js
// Simulate a click at coordinates (x=100, y=200)
await send('Input.dispatchMouseEvent', {
  type: 'mousePressed',
  x: 100,
  y: 200,
  button: 'left',
  clickCount: 1,
});
await send('Input.dispatchMouseEvent', {
  type: 'mouseReleased',
  x: 100,
  y: 200,
  button: 'left',
  clickCount: 1,
});
```

### Keyboard Input via Raw CDP

```js
// Type text character by character
await send('Input.dispatchKeyEvent', { type: 'keyDown', text: 'H' });
await send('Input.dispatchKeyEvent', { type: 'keyUp', text: 'H' });

// Or insert text directly
await send('Input.insertText', { text: 'Hello, World!' });
```

### Clicking Elements via Puppeteer (Recommended)

For most use cases, use Puppeteer's higher-level click APIs instead:

```js
const browser = await puppeteer.connect({ browserURL: 'http://127.0.0.1:9222' });
const page = await browser.newPage();
await page.goto('https://example.com');

// Click a link by selector
await page.click('a');

// Type into an input field
await page.focus('input[type="search"]');
await page.keyboard.type('search query');
await page.keyboard.press('Enter');

await browser.disconnect();
```

> **Limitation:** Because input events are JS-simulated (not native), some apps relying on native Android input events may not respond correctly.

---

## 17. Auto-start on Boot

Enable auto-start so DroidHeadless automatically starts its service when the device boots, without any manual interaction.

### Enabling Auto-start

1. Open the **DroidHeadless** app on your device.
2. Navigate to **Settings**.
3. Toggle **Auto-start on Boot** to ON.
4. The service will start automatically on the next reboot.

### Requirements

- The `RECEIVE_BOOT_COMPLETED` permission must be granted to the app.
- On some Android versions/OEMs, battery optimization or restricted background processes may prevent boot receivers from firing. Add DroidHeadless to the battery optimization exemption list if needed.

### Troubleshooting Auto-start

If auto-start is not working after enabling:

1. Verify the `RECEIVE_BOOT_COMPLETED` permission is granted:
   ```bash
   adb shell dumpsys package com.yourorg.droidheadless | grep BOOT
   ```
2. Toggle the setting off and back on.
3. Check your device's battery optimization settings — exempt DroidHeadless from optimization.
4. Some OEM launchers (Xiaomi, Huawei, etc.) have aggressive app-kill policies; add the app to the "auto-start" whitelist in your device's security/battery settings.

### Port Forwarding After Reboot

Remember that ADB port forwarding does **not** persist across device reboots. After a reboot, re-run:

```bash
adb forward tcp:9222 tcp:9222
adb forward tcp:9223 tcp:9223
```

You can automate this with a script:

```bash
#!/bin/bash
# wait-and-forward.sh
echo "Waiting for device..."
adb wait-for-device
echo "Device connected. Setting up port forwarding..."
adb forward tcp:9222 tcp:9222
adb forward tcp:9223 tcp:9223
echo "Ready! DroidHeadless accessible at http://127.0.0.1:9222"
```

---

## 18. Troubleshooting

### Connection Refused

**Symptom:** `curl: (7) Failed to connect to 127.0.0.1 port 9222: Connection refused`

**Causes and fixes:**

1. **Port forwarding not set up:**
   ```bash
   adb forward --list
   # If empty, run:
   adb forward tcp:9222 tcp:9222
   adb forward tcp:9223 tcp:9223
   ```

2. **Service not running:** Open the DroidHeadless app and tap **Start**.

3. **Wrong port:** Check what port is configured in Settings. If you changed it, forward that port instead.

4. **Device not connected:** Verify with `adb devices`.

---

### No Pages Available / WebSocket 404

**Symptom:** WebSocket connection fails with 404 or "no pages available"

**Fix:** The WebSocket path must match a valid page ID from `/json/list`.

```bash
# Get current page IDs
curl http://127.0.0.1:9222/json/list

# Create a page if none exist
curl -X PUT "http://127.0.0.1:9222/json/new?url=about:blank"
```

---

### Screenshot is Black

**Symptom:** `Page.captureScreenshot` or `page.screenshot()` returns a black image.

**Fix:** The page has not finished rendering. Wait for load:

```js
await page.goto(url, { waitUntil: 'networkidle0' });
// or add a delay:
await new Promise(resolve => setTimeout(resolve, 2000));
```

---

### Auto-start Not Working

See [Section 17](#17-auto-start-on-boot) for detailed steps.

Quick checklist:
- [ ] Permission `RECEIVE_BOOT_COMPLETED` granted
- [ ] Toggle off and on in Settings
- [ ] Device not killed the app in background (add to battery whitelist)
- [ ] OEM auto-start whitelist (Xiaomi MIUI, Huawei EMUI, etc.)

---

### Port Already in Use

**Symptom:** Service fails to start; logs show "Address already in use"

**Fix:** Change the port in Settings (e.g., 9224), restart the service, and update your `adb forward` commands.

---

### Android 13+ Notification Permission

**Symptom:** No foreground notification visible; service may appear not to start.

**Fix:** Go to Android Settings → Apps → DroidHeadless → Notifications → Allow.

Or grant via ADB:

```bash
adb shell pm grant com.yourorg.droidheadless android.permission.POST_NOTIFICATIONS
```

---

### Puppeteer Connection Fails with `browserWSEndpoint`

**Symptom:** `puppeteer.connect({ browserWSEndpoint: 'ws://127.0.0.1:9222/...' })` fails.

**Fix:** Use `browserURL` instead:

```js
// WRONG
await puppeteer.connect({ browserWSEndpoint: 'ws://127.0.0.1:9222' });

// CORRECT
await puppeteer.connect({ browserURL: 'http://127.0.0.1:9222' });
```

---

## 19. Known Limitations

| Limitation | Details |
|---|---|
| **Dual ports** | HTTP API and WebSocket CDP run on separate ports (HTTP port and HTTP port + 1). Always use `browserURL` with Puppeteer. |
| **JS-simulated input** | Mouse and keyboard events are JavaScript-simulated, not native Android input events. Some apps may not respond. |
| **No request body capture** | The Network domain cannot capture POST request bodies. |
| **Headless only** | No visible rendering on the device screen; everything is off-screen in the WebView. |
| **`addScriptToEvaluateOnNewDocument` stub** | Returns a stub identifier and has limited implementation. Script injection may not work reliably across all navigations. |
| **Multi-tab, no persistent UI** | Multiple pages (tabs) are supported via the Target domain and HTTP API, but there is no visible tab UI on the device. |
| **`setDeviceMetricsOverride` stub** | The Emulation domain's device metrics override is a stub — it does not actually change the viewport. |
| **DOM domain minimal** | `DOM.getDocument` returns only minimal node info (`nodeId`, `backendNodeId`, `documentURL`). |
| **ADB forwarding required** | You must re-run `adb forward` after every device reconnect or reboot. |
| **Boot auto-start reliability** | Auto-start depends on Android's boot completed broadcast, which some OEM ROMs may delay or suppress. |
| **WebView version** | Browser capabilities depend on the installed WebView version on the device. Older Android versions may have older WebView/Chromium versions. |
