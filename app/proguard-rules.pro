# Aggressive, safe R8 shrinking & optimization for smaller DEX and faster cold start.
-allowaccessmodification
-repackageclasses 'o'
-renamesourcefileattribute SourceFile
-optimizationpasses 5

# Keep WebView JS bridges intact while allowing R8 to shrink & inline the rest of com.aurex.browser.
-keepclassmembers class com.aurex.browser.** {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Strip verbose/debug Android Log calls and Kotlin runtime null-check strings in release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkParameterIsNotNull(java.lang.Object, java.lang.String);
    public static void checkNotNullParameter(java.lang.Object, java.lang.String);
    public static void checkExpressionValueIsNotNull(java.lang.Object, java.lang.String);
    public static void checkNotNullExpressionValue(java.lang.Object, java.lang.String);
    public static void checkReturnedValueIsNotNull(java.lang.Object, java.lang.String);
    public static void checkFieldIsNotNull(java.lang.Object, java.lang.String);
}

# Video engine (yt-dlp / Python / ffmpeg wrapper) is reached by reflection: keep its entry points & mappers.
-keep class com.yausername.youtubedl_android.** { *; }
-keep class com.yausername.youtubedl_common.** { *; }
-keep class com.yausername.ffmpeg.** { *; }
-keep class io.github.junkfood02.** { *; }
-dontwarn com.yausername.**
-dontwarn io.github.junkfood02.**

# SURGICAL Apache Commons Compress rule:
# youtubedl-android ZipUtils ONLY uses ZipFile -> ExtraFieldUtils.register() (ZIP extra fields via reflection).
# Keeping ONLY `archivers.zip.**` lets R8 strip ~500 unused tar/7z/bzip2/xz/zstd/lzma/cpio/brotli classes!
-keep class org.apache.commons.compress.archivers.zip.** { *; }
-keep class * implements org.apache.commons.compress.archivers.zip.ZipExtraField { *; }
-dontwarn org.apache.commons.compress.**
-dontwarn org.apache.commons.io.**
-dontwarn java.nio.file.**
-dontwarn javax.xml.**

# SURGICAL Jackson rule (cuts ~1 MB of unused serializers/modules while keeping ObjectMapper, readTree & mappers 100% safe):
-keep class com.fasterxml.jackson.annotation.** { *; }
-keep class com.fasterxml.jackson.databind.ObjectMapper { *; }
-keep class com.fasterxml.jackson.databind.JsonNode { *; }
-keep class com.fasterxml.jackson.databind.node.** { *; }
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.* <methods>;
    @com.fasterxml.jackson.annotation.* <fields>;
}
-dontwarn com.fasterxml.jackson.**

# Android 16+ Live Updates & OEM property reflection (NotifIsland.kt) — zero R8 warnings on compileSdk 35.
-dontwarn android.os.SystemProperties
-dontwarn android.app.Notification$ProgressStyle
-dontwarn android.app.Notification$ProgressStyle$**
-dontwarn com.google.mlkit.**
-dontwarn androidx.media3.**
