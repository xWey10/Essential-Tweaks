// SPDX-License-Identifier: GPL-3.0-only
package moe.xWey10.tweaks.features.navigation

import android.graphics.Canvas
import android.util.SparseBooleanArray
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XposedHelpers
import moe.xWey10.tweaks.hooks.FeatureHooks
import moe.xWey10.tweaks.hooks.TweakReader
import java.util.Collections
import java.util.WeakHashMap

internal class GestureHandle(private val loader: ClassLoader, private val tweaks: TweakReader) {
    private val handles = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val systemHandleClasses = listOf(
        "com.android.systemui.navigationbar.gestural.NavigationHandle",
        "com.android.systemui.navigationbar.gestural.QuickswitchOrientedNavHandle",
        "com.android.systemui.navigationbar.views.buttons.NavigationHandle",
    )

    fun installSystemUi() {
        val supported = systemHandleClasses.mapNotNull { XposedHelpers.findClassIfExists(it, loader) }
        supported.forEach(::hookOnDraw)
        val ids = SparseBooleanArray()
        hookChildDrawing { child ->
            if (child.id <= 0 || child.id ushr 24 == 0) {
                false
            } else {
                val index = ids.indexOfKey(child.id)
                val matches = if (index >= 0) {
                    ids.valueAt(index)
                } else {
                    // The resource entry survives an OEM changing the class or the XML namespace.
                    runCatching { child.resources.getResourceEntryName(child.id) == "home_handle" }
                        .getOrDefault(false).also { ids.put(child.id, it) }
                }
                matches
            }
        }
        observe()
    }

    fun installLauncher() {
        val type = XposedHelpers.findClassIfExists("com.android.launcher3.taskbar.StashedHandleView", loader)
            ?: return
        hookOnDraw(type)
        hookChildDrawing(type::isInstance)
        observe()
    }

    private fun hookOnDraw(type: Class<*>) {
        val method = runCatching { type.getDeclaredMethod("onDraw", Canvas::class.java) }.getOrNull()
            ?: return
        FeatureHooks("Gesture handle drawing: " + type.simpleName).install {
            add(method, before = true) { param ->
                handles.add(param.thisObject as View)
                if (tweaks.settings.hideGestureHandle) param.result = null
            }
        }
    }

    private fun hookChildDrawing(isHandle: (View) -> Boolean) = FeatureHooks("Gesture handle child drawing").install {
        val method = ViewGroup::class.java.getDeclaredMethod("drawChild", Canvas::class.java,
            View::class.java, Long::class.javaPrimitiveType)
        add(method, before = true) { param ->
            val child = param.args[1] as View
            if (!isHandle(child)) return@add
            handles.add(child)
            // Skip only pixels. Keep the stock visibility, hit rectangle, insets and listeners.
            if (tweaks.settings.hideGestureHandle) param.result = false
        }
    }

    private fun observe() {
        var hidden = tweaks.settings.hideGestureHandle
        tweaks.observe {
            val next = tweaks.settings.hideGestureHandle
            if (hidden != next) {
                hidden = next
                handles.toList().forEach { view ->
                    view.invalidate()
                    (view.parent as? View)?.invalidate()
                }
            }
        }
    }
}
