# Contributing

Thanks for your interest in improving **RS Kusum Scanner**! Bug reports, ideas and pull requests are welcome.

## Reporting a bug
Open an [issue](https://github.com/newsworldrs/rsappsstudio.github.io/issues/new/choose) and include:
- the library version and the phone model / Android version,
- what you did, what you expected, and what happened,
- a screenshot, the photo that was scanned, or the Logcat error, when you can.

For security problems, **don't open a public issue**. Follow [SECURITY.md](SECURITY.md) instead.

## Suggesting a feature
Open an issue with the **Feature request** template and describe the problem the feature solves.

## Pull requests
1. Fork the repository and create a branch from `main`.
2. Work in `scanner-android/` and open it in Android Studio (JDK 17, Android SDK 35).
3. Keep changes focused. Match the existing Kotlin style, and add or update the KDoc on any public API.
4. Check that the project builds:
   ```bash
   cd scanner-android
   ./gradlew :scanner:assembleRelease :app:assembleDebug
   ```
5. Test on a real device where possible, because scanning depends on the camera.
6. Add a line under a new version heading in [CHANGELOG.md](CHANGELOG.md).
7. Open the pull request and fill in the template.

## Licensing of contributions
By contributing, you agree that your contribution is licensed under the [Apache License 2.0](scanner-android/LICENSE), the same licence as the library.
Only add third-party code or models that use a permissive licence (Apache 2.0, MIT, BSD), and list them in [THIRD_PARTY_NOTICES.md](scanner-android/THIRD_PARTY_NOTICES.md).

## Code of Conduct
Please follow our [Code of Conduct](CODE_OF_CONDUCT.md).
