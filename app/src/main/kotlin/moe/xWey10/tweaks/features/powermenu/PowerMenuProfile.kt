// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.features.powermenu

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.os.UserHandle
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Exact runtime contract; no scan for vaguely similar classes or methods. */
@SuppressLint("PrivateApi") // Intentional LSPosed-only reflection, checked before installing hooks.
class PowerMenuProfile(loader: ClassLoader) {
    private val prefix = "com.android.systemui.globalactions.GlobalActionsDialogLite"
    val dialogClass: Class<*> = loader.loadClass(prefix)
    private val restartClass = loader.loadClass("$prefix\$RestartAction")
    private val actionClass = loader.loadClass("$prefix\$Action")
    val createItems = method(dialogClass, "createActionItems", Void.TYPE)
    val press = method(restartClass, "onPress", Void.TYPE)
    val longPress = method(restartClass, "onLongPress", Boolean::class.javaPrimitiveType!!)
    val enabled = method(restartClass, "isEnabled", Boolean::class.javaPrimitiveType!!)
    val addItem = method(dialogClass, "addActionItem", Void.TYPE, actionClass)
    val verify = method(dialogClass, "checkShouldPowerOffVerify", Boolean::class.javaPrimitiveType!!)
    val ageTest = method(dialogClass, "isAgeTest", Boolean::class.javaPrimitiveType!!)
    val userSetup = method(dialogClass, "isUserSetupComplete", Boolean::class.javaPrimitiveType!!, UserHandle::class.java)
    val mainItems = field(dialogClass, "mItems", List::class.java)
    val overflowItems = field(dialogClass, "mOverflowItems", List::class.java)
    val context = field(dialogClass, "mContext", Context::class.java)
    val provisioned = field(dialogClass, "mDeviceProvisioned", Boolean::class.javaPrimitiveType!!)
    val userTracker: Field = findField(dialogClass, "mUserTracker")
    val userId: Method = method(userTracker.type, "getUserId", Int::class.javaPrimitiveType!!)
    val userHandle: Method = method(userTracker.type, "getUserHandle", UserHandle::class.java)
    val message = field(restartClass, "mMessage", CharSequence::class.java)
    val messageId = field(restartClass, "mMessageResId", Int::class.javaPrimitiveType!!)
    val restartConstructor: Constructor<*> = restartClass.getDeclaredConstructor(dialogClass)
        .apply { isAccessible = true }
    val confirmationConstructor: Constructor<*> = loader
        .loadClass("com.android.systemui.statusbar.phone.SystemUIDialog")
        .also { check(AlertDialog::class.java.isAssignableFrom(it)) { "SystemUIDialog is not AlertDialog" } }
        .getDeclaredConstructor(Context::class.java).apply { isAccessible = true }

    init {
        check(actionClass.isAssignableFrom(restartClass)) { "RestartAction contract mismatch" }
        // WeakHashMap tags must use object identity, never an OEM value-based action equality.
        check(restartClass.getMethod("equals", Any::class.java).declaringClass == Any::class.java &&
            restartClass.getMethod("hashCode").declaringClass == Any::class.java) {
            "RestartAction must retain Object identity semantics"
        }
    }

    companion object {
        private fun findField(type: Class<*>, name: String): Field {
            var cursor: Class<*>? = type
            while (cursor != null) {
                try { return cursor.getDeclaredField(name).apply { isAccessible = true } }
                catch (_: NoSuchFieldException) { cursor = cursor.superclass }
            }
            error("Missing ${type.name}.$name")
        }

        private fun field(type: Class<*>, name: String, expected: Class<*>): Field =
            findField(type, name).also {
                check(!Modifier.isStatic(it.modifiers)) { "Unexpected static field: ${type.name}.$name" }
                check(expected.isAssignableFrom(it.type)) { "Unexpected field type: ${type.name}.$name" }
            }

        private fun method(type: Class<*>, name: String, result: Class<*>, vararg args: Class<*>): Method {
            var cursor: Class<*>? = type
            while (cursor != null) {
                try {
                    return cursor.getDeclaredMethod(name, *args).also {
                        check(!Modifier.isStatic(it.modifiers)) { "Unexpected static method: ${type.name}.$name" }
                        check(it.returnType == result) { "Unexpected return type: ${type.name}.$name" }
                        it.isAccessible = true
                    }
                } catch (_: NoSuchMethodException) { cursor = cursor.superclass }
            }
            error("Missing method: ${type.name}.$name")
        }
    }
}
