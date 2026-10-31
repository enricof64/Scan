# Fork ProGuard/R8 rules.
#
# The app is small and mostly reflection-free, but a few libraries need care.

# PdfBox-Android loads font resources by name and reflects over its
# java.awt-compat shims; keep what the library documents.
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.**

# OpenCV (ScanPipeline): the native lib binds JNI methods by symbol name
# derived from the Java class name (Java_org_opencv_core_...) and resolves
# members like Mat.nativeObj by name. The AAR ships no consumer rules, so
# without keeps R8 renaming breaks every call at runtime.
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# Coroutines debug metadata is safe to strip; silence warnings from
# kotlinx-coroutines internals pulled in transitively.
-dontwarn kotlinx.coroutines.debug.**

# DocAligner (evaluation benchmark): the detector is referenced only from
# instrumentation tests, so R8 would otherwise strip it — and with it the
# ONNX runtime — from the release APK. Keep both so the on-device benchmark
# can run the learned detector.
-keep class io.github.enricof64.scan.DocAligner { *; }
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
