// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.ui

import android.Manifest
import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.provider.DocumentsContract
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import moe.xWey10.tweaks.R
import moe.xWey10.tweaks.core.DotsMode
import moe.xWey10.tweaks.core.EssentialTarget
import moe.xWey10.tweaks.core.FingerprintImage
import moe.xWey10.tweaks.core.LocalConfig
import moe.xWey10.tweaks.core.LocalTweaks
import moe.xWey10.tweaks.core.RecorderDestination
import moe.xWey10.tweaks.core.RecorderFormat
import moe.xWey10.tweaks.core.RecorderNaming
import moe.xWey10.tweaks.core.RecorderQuality
import moe.xWey10.tweaks.core.Protocol
import moe.xWey10.tweaks.core.RebootTarget
import moe.xWey10.tweaks.core.TweakSettings
import moe.xWey10.tweaks.features.essentialkey.EssentialKeyAction
import moe.xWey10.tweaks.features.essentialkey.EssentialKeyCapture
import moe.xWey10.tweaks.features.essentialkey.EssentialKeyGesture
import moe.xWey10.tweaks.features.essentialkey.EssentialKeyResult
import moe.xWey10.tweaks.features.essentialkey.restoresStock
import moe.xWey10.tweaks.updates.GitHubUpdates
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private enum class Page(val title: Int) {
        HOME(R.string.app_name), POWER(R.string.power_menu),
        FINGERPRINT(R.string.fingerprint),
        LOCKSCREEN(R.string.lockscreen_gestures), ESSENTIAL_KEY(R.string.essential_key),
        SCREENSHOTS(R.string.screenshots), STATUS_BAR(R.string.status_bar)
    }

    private lateinit var config: LocalConfig
    private lateinit var essentialKey: EssentialKeyViewModel
    private lateinit var tweaks: LocalTweaks
    private lateinit var githubUpdates: GitHubUpdates
    private var checkingUpdates = false
    private var manualUpdateError = false
    private lateinit var root: LinearLayout
    private var renderedPage: Page? = null
    private var scrollView: ScrollView? = null
    private val main = Handler(Looper.getMainLooper())
    private var page = Page.HOME
    private var imageImporting = false
    private var nonce = ""
    private var receiverRegistered = false
    private var statusTitle: TextView? = null
    private var statusNote: TextView? = null
    private var statusDot: View? = null
    private var status = R.string.checking
    private var note: Int? = null
    private lateinit var back: OnBackPressedCallback

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && !imageImporting) {
            imageImporting = true
            render()
            Thread {
                val image = runCatching { loadFingerprintImage(contentResolver, uri) }.getOrNull()
                main.post {
                    imageImporting = false
                    if (isDestroyed || isFinishing) return@post
                    if (image == null) {
                        Toast.makeText(this, R.string.image_failed, Toast.LENGTH_LONG).show()
                        render()
                    } else {
                        render()
                        showCropEditor(image)
                    }
                }
            }.start()
        }
    }

    private val grantMicrophone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (!allowed) Toast.makeText(this, R.string.recorder_permission_needed, Toast.LENGTH_LONG).show()
        if (!isFinishing && !isDestroyed) render()
    }

    private val chooseRecorderFolder = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        val flags = result.data?.flags ?: 0
        val writeFlags = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (writeFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0) {
            Toast.makeText(this, R.string.recorder_folder_failed, Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        runCatching {
            contentResolver.takePersistableUriPermission(uri, writeFlags)
            val label = runCatching {
                val document = DocumentsContract.buildDocumentUriUsingTree(uri,
                    DocumentsContract.getTreeDocumentId(uri))
                contentResolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull().orEmpty().ifBlank { getString(R.string.recorder_custom_folder) }
            saveTweaks(tweaks.current.copy(recorderDestination = RecorderDestination.CUSTOM,
                recorderTreeUri = uri.toString(), recorderFolderLabel = label.take(100)))
        }.onFailure {
            Toast.makeText(this, R.string.recorder_folder_failed, Toast.LENGTH_LONG).show()
        }
    }

    private val response = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val systemUid = try { packageManager.getPackageUid(Protocol.SYSTEM_UI, 0) }
                catch (_: Exception) { return }
            if (sentFromUid != systemUid || intent.action != Protocol.RESPONSE ||
                intent.getStringExtra("nonce") != nonce) return
            main.removeCallbacks(timeout)
            when {
                !intent.getBooleanExtra("ready", false) -> setStatus(R.string.inactive, R.string.unsupported_note)
                !config.published || !intent.getBooleanExtra("readable", false) ||
                    intent.getLongExtra("revision", -1) != config.current.revision ->
                    setStatus(R.string.inactive, R.string.sync_note)
                else -> setStatus(R.string.active, null)
            }
        }
    }
    private val timeout = Runnable { setStatus(R.string.inactive, R.string.setup_note) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = LocalConfig(this)
        essentialKey = ViewModelProvider(this)[EssentialKeyViewModel::class.java]
        tweaks = LocalTweaks(this)
        githubUpdates = GitHubUpdates(this)
        tweaks.publish()
        page = Page.entries.firstOrNull { it.name == savedInstanceState?.getString("page") } ?: Page.HOME
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        back = object : OnBackPressedCallback(page != Page.HOME) {
            override fun handleOnBackPressed() { page = Page.HOME; render() }
        }
        onBackPressedDispatcher.addCallback(this, back)
        essentialKey.state.observe(this) { state ->
            if (state.operation == EssentialKeyViewModel.Operation.RESTORE &&
                state.result?.restoresStock == true &&
                tweaks.current.essentialKey.isNotEmpty()
            ) {
                // Original OEM handling must receive the physical key events, not our gesture tracker.
                saveTweaks(tweaks.current.copy(essentialKey = emptyMap(), essentialTargets = emptyMap()))
            } else if (page == Page.ESSENTIAL_KEY) {
                render()
            }
        }
        render()
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(response, IntentFilter(Protocol.RESPONSE), Protocol.PLATFORM_SENDER_PERMISSION,
            null, Context.RECEIVER_EXPORTED)
        receiverRegistered = true
        queryStatus()
        if (githubUpdates.shouldAutoCheck()) checkUpdates(manual = false)
    }

    override fun onStop() {
        if (receiverRegistered) { unregisterReceiver(response); receiverRegistered = false }
        main.removeCallbacks(timeout)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", page.name)
        super.onSaveInstanceState(outState)
    }

    private fun queryStatus() {
        if (!receiverRegistered) return
        nonce = UUID.randomUUID().toString()
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, 2000)
        sendBroadcast(Intent(Protocol.QUERY).setPackage(Protocol.SYSTEM_UI).putExtra("nonce", nonce),
            null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
    }

    private fun render() {
        val previousScroll = if (renderedPage == page) scrollView?.scrollY ?: 0 else 0
        renderedPage = page
        statusTitle = null; statusNote = null; statusDot = null
        back.isEnabled = page != Page.HOME
        root = column().apply { setBackgroundColor(getColor(R.color.surface)) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
        root.addView(header())
        val content = column().apply { setPadding(dp(20), 0, dp(20), dp(24)) }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content, ViewGroup.LayoutParams(-1, -2))
        }
        scrollView = scroll
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (page == Page.POWER) {
            content.addView(setting(RebootTarget.RECOVERY, R.string.recovery, R.string.recovery_summary, R.string.show_recovery))
            content.addView(divider())
            content.addView(setting(RebootTarget.BOOTLOADER, R.string.bootloader, R.string.bootloader_summary, R.string.show_bootloader))
            content.addView(divider())
            content.addView(setting(RebootTarget.SYSTEM_UI, R.string.restart_system_ui, R.string.restart_system_ui_summary, R.string.show_restart_system_ui))
            content.addView(divider())
            content.addView(toggleRow(R.string.confirm_normal_reboot, config.current.confirmNormalReboot) { enabled ->
                try { config.updateStockConfirm(reboot = enabled); queryStatus() }
                catch (_: Exception) { Toast.makeText(this, R.string.save_failed, Toast.LENGTH_LONG).show(); render() }
            })
            content.addView(divider())
            content.addView(toggleRow(R.string.confirm_power_off, config.current.confirmPowerOff) { enabled ->
                try { config.updateStockConfirm(powerOff = enabled); queryStatus() }
                catch (_: Exception) { Toast.makeText(this, R.string.save_failed, Toast.LENGTH_LONG).show(); render() }
            })
            content.addView(divider())
            content.addView(text(getString(R.string.apply_note), 14f, secondary = true).apply {
                setPadding(0, dp(20), 0, 0)
            })
            content.addView(text(getString(R.string.power_permissions_note), 14f, secondary = true).apply {
                setPadding(0, dp(12), 0, 0)
            })
        } else if (page == Page.FINGERPRINT) {
            addFingerprintSettings(content)
        } else if (page == Page.LOCKSCREEN) {
            content.addView(toggleRow(R.string.double_tap_wake, tweaks.current.doubleTapWake) {
                saveTweaks(tweaks.current.copy(doubleTapWake = it))
            })
            content.addView(divider())
            content.addView(toggleRow(R.string.double_tap_sleep, tweaks.current.doubleTapSleep) {
                saveTweaks(tweaks.current.copy(doubleTapSleep = it))
            })
            content.addView(divider())
            content.addView(text(getString(R.string.lockscreen_gestures_note), 14f, secondary = true).apply {
                setPadding(0, dp(16), 0, 0)
            })
            content.addView(text(getString(R.string.charging_info), 14f, secondary = true).apply {
                setPadding(0, dp(28), 0, dp(8))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                ViewCompat.setAccessibilityHeading(this, true)
            })
            addChargingSettings(content)
            addLockSettings(content)
        } else if (page == Page.ESSENTIAL_KEY) {
            addEssentialKey(content)
        } else if (page == Page.STATUS_BAR) {
            content.addView(toggleRow(R.string.brightness_swipe, tweaks.current.brightnessSwipe) {
                saveTweaks(tweaks.current.copy(brightnessSwipe = it))
            })
            content.addView(divider())
            content.addView(toggleRow(R.string.clock_seconds, tweaks.current.clockSeconds) {
                saveTweaks(tweaks.current.copy(clockSeconds = it))
            })
            content.addView(divider())
            content.addView(toggleRow(R.string.cutout_ring, tweaks.current.cutoutRing) {
                saveTweaks(tweaks.current.copy(cutoutRing = it))
            })
            content.addView(divider())
            addStatusAction(content)
            content.addView(divider())
            content.addView(text(getString(R.string.status_bar_note), 14f, secondary = true).apply {
                setPadding(0, dp(16), 0, 0)
            })
        } else if (page == Page.SCREENSHOTS) {
            content.addView(toggleRow(R.string.allow_screenshots, tweaks.current.allowScreenshots) {
                saveTweaks(tweaks.current.copy(allowScreenshots = it))
            })
            content.addView(divider())
            content.addView(text(getString(R.string.screenshot_requirements), 14f, secondary = true).apply {
                setPadding(0, dp(16), 0, 0)
            })
        } else {
            content.addView(statusView())
            content.addView(text(getString(R.string.system), 14f, secondary = true).apply {
                setPadding(0, dp(28), 0, dp(8))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                ViewCompat.setAccessibilityHeading(this, true)
            })
            content.addView(sectionRow(Page.POWER, R.drawable.ic_power, R.string.power_menu_summary))
            content.addView(divider())
            content.addView(sectionRow(Page.FINGERPRINT, R.drawable.ic_fingerprint, R.string.fingerprint_summary))
            content.addView(divider())
            content.addView(sectionRow(Page.LOCKSCREEN, R.drawable.ic_lockscreen, R.string.lockscreen_gestures_summary))
            content.addView(divider())
            content.addView(sectionRow(Page.ESSENTIAL_KEY, R.drawable.ic_essential_key, R.string.essential_key_summary))
            content.addView(divider())
            content.addView(sectionRow(Page.SCREENSHOTS, R.drawable.ic_screenshot, R.string.screenshot_summary))
            content.addView(divider())
            content.addView(sectionRow(Page.STATUS_BAR, R.drawable.ic_status_bar, R.string.status_bar_summary))
            content.addView(divider())
            addUpdateSettings(content)
        }
        ViewCompat.requestApplyInsets(root)
        if (previousScroll != 0) scroll.post { scroll.scrollTo(0, previousScroll) }
    }

    private fun addUpdateSettings(content: LinearLayout) {
        content.addView(text(getString(R.string.updates), 14f, secondary = true).apply {
            setPadding(0, dp(28), 0, dp(8))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            ViewCompat.setAccessibilityHeading(this, true)
        })
        githubUpdates.availableTag?.let { tag ->
            content.addView(actionRow(
                R.string.update_available,
                getString(R.string.update_available_summary, tag),
            ) { openReleases() })
            content.addView(divider())
        }
        val summary = when {
            checkingUpdates -> getString(R.string.update_checking)
            manualUpdateError -> getString(R.string.update_failed)
            githubUpdates.checkedSuccessfully -> getString(R.string.update_current)
            else -> getString(R.string.update_check_summary)
        }
        content.addView(actionRow(R.string.check_updates, summary, enabled = !checkingUpdates) {
            checkUpdates(manual = true)
        })
        content.addView(divider())
        content.addView(toggleRow(R.string.auto_check_updates, githubUpdates.autoCheckEnabled) { enabled ->
            githubUpdates.autoCheckEnabled = enabled
            if (enabled && githubUpdates.shouldAutoCheck()) checkUpdates(manual = false)
        })
    }

    private fun openReleases() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GitHubUpdates.RELEASES_URL))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        try {
            startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, R.string.update_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkUpdates(manual: Boolean) {
        if (checkingUpdates) return
        checkingUpdates = true
        manualUpdateError = false
        githubUpdates.recordAttempt()
        if (page == Page.HOME) render()
        Thread {
            val succeeded = runCatching { githubUpdates.fetchAndStoreLatest() }.isSuccess
            if (!succeeded) githubUpdates.recordFailure()
            main.post {
                checkingUpdates = false
                manualUpdateError = manual && !succeeded
                if (!isDestroyed && !isFinishing && page == Page.HOME) render()
            }
        }.start()
    }

    private fun header(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(72)
        setPadding(dp(if (page != Page.HOME) 8 else 20), dp(8), dp(20), dp(8))
        if (page != Page.HOME) addView(ImageButton(this@MainActivity).apply {
            setImageResource(R.drawable.ic_back)
            contentDescription = getString(R.string.back)
            background = selectable()
            setOnClickListener { page = Page.HOME; render() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(text(getString(page.title), 24f).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            ViewCompat.setAccessibilityHeading(this, true)
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun statusView(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
        setPadding(0, dp(8), 0, 0)
        val dot = View(this@MainActivity).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        statusDot = dot
        addView(dot, LinearLayout.LayoutParams(dp(7), dp(7)).apply { topMargin = dp(8); marginEnd = dp(10) })
        addView(column().apply {
            statusTitle = text("", 15f).also { addView(it) }
            statusNote = text("", 13f, secondary = true).also { it.setPadding(0, dp(6), 0, 0); addView(it) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        updateStatusViews()
    }

    private fun sectionRow(destination: Page, @DrawableRes icon: Int, @StringRes summary: Int): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(76)
            setPadding(0, dp(12), 0, dp(12))
            background = selectable()
            addView(ImageView(this@MainActivity).apply {
                setImageResource(icon)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(20) })
            addView(column().apply {
                addView(text(getString(destination.title), 17f))
                addView(text(getString(summary), 14f, secondary = true).apply {
                    setPadding(0, dp(3), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            isFocusable = true
            setOnClickListener { page = destination; render() }
        }

    private fun setting(target: RebootTarget, title: Int, summary: Int, description: Int): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(84)
            setPadding(0, dp(14), 0, dp(14))
            background = selectable()
            val toggle = SwitchCompat(this@MainActivity).apply {
                id = View.generateViewId()
                isChecked = config.current.enabled(target)
                contentDescription = getString(description)
                minimumWidth = dp(48)
                minimumHeight = dp(48)
            }
            addView(column().apply {
                addView(text(getString(title), 17f).apply { labelFor = toggle.id })
                addView(text(getString(summary), 14f, secondary = true).apply { setPadding(0, dp(3), dp(12), 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(toggle, LinearLayout.LayoutParams(-2, -2))
            setOnClickListener { toggle.isChecked = !toggle.isChecked }
            var restoring = false
            toggle.setOnCheckedChangeListener { _, value ->
                if (restoring) return@setOnCheckedChangeListener
                try {
                    config.update(target, value)
                    queryStatus()
                } catch (_: Exception) {
                    restoring = true
                    toggle.isChecked = config.current.enabled(target)
                    restoring = false
                    Toast.makeText(this@MainActivity, R.string.save_failed, Toast.LENGTH_LONG).show()
                }
            }
        }

    private fun addFingerprintSettings(content: LinearLayout) {
        val settings = tweaks.current
        val modes = listOf(R.string.dots_stock, R.string.dots_custom, R.string.dots_random)
        content.addView(actionRow(R.string.dots_color, getString(modes[settings.dots.ordinal])) {
            AlertDialog.Builder(this).setTitle(R.string.dots_color)
                .setSingleChoiceItems(modes.map(::getString).toTypedArray(), settings.dots.ordinal) { dialog, index ->
                    dialog.dismiss()
                    saveTweaks(tweaks.current.copy(dots = DotsMode.entries[index]))
                }.setNegativeButton(android.R.string.cancel, null).show()
        })
        if (settings.dots == DotsMode.CUSTOM) {
            content.addView(divider())
            content.addView(actionRow(R.string.custom_color, colorHex(settings.dotsColor), action = ::editColor))
        }
        content.addView(divider())
        if (settings.icon.isNotEmpty()) {
            content.addView(toggleRow(R.string.custom_icon, settings.iconEnabled) {
                saveTweaks(tweaks.current.copy(iconEnabled = it))
            })
            content.addView(divider())
        }
        content.addView(actionRow(
            R.string.pick_icon,
            summary = if (imageImporting) getString(R.string.importing_image) else null,
            enabled = !imageImporting,
        ) { pickImage.launch("image/*") })
        content.addView(divider())
        if (settings.icon.isNotEmpty()) {
            content.addView(actionRow(R.string.icon_size, getString(R.string.size_percent, settings.iconSize), action = ::editSize))
            content.addView(divider())
            content.addView(actionRow(R.string.remove_icon) {
                saveTweaks(tweaks.current.copy(icon = "", iconEnabled = false))
            })
            content.addView(divider())
        }
    }

    private fun addLockSettings(content: LinearLayout) {
        val s = tweaks.current
        fun toggle(title: Int, value: Boolean, change: (Boolean) -> TweakSettings) {
            content.addView(toggleRow(title, value) { saveTweaks(change(it)) })
        }
        fun choice(title: Int, names: List<String>, selected: Int, select: (Int) -> Unit) {
            content.addView(actionRow(title, names.getOrNull(selected)) {
                AlertDialog.Builder(this).setTitle(title)
                    .setSingleChoiceItems(names.toTypedArray(), selected) { dialog, index ->
                        dialog.dismiss()
                        select(index)
                    }.setNegativeButton(android.R.string.cancel, null).show()
            })
        }
        fun number(title: Int, values: List<Int>, value: Int, unit: String, change: (Int) -> TweakSettings) =
            choice(title, values.map { "$it $unit" }, values.indexOf(value)) { saveTweaks(change(values[it])) }

        fun heading(title: Int) {
            content.addView(text(getString(title), 15f, secondary = true).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(0, dp(24), 0, dp(4))
                ViewCompat.setAccessibilityHeading(this, true)
            })
        }
        toggle(R.string.lock_eta, s.eta) { tweaks.current.copy(eta = it) }
        content.addView(divider())
        heading(R.string.lock_widgets)
        toggle(R.string.lock_net, s.networkWidget) { tweaks.current.copy(networkWidget = it) }
        toggle(R.string.lock_bat, s.batteryWidget) { tweaks.current.copy(batteryWidget = it) }
        toggle(R.string.discharge_eta, s.dischargeEta) { tweaks.current.copy(dischargeEta = it) }
        toggle(R.string.lock_sys, s.systemWidget) { tweaks.current.copy(systemWidget = it) }
        heading(R.string.lock_layout)
        content.addView(text(getString(R.string.drag_hint), 14f, secondary = true))
        content.addView(LayoutPreview(this, s) { order ->
            saveTweaks(tweaks.current.copy(widgetOrder = order))
        })
        number(R.string.lock_gap, listOf(8, 16, 32, 56), s.layoutGap, "dp") { tweaks.current.copy(layoutGap = it) }
        content.addView(divider())

    }

    private fun addChargingSettings(content: LinearLayout) {
        val settings = tweaks.current
        content.addView(toggleRow(R.string.show_charging, settings.chargingEnabled) {
            saveTweaks(tweaks.current.copy(chargingEnabled = it))
        })
        content.addView(divider())
        content.addView(toggleRow(R.string.charging_only, settings.chargingOnly) {
            saveTweaks(tweaks.current.copy(chargingOnly = it))
        })
        content.addView(divider())
        content.addView(toggleRow(R.string.show_on_aod, settings.aod) {
            saveTweaks(tweaks.current.copy(aod = it))
        })
        content.addView(divider())
        content.addView(toggleRow(R.string.battery_voltage, settings.voltage) {
            saveTweaks(tweaks.current.copy(voltage = it))
        })
        content.addView(divider())
        content.addView(toggleRow(R.string.battery_current, settings.current) {
            saveTweaks(tweaks.current.copy(current = it))
        })
        content.addView(divider())
        content.addView(toggleRow(R.string.battery_power, settings.power) {
            saveTweaks(tweaks.current.copy(power = it))
        })
        content.addView(divider())
        content.addView(toggleRow(R.string.battery_temperature, settings.temperature) {
            saveTweaks(tweaks.current.copy(temperature = it))
        })
        content.addView(divider())
        content.addView(text(getString(R.string.battery_sign_note), 14f, secondary = true).apply {
            setPadding(0, dp(16), 0, 0)
        })
    }

    private fun editColor() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
            setText(colorHex(tweaks.current.dotsColor))
            selectAll()
        }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.custom_color)
            .setView(column().apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input) })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.apply, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim().removePrefix("#")
                if (!value.matches(Regex("[0-9a-fA-F]{6}"))) {
                    input.error = getString(R.string.invalid_color)
                } else {
                    saveTweaks(tweaks.current.copy(dotsColor = "#$value".toColorInt()))
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun showCropEditor(bitmap: Bitmap) {
        val crop = CircleCropView(this, bitmap)
        val diameter = SeekBar(this).apply {
            min = 20
            max = 100
            progress = 80
            contentDescription = getString(R.string.crop_diameter)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    crop.setDiameter(progress)
                }
            })
        }
        val height = minOf(dp(300), resources.displayMetrics.heightPixels / 3)
        AlertDialog.Builder(this).setTitle(R.string.crop_icon)
            .setView(column().apply {
                setPadding(dp(24), dp(8), dp(24), 0)
                addView(text(getString(R.string.crop_hint), 14f, secondary = true))
                addView(crop, LinearLayout.LayoutParams(-1, height))
                addView(diameter)
            })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.apply) { _, _ ->
                val encoded = runCatching { crop.encodedCircle() }.getOrNull()
                if (encoded != null) saveTweaks(tweaks.current.copy(icon = encoded, iconEnabled = true))
                else Toast.makeText(this, R.string.image_failed, Toast.LENGTH_LONG).show()
            }.show()
    }

    private fun editSize() {
        val settings = tweaks.current
        val preview = ImageView(this).apply {
            runCatching { FingerprintImage.decode(settings.icon) }.getOrNull()?.let { bitmap ->
                setImageDrawable(RoundedBitmapDrawableFactory.create(resources, bitmap).apply { isCircular = true })
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val currentSize = text("", 15f).apply { gravity = Gravity.CENTER }
        fun displaySize(size: Int) {
            currentSize.text = getString(R.string.size_percent, size)
            preview.scaleX = size / 100f
            preview.scaleY = size / 100f
        }
        val slider = SeekBar(this).apply {
            min = TweakSettings.MIN_ICON_SIZE
            max = TweakSettings.MAX_ICON_SIZE
            progress = settings.iconSize
            contentDescription = getString(R.string.icon_size)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    displaySize(progress)
                }
            })
        }
        displaySize(settings.iconSize)
        AlertDialog.Builder(this).setTitle(R.string.icon_size)
            .setView(column().apply {
                setPadding(dp(24), dp(12), dp(24), 0)
                addView(preview, LinearLayout.LayoutParams(dp(64), dp(64)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                })
                addView(currentSize)
                addView(slider)
            })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.apply) { _, _ ->
                saveTweaks(tweaks.current.copy(iconSize = slider.progress))
            }.show()
    }

    private fun colorHex(color: Int): String = String.format(Locale.ROOT, "#%06X", color and 0xFFFFFF)

    private fun saveTweaks(settings: TweakSettings) {
        try {
            if (!tweaks.update(settings)) {
                Toast.makeText(this, R.string.sync_note, Toast.LENGTH_LONG).show()
            }
            queryStatus()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_LONG).show()
        }
        render()
    }

    private fun actionRow(title: Int, summary: String? = null, enabled: Boolean = true, action: () -> Unit) =
        column().apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(0, dp(12), 0, dp(12))
            background = selectable()
            addView(text(getString(title), 17f))
            summary?.let {
                addView(text(it, 14f, secondary = true).apply { setPadding(0, dp(3), 0, 0) })
            }
            isEnabled = enabled
            isFocusable = enabled
            if (enabled) setOnClickListener { action() }
        }

    private fun toggleRow(title: Int, checked: Boolean, update: (Boolean) -> Unit) =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            background = selectable()
            val toggle = SwitchCompat(this@MainActivity).apply {
                id = View.generateViewId()
                contentDescription = getString(title)
                minimumWidth = dp(48)
                minimumHeight = dp(48)
                isChecked = checked
                setOnCheckedChangeListener { _, value -> update(value) }
            }
            addView(text(getString(title), 17f).apply {
                labelFor = toggle.id
                setPadding(0, dp(12), dp(12), dp(12))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(toggle, LinearLayout.LayoutParams(-2, -2))
            setOnClickListener { toggle.isChecked = !toggle.isChecked }
        }

    private val captureTitles = mapOf(
            EssentialKeyCapture.NONE to R.string.key_action_none,
            EssentialKeyCapture.SCREENSHOT to R.string.key_action_screenshot,
            EssentialKeyCapture.RECORD to R.string.key_action_record,
            EssentialKeyCapture.LAUNCH_APP to R.string.key_action_launch_app,
            EssentialKeyCapture.LAUNCH_ACTIVITY to R.string.key_action_launch_activity,
            EssentialKeyCapture.SOUND_RECORDER to R.string.key_action_sound_recorder,
            EssentialKeyCapture.NOTIFICATIONS to R.string.key_action_notifications,
            EssentialKeyCapture.CAMERA to R.string.key_action_camera,
        )

    private fun addStatusAction(content: LinearLayout) {
        val s = tweaks.current
        val actions = captureTitles.keys.toList()
        val labels = actions.map { getString(captureTitles.getValue(it)) } + getString(R.string.status_sleep)
        val selected = if (s.statusSleep) actions.size else actions.indexOf(s.statusAction)
        val target = s.statusTarget
        val targetLabel = listOf(target.packageName, target.className.substringAfterLast('.'))
            .filter { it.isNotEmpty() }.joinToString(" / ")
        val summary = listOf(labels[selected], targetLabel).filter { it.isNotEmpty() }.joinToString(" · ")
        content.addView(actionRow(R.string.status_action, summary) {
            AlertDialog.Builder(this).setTitle(R.string.status_action)
                .setSingleChoiceItems(labels.toTypedArray(), selected) { dialog, i ->
                    dialog.dismiss()
                    val action = actions.getOrNull(i) ?: EssentialKeyCapture.NONE
                    fun save(target: EssentialTarget) = saveTweaks(tweaks.current.copy(
                        statusAction = action, statusSleep = i == actions.size, statusTarget = target))
                    if (action == EssentialKeyCapture.LAUNCH_APP || action == EssentialKeyCapture.LAUNCH_ACTIVITY)
                        chooseTarget(action, ::save)
                    else save(EssentialTarget(""))
                }.setNegativeButton(android.R.string.cancel, null).show()
        })
        if (s.statusAction == EssentialKeyCapture.SOUND_RECORDER) addRecorderSettings(content)
    }

    private fun addEssentialKey(content: LinearLayout) {
        val gestureTitles = mapOf(
            EssentialKeyGesture.PRESS to R.string.key_press,
            EssentialKeyGesture.DOUBLE_PRESS to R.string.key_double_press,
            EssentialKeyGesture.HOLD to R.string.key_hold,
            EssentialKeyGesture.PRESS_AND_HOLD to R.string.key_press_hold,
            EssentialKeyGesture.DOUBLE_PRESS_AND_HOLD to R.string.key_double_press_hold,
        )
        val actions = captureTitles
        gestureTitles.forEach { (gesture, title) ->
            val selected = tweaks.current.essentialKey[gesture] ?: EssentialKeyCapture.NONE
            val target = tweaks.current.essentialTargets[gesture]
            val description = getString(actions.getValue(selected)) +
                (if (target != null && selected in setOf(EssentialKeyCapture.LAUNCH_APP, EssentialKeyCapture.LAUNCH_ACTIVITY)) {
                    " · " + target.packageName + (if (selected == EssentialKeyCapture.LAUNCH_ACTIVITY) " / " + target.className.substringAfterLast('.') else "")
                } else "")
            content.addView(actionRow(title, description) {
                val choices = actions.keys.toList()
                val labels = actions.values.map(::getString).toMutableList()
                if (gesture == EssentialKeyGesture.PRESS) labels.add(getString(R.string.key_action_original))
                AlertDialog.Builder(this)
                    .setTitle(title)
                    .setSingleChoiceItems(labels.toTypedArray(), choices.indexOf(selected)) { dialog, index ->
                        dialog.dismiss()
                        if (index == choices.size) {
                            confirmRestoreKey()
                        } else {
                            val chosen = choices[index]
                            if (chosen == EssentialKeyCapture.LAUNCH_APP || chosen == EssentialKeyCapture.LAUNCH_ACTIVITY) {
                                chooseKeyTarget(gesture, chosen)
                            } else {
                                val current = tweaks.current
                                val bindings = if (chosen == EssentialKeyCapture.NONE) {
                                    current.essentialKey - gesture
                                } else current.essentialKey + (gesture to chosen)
                                if (bindings != current.essentialKey || gesture in current.essentialTargets) {
                                    saveTweaks(current.copy(essentialKey = bindings,
                                        essentialTargets = current.essentialTargets - gesture))
                                }
                            }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            })
            content.addView(divider())
        }
        // Show recording options only when at least one Essential Key gesture starts the recorder.
        if (tweaks.current.essentialKey.values.any { it == EssentialKeyCapture.SOUND_RECORDER }) {
            addRecorderSettings(content)
        }

        content.addView(text(getString(R.string.key_mapping_note), 14f, secondary = true).apply {
            setPadding(0, dp(16), 0, dp(10))
        })
        content.addView(text(getString(R.string.key_stock_note), 14f, secondary = true).apply {
            setPadding(0, 0, 0, dp(20))
        })

        val state = essentialKey.state.value ?: EssentialKeyViewModel.State()
        content.addView(text(getString(R.string.free_key_description), 15f, secondary = true).apply {
            setPadding(0, dp(8), 0, dp(16))
        })
        content.addView(essentialKeyButton(
            if (state.running) R.string.free_key_checking else R.string.free_key,
            filled = true,
            enabled = !state.running,
            action = essentialKey::freeKey,
        ))
        content.addView(text(getString(R.string.restore_key_description), 15f, secondary = true).apply {
            setPadding(0, dp(20), 0, dp(12))
        })
        content.addView(essentialKeyButton(
            R.string.restore_key,
            enabled = !state.running,
            action = ::confirmRestoreKey,
        ))
        val result = state.result ?: return
        val restored = state.operation == EssentialKeyViewModel.Operation.RESTORE
        val message = if (restored && result.restoresStock && tweaks.current.essentialKey.isNotEmpty()) {
            R.string.restore_key_config_failed
        } else if (restored) when (result) {
            EssentialKeyResult.RESTORED -> R.string.restore_key_done
            EssentialKeyResult.ALREADY_RESTORED -> R.string.restore_key_already
            EssentialKeyResult.NOT_INSTALLED -> R.string.free_key_absent
            EssentialKeyResult.WRONG_USER -> R.string.free_key_wrong_user
            EssentialKeyResult.ROOT_REQUIRED -> R.string.restore_key_root_required
            EssentialKeyResult.TIMEOUT -> R.string.restore_key_timeout
            EssentialKeyResult.PARTIAL -> R.string.restore_key_partial
            EssentialKeyResult.FAILED -> R.string.restore_key_failed
            else -> R.string.restore_key_failed
        } else when (result) {
            EssentialKeyResult.FREED -> R.string.free_key_done
            EssentialKeyResult.ALREADY_FREE -> R.string.free_key_already
            EssentialKeyResult.NOT_INSTALLED -> R.string.free_key_absent
            EssentialKeyResult.WRONG_USER -> R.string.free_key_wrong_user
            EssentialKeyResult.ROOT_REQUIRED -> R.string.free_key_root_required
            EssentialKeyResult.TIMEOUT -> R.string.free_key_timeout
            EssentialKeyResult.PARTIAL -> R.string.free_key_partial
            EssentialKeyResult.FAILED -> R.string.free_key_failed
            else -> R.string.free_key_failed
        }
        content.addView(column().apply {
            background = GradientDrawable().apply {
                setColor(getColor(R.color.surface))
                setStroke(dp(1), getColor(R.color.divider))
                cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(text(getString(message), 15f).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            })
            if (result == EssentialKeyResult.ROOT_REQUIRED || result == EssentialKeyResult.TIMEOUT) {
                addView(essentialKeyButton(R.string.free_key_manual, action = { showEssentialKeyHelp(restored) }),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }

    private fun addRecorderSettings(content: LinearLayout) {
        val settings = tweaks.current
        content.addView(text(getString(R.string.recorder_settings_title), 17f).apply {
            setPadding(0, dp(24), 0, dp(8))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            ViewCompat.setAccessibilityHeading(this, true)
        })
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            content.addView(actionRow(R.string.recorder_grant_permission,
                getString(R.string.recorder_permission_needed)) {
                grantMicrophone.launch(Manifest.permission.RECORD_AUDIO)
            })
            content.addView(divider())
        }
        val formats = RecorderFormat.entries
        val formatNames = arrayOf(getString(R.string.recorder_format_aac),
            getString(R.string.recorder_format_opus), getString(R.string.recorder_format_amr))
        content.addView(actionRow(R.string.recorder_format,
            formatNames[formats.indexOf(settings.recorderFormat)]) {
            AlertDialog.Builder(this).setTitle(R.string.recorder_format)
                .setSingleChoiceItems(formatNames, formats.indexOf(tweaks.current.recorderFormat)) { dialog, index ->
                    dialog.dismiss()
                    saveTweaks(tweaks.current.copy(recorderFormat = formats[index]))
                }.setNegativeButton(android.R.string.cancel, null).show()
        })
        content.addView(divider())
        if (settings.recorderFormat != RecorderFormat.AMR_3GP) {
            val qualities = RecorderQuality.entries
            val qualityNames = arrayOf(getString(R.string.recorder_quality_low),
                getString(R.string.recorder_quality_normal), getString(R.string.recorder_quality_high))
            content.addView(actionRow(R.string.recorder_quality,
                qualityNames[qualities.indexOf(settings.recorderQuality)]) {
                AlertDialog.Builder(this).setTitle(R.string.recorder_quality)
                    .setSingleChoiceItems(qualityNames, qualities.indexOf(tweaks.current.recorderQuality)) { dialog, index ->
                        dialog.dismiss()
                        saveTweaks(tweaks.current.copy(recorderQuality = qualities[index]))
                    }.setNegativeButton(android.R.string.cancel, null).show()
            })
            content.addView(divider())
        }
        val destinations = RecorderDestination.entries
        val destinationNames = arrayOf(getString(R.string.recorder_folder_music),
            getString(R.string.recorder_folder_recordings), getString(R.string.recorder_custom_folder))
        val folderSummary = if (settings.recorderDestination == RecorderDestination.CUSTOM) {
            settings.recorderFolderLabel.ifBlank { getString(R.string.recorder_custom_folder) }
        } else destinationNames[destinations.indexOf(settings.recorderDestination)]
        content.addView(actionRow(R.string.recorder_folder, folderSummary) {
            AlertDialog.Builder(this).setTitle(R.string.recorder_folder)
                .setSingleChoiceItems(destinationNames, destinations.indexOf(tweaks.current.recorderDestination)) { dialog, index ->
                    dialog.dismiss()
                    if (destinations[index] == RecorderDestination.CUSTOM) {
                        chooseRecorderFolder.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                        })
                    } else {
                        saveTweaks(tweaks.current.copy(recorderDestination = destinations[index]))
                    }
                }.setNegativeButton(android.R.string.cancel, null).show()
        })
        content.addView(divider())
        content.addView(actionRow(R.string.recorder_filename, settings.recorderNameTemplate) {
            editRecorderFilename()
        })
        content.addView(divider())
        content.addView(text(getString(R.string.recorder_help), 14f, secondary = true).apply {
            setPadding(0, dp(10), 0, dp(16))
        })
    }

    private fun editRecorderFilename() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
            setText(tweaks.current.recorderNameTemplate)
            setSelection(text.length)
        }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.recorder_filename)
            .setView(column().apply {
                setPadding(dp(24), dp(8), dp(24), 0)
                addView(text(getString(R.string.recorder_filename_help), 14f, secondary = true))
                addView(input)
            })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.apply, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = RecorderNaming.normalizeTemplate(input.text.toString())
                if (input.text.length > 80) {
                    input.error = getString(R.string.recorder_filename_long)
                    return@setOnClickListener
                }
                saveTweaks(tweaks.current.copy(recorderNameTemplate = name))
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /** Select installed apps and exported activities only. Never bypass component export restrictions. */
    private fun chooseKeyTarget(gesture: EssentialKeyGesture, chosen: EssentialKeyCapture) =
        chooseTarget(chosen) { saveKeyTarget(gesture, chosen, it) }

    private fun chooseTarget(chosen: EssentialKeyCapture, select: (EssentialTarget) -> Unit) {
        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            .filter { it.enabled && (chosen == EssentialKeyCapture.LAUNCH_ACTIVITY ||
                pm.getLaunchIntentForPackage(it.packageName) != null) }
            .sortedBy { it.loadLabel(pm).toString().lowercase(Locale.getDefault()) }
        if (apps.isEmpty()) {
            Toast.makeText(this, R.string.key_no_apps, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = apps.map { "${it.loadLabel(pm)} (${it.packageName})" }.toTypedArray()
        AlertDialog.Builder(this).setTitle(R.string.key_pick_app).setItems(labels) { _, index ->
            val pkg = apps[index].packageName
            if (chosen == EssentialKeyCapture.LAUNCH_APP) {
                select(EssentialTarget(pkg))
            } else {
                val activities = runCatching {
                    pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong()))
                        .activities.orEmpty().filter { it.exported && it.enabled }
                        .sortedBy { it.loadLabel(pm).toString().lowercase(Locale.getDefault()) }
                }.getOrDefault(emptyList())
                if (activities.isEmpty()) {
                    Toast.makeText(this, R.string.key_no_activities, Toast.LENGTH_SHORT).show()
                    return@setItems
                }
                AlertDialog.Builder(this).setTitle(R.string.key_pick_activity)
                    .setItems(activities.map { "${it.loadLabel(pm)} (${it.name})" }.toTypedArray()) { _, activityIndex ->
                        select(EssentialTarget(pkg, activities[activityIndex].name))
                    }.setNegativeButton(android.R.string.cancel, null).show()
            }
        }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun saveKeyTarget(gesture: EssentialKeyGesture, action: EssentialKeyCapture, target: EssentialTarget) {
        val current = tweaks.current
        saveTweaks(current.copy(essentialKey = current.essentialKey + (gesture to action),
            essentialTargets = current.essentialTargets + (gesture to target)))
    }

    private fun confirmRestoreKey() {
        AlertDialog.Builder(this)
            .setTitle(R.string.restore_key)
            .setMessage(R.string.restore_key_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.restore_key) { _, _ -> essentialKey.restoreKey() }
            .show()
    }

    private fun showEssentialKeyHelp(restoring: Boolean) {
        val commands = if (restoring) EssentialKeyAction.manualRestoreCommandLines
            else EssentialKeyAction.manualCommandLines
        val paste = if (restoring) EssentialKeyAction.manualRestorePasteCommand
            else EssentialKeyAction.manualPasteCommand
        val instructions = column().apply {
            setPadding(dp(24), dp(12), dp(24), dp(4))
            addView(text(getString(R.string.free_key_termux), 15f))
            addView(text(commands.joinToString("\n"), 13f).apply {
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(12), 0, dp(12))
            })
            addView(text(getString(R.string.free_key_sudo_note), 13f, secondary = true))
            if (restoring) addView(text(getString(R.string.restore_key_manual_note), 13f, secondary = true))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.free_key_manual)
            .setView(ScrollView(this).apply { addView(instructions) })
            .create()
        instructions.addView(essentialKeyButton(R.string.free_key_copy, filled = true) {
            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                ClipData.newPlainText(getString(R.string.essential_key), paste)
            )
            dialog.dismiss()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        instructions.addView(essentialKeyButton(android.R.string.cancel, action = dialog::dismiss),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(12) })
        dialog.show()
    }

    private fun essentialKeyButton(
        @StringRes label: Int,
        filled: Boolean = false,
        enabled: Boolean = true,
        action: () -> Unit,
    ): AppCompatButton {
        val surface = getColor(R.color.surface)
        val foreground = getColor(R.color.foreground)
        val outline = getColor(R.color.divider)
        val fill = when {
            !enabled -> outline
            filled -> foreground
            else -> surface
        }
        val ink = when {
            !enabled -> getColor(R.color.secondary)
            filled -> surface
            else -> foreground
        }
        return AppCompatButton(this).apply {
            setText(label)
            setTextColor(ink)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textSize = 15f
            isAllCaps = false
            isEnabled = enabled
            minimumHeight = dp(52)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            val shape = GradientDrawable().apply {
                cornerRadius = dp(26).toFloat()
                setColor(fill)
                if (!filled) setStroke(dp(1), outline)
            }
            val ripple = (ink and 0xFFFFFF) or 0x26000000
            backgroundTintList = null
            background = RippleDrawable(ColorStateList.valueOf(ripple), shape, null)
            setOnClickListener { action() }
        }
    }

    private fun setStatus(title: Int, explanation: Int?) {
        status = title; note = explanation; updateStatusViews()
    }

    private fun updateStatusViews() {
        statusTitle?.setText(status)
        statusNote?.apply { visibility = if (note == null) View.GONE else View.VISIBLE; note?.let(::setText) }
        statusDot?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(getColor(if (status == R.string.inactive) R.color.accent else R.color.secondary))
        }
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun text(value: String, size: Float, secondary: Boolean = false) = TextView(this).apply {
        text = value; textSize = size
        setTextColor(getColor(if (secondary) R.color.secondary else R.color.foreground))
        setLineSpacing(dp(2).toFloat(), 1f)
    }
    private fun divider() = View(this).apply {
        setBackgroundColor(getColor(R.color.divider))
        layoutParams = LinearLayout.LayoutParams(-1, dp(1))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private fun selectable(): android.graphics.drawable.Drawable? {
        val attributes = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        return attributes.getDrawable(0).also { attributes.recycle() }
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
