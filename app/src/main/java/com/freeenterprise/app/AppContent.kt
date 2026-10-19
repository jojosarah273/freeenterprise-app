package com.freeenterprise.app

import android.content.Context
import java.io.File
import java.io.InputStream

/**
 * Where the app's data files live.
 *
 * `spec/` (flag editor + checks), `presets/` and the engine can all be replaced
 * in place by an engine update, which writes them into app storage. So every
 * reader asks here first and falls back to the copies bundled in the APK — which
 * is what a fresh install has.
 *
 * Without this the in-place update would only ever swap files nothing read.
 */
object AppContent {

    fun file(context: Context, relativePath: String): File =
        File(context.filesDir, relativePath)

    /** True when an in-place update has provided this file. */
    fun isUpdated(context: Context, relativePath: String): Boolean =
        file(context, relativePath).exists()

    /** Reads [relativePath] from storage if an update provided it, else from assets. */
    fun read(context: Context, relativePath: String): String {
        val updated = file(context, relativePath)
        return if (updated.exists()) {
            updated.readText()
        } else {
            context.assets.open(relativePath).use { it.readBytes().toString(Charsets.UTF_8) }
        }
    }

    /** Streams [relativePath], same precedence as [read]. */
    fun open(context: Context, relativePath: String): InputStream {
        val updated = file(context, relativePath)
        return if (updated.exists()) updated.inputStream() else context.assets.open(relativePath)
    }
}
