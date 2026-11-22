# Changelog

## 0.2.18 — Stock reboot confirmation and status-bar fixes

- Optional native-style SystemUIDialog confirmation for the stock Restart and Power off actions (separate toggles, disabled by default). Confirmed actions run the original OEM handler, preserving OEM authentication and power behavior; no root escalation.
- Fixed both status-bar gestures potentially never installing: find dispatchTouchEvent in the view hierarchy rather than requiring a declaration on PhoneStatusBarView. Gesture hook filters to that view when the inherited framework method is shared.
- Brightness gesture now applies temporary and persistent display brightness with SystemUI DisplayManager, with Settings.System fallback. Vertical shade gestures remain unmodified.
- Voice recording starts through an invisible/translucent permission handoff. Recording continues in a microphone foreground service with required system indicator and notification; first use may still show the Android permission dialog.
- Updated build-and-sign helper to emit v0.2.18 APK. Compile and on-device behavior have not been verified.


## 0.2.17 — Essential Key actions and status bar

- Added installed-app and exported-Activity picker for each Essential Key gesture. No private activities or arbitrary Java methods are invoked.
- Added audio recording by Essential Key: app-owned microphone foreground service, run-time microphone permission, and M4A/AAC output to `Music/EssentialTweaks`; a required recording notification provides Stop.
- Added camera launch and notification shade expansion as additional key actions.
- Added optional horizontal brightness swipe, native clock seconds and double-tap-to-sleep on the collapsed status bar.
- Added local developer build-and-sign helper using an existing keystore. No signing secrets stored in source.
- **Experimental/unverified on real device; Android SDK was unavailable in the editing environment.**

## 0.2.16 — SystemUI-only power actions

- Removed the `su`/`svc` root-shell fallback for Recovery and Bootloader; the power menu now uses SystemUI's `PowerManager.reboot(reason)` only.
- Missing `REBOOT`/`RECOVERY` system permissions show a clear in-menu message instead of requesting root or attempting a reboot; all permission and lock-state gates remain.
- System UI restart still terminates only the injected SystemUI process and needs no root grant.
- Removed the obsolete root-shell implementation and its tests; added permission-policy regression tests.
- Essential Key's optional app-side root operations are unchanged. Device behavior is not verified by this source-only change.

## 0.2.15 — In-app update checks

- Check the newest stable GitHub Release via `https://api.github.com/repos/xWey10/Essential-Tweaks/releases/latest` when the app opens (at most once per 24 hours after a successful check, with a one-hour retry window after errors).
- Show a new-version link and manual check on the app home screen. An opt-out switch disables automatic checks.
- No Android notifications, background workers, installer permissions or root access used for update checks.
- The repo must be public with at least one published stable GitHub Release for the API to respond successfully; prereleases and draft releases are not included.

## 0.2.14 — Restore stock Essential Key

- Added an explicit **Restore original Essential Key** action. Uses root to re-enable Essential Space and Essential Recorder for user 0.
- Once the apps are confirmed enabled, clears all custom Essential Key gesture bindings and returns hardware events to Nothing OS. Other tweak settings are preserved.
- Added a confirmation dialog, operation-specific status messages and manual enable commands for root failures.
- Requires device validation: stock key handling depends on Nothing OS and may need a restart.

## 0.2.13 — GitHub source release

- Removed ReSukiSU-specific text from power-menu settings, reboot errors, and the system-process fallback strings. Root implementation is unchanged.
- Changed original project code licensing from Apache-2.0 to GPL-3.0-only, retaining third-party licenses.
- Added repository documentation, public-build CI and tighter ignore rules; bumped `versionCode` to 16.

## 0.2.12

- Charging metrics moved to the lock-screen settings section.
- Essential Key supports press, double press, hold, press+hold and double press+hold with screenshot / screen recording actions.
- Added hooks for System Framework and System UI.

## Earlier releases

Full historical notes for v0.2.0–0.2.12 are archived in [docs/RELEASE-NOTES-0.2.12.md](docs/RELEASE-NOTES-0.2.12.md).
