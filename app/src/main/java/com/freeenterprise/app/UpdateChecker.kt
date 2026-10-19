package com.freeenterprise.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One attached file on a GitHub release. */
data class ReleaseAsset(
    val name: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String?,
)

/** The bits of a release the app cares about. */
data class ReleaseInfo(
    val tag: String,
    val notes: String,
    val assets: List<ReleaseAsset>,
) {
    val version: String get() = tag.trimStart('v')

    /** The engine bundle, if this release carries one. */
    val engineAsset: ReleaseAsset?
        get() = assets.firstOrNull { it.name.startsWith(ENGINE_PREFIX) && it.name.endsWith(".zip") }

    /** Engine version, read straight from the asset name. */
    val engineVersion: String?
        get() = engineAsset?.name?.removePrefix(ENGINE_PREFIX)?.removeSuffix(".zip")

    companion object {
        /** Release assets must be named `freeenterprise-engine-<version>.zip`. */
        const val ENGINE_PREFIX = "freeenterprise-engine-"
    }
}

/**
 * Reads the project's latest GitHub release. The repository is public, so this
 * needs no token; the API also gives us each asset's sha256 digest, which is what
 * the download is verified against.
 *
 * This is the app's *only* network use, and only when the user asks.
 */
object UpdateChecker {

    const val REPO = "jojosarah273/freeenterprise-app"
    private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"

    fun fetchLatest(): ReleaseInfo {
        val connection = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "freeenterprise-app")
        }
        try {
            if (connection.responseCode !in 200..299) {
                error("GitHub returned HTTP ${connection.responseCode}")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)

            val assets = mutableListOf<ReleaseAsset>()
            json.optJSONArray("assets")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val a = arr.getJSONObject(i)
                    assets.add(
                        ReleaseAsset(
                            name = a.optString("name"),
                            url = a.optString("browser_download_url"),
                            sizeBytes = a.optLong("size"),
                            sha256 = a.optString("digest")
                                .takeIf { it.startsWith("sha256:") }
                                ?.removePrefix("sha256:"),
                        )
                    )
                }
            }
            return ReleaseInfo(json.optString("tag_name"), json.optString("body"), assets)
        } finally {
            connection.disconnect()
        }
    }

    /** Plain x.y.z comparison; ignores any tag suffix. True when [candidate] is newer. */
    fun isNewer(candidate: String, installed: String): Boolean {
        fun parts(s: String) = s.trimStart('v')
            .substringBefore('-').substringBefore('+')
            .split('.').mapNotNull { it.toIntOrNull() }

        val a = parts(candidate)
        val b = parts(installed)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
