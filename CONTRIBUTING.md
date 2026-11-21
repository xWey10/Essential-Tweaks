# Contributing

Issues and pull requests are welcome.

1. Search existing reports and include ROM/version, Android API, affected feature, steps to reproduce, and whether the relevant LSPosed scopes are enabled.
2. **Sanitize logs** before posting: remove user names, emails, device identifiers, package-private data, authentication tokens, and anything personal. Never upload entire private SystemUI APKs or proprietary source dumps.
3. Keep features opt-in, fail closed on unknown ROM signatures, and retain stock behavior if hooks cannot be installed.
4. Run `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` when you can.
5. All contributions to original project code are submitted under the repository's GPL-3.0-only license unless explicitly documented otherwise.

No device is assumed to be supported merely because it runs Nothing OS.
