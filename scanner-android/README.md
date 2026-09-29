# RS Kusum Scanner: an Android document scanner library

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
* **Output:** a compressed PDF (built with PDFBox) and/or one JPEG per page.

## Add it to your app

**1. Repositories.** Add JitPack in `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

**2. Dependency.** In `app/build.gradle.kts`, use a release tag of this repository (for example `scanner-1.0.0`):

```kotlin
dependencies {
    implementation("com.github.newsworldrs:rsappsstudio.github.io:scanner-1.0.0")
}
```

As an alternative, download `RSKusumScanner-library.aar` from the [latest build](https://github.com/newsworldrs/rsappsstudio.github.io/releases/tag/scanner-latest). Put it in `app/libs/` and add the same third-party dependencies that are listed in `scanner/build.gradle.kts`.

**3. Requirements.**
- minSdk 26.
- compileSdk 35.
- Java 17.
- Compose. The library brings its own Compose dependencies, so your app does not have to use Compose.

**4. Open the scanner and get the result.**

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

**What the result contains.**
- The files are saved in your app's private storage under `files/rsscanner/scan_<time>/`.
- The URIs are `content://` URIs, so you can read them with `contentResolver`.
- You can share the URIs with other apps if you add `FLAG_GRANT_READ_URI_PERMISSION`.
- Move or delete the files once you have stored them.

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

**Permissions.**
- The library declares `CAMERA`, and `WRITE_EXTERNAL_STORAGE` for Android 9 and older.
- It asks for the camera permission itself when the scanner opens.
- It uses its own FileProvider (`<applicationId>.rsscanner.fileprovider`), so there is no clash with yours.
- R8/ProGuard rules are included in the library, so you don't need to add any.

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
