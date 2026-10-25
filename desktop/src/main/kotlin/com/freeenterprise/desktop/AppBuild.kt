package com.freeenterprise.desktop

/**
 * Which build this is: version, timestamp and commit, written into the build by the
 * packaging script (`runtime/lib/build.txt`).
 *
 * Every development build used to be called "0.1.25", which made it impossible to
 * tell one from another - so this is shown in the window title and the main menu.
 */
object AppBuild {

    private fun resource(name: String): String? = try {
        AppBuild::class.java.getResourceAsStream(name)?.bufferedReader()?.use { it.readText().trim() }
    } catch (t: Throwable) {
        null
    }

    /** e.g. "0.1.25-dev.20261025-1930.eb713ba", or just the version for a plain build. */
    fun id(): String =
        System.getenv("FE_BUILD")?.takeIf { it.isNotBlank() }
            ?: resource("/build.txt")?.takeIf { it.isNotBlank() }
            ?: resource("/version.txt")?.takeIf { it.isNotBlank() }
            ?: "0.0.0"

    /** e.g. "0.1.25", for the update check. */
    fun version(): String =
        System.getenv("FE_VERSION")?.takeIf { it.isNotBlank() }
            ?: resource("/version.txt")?.takeIf { it.isNotBlank() }
            ?: "0.0.0"
}
