// SPDX-License-Identifier: GPL-3.0-only
package moe.xWey10.tweaks.features.recents

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isEmpty
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
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
    private data class App(val packageName: String, val user: UserHandle)

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val bindings = mutableMapOf<FrameLayout, OverviewBinding>()
    private val menuRamTag = Any()
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
                bindings[param.thisObject]?.reserveMemorySpace()
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
                options.findViewWithTag<View>(menuRamTag)?.let(options::removeView)
                if (!tweaks.settings.recentsAppRam || options.orientation != LinearLayout.VERTICAL || options.isEmpty()) return@add
                val target = (param.args.asSequence() + sequenceOf(
                    field(menu, "mTaskContainer", "taskContainer", "mTaskView", "taskView")
                )).filterNotNull().firstNotNullOfOrNull(::appForTask) ?: return@add
                val strings = ModuleText(menu.context, modulePath)
                val (row, label) = memoryMenuRow(options, strings) ?: return@add
                label.text = strings.get(R.string.recents_app_ram_value, "…")
                // Nothing rounds the outer rows; keep the original first and last items on the ends.
                options.addView(row, (options.childCount - 1).coerceAtLeast(0))
                val context = menu.context.applicationContext
                worker.execute {
                    val value = appPss(context, target)?.let {
                        String.format(context.resources.configuration.locales[0], "%,d MiB", it)
                    } ?: "—"
                    main.post {
                        if (row.parent === options) label.text = strings.get(R.string.recents_app_ram_value, value)
                    }
                }
            }
        }
    }

    private fun memoryMenuRow(options: LinearLayout, strings: ModuleText): Pair<View, TextView>? {
        val sample = ((options.childCount - 2).coerceAtLeast(0) downTo 0).firstNotNullOfOrNull { index ->
            val child = options.getChildAt(index)
            (namedView(child, "text") as? TextView)?.let { child to it }
        }
        val stockRow = sample?.first
        val stockLabel = sample?.second
        val optionLayout = launcherResource(options, "task_view_menu_option", "layout")
        val row = if (optionLayout != 0) {
            LayoutInflater.from(options.context).inflate(optionLayout, options, false)
        } else {
            val template = stockRow as? LinearLayout ?: return null
            val templateText = stockLabel ?: return null
            LinearLayout(template.context).apply {
                orientation = template.orientation
                gravity = template.gravity
                val icon = namedView(template, "icon") ?: return null
                val iconView = View(context).apply { id = icon.id }
                addView(iconView, LinearLayout.LayoutParams(icon.layoutParams))
                addView(TextView(context).apply { id = templateText.id },
                    LinearLayout.LayoutParams(templateText.layoutParams))
            }
        }
        val label = namedView(row, "text") as? TextView ?: return null
        row.tag = menuRamTag
        row.isClickable = false
        row.isFocusable = false
        val sourceIcon = stockRow?.let { namedView(it, "icon") }
        val icon = namedView(row, "icon")
        if (stockRow != null && stockLabel != null) {
            row.minimumHeight = stockRow.minimumHeight
            row.setPaddingRelative(stockRow.paddingStart, stockRow.paddingTop, stockRow.paddingEnd, stockRow.paddingBottom)
            // A middle item has the same surface as the native options, including its corner treatment.
            stockRow.background?.constantState?.newDrawable(row.resources)?.mutate()?.let { row.background = it }
            row.backgroundTintList = stockRow.backgroundTintList
            val params = stockRow.layoutParams as? LinearLayout.LayoutParams
            row.layoutParams = params?.let { LinearLayout.LayoutParams(it) }
                ?: LinearLayout.LayoutParams(-1, -2)
            label.copyTypefaceFrom(stockLabel)
        } else {
            row.layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        val glyph = strings.drawable(R.drawable.ic_recents_memory)
        if (icon is android.widget.ImageView) {
            icon.setImageDrawable(glyph)
            icon.imageTintList = label.textColors
        } else if (icon != null) {
            icon.background = glyph
            icon.backgroundTintList = sourceIcon?.backgroundTintList ?: label.textColors
        }
        icon?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        return row to label
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
        private val memory = TextView(screenshot.context).apply {
            copyTypefaceFrom(screenshot)
            setTextSize(13f)
            gravity = Gravity.CENTER
            setShadowLayer(dp(2).toFloat(), 0f, 1f, Color.BLACK)
            visibility = View.INVISIBLE
        }
        private val screenshotBounds = Rect()
        private val copy = createCopyButton()
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
                reserveMemorySpace()
                host.invalidate()
            } else {
                restoreActionMargin()
                stopSampling()
                host.removeView(memory)
            }
            if (row?.orientation == LinearLayout.HORIZONTAL) {
                if (tweaks.settings.recentsActions) {
                    if (copy.parent == null) {
                        val params = LinearLayout.LayoutParams(screenshot.layoutParams as LinearLayout.LayoutParams)
                        params.width = ViewGroup.LayoutParams.WRAP_CONTENT
                        params.weight = 0f
                        params.marginStart += copy.dp(6)
                        row.addView(copy, row.indexOfChild(screenshot) + 1, params)
                    }
                } else {
                    row.removeView(copy)
                }
            }
        }

        fun reserveMemorySpace() {
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
            if (copy.parent != null) {
                copy.isVisible = screenshot.isVisible
                copy.isEnabled = screenshot.isEnabled
                copy.alpha = screenshot.alpha
            }
            if (memory.currentTextColor != screenshot.currentTextColor) memory.setTextColor(screenshot.currentTextColor)
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

        private fun createCopyButton(): Button = Button(screenshot.context).apply {
            text = strings.get(R.string.recents_action_copy)
            contentDescription = strings.get(R.string.recents_action_copy_package)
            copyTypefaceFrom(screenshot)
            gravity = screenshot.gravity
            minimumHeight = screenshot.minimumHeight
            minimumWidth = screenshot.minimumWidth
            background = screenshot.background?.constantState?.newDrawable(resources)?.mutate()
            backgroundTintList = screenshot.backgroundTintList
            if (background == null) {
                val ripple = TypedValue()
                if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
                    setBackgroundResource(ripple.resourceId)
                }
            }
            setPaddingRelative(screenshot.paddingStart, screenshot.paddingTop, screenshot.paddingEnd, screenshot.paddingBottom)
            val screenshotIcon = screenshot.compoundDrawablesRelative[0]
            val icon = strings.drawable(R.drawable.ic_recents_copy)
            val size = screenshotIcon?.bounds?.width()?.takeIf { it > 0 } ?: dp(18)
            icon?.setBounds(0, 0, size, size)
            compoundDrawablePadding = screenshot.compoundDrawablePadding
            setCompoundDrawablesRelative(icon, null, null, null)
            TextViewCompat.setCompoundDrawableTintList(this,
                screenshot.compoundDrawableTintList ?: screenshot.textColors)
            isFocusable = true
            setOnClickListener {
                val target = currentApp(host) ?: return@setOnClickListener
                runCatching {
                    val clipboard = checkNotNull(context.getSystemService(ClipboardManager::class.java))
                    clipboard.setPrimaryClip(ClipData.newPlainText(contentDescription, target.packageName))
                }.onFailure {
                    HookLog.error("Recents copy action unavailable", it)
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
        return App(packageName, user)
    }

    private fun namedView(host: View, name: String): View? {
        val id = launcherResource(host, name, "id")
        return if (id != 0) host.findViewById(id) else null
    }

    @SuppressLint("DiscouragedApi") // Resolve the launcher package’s resource IDs at runtime.
    private fun launcherResource(host: View, name: String, type: String): Int {
        val resources = host.resources
        val owner = runCatching { resources.getResourcePackageName(host.id) }.getOrNull()
        return sequenceOf(host.context.packageName, owner, "com.android.launcher3").filterNotNull().distinct()
            .map { resources.getIdentifier(name, type, it) }.firstOrNull { it != 0 } ?: 0
    }

    private fun field(owner: Any, vararg names: String): Any? =
        names.firstNotNullOfOrNull { name ->
            runCatching { XposedHelpers.getObjectField(owner, name) }.getOrNull()
        }

    private fun call(owner: Any, name: String): Any? =
        runCatching { XposedHelpers.callMethod(owner, name) }.getOrNull()

    private fun TextView.copyTypefaceFrom(native: TextView) {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, native.textSize)
        setTextColor(native.textColors)
        typeface = native.typeface
        letterSpacing = native.letterSpacing
        includeFontPadding = native.includeFontPadding
        isAllCaps = native.isAllCaps
    }

    private fun View.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
