# Applied to apps that use the RS Kusum Scanner library.

# OpenCV Java bindings call into JNI by name.
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# TensorFlow Lite JNI
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# PDFBox-Android (optional JPX/JBIG2 decoders are not used)
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.**
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
