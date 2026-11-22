# SPDX-License-Identifier: GPL-3.0-only
-keep class moe.xWey10.tweaks.hooks.XposedEntry { *; }
# Xposed calls the entry point from assets/xposed_init; do not package the API.
-dontwarn de.robv.android.xposed.**
