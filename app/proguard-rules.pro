# File: app/proguard-rules.pro

# Keep all CDP-related classes (they use reflection for JSON mapping)
-keep class com.sleepy.droidheadless.cdp.** { *; }
-keep class com.sleepy.droidheadless.browser.** { *; }

# Keep NanoHTTPD
-keep class org.nanohttpd.** { *; }
-keepclassmembers class org.nanohttpd.** { *; }

# Keep Java-WebSocket
-keep class org.java_websocket.** { *; }
-keepclassmembers class org.java_websocket.** { *; }

# Keep WebView JavaScript interfaces
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep service and receiver declarations
-keep class com.sleepy.droidheadless.HeadlessBrowserService { *; }
-keep class com.sleepy.droidheadless.BootReceiver { *; }

# Standard Android rules
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-dontwarn org.slf4j.**
