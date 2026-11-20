# R8 rules for release builds. Most libraries ship their own (kotlinx.serialization, Compose,
# AndroidX); these cover the rest.

# Apache Commons Compress (reading .7z): only SevenZFile with LZMA/LZMA2 (XZ for Java) and
# Deflate is used. Its other formats' optional dependencies aren't in the app.
-dontwarn org.apache.commons.compress.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
-dontwarn org.osgi.**

# jsoup's optional regex engine.
-dontwarn com.google.re2j.**

# zstd-jni (RVZ GameCube images): its native library calls back into these classes by name.
-keep class com.github.luben.zstd.** { *; }
-dontwarn com.github.luben.zstd.**
