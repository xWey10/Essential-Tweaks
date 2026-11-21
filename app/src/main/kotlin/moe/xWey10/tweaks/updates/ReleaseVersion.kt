// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.updates

/** Stable three-part version tags used for GitHub Releases; ignores prereleases. */
internal object ReleaseVersion {
    private val pattern = Regex("""^[vV]?(\d{1,9})\.(\d{1,9})\.(\d{1,9})$""")

    fun isValid(tag: String): Boolean = parse(tag) != null

    fun isNewer(remoteTag: String, installedName: String): Boolean {
        val remote = parse(remoteTag) ?: return false
        val installed = parse(installedName) ?: return false
        for (part in remote.indices) {
            if (remote[part] != installed[part]) return remote[part] > installed[part]
        }
        return false
    }

    private fun parse(version: String): List<Long>? {
        val match = pattern.matchEntire(version) ?: return null
        return (1..3).map { match.groupValues[it].toLongOrNull() ?: return null }
    }
}
