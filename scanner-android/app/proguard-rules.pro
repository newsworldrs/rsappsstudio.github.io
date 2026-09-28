# OpenCV Java bindings call into JNI by name.
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# TensorFlow Lite JNI
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
