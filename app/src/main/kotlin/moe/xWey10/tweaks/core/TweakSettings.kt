// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.core

import moe.xWey10.tweaks.features.essentialkey.EssentialKeyCapture
import moe.xWey10.tweaks.features.essentialkey.EssentialKeyGesture
import org.json.JSONObject

enum class DotsMode { STOCK, CUSTOM, RANDOM }

enum class RecorderFormat { AAC_M4A, OPUS_OGG, AMR_3GP }
enum class RecorderQuality { LOW, NORMAL, HIGH }
enum class RecorderDestination { MUSIC, RECORDINGS, CUSTOM }


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
    val eta: Boolean = false,
    val networkWidget: Boolean = false,
    val batteryWidget: Boolean = false,
    val systemWidget: Boolean = false,
    val widgetOrder: String = "cbns",
    val layoutGap: Int = 8,
    val aod: Boolean = false,
    val doubleTapWake: Boolean = false,
    val doubleTapSleep: Boolean = false,
    val allowScreenshots: Boolean = false,
    val essentialKey: Map<EssentialKeyGesture, EssentialKeyCapture> = emptyMap(),
    val essentialTargets: Map<EssentialKeyGesture, EssentialTarget> = emptyMap(),
    val brightnessSwipe: Boolean = false,
    val clockSeconds: Boolean = false,
    val cutoutRing: Boolean = false,
    val dischargeEta: Boolean = false,
    val statusAction: EssentialKeyCapture = EssentialKeyCapture.NONE,
    val statusSleep: Boolean = false,
    val statusTarget: EssentialTarget = EssentialTarget(""),
    val recorderFormat: RecorderFormat = RecorderFormat.AAC_M4A,
    val recorderQuality: RecorderQuality = RecorderQuality.NORMAL,
    val recorderDestination: RecorderDestination = RecorderDestination.MUSIC,
    val recorderTreeUri: String = "",
    val recorderFolderLabel: String = "",
    val recorderNameTemplate: String = "Essential-{datetime}",
) {
    val hasMetrics get() = voltage || current || power || temperature || eta

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
        put("eta", eta)
        put("networkWidget", networkWidget)
        put("batteryWidget", batteryWidget)
        put("systemWidget", systemWidget)
        put("widgetOrder", widgetOrder)
        put("layoutGap", layoutGap)
        put("aod", aod)
        put("doubleTapWake", doubleTapWake)
        put("doubleTapSleep", doubleTapSleep)
        put(ALLOW_SCREENSHOTS, allowScreenshots)
        put("brightnessSwipe", brightnessSwipe)
        put("clockSeconds", clockSeconds)
        put("cutoutRing", cutoutRing)
        put("dischargeEta", dischargeEta)
        put("statusAction", statusAction.name)
        put("statusSleep", statusSleep)
        put("statusPackage", statusTarget.packageName)
        put("statusClass", statusTarget.className)
        put("recorderFormat", recorderFormat.name)
        put("recorderQuality", recorderQuality.name)
        put("recorderDestination", recorderDestination.name)
        put("recorderTreeUri", recorderTreeUri)
        put("recorderFolderLabel", recorderFolderLabel)
        put("recorderNameTemplate", recorderNameTemplate)
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
                eta = json.optBoolean("eta", false),
                networkWidget = json.optBoolean("networkWidget", false),
                batteryWidget = json.optBoolean("batteryWidget", false),
                systemWidget = json.optBoolean("systemWidget", false),
                widgetOrder = json.optString("widgetOrder", "cbns").takeIf { it.length == 4 && it.toSet() == "cbns".toSet() } ?: "cbns",
                layoutGap = json.optInt("layoutGap", 8).coerceIn(8, 56),
                aod = json.optBoolean("aod"),
                doubleTapWake = json.optBoolean("doubleTapWake"),
                doubleTapSleep = json.optBoolean("doubleTapSleep"),
                allowScreenshots = json.optBoolean(ALLOW_SCREENSHOTS),
                brightnessSwipe = json.optBoolean("brightnessSwipe"),
                clockSeconds = json.optBoolean("clockSeconds"),
                cutoutRing = json.optBoolean("cutoutRing"),
                dischargeEta = json.optBoolean("dischargeEta"),
                statusAction = EssentialKeyCapture.entries.firstOrNull { it.name == json.optString("statusAction") } ?: EssentialKeyCapture.NONE,
                statusSleep = json.optBoolean("statusSleep", json.optBoolean("statusDoubleTapSleep")),
                statusTarget = EssentialTarget(json.optString("statusPackage").take(200), json.optString("statusClass").take(300)),
                recorderFormat = RecorderFormat.entries.firstOrNull { it.name == json.optString("recorderFormat") }
                    ?: RecorderFormat.AAC_M4A,
                recorderQuality = RecorderQuality.entries.firstOrNull { it.name == json.optString("recorderQuality") }
                    ?: RecorderQuality.NORMAL,
                recorderDestination = RecorderDestination.entries.firstOrNull { it.name == json.optString("recorderDestination") }
                    ?: RecorderDestination.MUSIC,
                recorderTreeUri = json.optString("recorderTreeUri").take(2000),
                recorderFolderLabel = json.optString("recorderFolderLabel").take(100),
                recorderNameTemplate = RecorderNaming.normalizeTemplate(json.optString("recorderNameTemplate", "Essential-{datetime}")),
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
