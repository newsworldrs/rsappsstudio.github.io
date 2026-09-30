<div align="center">

# RS Kusum Scanner

**An Android document scanner library: auto capture, precise auto crop, AI Text, and PDF output. It runs 100% on the device.**

[![JitPack](https://jitpack.io/v/newsworldrs/rsappsstudio.github.io.svg)](https://jitpack.io/#newsworldrs/rsappsstudio.github.io)
[![Build](https://github.com/newsworldrs/rsappsstudio.github.io/actions/workflows/scanner-android.yml/badge.svg)](https://github.com/newsworldrs/rsappsstudio.github.io/actions/workflows/scanner-android.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
![Min SDK](https://img.shields.io/badge/minSdk-26-3DDC84)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack_Compose-UI-4285F4?logo=jetpackcompose&logoColor=white)

Made by **RS KUSUM** · [RS Apps Studio](https://www.rsappsstudio.com) · [rskusum@rsappsstudio.com](mailto:rskusum@rsappsstudio.com)

</div>

A complete document scanner for Android apps, and a ready-made scanner app (`com.rskusum.scanner`) built on it.

- **Built with:** Kotlin, Jetpack Compose, CameraX, OpenCV and TensorFlow Lite.
- **No ML Kit:** it doesn't use ML Kit, Google Play services or any cloud service, so it runs fully offline.
- **License:** Apache 2.0, free for commercial and non-commercial apps (see [Licensing](#licensing)).

## Features

* **Automatic capture.** The scanner fires once the document is lined up in the guide frame and held steady. It rejects duplicate pages and empty shots.
* **Precise automatic crop.** On-device edge detection (a learned HED-lite model plus OpenCV) finds the page edges and corrects perspective on the full-resolution photo. Pages come out as A4, ID cards at ID-1 size.
* **Automatic page orientation.** An on-device text-orientation model turns upside-down or sideways pages upright.
* **Modes:**
  * Document.
  * Book: the open book is split at the spine into two separate pages.
  * Book cover.
  * Whiteboard: A4 landscape.
  * ID card: the front and back are placed together on one PDF page.
  * Business card.
* **Editing:**
  * Crop with a magnifier and Auto detect.
  * Rotate.
  * Filters: Auto color, Original, No shadow, Light text, Grayscale, B&W, Whiteboard.
  * Eraser: "marks only" keeps printed text; "everything" wipes the area.
* **Output:** a compressed PDF (built-in PDF writer, no PDF library needed) and/or one JPEG per page.
* **AI Text mode:** the live camera highlights the text lines on the page. Each capture is then read on the device and opens in an AI Text screen showing:
  * the editable text,
  * the detected document type (invoice, receipt, letter, ID, resume, exam paper, …),
  * tappable key details: links, e-mails, phone numbers, dates, amounts, PAN, GSTIN and PIN code.

  The Review screen also has a **Text** tool that does the same for any page.
* **Text recognition (OCR):** on-device Tesseract (LSTM), with 100+ languages including English and Hindi. It doesn't use ML Kit (see [OCR](#text-recognition-ocr)).

## Add it to your app: step by step

### Step 0: Check your project

| Setting | Required |
|---|---|
| `minSdk` | 26 or higher |
| `compileSdk` | 35 or higher |
| Java / Kotlin JVM target | 17 |
| Android Gradle Plugin | 8.5 or newer (the library is built with 8.7.3) |
| Kotlin | 2.0 or newer (only needed for Kotlin apps; Java apps work too) |

Your app **does not** need to use Jetpack Compose. The scanner brings its own screens.

### Step 1: Add the library (choose ONE way)

**Way A: the library's source module.** This is the easiest way to test it and to change the library.

1. Download [RSKusumScanner-project.zip](https://github.com/newsworldrs/rsappsstudio.github.io/releases/download/scanner-latest/RSKusumScanner-project.zip).
2. Copy the `scanner/` folder from it into the root of your project, next to your `app/` folder.
3. In your `settings.gradle.kts`, add:
   ```kotlin
   include(":scanner")
   ```
4. In your root `build.gradle.kts`, add these plugins if they are missing:
   ```kotlin
   plugins {
       id("com.android.library") version "8.7.3" apply false
       id("org.jetbrains.kotlin.android") version "2.0.21" apply false
       id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
       id("org.jetbrains.kotlin.plugin.parcelize") version "2.0.21" apply false
   }
   ```
   Use the same AGP and Kotlin versions your app already uses.
5. In `app/build.gradle.kts`, add:
   ```kotlin
   dependencies { implementation(project(":scanner")) }
   ```

**Way B: the ready-built AAR file.**

1. Download [RSKusumScanner-library.aar](https://github.com/newsworldrs/rsappsstudio.github.io/releases/download/scanner-latest/RSKusumScanner-library.aar).
2. Put it in `app/libs/`.
3. A local AAR does not bring its dependencies with it, so add them yourself in `app/build.gradle.kts`:
   ```kotlin
   dependencies {
       implementation(files("libs/RSKusumScanner-library.aar"))

       implementation(platform("androidx.compose:compose-bom:2024.12.01"))
       implementation("androidx.compose.ui:ui")
       implementation("androidx.compose.ui:ui-graphics")
       implementation("androidx.compose.foundation:foundation")
       implementation("androidx.compose.material3:material3")
       implementation("androidx.compose.material:material-icons-extended")
       implementation("androidx.core:core-ktx:1.15.0")
       implementation("androidx.activity:activity-compose:1.9.3")
       implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
       implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
       implementation("androidx.exifinterface:exifinterface:1.3.7")
       implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
       implementation("org.jetbrains.kotlin:kotlin-parcelize-runtime:2.0.21")
       implementation("androidx.camera:camera-core:1.4.1")
       implementation("androidx.camera:camera-camera2:1.4.1")
       implementation("androidx.camera:camera-lifecycle:1.4.1")
       implementation("androidx.camera:camera-view:1.4.1")
       implementation("org.opencv:opencv:4.12.0")
       implementation("org.tensorflow:tensorflow-lite:2.16.1")
       implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0") // needs maven("https://jitpack.io")
   }
   ```

**Way C: Gradle from JitPack.** Use this for real releases, because updates are just a version change.

1. In `settings.gradle.kts`, add the JitPack repository:
   ```kotlin
   dependencyResolutionManagement {
       repositories {
           google()
           mavenCentral()
           maven("https://jitpack.io")
       }
   }
   ```
2. In `app/build.gradle.kts`, add:
   ```kotlin
   dependencies {
       implementation("com.github.newsworldrs:rsappsstudio.github.io:VERSION")
   }
   ```
   `VERSION` can be:
   - a release tag, such as `scanner-1.0.0`: create it on GitHub under *Releases → Draft a new release*;
   - a commit id, such as `d984e83`;
   - `BRANCH-SNAPSHOT`, with `/` in the branch name written as `~`.

The first time a version is requested, JitPack builds it, which takes a few minutes. You can watch the build on https://jitpack.io/#newsworldrs/rsappsstudio.github.io. With JitPack, all dependencies come along automatically.

### Step 2: Permissions and manifest

Nothing to add:
- The library's manifest is merged into your app's manifest automatically.
- It declares the scanner screen, `CAMERA` (and storage for Android 9 and older only), and its own FileProvider.
- It asks for the camera permission when the scanner opens.

If your app must only install on phones with a camera, add this to your manifest:
`<uses-feature android:name="android.hardware.camera.any" android:required="true" />`.

### Step 3: Open the scanner

In Kotlin with Compose:

```kotlin
val scanner = rememberLauncherForActivityResult(ScanDocument()) { result: ScanResult? ->
    if (result != null) {
        val pdf: Uri? = result.pdfUri        // the finished PDF
        val pages: List<Uri> = result.pageUris // one JPEG per page
    }
}

Button(onClick = {
    scanner.launch(
        ScannerOptions(
            modes = listOf(ScanMode.DOCUMENT, ScanMode.ID_CARD, ScanMode.BOOK),
            initialMode = ScanMode.DOCUMENT,
            pageLimit = 0,            // 0 = unlimited
            galleryImport = true,
            returnPdf = true,
            returnJpegs = true,
            pdfQuality = PdfQuality.BALANCED,
        )
    )
}) { Text("Scan") }
```

In Kotlin with a Fragment or View-based Activity:

```kotlin
private val scanner = registerForActivityResult(ScanDocument()) { result -> /* ... */ }
scanner.launch(ScannerOptions())
```

In Java:

```java
ActivityResultLauncher<ScannerOptions> scanner =
        registerForActivityResult(new ScanDocument(), result -> { /* result may be null */ });
scanner.launch(new ScannerOptions());
```

Imports: `com.rskusum.scanner.ScanDocument`, `com.rskusum.scanner.ScannerOptions`,
`com.rskusum.scanner.ScanResult`, `com.rskusum.scanner.data.ScanMode`,
`com.rskusum.scanner.data.PdfQuality`.

In Java, you can pass only the modes you want:
`new ScannerOptions(Arrays.asList(ScanMode.DOCUMENT, ScanMode.ID_CARD))`.

### Step 4: Use the result

`result` is `null` if the user backed out. Otherwise the user tapped **Done**, and:
- The files are saved in your app's private storage under `files/rsscanner/scan_<time>/`.
- The URIs are `content://` URIs, so you can read them with `contentResolver`.
- You can share the URIs with other apps if you add `FLAG_GRANT_READ_URI_PERMISSION`.
- Move or delete the files once you have stored them.

```kotlin
// Keep the PDF: copy it where your app stores documents (or upload it).
result.pdfUri?.let { uri ->
    context.contentResolver.openInputStream(uri)?.use { input ->
        File(context.filesDir, "invoice.pdf").outputStream().use { input.copyTo(it) }
    }
}

// Open it in a PDF viewer.
result.pdfUri?.let { uri ->
    context.startActivity(
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    )
}

// Show the first page in Compose with Coil: AsyncImage(model = result.pageUris.first(), ...)
```

### Step 5: Test it

Run on a **real phone**, because emulators have no usable camera. Check that:

1. Tapping *Scan* opens the camera and asks for the camera permission once.
2. **Document mode:** hold an A4 page inside the frame. It captures by itself and the crop is tight.
3. **Book mode:** hold the phone sideways across an open book with the spine on the amber line. You get two separate pages.
4. **ID card mode:** front, then back. Both appear on one PDF page.
5. Crop, rotate, filters and eraser work on the review screen.
6. **Done** returns to your app with `pdfUri` / `pageUris` set, and **back** returns `null`.
7. The release build works too (`./gradlew assembleRelease`). The library's R8 rules are applied automatically.

### Step 6: Before you publish your app

- Show the licenses: add the text of [NOTICE](NOTICE) to an "Open-source licenses" screen, or generate that screen with the `com.google.android.gms.oss-licenses-plugin` or `aboutlibraries` Gradle plugin.
- APK size: most of the size is OpenCV and TensorFlow Lite native code. The sample app's arm64-only APK is about 39 MB, and a universal APK with every ABI is much larger. Publish an **App Bundle (AAB)**, or use ABI splits, so each phone downloads only its own ABI.
- Use your own signing key, app name and icon. Don't use the "RS Kusum" name or logo without permission.

### Troubleshooting

| Problem | Fix |
|---|---|
| `Manifest merger failed: minSdk` | Set `minSdk = 26` or higher |
| `requires compileSdk 35` | Set `compileSdk = 35` |
| `Plugin with id 'org.jetbrains.kotlin.plugin.parcelize' not found` (Way A) | Add the plugins from Step 1 to the root `build.gradle.kts` |
| `NoClassDefFoundError: org/opencv/...` (Way B) | You missed a dependency from the Way B list |
| JitPack says "Build failed" | Open the log on jitpack.io; the tag must contain `jitpack.yml` (commit `d984e83` or later) |
| Black preview / "Detector error" on screen | The camera permission was denied, or the phone's ABI is missing (keep arm64-v8a in your ABI filters) |
| Two FileProviders clash | They can't: the library uses its own authority `<applicationId>.rsscanner.fileprovider` |

### Text recognition (OCR)

`RsOcr` reads the text of a scanned page on the device. It uses Tesseract's LSTM engine, not ML Kit, and needs no cloud service.

```kotlin
import com.rskusum.scanner.ocr.RsOcr

// In a coroutine, e.g. lifecycleScope.launch { ... }
val ocr = RsOcr.recognize(context, result.pageUris.first())            // English
val ocr = RsOcr.recognize(context, uri, languages = "eng+hin")          // English + Hindi
ocr.text        // the page text
ocr.confidence  // 0-100
```

**Language models.** Each language needs a `<lang>.traineddata` file.
- The best-quality model is about 12–15 MB per language.
- `OcrModel.FAST` is about 2–4 MB per language.
- The file is downloaded once from the Tesseract project on first use and then kept on the device.
- To warm up at app start, call `RsOcr.prepare(context, "eng")` early, so the first scan doesn't wait for the download.
- To work fully offline, put the files in your app at `app/src/main/assets/tessdata/eng.traineddata`, taken from https://github.com/tesseract-ocr/tessdata_best. They are then used without any download.

Language codes: `eng`, `hin`, `mar`, `ben`, `guj`, `pan`, `tam`, `tel`, `kan`, `mal`, `urd`, `ara`, `fra`, `deu`, `spa`, … To combine languages, join them with `+`.

### AI Text: text back to your app

Open the scanner in AI Text mode and read `result.text` when it returns:

```kotlin
scanner.launch(ScannerOptions(modes = listOf(ScanMode.AI_TEXT), initialMode = ScanMode.AI_TEXT,
                              returnPdf = false, ocrLanguages = "eng"))
// ... in the result callback:
result?.text   // all pages that were read, separated by a blank line
```

In AI Text mode, the user points the camera at a page and the text lines are highlighted live. The page is captured automatically, and the AI Text screen shows:
- the text,
- what kind of document it is,
- key details.

**Done** returns everything to your app. `ScanResult.text` is also filled when the user read pages with the **Text** tool in any other mode.

For key details and the document type on your own text, call `TextInsights.analyze(text)`. It runs offline.

### Your app's language

All text the scanner shows is in `scanner/src/main/res/values/rs_scanner_strings.xml`. Every name starts with `rs_scanner_`. English is included.

To show the scanner in another language, add the same names to your app, for example `app/src/main/res/values-hi/rs_scanner_strings.xml`. Android then picks the language from the phone's settings automatically; there is no language code to set anywhere.

```xml
<resources>
    <string name="rs_scanner_doc_place">पेज को फ्रेम के अंदर रखें</string>
    <!-- … the other rs_scanner_ strings … -->
</resources>
```

To change a wording in English, override the same name in your app's `values/` folder. The "powered by RS Apps Studio" line is part of the scanner and is not a string resource.

### PDF tools in your own app

The library writes its PDFs itself and does not include a PDF library. If your app needs PDF tools (merge, split, protect…), add a PDF library to your app yourself, for example `com.tom-roush:pdfbox-android`. The scanner doesn't depend on it.

### More ways to use it

**Embedding in your own Compose navigation.** Instead of opening a separate Activity, use the `ScannerFlow(options) { result -> }` composable inside `ScannerTheme { }`.

**Advanced API.** The vision engine can be used on its own. Call `RsScanner.init(context)` once, then use:
- `DocumentDetector` for detection, refinement and warping.
- `ImageEnhancer` for the filters.
- `Eraser`.
- `OrientationModel`.
- `EdgeModel`.

### ScannerOptions

| Option | Default | Meaning |
|---|---|---|
| `modes` | all | Scan modes shown in the mode bar, in order |
| `initialMode` | `DOCUMENT` | Mode the camera starts in |
| `pageLimit` | `0` | Maximum number of pages per scan (0 = unlimited) |
| `galleryImport` | `true` | Show the "import from gallery" button |
| `autoCapture` | `true` | Start with automatic capture on |
| `returnPdf` | `true` | Create a PDF |
| `returnJpegs` | `true` | Return every page as a JPEG |
| `pdfQuality` | `BALANCED` | `SMALL` / `BALANCED` / `HIGH` |
| `standalone` | `false` | Full app behaviour: home screen and PDF library, QR scanner, saving to Downloads |


## Project layout

| Path | Purpose |
|---|---|
| `scanner/` | **The library** (Android library module) |
| `scanner/.../RsScanner.kt` | Public API: `ScanDocument`, `ScannerOptions`, `ScanResult`, `RsScanner` |
| `scanner/.../ScannerActivity.kt` | Scanner screen and the `ScannerFlow` composable |
| `scanner/.../vision/` | Detection, warp, enhancement, eraser, TFLite models |
| `scanner/.../camera/` | CameraX analyzer, auto-capture tracker |
| `scanner/.../ui/` | Compose screens: camera, review, crop, erase, home |
| `scanner/src/main/assets/models/` | The two bundled TFLite models and their license notice |
| `app/` | The RS Kusum Scanner app, which is the library in standalone mode |

## Build

You need JDK 17 and the Android SDK (compileSdk 35). The project uses:
- AGP 8.7.3.
- Kotlin 2.0.21.
- Gradle 8.11.1, included as the wrapper.

**Android Studio (Ladybug or newer):**
1. Open the project folder with *File → Open*.
2. Let Gradle sync.
3. Run the `app` configuration.

**Command line:**

```bash
./gradlew assembleDebug                      # app APKs
./gradlew :scanner:assembleRelease           # library AAR: scanner/build/outputs/aar/
./gradlew :scanner:publishToMavenLocal       # library into ~/.m2
```

**Continuous integration.** On every push, `.github/workflows/scanner-android.yml` builds the APKs, the AAR and this project as a zip. It publishes them on the `scanner-latest` release.

**Publishing a library version on JitPack:**
1. Create a GitHub release or tag, for example `scanner-1.0.0`.
2. Open `https://jitpack.io/#newsworldrs/rsappsstudio.github.io` and click *Get it*.

JitPack builds the library with `jitpack.yml`.

## Licensing

The library and the app are licensed under the **Apache License 2.0** ([LICENSE](LICENSE)). You may use them in free and commercial, open and closed-source apps. You must keep the license and [NOTICE](NOTICE) texts, for example on an "Open-source licenses" screen.

Every bundled model and dependency is also under a permissive license (Apache 2.0, BSD, MIT or OFL). None is GPL or LGPL, and neither ML Kit nor Play services is used.

The full audit is in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Author

**RS KUSUM**, Android & web developer ([RS Apps Studio](https://www.rsappsstudio.com))
✉️ [rskusum@rsappsstudio.com](mailto:rskusum@rsappsstudio.com) · Changelog: [../CHANGELOG.md](../CHANGELOG.md) · Contributing: [../CONTRIBUTING.md](../CONTRIBUTING.md)

If this library helps you, please ⭐ the repository and mention *RS Kusum Scanner by RS Apps Studio* in your app's credits.
