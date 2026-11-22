// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.core

import moe.xWey10.tweaks.features.essentialkey.EssentialKeyCapture
import moe.xWey10.tweaks.features.essentialkey.EssentialKeyGesture
import org.json.JSONObject

enum class DotsMode { STOCK, CUSTOM, RANDOM }

data class EssentialTarget(val packageName: String, val className: String = "")

data class TweakSettings(
    val dots: DotsMode = DotsMode.STOCK,
    val dotsColor: Int = 0xFFE53935.toInt(),
    val iconEnabled: Boolean = false,
    val iconSize: Int = 80,
    val icon: String = "",
    val chargingEnabled: Boolean = false,
    val chargingOnly: Boolean = true,
    val voltage: Boolean = true,
    val current: Boolean = true,
    val power: Boolean = true,
    val temperature: Boolean = true,
    val aod: Boolean = false,
    val doubleTapWake: Boolean = false,
    val doubleTapSleep: Boolean = false,
    val allowScreenshots: Boolean = false,
    val essentialKey: Map<EssentialKeyGesture, EssentialKeyCapture> = emptyMap(),
    val essentialTargets: Map<EssentialKeyGesture, EssentialTarget> = emptyMap(),
    val brightnessSwipe: Boolean = false,
    val clockSeconds: Boolean = false,
    val statusDoubleTapSleep: Boolean = false,
) {
    val hasMetrics get() = voltage || current || power || temperature

    fun encode() = JSONObject().apply {
        put("schema", 1)
        put("dots", dots.name)
        put("dotsColor", dotsColor)
        put("iconEnabled", iconEnabled)
        put("iconSize", iconSize)
        put("icon", icon)
        put("chargingEnabled", chargingEnabled)
        put("chargingOnly", chargingOnly)
        put("voltage", voltage)
        put("current", current)
        put("power", power)
        put("temperature", temperature)
        put("aod", aod)
        put("doubleTapWake", doubleTapWake)
        put("doubleTapSleep", doubleTapSleep)
        put(ALLOW_SCREENSHOTS, allowScreenshots)
        put("brightnessSwipe", brightnessSwipe)
        put("clockSeconds", clockSeconds)
        put("statusDoubleTapSleep", statusDoubleTapSleep)
        put("essentialTargets", JSONObject().apply {
            essentialTargets.forEach { (gesture, target) ->
                put(gesture.name, JSONObject().apply {
                    put("package", target.packageName)
                    put("class", target.className)
                })
            }
        })
        put(ESSENTIAL_KEY, JSONObject().apply {
            essentialKey.forEach { (gesture, action) -> put(gesture.name, action.name) }
        })
    }.toString()

    companion object {
        const val FILE = "tweaks_v1"
        const val SNAPSHOT = "snapshot"
        const val ALLOW_SCREENSHOTS = "allowScreenshots"
        private const val ESSENTIAL_KEY = "essentialKey"
        const val ICON_EDGE = 128
        const val ICON_LIMIT = 100_000
        const val MIN_ICON_SIZE = 25
        const val MAX_ICON_SIZE = 100

        fun decode(raw: String): TweakSettings {
            require(raw.length <= ICON_LIMIT + 8192)
            val json = JSONObject(raw)
            require(json.optInt("schema") == 1)
            val key = json.optJSONObject(ESSENTIAL_KEY)
            val targets = json.optJSONObject("essentialTargets")
            return TweakSettings(
                dots = DotsMode.entries.firstOrNull { it.name == json.optString("dots") } ?: DotsMode.STOCK,
                dotsColor = json.optInt("dotsColor", TweakSettings().dotsColor) or 0xFF000000.toInt(),
                iconEnabled = json.optBoolean("iconEnabled"),
                iconSize = json.optInt("iconSize", 80).coerceIn(MIN_ICON_SIZE, MAX_ICON_SIZE),
                icon = json.optString("icon").takeIf { it.length <= ICON_LIMIT }.orEmpty(),
                chargingEnabled = json.optBoolean("chargingEnabled"),
                chargingOnly = json.optBoolean("chargingOnly", true),
                voltage = json.optBoolean("voltage", true),
                current = json.optBoolean("current", true),
                power = json.optBoolean("power", true),
                temperature = json.optBoolean("temperature", true),
                aod = json.optBoolean("aod"),
                doubleTapWake = json.optBoolean("doubleTapWake"),
                doubleTapSleep = json.optBoolean("doubleTapSleep"),
                allowScreenshots = json.optBoolean(ALLOW_SCREENSHOTS),
                brightnessSwipe = json.optBoolean("brightnessSwipe"),
                clockSeconds = json.optBoolean("clockSeconds"),
                statusDoubleTapSleep = json.optBoolean("statusDoubleTapSleep"),
                essentialTargets = buildMap {
                    EssentialKeyGesture.entries.forEach { gesture ->
                        val target = targets?.optJSONObject(gesture.name) ?: return@forEach
                        val pkg = target.optString("package")
                        val clazz = target.optString("class")
                        // No arbitrary component strings from untrusted/stale settings.
                        if (pkg.matches(Regex("[A-Za-z_][\\w]*(\\.[A-Za-z_][\\w]*)+")) &&
                            clazz.length <= 300 && pkg.length <= 200
                        ) put(gesture, EssentialTarget(pkg, clazz))
                    }
                },
                essentialKey = buildMap {
                    EssentialKeyGesture.entries.forEach { gesture ->
                        val action = EssentialKeyCapture.entries.firstOrNull {
                            it != EssentialKeyCapture.NONE && it.name == key?.optString(gesture.name)
                        }
                        if (action != null) put(gesture, action)
                    }
                },
            )
        }
    }
}
