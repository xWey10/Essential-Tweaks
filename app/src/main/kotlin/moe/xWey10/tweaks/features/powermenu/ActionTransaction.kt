// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.features.powermenu

/** Restore both stock lists if native placement fails part way through. */
internal fun <T> appendTransaction(
    main: MutableList<T>,
    overflow: MutableList<T>,
    additions: List<T>,
    append: (T) -> Unit,
) {
    val originalMain = main.toList()
    val originalOverflow = overflow.toList()
    try {
        additions.forEach(append)
    } catch (error: Throwable) {
        main.clear()
        main.addAll(originalMain)
        overflow.clear()
        overflow.addAll(originalOverflow)
        throw error
    }
}
