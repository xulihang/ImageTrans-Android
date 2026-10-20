# Keep the classes loaded through JNI by the RapidOCR AAR.
-keep class org.opencv.** { *; }
-keep class ai.onnxruntime.** { *; }
-keep class io.github.hzkitty.** { *; }
-keep class de.lighti.** { *; }

# Javascript interfaces used by the WebView renderer.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
