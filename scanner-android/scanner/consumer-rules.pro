# Applied to apps that use the RS Kusum Scanner library.

# OpenCV Java bindings call into JNI by name.
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# TensorFlow Lite JNI
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# Tesseract OCR JNI
-keep class com.googlecode.tesseract.android.** { *; }
-dontwarn com.googlecode.tesseract.android.**
