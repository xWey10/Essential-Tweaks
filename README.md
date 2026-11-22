# Essential Tweaks

**English** · [Русский](README.ru.md)

Essential Tweaks is an unofficial [LSPosed](https://github.com/LSPosed/LSPosed) module for Nothing OS on Android 16. It adds optional tweaks to the stock interface **without replacing System UI**.

**Version:** 0.2.18 · **License:** [GPL-3.0-only](LICENSE) · **Status:** experimental

This project is not affiliated with or endorsed by Nothing or CMF. System hooks may stop working after an OS update; enable and test features individually.

## Features

- **Power menu:** adds optional Recovery, Bootloader and System UI restart actions to the stock power menu, with confirmation and safety checks. 
- **Fingerprint indicator:** replaces the idle under-display fingerprint indicator with a custom image and changes the scan-dot color. 
- **Lock screen:** displays charging voltage, current, power and temperature, and double-tap-to-wake and double-tap-to-sleep.
- **Essential Key:** assigns screenshots or screen recording to press, double-press, hold, press-then-hold and double-press-then-hold gestures. The original Nothing behavior can be restored by re-enabling Essential Space and Essential Recorder and clearing custom assignments.
- **Screenshots:** optional System Framework hook that relaxes `FLAG_SECURE` for application windows. It does **not** (for now) bypass DRM or guarantee capture in every app.
- **Update checks:** checks published stable [GitHub Releases](https://github.com/xWey10/Essential-Tweaks/releases) and shows a newer version **inside the app only**. Automatic checks happen when the app opens, no more than once every 24 hours after a successful check; manual checking and an auto-check toggle are also available. No Android notifications, background update service or automatic APK installation. 

## Requirements and compatibility

- **Android 16 (API 36)** and a compatible Nothing OS System UI build.
- A compatible LSPosed/Xposed implementation supporting the module's legacy API requirements (**API 93+ and New XSharedPreferences**).
- A working `su` implementation for privileged operations, such as freeing/restoring Essential Key and some reboot actions. 

**Reference device/configuration:** CMF Phone 2 Pro, build `Galaga-B4.1-260812-1729`. `NothingSystemUI.apk` package version: `16.0.0`, SHA-256: `7e0c584a22ca82000343a5bec356a6f0d2468cd377092d2d261dec05a92093ec`. 

## Installation

1. Install the APK and enable the module in your LSPosed-compatible manager.
2. Enable the scopes **System UI** (`com.android.systemui`) and **Essential Tweaks** (`moe.xWey10.tweaks`). For screenshot and Essential Key framework hooks, also enable **System Framework** (`android`).
3. Reboot, open Essential Tweaks and enable the features you need.

## Contributing and security

Issues and pull requests are welcome. Include your device, ROM/build, steps to reproduce and **sanitized** logs; see [CONTRIBUTING.md](CONTRIBUTING.md). Report sensitive security issues as described in [SECURITY.md](SECURITY.md), not in a public issue.

See the [changelog](CHANGELOG.md) for release history.

## License

Copyright © 2026 xWey10.

Original project code is licensed under **GNU General Public License v3.0 only** (`GPL-3.0-only`); see [LICENSE](LICENSE). Modified versions distributed as derivative works must follow the license's source-sharing requirements. Third-party components retain their own licenses; see [NOTICE](NOTICE).

The software is provided **without warranty**, to the extent permitted by applicable law.
