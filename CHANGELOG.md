# Changelog

All notable changes to the **RS Kusum Scanner** Android library (`scanner-android/`).
Versions follow [Semantic Versioning](https://semver.org/). Use them from JitPack:
`implementation("com.github.newsworldrs:rsappsstudio.github.io:<version>")`.

## [Unreleased]
### Added
- **Color filter** (`ScanFilter.COLOR`): pure white paper with vivid ink, stamp, logo and photo colours. Lighting is evened out from brightness only, so large colour areas stay clean and even. Shown second in the filter list.
- `ScannerOptions(startWithGallery = true)`: opens the system photo picker instead of the camera (for an "image to PDF" button). Picked pictures go through the same edge detection, cropping, straightening and filters as camera pages and open in the editor. Cancelling the picker closes the scanner.
- `ScannerOptions(startHd = true)`: opens the camera with HD already ticked.
### Changed
- **Play Console recommendations:**
  - The scanner screen sets up edge-to-edge itself instead of calling androidx's `enableEdgeToEdge()`, which still uses the cutout mode `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES` (deprecated in Android 15). On Android 11+ it uses `ALWAYS`.
  - `androidx.activity` 1.10.1.
  - Consumer R8 rules: the leftover TensorFlow Lite entry is removed, and `ScanFilter` names stay readable for batch recovery, so apps no longer need a blanket keep of `com.rskusum.scanner.**`.
- Faster capture: auto capture shoots at once (the page is already steady and in focus); the manual shutter waits at most 0.7 s for focus.
- Thumbnails show instantly: the preview taken at the shutter becomes the page's first thumbnail, the page is turned upright afterwards, and the page photo is written in the background while the outline is found.
### Fixed
- **Pages came out sideways ("stretched") in Document mode:** with the guide frame on screen, the frame's shape now decides upright or across. An upright frame always gives an upright page, and the text check may then only fix upside down. Quarter turns remain for Free mode and gallery pictures.
- **Crop didn't match the frame:** only outlines about the size of the frame (at least half its area) can replace the frame, so an inner box, table or picture is never taken as the page.
- **Model self-check:** each on-device model is checked on the phone against the original model's answer for a fixed test picture. If it doesn't match, it stays off and classic detection is used.
- **Faster:**
  - Both models load in the background when the scanner starts, no longer on the main thread when the camera opens.
  - The text-orientation check looks at 3 crops instead of 6.
- **16 KB memory pages (Android 15+, Google Play requirement):**
  - TensorFlow Lite is removed; its native library (`libtensorflowlite_jni.so`) is only 4 KB aligned.
  - The two on-device models (page edges, text orientation) now run on OpenCV's DNN module, as ONNX. OpenCV is already in the library and 16 KB aligned.
  - Every native library the scanner ships is now 16 KB aligned: OpenCV, Tesseract, Leptonica, libjpeg, libpng and CameraX.
  - Checked against TensorFlow Lite on real photos: edge maps correlate at 0.9997, and the orientation decisions are identical wherever the scanner acts on them.
- **Book pages upside down or in the wrong order:**
  - How the phone is held is now read from gravity directly. It works down to about 7° of tilt, where Android's orientation listener gave up below about 25° and the scanner fell back to a guess.
  - All four ways of holding the phone are handled: sideways either way, upright, and upside down. Each gives the right turn and puts the book's left page first.
  - When the phone's position is known, it decides the page orientation. The text-orientation check, which is unreliable on handwriting, only decides when the position is unknown.
  - The Page 1 / Page 2 labels on the camera follow how the phone is held.
- **Book mode kept the open book as one page:** with the book frame on screen, an outline that fills the frame or has an open book's shape is always split into two pages. The text-line test no longer overrules it, since pictures, tables or short text could fool it.
- **Book mode didn't split the pages with the phone held upright:** now that the camera screen stays portrait, the book guide follows how the phone is held. Upright: wide frame, vertical spine line, pages left / right. Sideways: tall frame, horizontal spine line, pages top / bottom (as before). The spread is split the matching way.
- **Slow thumbnail and slow editor:**
  - The thumbnail appears the instant the shutter fires (cut from the live preview), and the shutter is free for the next page as soon as the photo is taken.
  - Every page is prepared in two steps: a screen-size version (about 1600 px) shows in the editor within moments, and the full-resolution version for the PDF follows in the background without holding anything up.
  - Each page is loaded into memory as soon as its screen-size version is ready (the 6 most recent pages). Tapping the thumbnail opens the editor on the finished page at once: no decoding, no placeholder.
- **Filters looked worse than in 1.6.0:** photos are taken with the camera's full-quality processing again (the low-latency mode added in the test builds made photos noisier, and every filter exaggerated that).
- **Camera screen broke when the phone was turned sideways:** the scanner now always keeps its portrait layout. It is locked in code as well as in the manifest, and opted out of Android 16's and the user's "full screen" overrides. Only the icons (tool rail, shutter, thumbnail, gallery, top bar) turn with the phone, as in the system camera, so the preview, frame and every button stay usable.
- **Wrong "already scanned":** a page is a repeat only when its ink matches the earlier page everywhere once the two are lined up. Copies of the same form, bill or ID page with different names, dates or amounts are new pages. A page you capture with the shutter button is always kept; only auto capture skips repeats.
- **Orientation:** page photos are turned upright in their pixels again (as in 1.6.0) instead of relying on an EXIF tag, so pages taken with the phone or screen turned sideways come out the right way.

## [1.6.0] - 2026-10-06
### Added
- **HD scan:** a check box in the camera tool rail (off by default). HD pages are captured at the camera's full resolution (up to 6000 px), rendered at 5000 px and kept at that size in the PDF. Up to 5 HD pages per scan.
- **First-run tour:** three swipeable pages, each a mockup of a screen with numbered buttons explained: camera buttons, scan modes, and editing and saving. Shown once per install; turn it off with `ScannerOptions(showIntro = false)`.
- **Batch recovery:** a small manifest of the scan in progress (file paths and edit settings only) is kept on disk. If the app is killed or the phone restarts, the unfinished batch comes back.
- **Back to exit:** in the camera, Back shows "Press back again to exit". If pages aren't saved, the next Back warns "Scan not saved. Press back again to exit", and the one after that leaves without saving. Back no longer jumps between the camera and review. `ScannerFlow` takes an optional `onExit`.
- "Please wait, preparing your scans…" when the thumbnail is tapped while pages are still being prepared, and in review with the number left.
- The screen stays on while scanning, while pages are prepared and while the PDF is saved.
### Changed
- **Text orientation now works on the device.** The orientation model needs TensorFlow Lite 2.17, and with 2.16.1 it never loaded. `org.tensorflow:tensorflow-lite` is now **2.17.0**. Orientation is decided before the first thumbnail, so pages show upright from the start. Model answers are also cross-checked, and the Devanagari headline test is stricter.
- **Long batches (100+ pages) with bounded memory:**
  - At most 2 photos are analysed at a time, and at most 3 captures can be queued.
  - The PDF is written one page at a time.
  - Thumbnails are RGB_565 at 280 px.
  - Review decodes pages at 2400 px.
  - Rendered pages are kept with the session instead of in the cache.
- **Eraser:**
  - *Everything* fills the area from the paper right around the stroke, so there are no grey or off-white patches over large marks.
  - *Marks only* finds text against the local background and against the bare paper. Bold headings, light-grey print and black text under a highlighter are now kept.
  - Tested with blue, red, green, purple, orange and light-blue pens, yellow, pink, green and blue highlighters, and a coffee stain.
- The gallery import allows up to 30 pictures at once.

## [1.5.0] - 2026-10-05
### Added
- **Text orientation for every script.** Pages are turned upright from the direction of their text lines, which works for any script. Upright vs. upside down is decided by the headline of Devanagari, Bengali and Gurmukhi words (Hindi, Marathi, Nepali, Sanskrit, ...) and by the on-device model for other scripts. Fixes Hindi pages that were left sideways or flipped.
- **Gallery imports keep already-cropped pictures whole.** The picture borders are the page corners unless a real page lying on a surface is found. Shapes inside the picture (a photo, a table, a box) are no longer cropped to, and the picture keeps its own proportions.
- **Camera zoom** (pinch or 1x / 2x chips, up to 2x) and **zoom in review** (pinch up to 5x, double-tap, pan).
- Blue-purple brand gradient on buttons and icon buttons.
### Changed
- **Book mode:**
  - A two-page spread is told apart from a single page by the direction of its text lines, so one page is never cut into two halves. A single page is kept whole.
  - When the capture finds only one page, the whole open book shown by the live outline is used. The other page can also be found from its text when its edges are faint.
  - Each page's outer edges are cropped again after the split. Book pages are always portrait.
- **Frame mode** trusts the frame. Two or three real sides near the frame are enough, and the missing corners are completed from them, so there is no false "Move closer". At capture, if the page doesn't sit on the frame all round, the page itself is searched and the frame is only a hint.
- **Sharper, faster captures:**
  - The camera takes ~12 MP 4:3 photos instead of the sensor maximum (50-200 MP sensors give slow, softer shots).
  - The shutter waits for a steady phone (gyroscope).
  - A quick preview thumbnail appears at once.
  - Two pages render in parallel at 3300 px (the largest PDF quality).
  - Gallery pictures are processed one at a time in the chosen order.

## [1.4.0] - 2026-09-30
### Changed
- **Branding:** the camera now shows **"Scan · powered by RS Apps Studio"**. PDFs are labelled "Scan - powered by RS Apps Studio", and the standalone app saves to the `RS Apps Studio Scan` folder.
- **All visible text is in string resources** (`res/values/rs_scanner_strings.xml`: 211 strings and 5 plurals). Apps translate the scanner by adding `values-xx/rs_scanner_strings.xml`. Enum labels are now resource IDs:
  - `ScanFilter.labelRes` and `label(context)`
  - `ScanMode.labelRes`
  - `PdfQuality.labelRes`
  - `Insight.Kind.labelRes`
  - `DocInsights.type` is now a `DocType`
- **Smaller library:**
  - PDFBox (and the BouncyCastle crypto library it pulled in) is removed. PDFs are written by a built-in writer, and are just as small because pages are embedded as JPEG without re-compression.
  - The orientation model now has 8-bit weights: 3.4 MB → 1.8 MB, with the same results in testing.
### Fixed
- **Book spine:** the spine is now searched only near the middle of the spread (40–60%, preferring the centre), so both pages come out the same size.
- **Book in Free mode:** if the open book lies across a portrait screen, the camera asks you to turn the phone so the book fills the long side, and doesn't auto-capture a small, sideways spread.

## [1.3.0] - 2026-09-30
### Added
- **Dark text** filter for light photocopies and faded print. It turns every stroke dark and sharp on pure white, and removes paper grain and show-through.
- **Remove shadow** is now its own on/off switch in the filter strip. It works together with every filter, Original included, and stays on when you change filters.
- **Apply to all** is now a prominent button. It applies the current filter and shadow setting to every page.
- **Smart filter shows what it picked.** You see a toast after each capture, a "Smart: …" chip in the camera and a label in review. It also turns on shadow removal when the lighting is uneven.
- **Book spine detection.** The spread is cut at the real spine, found from the gutter shadow or the blank band between the pages, instead of at the geometric middle. In Free mode the detected spine line and the PAGE 1 / PAGE 2 labels follow the book live.
- **Eraser improvements:**
  - two-finger pinch zoom (up to 6×) and pan;
  - a **Restore** brush;
  - feathered edges and a hold-to-compare view;
  - smarter **Marks only**, which removes pen, highlighter and pencil but keeps black text, even under a highlighter.
### Changed
- Capture edge detection: in Free mode, the learned edge model, the classic detector and the live outline now compete. The best-scoring outline wins, instead of the first one found. In guide mode, a better-scoring outline replaces the frame fallback when sides are missing.

## [1.2.1] - 2026-09-29
### Fixed
- **Duplicate pages.** A page that is already in the scan is now reliably recognised, whatever the crop, tilt, blur or lighting, and even when it is turned sideways or upside down. The new `PageMatcher` combines a text-layout map with feature-point matching.
  - In testing it caught 99 of 100 re-captures (the old check caught 2 of 40) and raised no false alarms on different pages.
  - It blocks both the auto-capture and the manual shutter.

## [1.2.0] - 2026-09-29
### Added
- **AI Text mode (`ScanMode.AI_TEXT`).**
  - The camera highlights text lines live.
  - Each capture is read on the device.
  - A new AI Text screen shows the editable text, the detected document type and tappable key details: links, e-mails, phone numbers, dates, amounts, PAN, GSTIN and PIN code.
- **Text** tool in the review screen, which reads any page.
- `ScanResult.text`, `ScannerOptions.ocrLanguages` and `TextInsights.analyze(text)`.

## [1.1.0] - 2026-09-29
### Added
- **On-device OCR (`RsOcr`)** with Tesseract (LSTM), in 100+ languages (for example `"eng+hin"`). No ML Kit is needed.
  - Language models are downloaded once, or can be bundled in `assets/tessdata/`.

## [1.0.0] - 2026-09-29
### Added
- First public release of the scanner library, with the `ScanDocument` contract, `ScannerOptions` and `ScanResult`, and the `ScannerFlow` composable.
- Auto capture with a guide frame, and precise auto crop using the on-device HED-lite edge model plus OpenCV.
- Scan modes:
  - Document
  - Book, split into two separate pages at the spine
  - Book cover
  - Whiteboard
  - ID card, with front and back on one A4 page
  - Business card
- Automatic page orientation using an on-device text-orientation model.
- Filters (including No shadow), an eraser (marks only / everything), crop with a magnifier, and rotate.
- Compressed PDF output with PDFBox, and JPEG pages.
- Apache 2.0 licence and a full third-party licence audit.

[1.6.0]: https://github.com/newsworldrs/rsappsstudio.github.io/releases/tag/1.6.0
[1.5.0]: https://github.com/newsworldrs/rsappsstudio.github.io/releases/tag/1.5.0
[1.4.0]: https://github.com/newsworldrs/rsappsstudio.github.io/releases/tag/1.4.0
[1.3.0]: https://github.com/newsworldrs/rsappsstudio.github.io/releases/tag/1.3.0
[1.2.1]: https://github.com/newsworldrs/rsappsstudio.github.io/releases/tag/1.2.1
[1.2.0]: https://github.com/newsworldrs/rsappsstudio.github.io/releases/tag/1.2.0
[1.1.0]: https://github.com/newsworldrs/rsappsstudio.github.io/releases/tag/1.1.0
[1.0.0]: https://github.com/newsworldrs/rsappsstudio.github.io/releases/tag/1.0.0
