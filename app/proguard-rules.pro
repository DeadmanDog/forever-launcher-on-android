# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
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




# From Winlator
-dontobfuscate




# Native GetMethodID/FindClass callbacks: preserve reachability under R8.
-keepclassmembers class com.winlator.xconnector.XConnectorEpoll {
    void handleNewConnection(int);
    void handleExistingConnection(int);
    void handleWritable(int);
    void handleConnectionClosed(int);
}
-keepclassmembers class com.winlator.xconnector.ClientSocket { void addAncillaryFd(int); }
-keepclassmembers class com.winlator.renderer.GPUImage { void setStride(short); }
-keep class com.winlator.core.AppUtils { public static java.lang.String getNativeLibDir(android.content.Context); }
-keep class com.winlator.contents.AdrenotoolsManager {
    public <init>(android.content.Context);
    public java.lang.String getLibraryName(java.lang.String);
}

# 0.1.44 R8 build crashed on app open (no logcat). R8 merged the coroutines
# MainDispatcherFactory interface into AndroidDispatcherFactory and renamed the
# ServiceLoader file. Keep the service contract and its Android provider intact.
-keep interface kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keep class kotlinx.coroutines.android.AndroidDispatcherFactory { *; }
-keep class kotlinx.coroutines.CoroutineExceptionHandler { *; }
-keep class kotlinx.coroutines.android.AndroidExceptionPreHandler { *; }

# 0.1.44 and 0.1.47 crashed when bundled components were extracted (device
# crash buffer): NoSuchFieldError "srcPos" in ZstdInputStreamNoFinalizer.
# zstd-jni fields are written only from native code (GetFieldID), so R8
# optimization dropped them; the AAR ships no consumer rules.
-keep class com.github.luben.zstd.** { *; }

# R8 optimization is ON from 1.2.13 (it was disabled since 0.1.44 after the zstd-jni crash,
# which the keep rule above now prevents). RadialMenuCoordinator copies the ViewTree owners
# between views by reflection, so inlining must not remove their accessors.
-keep class androidx.savedstate.ViewTreeSavedStateRegistryOwner { *; }
-keep class androidx.lifecycle.ViewTreeLifecycleOwner { *; }
-keep class androidx.lifecycle.ViewTreeViewModelStoreOwner { *; }
