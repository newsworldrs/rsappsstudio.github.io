# Applied to apps that use the RS Kusum Scanner library.

# OpenCV Java bindings call into JNI by name.
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# Tesseract OCR JNI
-keep class com.googlecode.tesseract.android.** { *; }
-dontwarn com.googlecode.tesseract.android.**

# Scan settings saved for batch recovery store these enum names: keep them readable, so a batch
# saved before an app update still restores. (No blanket keep of com.rskusum.scanner.** is needed:
# apps can let R8 obfuscate the library.)
-keepclassmembers enum com.rskusum.scanner.vision.ScanFilter { <fields>; }
