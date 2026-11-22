// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.features.lockscreen

import android.hardware.Sensor
import android.os.SystemClock
import android.view.ViewConfiguration
import moe.xWey10.tweaks.hooks.FeatureHooks
import moe.xWey10.tweaks.hooks.TweakReader
import java.util.WeakHashMap
import java.util.function.Consumer

internal class DoubleTapWake(private val loader: ClassLoader, private val tweaks: TweakReader) {
    private fun type(name: String): Class<*> = Class.forName(name, false, loader)

    fun install() = FeatureHooks("Double tap to wake").install {
        val sensors = type("com.android.systemui.doze.DozeSensors")
        val trigger = type("com.android.systemui.doze.DozeSensors\$TriggerSensor")
        val triggers = type("com.nothing.systemui.doze.DozeTriggersEx")
        val host = type("com.android.systemui.doze.DozeHost")
        val state = type("com.android.systemui.doze.DozeMachine\$State")
        val reason = trigger.getDeclaredField("mPulseReason").apply { isAccessible = true }
        val hardware = trigger.getDeclaredField("mSensors").apply { isAccessible = true }
        val posture = trigger.getDeclaredField("mPosture").apply { isAccessible = true }
        val configured = trigger.getDeclaredField("mConfigured").apply { isAccessible = true }
        val triggerList = sensors.getDeclaredField("mTriggerSensors").apply { isAccessible = true }
        val ownerSensors = triggers.getDeclaredField("mDozeSensors").apply { isAccessible = true }
        val setting = trigger.getDeclaredMethod("enabledBySetting")
        val update = sensors.getDeclaredMethod("updateListening").apply { isAccessible = true }
        val onTap = host.getMethod("onSlpiTap", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
        val arguments = arrayOf(host, state, Consumer::class.java, Float::class.javaPrimitiveType!!,
            Float::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
        val single = triggers.getDeclaredMethod("handleSingleTapEvent", *arguments)
        val double = triggers.getDeclaredMethod("handleDoubleTapEvent", *arguments)

        val instances = WeakHashMap<Any, Unit>()
        val firstTapAt = WeakHashMap<Any, Long>()
        var enabled = false
        fun hardwareTap(sensor: Any): Boolean {
            val current = (hardware.get(sensor) as Array<*>).getOrNull(posture.getInt(sensor))
            return configured.getBoolean(sensor) && current is Sensor
        }

        fun hasDoubleSensor(owner: Any): Boolean {
            val instance = ownerSensors.get(owner) ?: return false
            return (triggerList.get(instance) as Array<*>).filterNotNull().any {
                reason.getInt(it) == DOUBLE_TAP && hardwareTap(it)
            }
        }

        sensors.declaredConstructors.forEach { constructor ->
            add(constructor) { instances[it.thisObject] = Unit }
        }
        add(setting) { param ->
            if (!enabled) return@add
            val sensorReason = reason.getInt(param.thisObject)
            if ((sensorReason == SINGLE_TAP || sensorReason == DOUBLE_TAP) && hardwareTap(param.thisObject)) {
                param.result = true
            }
        }
        add(single, before = true) { param ->
            if (!enabled) return@add
            val supportsDouble = hasDoubleSensor(param.thisObject)
            param.result = null
            if (supportsDouble || !wakeState(param.args[1])) return@add
            // Some panels expose only single-tap events. The stock trigger re-arms after each event.
            val now = SystemClock.uptimeMillis()
            val first = firstTapAt.put(param.thisObject, now) ?: return@add
            if (now - first in 40L..(ViewConfiguration.getDoubleTapTimeout() + 100L)) {
                firstTapAt.remove(param.thisObject)
                wake(param.args, onTap)
            }
        }
        add(double, before = true) { param ->
            if (!enabled || !wakeState(param.args[1])) return@add
            firstTapAt.remove(param.thisObject)
            param.result = null
            wake(param.args, onTap)
        }
        tweaks.observe {
            if (enabled != tweaks.settings.doubleTapWake) {
                enabled = tweaks.settings.doubleTapWake
                firstTapAt.clear()
                instances.keys.toList().forEach { instance ->
                    update.invoke(instance)
                }
            }
        }
    }

    private fun wakeState(value: Any?) = (value as? Enum<*>)?.name in wakeStates

    @Suppress("UNCHECKED_CAST")
    private fun wake(args: Array<Any?>, notifyTap: java.lang.reflect.Method) {
        // Nothing's handlers are called after DozeTriggers' proximity check.
        notifyTap.invoke(args[0], args[3], args[4])
        (args[2] as Consumer<Int>).accept(DOUBLE_TAP)
    }

    private companion object {
        const val DOUBLE_TAP = 4
        const val SINGLE_TAP = 9
        val wakeStates = setOf("DOZE", "DOZE_AOD", "DOZE_PULSING", "DOZE_AOD_DOCKED")
    }
}
