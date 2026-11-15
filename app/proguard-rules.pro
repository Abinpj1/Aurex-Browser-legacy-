# Keep the whole app package (WebView bridges, reflection-free but safe), shrink only the libraries.
-keep class com.aurex.browser.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# The video engine (yt-dlp / Python / ffmpeg wrapper) is reached by reflection: R8 must not strip or rename it,
# otherwise the engine fails with "could not start on this device (INSTANCE)" in release builds.
-keep class com.yausername.** { *; }
-keep class io.github.junkfood02.** { *; }
-keep class com.junkfood.** { *; }
-dontwarn com.yausername.**
-dontwarn io.github.junkfood02.**
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class **$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class * { *; }
-keep @kotlinx.serialization.Serializable class * { *; }
-dontwarn kotlinx.serialization.**
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.**
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
