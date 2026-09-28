# RS Kusum Scanner (`com.rskusum.scanner`)

An Adobe Scan–style document scanner for Android. It is built with Kotlin, Jetpack Compose,
CameraX and OpenCV. It does **not** use ML Kit, so every pixel of the UI and every step of the
pipeline is under our control.

## What it does

* **The app opens straight into the camera.** The layout follows Adobe Scan: a black top bar
  (Home, the app logo with a badge, a QR scanner), a 4:3 live preview, a floating
  `Scan | AI assist` pill, a swipeable mode carousel (`Whiteboard · Book · Document · ID card ·
  Business card`) and a bottom row (gallery import, auto-capture toggle, shutter, flash, page
  stack).
* **Live edge detection.** The detected page is outlined in blue on the preview at camera frame
  rate.
* **Auto-capture.** When the page is large enough and held steady for about 1.1 s, a blue arc fills
  around the shutter and the photo is taken. The app then waits for a *new* page (it compares a
  content fingerprint) before it arms again, so you can scan a stack of pages hands-free.
* **Automatic crop and perspective correction on the full-resolution photo.** The detected corners
  are refined to about 1–2 px accuracy. The page's true aspect ratio is recovered from the
  perspective itself (Zhang & He's rectangle method), so an A4 sheet comes out as A4 even when the
  phone is tilted.
* **Enhancement filters:** Auto color (the default: removes shadows, whitens the paper, keeps ink
  colour), Original, Light text, Grayscale, B&W and Whiteboard. **AI assist** picks the filter for
  each page automatically.
* **Modes:** Book splits a two-page spread into two pages. ID card and Business card enforce the
  standard card proportions.
* **Review:** swipe between pages, crop (drag handles with a magnifier loupe, auto-detect, no-crop),
  rotate, filters with live previews ("Apply to all"), delete, add more pages, rename.
* **Export:** compact PDFs (JPEG pages embedded directly, A4 width), saved in the app library and
  in `Download/RS Kusum Scanner`. Pages can also be saved as JPGs to `Pictures/RS Kusum Scanner`,
  or shared.
* **Home:** a list of recent scans with thumbnails, where you can open, share, rename or delete them.
* **QR codes** are decoded with OpenCV's `QRCodeDetector`.

## Pipeline

```
CameraX ImageAnalysis (640x480 Y plane) ──► DocumentDetector.detect ──► AutoCaptureTracker
                                                          │                    │ steady ~1.1 s
                                                          ▼                    ▼
                                                   blue quad overlay     ImageCapture (≤ 4032x3024)
                                                                               │
    detect again on the still ◄────────────────────────────────────────────────┘
      └► refine (line fit to edge pixels per side, intersect)  ~1–2 px corners
          └► estimateAspect (focal length from perspective) ──► warpPerspective (cubic)
              └► [Book: split halves] ► rotate ► ImageEnhancer filter ► JPEG page
                  └► PdfWriter (DCTDecode, no re-compression) ► Downloads
```

Detection runs a morphological close (to erase text), then Canny edge maps at three thresholds
driven by Otsu, plus an Otsu mask. It takes the convex hull of each contour, approximates it to
four corners, and scores each candidate by *area × edge support*. That score rejects false quads
caused by lighting gradients, including white paper on a light, patterned sheet.

## Build

Requirements: JDK 17 and the Android SDK (compileSdk 35).

```bash
cd scanner-android
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```

The GitHub Actions workflow `.github/workflows/scanner-android.yml` builds the debug and release
APKs on every push that touches `scanner-android/`, and uploads them as the
`rskusum-scanner-apks` artifact.

The release build type is currently signed with the debug key. Replace it with your own keystore
before publishing to Google Play.

## Code map

| Path | Purpose |
|---|---|
| `vision/DocumentDetector.kt` | edge detection, corner refinement, aspect estimation, warp, page fingerprint |
| `vision/ImageEnhancer.kt` | illumination normalization and filters |
| `vision/SmartFilter.kt` | AI assist filter choice |
| `camera/DocumentAnalyzer.kt` | CameraX analyzer (Y plane → OpenCV), QR mode |
| `camera/AutoCaptureTracker.kt` | stability, progress, next-page arming |
| `ScannerViewModel.kt` | capture → pages → render pipeline, save and export |
| `data/PdfWriter.kt` | minimal JPEG-in-PDF writer |
| `data/DocumentStore.kt` | library, MediaStore export, sharing, thumbnails |
| `ui/camera`, `ui/review`, `ui/crop`, `ui/home` | Compose screens |
