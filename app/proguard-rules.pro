# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-keepattributes SourceFile, LineNumberTable

-keepnames class * extends android.app.Activity
-keepnames class * extends androidx.fragment.app.Fragment

-keep class * extends cn.jzvd.** { *; }

-keep class com.google.android.gms.** { *; }
-keep interface com.google.android.gms.** { *; }
-keep class androidx.appcompat.view.** { *; }
-keep class androidx.window.extensions.embedding.** { *; }
-keep class is.xyz.mpv.** { *; }
-keep class lis.xyz.mpv.** { *; }

# PostHog KMP SDK: R8 混淆会破坏其内部反射/序列化调用,
# 导致 release 构建在运行时抛 NoClassDefFoundError/NoSuchMethodError
# (debug 不混淆所以正常)。PostHog 官方要求 keep 整个包。
-keep class com.posthog.** { *; }
-keepclassmembers class com.posthog.** { *; }
-dontwarn com.posthog.**
-keep class com.posthog.**$$serializer { *; }
-keepclasseswithmembers class com.posthog.** {
    kotlinx.serialization.KSerializer serializer(...);
}