# Changelog

## 0.3.5 — Beta

- Matched the camera progress indicator to the display cutout, with oval and capsule options and adjustable width and height.
- Combined charge and discharge estimates into one lock-screen widget setting.
- Added rootless launcher restart when the launcher hook is active, and short animations between settings pages.

## 0.3.4 — Beta

- Added Apply buttons that publish settings and restart the relevant launcher or System UI process on request.

## 0.3.3 — Beta

- Added adjustable camera ring size, shape, color, stroke, and screen-edge position.
- Hid the home-handle drawing by resource ID in SystemUI and supported the launcher taskbar handle.
- Matched Recents to the launcher styles, removed the duplicate Info action, and moved app RAM into the native card menu.
- Improved icon contrast and separated Essential Key gesture assignments, stock behavior, and recorder options.

## 0.3.2 — Beta

- Added Camera progress ring modes for battery and media and moved the ring to a passive SystemUI window.
- Added a gesture bar drawing toggle, plus upload and download rates next to the status bar clock.
- Added available RAM, per-app memory and app shortcuts in Nothing Launcher Recents.

## 0.3.1 — Status bar and lock screen

- Drag the sample widget rows to reorder the lock screen. Added an optional estimate of time to empty.
- Clock and shortcut configuration is handled by Nothing OS.
- Assign an action to a status bar double tap, or show a charge ring around a camera cutout.

## 0.3.0 — Lock screen customization

- Added the Android charge-time estimate and event-driven battery updates.
- Added reorderable charging, battery, traffic and memory/processor rows above the fingerprint sensor.
- Added size, vertical offset and digital styles with optional seconds for Nothing clocks.
- Added app, settings and alarm targets for configured lock screen shortcuts.

## 0.2.19 — Voice recording controls

- Voice recording no longer opens the Essential Tweaks app when triggered by Essential Key.
- Added recording options when the voice recorder is assigned to a gesture: M4A/AAC, OGG/Opus or 3GP/AMR, audio quality, and save folder.
- Added a folder picker for custom recording destinations and a filename template with date and time tokens.
- Microphone permission is requested from Essential Tweaks settings instead of interrupting a button gesture.

## 0.2.18 — Power menu confirmations and status bar

- Added optional Nothing-style confirmation dialogs for the stock Restart and Power off actions, with separate switches.
- Fixed status-bar swipe-to-adjust-brightness and double-tap-to-sleep gestures.
- Voice recording now starts without opening a visible recorder screen. The microphone permission prompt and recording notification remain available as required by Android.

## 0.2.17 — Essential Key actions and status bar

- Added selection of an installed app or exported Activity for each Essential Key gesture.
- Added audio recording on Essential Key press: tap again to stop; recordings are saved as M4A/AAC in `Music/EssentialTweaks`.
- Added camera launch and notification shade expansion as key actions.
- Added options for status-bar brightness swipe, seconds in the clock, and double-tap-to-sleep.

## 0.2.16 — Power actions without root

- Recovery and Bootloader actions now use system reboot permissions rather than requesting root.
- If the required system permission is unavailable, the power menu displays an error instead of attempting to reboot.
- Restarting System UI remains available without root.

## 0.2.15 — In-app update checks

- Added manual and automatic checks for new stable GitHub Releases.
- New versions are announced on the app home screen with a link to the release.
- Added a switch to disable automatic checks; no update notifications are posted.

## 0.2.14 — Restore stock Essential Key

- Added a **Restore original Essential Key** action that re-enables Essential Space and Essential Recorder.
- Restoring the stock key behavior clears custom Essential Key assignments while preserving other tweaks.
- Added confirmation and error messages for restoring the key.

## 0.2.13 — Power menu wording

- Removed ReSukiSU-specific wording from power-menu settings and error messages.

## 0.2.12

- Moved charging metrics into the lock-screen settings section.
- Added screenshot and screen-recording actions for Essential Key gestures: press, double press, hold, press+hold and double press+hold.
- Added support for System Framework and System UI hooks.

## Earlier releases

See [older release notes](docs/RELEASE-NOTES-0.2.12.md) for earlier app changes.
