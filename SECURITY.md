# Security Policy

## Supported versions

| Version | Supported |
|---|---|
| 1.2.x (latest) | ✅ |
| < 1.2 | ❌ Please update |

## Reporting a vulnerability
**Please don't report security problems in public issues.**

Email **rskusum@rsappsstudio.com** with:
- a description of the problem and its impact,
- steps to reproduce it, or a proof of concept,
- the affected version.

You'll receive a reply within **3 working days**. The problem is fixed as quickly as possible, and you'll be credited in the release notes unless you'd rather stay anonymous.

## How the library protects users
- All scanning, OCR and document analysis run **on the device**.
- The library's only network access is the optional one-time download of an OCR language model from the official Tesseract repository.
- Scanned files are kept in the app's **private storage**. They're shared only through the library's own `FileProvider`, with temporary read permission.
- The library collects no analytics, and has no ads or tracking code.
