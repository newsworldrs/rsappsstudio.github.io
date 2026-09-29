# Changelog

All notable changes to the **RS Kusum Scanner** Android library (`scanner-android/`).
Versions follow [Semantic Versioning](https://semver.org/). Use them from JitPack:
`implementation("com.github.newsworldrs:rsappsstudio.github.io:<version>")`.

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

[1.2.1]: https://github.com/newsworldrs/rsappsstudio.github.io/releases/tag/1.2.1
[1.2.0]: https://github.com/newsworldrs/rsappsstudio.github.io/releases/tag/1.2.0
[1.1.0]: https://github.com/newsworldrs/rsappsstudio.github.io/releases/tag/1.1.0
[1.0.0]: https://github.com/newsworldrs/rsappsstudio.github.io/releases/tag/1.0.0
