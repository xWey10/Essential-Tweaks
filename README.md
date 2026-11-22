# Essential Tweaks

**English** · [Русский](README.ru.md)

Essential Tweaks is an unofficial [LSPosed](https://github.com/LSPosed/LSPosed) module for Nothing OS on Android 16. It adds optional tweaks to the stock interface **without replacing System UI**.

**Version:** 0.3.5 · **License:** [GPL-3.0-only](LICENSE) · **Status:** experimental

This project is not affiliated with or endorsed by Nothing or CMF. System hooks may stop working after an OS update; enable and test features individually.

## Features

- **Power menu:** adds optional Recovery, Bootloader and System UI restart actions to the stock power menu, with confirmation and safety checks. You can also optionally require a native SystemUI confirmation for the stock Restart and Power off actions. Recovery/Bootloader use the system permissions of the hooked SystemUI process, **without `su`**. If SystemUI lacks `REBOOT` or `RECOVERY`, the action is unavailable.
- **Fingerprint indicator:** replaces the idle under-display fingerprint indicator with a custom image and changes the scan-dot color. Availability depends on the device and ROM.
- **Lock screen:** displays charging voltage, current, power and temperature, and optionally enables double-tap-to-wake and double-tap-to-sleep.
- **Essential Key:** assigns screenshots, screen recording, launching a selected app, an exported Activity, voice recording without opening an Activity, opening the camera or notification shade to press, double-press, hold, press-then-hold and double-press-then-hold gestures. The built-in audio recorder starts/stops on subsequent key activations. When assigned, its settings offer M4A/AAC, OGG/Opus or 3GP/AMR, quality, a preset or custom folder, and filename templates (for example, `Voice-{datetime}`). By default, recordings are stored in `Music/EssentialTweaks`. Grant microphone permission in the app settings before pressing the key. Android 16 may restrict background microphone service startup on some firmware; silent launch on all devices cannot be guaranteed. Android requires a microphone grant and an ongoing foreground-service notification while recording; this notification is unrelated to update checking. The Activity picker lists exported/enabled components; activities expecting private extras may not launch. The original Nothing behavior can be restored by re-enabling Essential Space and Essential Recorder and clearing custom assignments (may require root).
- **Status bar:** brightness swipe, clock seconds, action on double tap and live upload/download speed. The camera progress ring shows battery level or playback progress for media sessions with a known duration.
- **Gesture navigation:** hides the gesture bar drawing while keeping its touch area.
- **Recents:** available RAM under Screenshot, per-app PSS in the card menu and an optional Copy ID button. Enable the **Nothing Launcher** scope (`com.nothing.launcher`) and restart the launcher.
- **Screenshots:** optional System Framework hook that relaxes `FLAG_SECURE` for application windows. It does **not** bypass DRM or guarantee capture in every app. Disabled by default; use only where you are permitted to capture content.
- **Update checks:** checks published stable [GitHub Releases](https://github.com/xWey10/Essential-Tweaks/releases) and shows a newer version **inside the app only**. Automatic checks happen when the app opens, no more than once every 24 hours after a successful check; manual checking and an auto-check toggle are also available. No Android notifications, background update service or automatic APK installation. Checking contacts GitHub directly.

## Requirements and compatibility

- **Android 16 (API 36)** and a compatible Nothing OS System UI build.
- A compatible LSPosed/Xposed implementation supporting the module's legacy API requirements (**API 93+ and New XSharedPreferences**).
- A working `su` implementation only if you want to free or restore Essential Key through the app. The voice recorder does not use `su`, but needs Android microphone permission. `QUERY_ALL_PACKAGES` is used only for locally listing selectable installed apps and exported activities; app inventory is not uploaded. **Power-menu reboots do not use `su`**, nor does the screenshot hook.

**Reference device/configuration:** CMF Phone 2 Pro, build `Galaga-B4.1-260812-1729` (reported by the author). The supplied `NothingSystemUI.apk` was inspected statically: package version `16.0.0`, SHA-256 `7e0c584a22ca82000343a5bec356a6f0d2468cd377092d2d261dec05a92093ec`. Static inspection is **not** proof that every feature works on a device; full device testing and support for other Nothing/CMF models remain unverified.

For implementation details and a test checklist, see [compatibility](docs/COMPATIBILITY.md), [validation](docs/VALIDATION.md) and [device tests](docs/DEVICE-TEST.md).

## Installation

1. Install the APK and enable the module in your LSPosed-compatible manager.
2. Enable the scopes **System UI** (`com.android.systemui`) and **Essential Tweaks** (`moe.xWey10.tweaks`). For screenshot and Essential Key framework hooks, also enable **System Framework** (`android`).
3. Reboot, open Essential Tweaks and enable only the features you need.
4. Test changes carefully. Do not try Recovery/Bootloader actions without a reliable way to recover the device if something goes wrong.

## Build from source

Install **JDK 17+**, Android SDK **Platform 36** and **Build Tools 36.0.0**. The project pins Gradle 8.13, Android Gradle Plugin 8.13.2 and Kotlin 2.2.21.

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk` (signed with a local debug key).

To build a release APK:

```bash
./gradlew :app:assembleRelease
```

You can sign with your existing key using:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export KEYSTORE_PATH="$HOME/android-keys/essential-tweaks.jks"
export KEY_ALIAS="essential-tweaks"
./scripts/build-signed-release.sh
```

The script reads `versionName` from `app/build.gradle.kts`, saves `dist/EssentialTweaks-v<version>.apk`, and prompts for the keystore password (it is not stored). Alternatively, sign the resulting release APK manually with **your own persistent release key**. Do not commit signing keys, passwords or private configuration. Android generally requires the same signing certificate to update an existing installation without uninstalling it. Source archives are not signed APK releases.

## Contributing and security

Issues and pull requests are welcome. Include your device, ROM/build, steps to reproduce and **sanitized** logs; see [CONTRIBUTING.md](CONTRIBUTING.md). Report sensitive security issues as described in [SECURITY.md](SECURITY.md), not in a public issue.

See the [changelog](CHANGELOG.md) for release history.

## License

Copyright © 2026 xWey10.

Original project code is licensed under **GNU General Public License v3.0 only** (`GPL-3.0-only`); see [LICENSE](LICENSE). Modified versions distributed as derivative works must follow the license's source-sharing requirements. Third-party components retain their own licenses; see [NOTICE](NOTICE).

The software is provided **without warranty**, to the extent permitted by applicable law.
