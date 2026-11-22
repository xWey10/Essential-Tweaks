// SPDX-License-Identifier: GPL-3.0-only
package moe.xWey10.tweaks.features.navigation

import android.graphics.Canvas
import android.view.View
import de.robv.android.xposed.XposedHelpers
import moe.xWey10.tweaks.hooks.FeatureHooks
import moe.xWey10.tweaks.hooks.TweakReader
import java.util.Collections
import java.util.WeakHashMap

internal class GestureHandle(private val loader: ClassLoader, private val tweaks: TweakReader) {
    fun install() {
        val handles = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
        val classes = listOf("NavigationHandle", "QuickswitchOrientedNavHandle")
        for (name in classes) {
            val type = XposedHelpers.findClassIfExists(
                "com.android.systemui.navigationbar.gestural." + name, loader
            ) ?: continue
            val draw = runCatching { type.getDeclaredMethod("onDraw", Canvas::class.java) }.getOrNull()
                ?: continue // An inherited draw method is already covered by the base handle.
            FeatureHooks("Gesture handle drawing: " + name).install {
                add(draw, before = true) { param ->
                    handles.add(param.thisObject as View)
                    // Leave the view, hit region, insets and input dispatch intact.
                    if (tweaks.settings.hideGestureHandle) param.result = null
                }
            }
        }
        tweaks.observe { handles.toList().forEach(View::invalidate) }
    }
}
