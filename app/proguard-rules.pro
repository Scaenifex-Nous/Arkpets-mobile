# =============================================================================
# ArkPets Mobile ProGuard / R8 rules
# =============================================================================

# ---- Application classes ----
-keep class com.arkpets.mobile.** { *; }
-keepclassmembers class com.arkpets.mobile.** { *; }

# ---- libGDX (reflection-based proxies) ----
-keep class com.badlogic.gdx.** { *; }
-keepclassmembers class com.badlogic.gdx.** { *; }
-dontwarn com.badlogic.gdx.**
-dontwarn com.badlogic.gdx.graphics.**

# ---- Spine runtime (native JNI + reflection) ----
-keep class com.esotericsoftware.spine.** { *; }
-keepclassmembers class com.esotericsoftware.spine.** { *; }
-dontwarn com.esotericsoftware.spine.**

# ---- Keep Spine attachment types (loaded via reflection) ----
-keep class com.esotericsoftware.spine.attachments.** { *; }

# ---- Gson (used for potential future JSON feature parity) ----
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**

# ---- Kotlin reflection ----
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata { *; }

# ---- AndroidX ----
-keep class androidx.lifecycle.** { *; }
-dontwarn androidx.lifecycle.**

# ---- Optimization hints ----
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizationpasses 5
-allowaccessmodification
-repackageclasses com.arkpets.mobile.internal

# ---- Remove logging in release ----
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}
