# Changelog

All notable changes to the **RS Kusum Scanner** Android library (`scanner-android/`).
Versions follow [Semantic Versioning](https://semver.org/). Use them from JitPack:
`implementation("com.github.newsworldrs:rsappsstudio.github.io:<version>")`.

## [Unreleased]
### Added
- **Magic color filter** (`ScanFilter.MAGIC`, last in the filter list): crisp text on pure white paper, also from a soft photo. It measures how blurry the page is and deblurs it by that much (Richardson-Lucy deconvolution of the brightness), then makes the paper white and the ink darker, keeping colours vivid. A sharp page is deblurred little or not at all. Measured on a book page blurred on purpose: sharpness (variance of the Laplacian) 43 before, 628 after (the Color filter gives 280). Existing filters and their order are unchanged.
- **Book pages that don't lie flat are flattened, and cropped on their real edges.** A page of a book that isn't flat is curved: its side edges, the spine and every text line are curves in the photo, and a four-corner crop left background beside the page and kept its lines bent. All four boundaries of each page are now traced on the photo - the sides and the outer edge where paper meets the background (by brightness and colour), the spine along the gutter shadow - as smooth curves with stray hits rejected, and the page is straightened between them; the squeeze of the text near the spine is reduced when the edges really bend. A boundary that can't be traced reliably (paper on a same-coloured surface, a white patch touching the page) stays straight. Checked with the library code on a camera frame of a curved book from a test recording and on frames of a flat book (unchanged).
- **Sharp auto captures.** Auto capture now focuses on the page and waits for a steady phone before shooting, like the manual shutter (it used to shoot at once, relying on continuous autofocus, which was often still hunting close up - soft photos). A phone still shaking after 1.5 s doesn't shoot; auto capture re-arms instead.
- **Book mode: auto capture only for an open book on the dotted line, and closer.** The shutter fires only when an open book is found, big enough, with the dotted line through its middle (no more captures of a bedsheet or half a book). When only one page is found clearly - the book held close, filling the screen - the other page is taken as its mirror image across the dotted line, so the book no longer has to be held far away.
- **Book pages: bent text lines are straightened too.** On a curved page the middle of the page bends differently from its top and bottom edges, so straightening between the edges alone left lines bent. After the edges, the text lines themselves are now found (followed strip by strip across the page) and the page is shifted so each becomes straight. Checked on the pages of a PDF made with the previous build: the worst line bend went from 15-21 px to about 7 px on a 1600 px page. Pages with fewer than 8 lines running across them (mostly a picture, little text) are left as they are, so pictures are never distorted.
- **Book mode is about 2.5x faster after the shutter.** The per-page re-crop (slow, and the step that drifted onto patterned backgrounds) is replaced by the tracing above, the page search uses the lighter mode, and one redundant high-resolution refine is skipped. Measured on desktop at full photo size: 1.3 s instead of 3.5 s.

### Fixed
- **Book mode bent a flat register page into waves again.** Two causes: (1) the check meant to prevent it judged the line-straightened page by re-measuring the very lines it had just made straight, so it always looked better; (2) the correction could push neighbouring rows in opposite directions. Now the line correction is one smooth curve down the page at each point across it (the shape of a real page curl - rows can't wave), it is used only when the plain crop itself has measurably bent, consistent lines, and a page with nothing measurable (tables full of handwriting, pictures) always gets the plain crop - edge tracing alone no longer reshapes it. Checked on the attendance register photo in four framings (plain crop on every page), on the curved pages of an earlier PDF and test frames (still straightened), and on 13 kinds of test pages flat and curved (flat ones untouched).
- **Book mode cut one page in half and dropped the other.** When one page of the open book was clearer than the other (whiter paper, better light), only that page was outlined, the text test took it for an open book, and it was cut down its middle while the other page was lost. The camera photo is always upright with the screen, so in book mode the open book always lies with its spine across the picture, one page above and one below - the whole book is taller than wide. An outline wider than tall is now always taken as ONE page, wherever it lies: the other page is completed on its far side of the spine (snapped to its real edges, or mirrored when they are faint) when it is really there (edges found, or paper as bright as this page), otherwise the page is kept whole - never cut down its middle. Checked on a photo of a receipt register with only its bottom page outlined, with the spine on the line and moved well above and below it, with only the top page outlined, and on frames of test recordings.
- **Book pages: a plain correct crop first.** Every page is also rendered as a plain perspective crop of its four corners, and how straight its lines are is measured on both. The flattened version is used only when its lines are measurably straighter (or, on a page without clear lines, when the page's edges are clearly curved); otherwise the plain crop is kept. On a frame from a test recording this caught the edge flattening making lines worse (bend 5.1 vs 1.9 for the plain crop).
- **Line spacing misread on some pages** (the thickness of a text band taken for the spacing between lines), so their lines weren't found: the spacing search now starts after the first dip of the pattern. Curved pages with headings and mixed font sizes are now straightened too.
- **Book mode bent flat pages of registers and tables.** The text-line straightening followed handwriting and table rows that don't run together, and bent a flat page into waves. A real curve moves neighbouring lines alike, so lines that disagree with their neighbours are now dropped, and a page whose lines mostly disagree is left as it is. Checked on a photo of a flat attendance register (no longer bent) and on the curved pages used before (still straightened).
- **Auto crop kept a strip of the table (or bedsheet) on one side.** An outline with one side on the picture border was treated as fully supported and, being larger, beat the page's true outline; a shortcut after capture then accepted it without comparing. Outlines whose four sides are all real edges now win over border-hugging ones of similar size, and the shortcut is taken only when all four sides are clearly on edges. Reproduced and checked with the library's own detector on frames of a book on a patterned bedsheet; a page that really runs off the picture still crops to the border.
### Changed
- **Book mode: one dotted line for the spine, no frame.** The screen shows a single dotted line across the middle; place the open book along the phone with its spine on the line (one page above, one below). The open book is then found on the photo, cut at the spine and each page cropped on its own edges. The frame and the layout that switched with the phone's tilt (unreliable with the phone held flat over a book) are gone. When the line runs through the middle of the found outline, the outline is always split in two pages.
- **Auto capture waits for a real page outline in every mode.** In frame mode two or three edges near the frame used to be enough (the missing sides were filled in from the frame), and holding still could capture with no outline at all. The page now counts as "in the frame" only with a four-sided outline: the detected page when it sits in the frame, or all four sides found next to the frame. The manual shutter still works at any time.

## [1.7.0] - 2026-11-13
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
- The page photo is written in the background while the outline is found, so the shutter is free again at once.
- **Every remaining visible text now comes from string resources**, so Google Play Console's automatic translation can translate it: the top bar's brand line, the auto-capture toggle letter, the QR copy label, and the error text behind a few failures (unreadable file, can't open/create a file, OpenCV failed to load, can't read an image for OCR) that could otherwise show raw English inside an already-translated message. The library's name and the export folder name stay fixed text in every language, so exported files always land in the same folder and the brand name is never altered by translation.
### Fixed
- **Auto crop corners, and book mode's split, were a little off:** the speed round above that made page-finding work on one shrunken (1600 px) copy of the photo meant every step, including the final corner fit, ran at that same resolution - fine for finding roughly where the page is, not quite enough to place its corners precisely once scaled back up to the full photo, and book mode's split cuts exactly on those corners. A second, sharper copy (3000 px) is now built from the same decode and used only for the one or two final corner-fitting passes once the wide search has already picked the outline - the search itself (the expensive, repeated part) is unchanged, so this doesn't reintroduce the capture delay fixed above.
- **Shutter sound and vibration came late:** they fired only after the camera finished delivering the photo (which `CAPTURE_MODE_MAXIMIZE_QUALITY`'s multi-frame processing could delay noticeably), so the picture seemed to be "taken" before the sound. They now fire the instant the shutter actually triggers, like a real camera.
- **Capture itself was slow:** the camera now uses `CAPTURE_MODE_MINIMIZE_LATENCY` instead of `MAXIMIZE_QUALITY`. The sensor fires in one frame instead of several being merged by the camera HAL, so the photo (and the page prepared from it) is ready much sooner; the filters and two-step render already clean up the page.
- **Opening the editor right after capture still showed "under processing" for seconds:** the camera's status line was timing only page detection, not the full wait until the page is actually ready - it now times start to finish and the number shown matches what the user waits for. OpenCV (detection, the enhancer, the edge and orientation models) now explicitly uses every core instead of whatever an Android build happens to default to. The upright/upside-down check also looks at the page a second time (rotated 180°) only when the first look is not already clear, halving that model's work for most pages (upright ones), with no change to the decision itself.
- **A page's thumbnail looked finished well before the page actually was**, because it started out as the raw, unprocessed shutter preview - unlike Google ML Kit, which shows nothing until processing is done. A page's thumbnail now stays unset (a spinner is shown) until the real, enhanced, correctly oriented render is ready - the same moment the editor itself stops waiting - so there is no in-between state that looks done but isn't, for a freshly captured page or an imported one.
- **The captured photo is saved at JPEG quality 90 instead of 95**, measurably cutting the encode time that the page's processing has to wait for, and the two later decodes of this same file (for the quick and full renders) - no visible difference on a document; it is never the exported page image, only the source re-rendered through the enhancer, which keeps its own quality.
- **Same page captured twice:** auto capture took the same page again while the first photo was still being processed. A photo now counts as "already scanned" from the moment it is taken (its live signature), not only once its page is ready.
- **Auto capture kept shooting after tapping Import or the thumbnail:** both now stop auto capture at once, including a capture that was waiting for the phone to be steady.
- **ID card front and back on separate PDF pages:** when the back was captured while the front was still being processed, it started a new card. Each side now joins its card the moment it is taken, front stays before back, and a deleted side can be scanned again. Older sessions with two lone sides are also put on one page.
- **Slow pages after capture:** the page is found on one shrunken copy of the photo instead of shrinking the full 12-megapixel photo again in each of about 20 steps, and the live camera analyses only every third frame while photos are being processed. The thumbnail opens the editor right away, even while pages are still being prepared. The camera's status line shows how long the last photo took (e.g. "4.2 s").
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

[1.7.0]: https://github.com/newsworldrs/rsappsstudio.github.io/releases/tag/1.7.0
[1.6.0]: https://github.com/newsworldrs/rsappsstudio.github.io/releases/tag/1.6.0
[1.5.0]: https://github.com/newsworldrs/rsappsstudio.github.io/releases/tag/1.5.0
[1.4.0]: https://github.com/newsworldrs/rsappsstudio.github.io/releases/tag/1.4.0
[1.3.0]: https://github.com/newsworldrs/rsappsstudio.github.io/releases/tag/1.3.0
[1.2.1]: https://github.com/newsworldrs/rsappsstudio.github.io/releases/tag/1.2.1
[1.2.0]: https://github.com/newsworldrs/rsappsstudio.github.io/releases/tag/1.2.0
[1.1.0]: https://github.com/newsworldrs/rsappsstudio.github.io/releases/tag/1.1.0
[1.0.0]: https://github.com/newsworldrs/rsappsstudio.github.io/releases/tag/1.0.0
