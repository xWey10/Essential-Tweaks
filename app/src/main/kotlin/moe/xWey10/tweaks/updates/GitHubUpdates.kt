// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.updates

import android.content.Context
import moe.xWey10.tweaks.BuildConfig
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import javax.net.ssl.HttpsURLConnection

/** Local-only update status. Does not schedule work or post Android notifications. */
internal class GitHubUpdates(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("github_updates", Context.MODE_PRIVATE)

    var autoCheckEnabled: Boolean
        get() = preferences.getBoolean("auto_check", true)
        set(value) { preferences.edit().putBoolean("auto_check", value).apply() }

    val availableTag: String?
        get() = preferences.getString("last_release_tag", null)
            ?.takeIf { ReleaseVersion.isNewer(it, BuildConfig.VERSION_NAME) }

    val checkedSuccessfully: Boolean
        get() = preferences.getString("last_release_tag", null)?.let(ReleaseVersion::isValid) == true

    fun shouldAutoCheck(nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (!autoCheckEnabled) return false
        val lastAttempt = preferences.getLong("last_attempt_ms", 0L)
        val retryDelay = if (preferences.getBoolean("last_attempt_succeeded", false)) DAY_MS else RETRY_MS
        return lastAttempt <= 0L || nowMillis < lastAttempt || nowMillis - lastAttempt >= retryDelay
    }

    fun recordAttempt(nowMillis: Long = System.currentTimeMillis()) {
        preferences.edit().putLong("last_attempt_ms", nowMillis).apply()
    }

    /** Call on a worker thread. Only a valid version tag is cached. */
    fun fetchAndStoreLatest() {
        val connection = (URI.create(API_URL).toURL().openConnection() as HttpsURLConnection).apply {
            connectTimeout = 6_000
            readTimeout = 6_000
            instanceFollowRedirects = false
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "Essential-Tweaks-Update-Check")
        }
        try {
            if (connection.responseCode != HttpsURLConnection.HTTP_OK) {
                throw IOException("GitHub release endpoint returned HTTP ${connection.responseCode}")
            }
            val payload = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                // Bound the response before parsing untrusted remote JSON.
                val data = StringBuilder()
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (data.length + count > MAX_RESPONSE_CHARS) {
                        throw IOException("GitHub release response is too large")
                    }
                    data.append(buffer, 0, count)
                }
                data.toString()
            }
            val version = JSONObject(payload).optString("tag_name", "")
            if (!ReleaseVersion.isValid(version)) throw IOException("Invalid GitHub release version")
            preferences.edit()
                .putString("last_release_tag", version)
                .putBoolean("last_attempt_succeeded", true)
                .apply()
        } finally {
            connection.disconnect()
        }
    }

    fun recordFailure() {
        // Keep the last known release visible when a subsequent request fails.
        preferences.edit().putBoolean("last_attempt_succeeded", false).apply()
    }

    companion object {
        const val RELEASES_URL = "https://github.com/xWey10/Essential-Tweaks/releases/latest"
        private const val API_URL = "https://api.github.com/repos/xWey10/Essential-Tweaks/releases/latest"
        private const val MAX_RESPONSE_CHARS = 262_144
        private const val DAY_MS = 24L * 60L * 60L * 1000L
        private const val RETRY_MS = 60L * 60L * 1000L
    }
}
