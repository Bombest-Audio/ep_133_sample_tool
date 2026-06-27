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

# ── JNI / Oboe native synth ───────────────────────────────────────────────────
# native_synth.cpp exports JNI functions by mangled name:
#   Java_com_ep133_sampletool_domain_midi_NativeSynth_nativeCreate, etc.
# Resolved by dynamic name lookup after System.loadLibrary("nativesynth"). If R8
# renames the NativeSynth class or its native methods the runtime lookup fails
# with UnsatisfiedLinkError. Keep the class name and its native methods.
-keep class com.ep133.sampletool.domain.midi.NativeSynth {
    native <methods>;
    long nativeCreate(int);
    void nativeNoteOn(long, int, int);
    void nativeNoteOff(long, int);
    void nativeAllNotesOff(long);
    void nativeClose(long);
}
# Generic safety net: any native method anywhere keeps its declaring class name.
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
-dontnote com.ep133.sampletool.domain.midi.NativeSynth
-dontnote com.ep133.sampletool.webview.MIDIBridge
