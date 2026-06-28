# ── EP-133 Sample Tool — R8 / ProGuard keep rules ────────────────────────────
#
# Scope is deliberately minimal: keep ONLY what R8 cannot see through —
# reflection (the WebView JS bridge) and native name-based linking (JNI).
# Everything else R8 may shrink and rename freely.
#
# NOTE: the release build currently ships with isMinifyEnabled = false (see
# build.gradle.kts), so these rules are inert today. They are committed now so
# that flipping minify on in a later release is a one-line change with reviewed
# keep-rules already in place.

# ── WebView JavaScript bridge ─────────────────────────────────────────────────
# MIDIBridge is handed to the WebView via addJavascriptInterface(bridge,
# "EP133Bridge") (see webview/EP133WebViewSetup.kt). The web app calls
# window.EP133Bridge.<method>() across the JNI->JS boundary reflectively, so the
# class AND every @JavascriptInterface method name must survive R8.
-keep class com.ep133.sampletool.webview.MIDIBridge { *; }
-keepclassmembers class com.ep133.sampletool.webview.MIDIBridge {
    @android.webkit.JavascriptInterface <methods>;
}
# Safety net for any future @JavascriptInterface-annotated classes.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ── JNI native methods ────────────────────────────────────────────────────────
# (The NativeSynth offline synth was removed with the Chords screen.) Any
# remaining JNI native method must keep its declaring class name so the
# System.loadLibrary lookup resolves it.
-keepclasseswithmembernames class * {
    native <methods>;
}

# ── Jetpack Compose ───────────────────────────────────────────────────────────
# Intentionally NO extra Compose keeps. The AGP-bundled Compose consumer rules
# (shipped inside the Compose artifacts) already cover Composable lambdas, the
# runtime, and tooling. Over-keeping Compose here would defeat shrinking.

# ── kotlinx.coroutines ────────────────────────────────────────────────────────
# coroutines ships its own consumer rules in the .aar (ServiceLoader main
# dispatcher, DebugProbes). We only silence the benign warnings R8 emits about
# its optional/desktop-only internals.
-dontwarn kotlinx.coroutines.**
-dontwarn org.jetbrains.annotations.**

# ── Notes suppression for the JNI-referenced classes we already -keep above ────
-dontnote com.ep133.sampletool.webview.MIDIBridge
