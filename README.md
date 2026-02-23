<!-- File: README.md -->
# DroidHeadless 🤖

A production-ready Android app that runs a headless Chromium-based browser with full Chrome DevTools Protocol (CDP) support. Uses Android's built-in WebView — no custom Chromium build required.

## Features

- ✅ Full CDP server (HTTP + WebSocket) on localhost
- ✅ Compatible with Puppeteer, Playwright, and raw CDP clients
- ✅ Network traffic interception with full request/response capture
- ✅ Screenshots (base64 PNG/JPEG)
- ✅ JavaScript evaluation (sync + async)
- ✅ Cookie management
- ✅ Console message capture
- ✅ User-agent override
- ✅ Auto-start on boot
- ✅ Foreground service with live stats
- ✅ Configurable port

## Build & Install

### Prerequisites
- Android Studio Arctic Fox or newer
- Android SDK 34
- Device or emulator running Android 8.0+ (API 26+)

### Build
```bash
# Clone the repo
git clone https://github.com/imsleepy69x/DroidHeadless.git
cd DroidHeadless

# Build debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug
