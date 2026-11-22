// SPDX-License-Identifier: GPL-3.0-only
package moe.xWey10.tweaks.features.recents

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.LauncherApps
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import de.robv.android.xposed.XposedHelpers
import moe.xWey10.tweaks.R
import moe.xWey10.tweaks.features.powermenu.ModuleText
import moe.xWey10.tweaks.hooks.FeatureHooks
import moe.xWey10.tweaks.hooks.HookLog
import moe.xWey10.tweaks.hooks.TweakReader
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Runs in the stock launcher, which already holds the Recents role and task access. */
internal class RecentsFeature(
    private val loader: ClassLoader,
    private val tweaks: TweakReader,
    private val modulePath: String,
) {
    private data class App(val packageName: String, val component: ComponentName?, val user: UserHandle)

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val bindings = mutableMapOf<FrameLayout, OverviewBinding>()
    private val menuBadgeTag = Any()
    private var pssErrorLogged = false

    fun install() {
        installOverview()
        installMenu("TaskMenuView")
        installMenu("TaskMenuViewWithArrow")
    }

    private fun installOverview() = FeatureHooks("Recents overview").install {
        val overview = XposedHelpers.findClass("com.android.quickstep.views.OverviewActionsView", loader)
        add(overview.getDeclaredMethod("onFinishInflate")) { param ->
            val host = param.thisObject as FrameLayout
            val screenshot = namedView(host, "action_screenshot") as? TextView
                ?: field(host, "mScreenshotButton") as? TextView
                ?: return@add
            val binding = OverviewBinding(host, screenshot)
            host.addOnAttachStateChangeListener(binding)
            if (host.isAttachedToWindow) binding.onViewAttachedToWindow(host)
        }
        overview.declaredMethods.filter { it.name == "updateVerticalMargin" }.forEach { update ->
            add(update, before = true) { param ->
                bindings[param.thisObject]?.restoreActionMargin()
            }
            add(update) { param ->
                bindings[param.thisObject]?.reserveBadgeSpace()
            }
        }
        tweaks.observe { bindings.values.forEach(OverviewBinding::applySettings) }
    }

    private fun installMenu(name: String) {
        val type = XposedHelpers.findClassIfExists("com.android.quickstep.views." + name, loader) ?: return
        FeatureHooks("Recents app RAM: " + name).install {
            // Legacy menus choose the options orientation after adding the stock rows.
            val methods = type.declaredMethods
            val populate = listOf("populateAndLayoutMenu", "populateMenu", "populateAndShowForTask", "addMenuOptions")
                .firstNotNullOf { name -> methods.firstOrNull { it.name == name && !it.isSynthetic } }
            add(populate) { param ->
                if (param.result == false) return@add
                val menu = param.thisObject as? ViewGroup ?: return@add
                val options = field(menu, "mOptionLayout", "optionLayout") as? LinearLayout
                    ?: namedView(menu, "menu_option_layout") as? LinearLayout
                    ?: return@add
                options.findViewWithTag<View>(menuBadgeTag)?.let(options::removeView)
                if (!tweaks.settings.recentsAppRam || options.orientation != LinearLayout.VERTICAL) return@add
                val target = (param.args.asSequence() + sequenceOf(
                    field(menu, "mTaskContainer", "taskContainer", "mTaskView", "taskView")
                )).filterNotNull().firstNotNullOfOrNull(::appForTask) ?: return@add
                val text = ModuleText(menu.context, modulePath)
                val label = badge(menu.context).apply {
                    tag = menuBadgeTag
                    setTextSize(13f)
                    setText(text.get(R.string.recents_app_ram_value, "…"))
                }
                options.addView(label, LinearLayout.LayoutParams(-2, -2).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = label.dp(6)
                    bottomMargin = label.dp(6)
                })
                val context = menu.context.applicationContext
                worker.execute {
                    val value = appPss(context, target)?.let {
                        String.format(context.resources.configuration.locales[0], "%,d MiB", it)
                    } ?: "—"
                    main.post {
                        if (label.parent === options) {
                            label.text = text.get(R.string.recents_app_ram_value, value)
                        }
                    }
                }
            }
        }
    }

    private fun appPss(context: Context, app: App): Long? {
        return runCatching {
            val manager = context.getSystemService(ActivityManager::class.java) ?: return null
            val pids = manager.runningAppProcesses.orEmpty()
                .filter { UserHandle.getUserHandleForUid(it.uid) == app.user && app.packageName in it.pkgList.orEmpty() }
                .map { it.pid }.distinct().toIntArray()
            if (pids.isEmpty()) return null
            val pss = manager.getProcessMemoryInfo(pids).sumOf { it.totalPss.toLong() }
            pss.takeIf { it > 0 }?.let { (it + 512) / 1024 }
        }.onFailure {
            if (!pssErrorLogged) HookLog.error("Recents app RAM sampling unavailable", it)
            pssErrorLogged = true
        }.getOrNull()
    }

    private inner class OverviewBinding(
        private val host: FrameLayout,
        private val screenshot: TextView,
    ) : View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener, Runnable {
        private val strings = ModuleText(host.context, modulePath)
        private val row = screenshot.parent as? LinearLayout
        private val memory = badge(host.context).apply { visibility = View.INVISIBLE }
        private val screenshotBounds = Rect()
        private val info = button(R.string.recents_action_info, R.string.recents_action_app_info) { view, app ->
            val component = app.component ?: return@button
            view.context.getSystemService(LauncherApps::class.java)
                ?.startAppDetailsActivity(component, app.user, null, null)
        }
        private val copy = button(R.string.recents_action_copy, R.string.recents_action_copy_package) { view, app ->
            view.context.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText(strings.get(R.string.recents_action_copy_package), app.packageName))
        }
        private var scheduled = false
        private var visible = false
        private var samplingGeneration = 0
        private var actionMargin: FrameLayout.LayoutParams? = null
        private var originalBottomMargin = 0

        override fun onViewAttachedToWindow(v: View) {
            bindings[host] = this
            host.viewTreeObserver.addOnPreDrawListener(this)
            applySettings()
        }

        override fun onViewDetachedFromWindow(v: View) {
            bindings.remove(host)
            host.viewTreeObserver.removeOnPreDrawListener(this)
            restoreActionMargin()
            stopSampling()
        }

        fun applySettings() {
            if (tweaks.settings.recentsAvailableRam) {
                if (memory.parent == null) {
                    host.addView(memory, FrameLayout.LayoutParams(-2, -2,
                        Gravity.TOP or Gravity.CENTER_HORIZONTAL))
                }
                reserveBadgeSpace()
                host.invalidate()
            } else {
                restoreActionMargin()
                stopSampling()
                host.removeView(memory)
            }
            if (row?.orientation == LinearLayout.HORIZONTAL) {
                if (tweaks.settings.recentsActions) {
                    if (info.parent == null) {
                        row.addView(info, row.indexOfChild(screenshot) + 1, LinearLayout.LayoutParams(-2, -2))
                        row.addView(copy, row.indexOfChild(info) + 1, LinearLayout.LayoutParams(-2, -2))
                    }
                } else {
                    row.removeView(info)
                    row.removeView(copy)
                }
            }
        }

        fun reserveBadgeSpace() {
            if (memory.parent == null || actionMargin != null || row?.parent !== host) return
            val params = row.layoutParams as? FrameLayout.LayoutParams ?: return
            if (params.gravity == -1 || params.gravity and Gravity.VERTICAL_GRAVITY_MASK != Gravity.BOTTOM) return
            actionMargin = params
            originalBottomMargin = params.bottomMargin
            params.bottomMargin += memory.dp(29)
            row.requestLayout()
        }

        fun restoreActionMargin() {
            val params = actionMargin ?: return
            if (row?.layoutParams === params && params.bottomMargin == originalBottomMargin + memory.dp(29)) {
                params.bottomMargin = originalBottomMargin
                row.requestLayout()
            }
            actionMargin = null
        }

        override fun onPreDraw(): Boolean {
            val shown = host.isShown && host.alpha > .01f && screenshot.isShown && screenshot.alpha > .01f
            if (info.parent != null) {
                val visibility = if (screenshot.isVisible) View.VISIBLE else View.GONE
                if (info.visibility != visibility) {
                    info.visibility = visibility
                    copy.visibility = visibility
                }
                info.isEnabled = screenshot.isEnabled
                copy.isEnabled = screenshot.isEnabled
            }
            if (memory.parent != null && shown) {
                screenshotBounds.set(0, 0, screenshot.width, screenshot.height)
                host.offsetDescendantRectToMyCoords(screenshot, screenshotBounds)
                val insetBottom = host.rootWindowInsets?.getInsets(WindowInsets.Type.navigationBars())?.bottom ?: 0
                val gap = memory.dp(5)
                val yBelow = screenshotBounds.bottom + gap
                val maxY = host.height - insetBottom - memory.height
                val y = if (yBelow <= maxY) yBelow else screenshotBounds.top - gap - memory.height
                if (y >= 0 && memory.text.isNotEmpty()) {
                    memory.translationY = y.toFloat()
                    memory.visibility = View.VISIBLE
                } else {
                    memory.visibility = View.INVISIBLE
                }
                if (!visible) {
                    visible = true
                    schedule(0)
                }
            } else if (visible) {
                stopSampling()
            }
            return true
        }

        private fun schedule(delay: Long) {
            if (!scheduled) {
                scheduled = true
                main.postDelayed(this, delay)
            }
        }

        private fun stopSampling() {
            main.removeCallbacks(this)
            scheduled = false
            visible = false
            samplingGeneration++
            memory.visibility = View.INVISIBLE
        }

        override fun run() {
            if (!visible) {
                scheduled = false
                return
            }
            val context = host.context.applicationContext
            val generation = samplingGeneration
            worker.execute {
                val info = ActivityManager.MemoryInfo()
                val updated = runCatching { context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info) }
                    .isSuccess && info.totalMem > 0
                main.post {
                    if (generation != samplingGeneration || !visible) return@post
                    scheduled = false
                    if (updated) {
                        val locale = host.resources.configuration.locales[0]
                        val available = String.format(locale, "%.1f", info.availMem / 1073741824.0)
                        val total = String.format(locale, "%.1f", info.totalMem / 1073741824.0)
                        val value = strings.get(R.string.recents_available_ram_value, available, total)
                        if (memory.text != value) memory.text = value
                    }
                    schedule(2000)
                }
            }
        }

        private fun button(label: Int, description: Int, click: (View, App) -> Unit): TextView =
            TextView(host.context).apply {
                setText(strings.get(label))
                contentDescription = strings.get(description)
                gravity = Gravity.CENTER
                setTextSize(12f)
                setTextColor(screenshot.textColors)
                setPaddingRelative(dp(7), 0, dp(7), 0)
                minimumHeight = dp(48)
                isFocusable = true
                val ripple = android.util.TypedValue()
                if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
                    setBackgroundResource(ripple.resourceId)
                }
                setOnClickListener { view ->
                    val target = currentApp(host) ?: return@setOnClickListener
                    runCatching { click(view, target) }.onFailure {
                        HookLog.error("Recents action unavailable", it)
                        Toast.makeText(context, strings.get(R.string.recents_action_unavailable), Toast.LENGTH_SHORT).show()
                    }
                }
            }
    }

    private fun currentApp(host: View): App? {
        var context: Context? = host.context
        var panel: Any? = null
        while (context != null && panel == null) {
            panel = call(context, "getOverviewPanel")
            val base = (context as? ContextWrapper)?.baseContext
            context = base?.takeUnless { it === context }
        }
        panel = panel ?: namedView(host.rootView, "overview_panel") ?: return null
        val card = call(panel, "getCurrentPageTaskView")
            ?: call(panel, "getFocusedTaskView")
            ?: return null
        return appForTask(card)
    }

    private fun appForTask(source: Any): App? {
        val task = call(source, "getTask") ?: call(source, "getFirstTask") ?: source
        val key = field(task, "key") ?: return null
        val component = call(key, "getComponent") as? ComponentName
        val packageName = component?.packageName ?: call(key, "getPackageName") as? String ?: return null
        val userId = field(key, "userId") as? Int ?: return null
        if (userId < 0) return null
        val user = XposedHelpers.callStaticMethod(UserHandle::class.java, "of", userId) as UserHandle
        return App(packageName, component, user)
    }

    @SuppressLint("DiscouragedApi") // Resolve the launcher package’s resource IDs at runtime.
    private fun namedView(host: View, name: String): View? {
        val id = host.resources.getIdentifier(name, "id", host.context.packageName)
        return if (id != 0) host.findViewById(id) else null
    }

    private fun field(owner: Any, vararg names: String): Any? =
        names.firstNotNullOfOrNull { name ->
            runCatching { XposedHelpers.getObjectField(owner, name) }.getOrNull()
        }

    private fun call(owner: Any, name: String): Any? =
        runCatching { XposedHelpers.callMethod(owner, name) }.getOrNull()

    private fun badge(context: Context) = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(13f)
        gravity = Gravity.CENTER
        setPaddingRelative(dp(9), dp(3), dp(9), dp(3))
        background = GradientDrawable().apply {
            setColor(0xCC000000.toInt())
            cornerRadius = dp(8).toFloat()
        }
    }

    private fun View.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
