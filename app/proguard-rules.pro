# ══════════════════════════════════════════════════════════════════════════
#  Hermes Android — release ProGuard rules
#  Conservative set tuned for UniFFI / JNA / Compose / wysiwyg reflection.
# ══════════════════════════════════════════════════════════════════════════

# ── UniFFI generated bindings ────────────────────────────────────────────
# The native lib is loaded via JNA Native.register() in each UniffiLib object.
# Every external() function and callback-interface wrapper must survive
# shrinking + obfuscation or the app crashes at first FFI call.

-keep,includedescriptorclasses class org.matrix.rustcomponents.sdk.** { *; }
-keep,includedescriptorclasses class uniffi.** { *; }

# JNA: keep Structure / Callback subclasses and all `external` methods.
-keep class com.sun.jna.** { *; }
-keep class * extends com.sun.jna.Structure { *; }
-keep class * extends com.sun.jna.Callback { *; }
-keepclassmembers class * extends com.sun.jna.Structure {
    public *;
    protected *;
    private *;
}
-keepclassmembers class * extends com.sun.jna.Callback {
    *;
}
-keepclassmembers class * {
    @com.sun.jna.* <fields>;
    @com.sun.jna.* <methods>;
}

# Native.findLibraryName is looked up by name — must not be stripped.
-keep class com.sun.jna.Native {
    *;
}

# ── JNA internal field / method references accessed reflectively ─────────
# Keep the historical-method table JNA uses to resolve K/N & legacy API names.
-keepclassmembers class com.sun.jna.Native {
    *;
}
-dontwarn com.sun.jna.**

# ── matrix-rust-sdk native-dispatcher (libjnidispatch) ──────────────────
# JNA ships its own bundled native; we must not strip its entry points.
-keep class com.sun.jna.ptr.** { *; }

# ── Reflection inside app code ──────────────────────────────────────────
# HtmlText.kt uses reflection against the wysiwyg library to swap an
# internal GestureDetector. Keep the wysiwyg classes it reaches into.
-keep class io.element.android.wysiwyg.view.** { *; }
-keepclassmembers class io.element.android.wysiwyg.view.EditorStyledTextView {
    public *;
    private *;
    protected *;
}

# ── Coil / OkHttp / UniFfi cleanup ──────────────────────────────────────
# OkHttp4 platform — keep only if reflection is used at runtime.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ── wysiwyg compose mangled accessors ───────────────────────────────────
# HtmlText.kt reaches into InlineCodeStyle / CodeBlockStyle / BulletListStyle
# / PillStyle via mangled getter names. Those names are stable only if
# Kotlin's inline-class ABI is preserved exactly.
-keep class io.element.android.wysiwyg.compose.** { *; }
-keepclassmembers class io.element.android.wysiwyg.compose.* {
    *;
}

# ── Compose runtime ─────────────────────────────────────────────────────
# R8 ships built-in Compose rules, but we pin a safety net to be explicit.
-keep class androidx.compose.runtime.** { *; }

# Hilt / KSP generated code
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper
-dontwarn dagger.hilt.**
