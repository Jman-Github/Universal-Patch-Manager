-dontobfuscate

# Downloader plugins are loaded with a parent-first class loader and resolve
# Kotlin runtime classes from the host app. Keep the host Kotlin stdlib intact
# so externally compiled plugins do not fail on stripped stdlib methods.
-keep class kotlin.** { *; }

# Keep signing helpers; patcher engines live exclusively in runtime plugins.

-keep class app.revanced.library.ApkSigner** { *; }

# External v22 patch bundles compile against Patcher's relocated mutable
# Dexlib API. These calls are invisible to R8 because the bundles are loaded
# at runtime, so preserve the two relocated API packages they can reference.



# ReVanced engine entry points are retained by their standalone runtime plugins.


-keep class com.android.tools.smali.** { *; }

# The bound ART fallback invokes the same pure tool entry through reflection.
-keep class app.universal.patch.manager.patcher.host.ToolRuntimeEntry { *; }
-keep interface app.universal.patch.manager.patcher.host.ToolRuntimeCallback { *; }

-keep class app.revanced.manager.plugin.downloader.** { *; }
-keep class app.revanced.manager.downloader.** { *; }
-keep class app.universal.patch.manager.plugin.downloader.** { *; }
-keep class app.universal.patch.manager.downloader.** { *; }
-keepnames class com.android.apksig.internal.** { *; }
-keepnames class org.xmlpull.** { *; }

-dontwarn com.google.j2objc.annotations.*
-dontwarn app.revanced.patcher.PatcherResult
-dontwarn app.revanced.patcher.PatcherResult$PatchedDexFile
-dontwarn app.revanced.patcher.PatcherResult$PatchedResources
-dontwarn java.awt.**
-dontwarn javax.**
-dontwarn org.slf4j.**

# JNI entry point adapted from Morphe PR #1202.
-keep class app.universal.patch.manager.patcher.runtime.jvm.EmbeddedJvm { *; }

-keep class app.universal.patch.manager.patcher.runtime.ArtProcessMain { public static void main(java.lang.String[]); }
