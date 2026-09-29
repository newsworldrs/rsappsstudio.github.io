# Licensing and third-party notices

**The RS Kusum Scanner library and app are licensed under the [Apache License 2.0](LICENSE).**
You may use them in free *and* commercial apps (closed source included), modify them, and
redistribute them. You don't pay royalties and you don't have to publish your own source code.
The conditions are:

1. Keep the `LICENSE` and `NOTICE` texts with what you distribute. For an app, list them on an
   "Open-source licenses" screen or in your app's documentation.
2. If you change the library's source files, mark them as changed.
3. Don't use the "RS Kusum" name or logo to promote your product unless you have permission.
   Apache 2.0 does not grant trademark rights.

## Audit of every component

Every component that ships inside an app built with this library is listed below. All of them
are permissive licenses (Apache 2.0, BSD, MIT or SIL OFL). **None is copyleft**, so there is no
GPL, LGPL or AGPL, and nothing requires your app to be open source.

| Component | Version | License | Commercial use | Notes |
|---|---|---|---|---|
| RS Kusum Scanner (this code) | 1.0.0 | Apache 2.0 | Yes | |
| HED-lite edge model (`doc_edges_hed_lite.tflite`) | from SmartCropper (`hed_lite_model_quantize.tflite`) | Apache 2.0 | Yes | © 2017 pqpo |
| Text orientation model (`doc_orientation.tflite`) | RapidOrientation 0.0.11 | Apache 2.0 | Yes | PaddleClas PP-LCNet (Apache 2.0); converted to TFLite. The conversion is recorded in NOTICE, as Apache 2.0 requires. |
| OpenCV (`org.opencv:opencv`) | 4.12.0 | Apache 2.0 | Yes | Apache 2.0 since 4.5.0. Its bundled 3rd-party code (libjpeg-turbo, libpng, libwebp, zlib, OpenJPEG, protobuf, quirc, carotene, …) is BSD/zlib/MIT/IJG-style. |
| TensorFlow Lite (`org.tensorflow:tensorflow-lite`) | 2.16.1 | Apache 2.0 | Yes | Bundles FlatBuffers (Apache 2.0), XNNPACK (BSD), ruy (Apache 2.0). |
| PDFBox-Android (`com.tom-roush:pdfbox-android`) | 2.0.27.0 | Apache 2.0 | Yes | Port of Apache PDFBox. Includes Adobe AFM font metrics (freely redistributable) and Liberation Sans (SIL OFL 1.1, allows commercial embedding). |
| AndroidX: Compose, Material 3, Material Icons, Activity, Lifecycle, Core, ExifInterface | see `scanner/build.gradle.kts` | Apache 2.0 | Yes | |
| CameraX | 1.4.1 | Apache 2.0 | Yes | |
| Kotlin stdlib, kotlinx.coroutines | 2.0.21 / 1.9.0 | Apache 2.0 | Yes | |

**What the library does *not* use:**
- Google ML Kit, Google Play services, and Firebase.
- Any cloud API.
- Any analytics.

Everything runs on the device.

## Design and trademarks

- The scanner's UI and code were written for this project. The app doesn't copy Adobe Scan's
  code, icons, images or fonts. Material Icons are Apache 2.0, and the app icon was drawn for
  this project.
- Don't describe your product using other companies' trademarks (for example "Adobe Scan
  clone") in store listings or marketing.
- The Apache License only covers copyright and patents. Your app's own name, logo and store
  listing are yours to clear.

## Showing the notices in your app

You can use the Gradle plugin `com.google.android.gms.oss-licenses-plugin` or
`com.mikepenz.aboutlibraries` to generate a licenses screen automatically. The simplest
alternative is to show the text of `NOTICE` on an "About / Open-source licenses" screen.

*This page summarises the licenses of the components as published by their authors. It is not
legal advice. For a large commercial release, have your own counsel review it.*
