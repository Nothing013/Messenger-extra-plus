# Keep the module entry class reachable and rewrite java_init.list if R8
# renames it.
-dontwarn io.github.libxposed.**
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
-adaptresourcefilecontents META-INF/xposed/java_init.list

# DexKit is JNI-backed; its native bridge looks classes up by name.
-keep class org.luckypray.dexkit.** { *; }
-keep class com.google.flatbuffers.** { *; }
-dontwarn org.luckypray.dexkit.**

# The library's own consumer rules already cover the service, but keep the
# provider explicit so manifest merging never strips it.
-keep class io.github.libxposed.service.** { *; }
