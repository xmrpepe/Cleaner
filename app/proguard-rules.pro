# Shizuku UserService is instantiated reflectively by Shizuku in a separate process.
-keep class dev.pixelforge.shizuku.ShellService { *; }
-keep class dev.pixelforge.shizuku.IShellService* { *; }
# JNI entry points.
-keepclasseswithmembernames class * { native <methods>; }
-keep class dev.pixelforge.core.nativebridge.NativeLab { *; }
