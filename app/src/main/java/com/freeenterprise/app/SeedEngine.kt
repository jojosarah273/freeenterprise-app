package com.freeenterprise.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File

/**
 * Drives the embedded CPython (Chaquopy) randomizer.
 *
 * On first run the bundled `python_ext` directory (FreeEnt + f4c + pyaes) is
 * unpacked from assets into filesDir so the generator can read its data files
 * (scripts/, assets/, compiled_songs/, …) relative to the package location.
 */
class SeedEngine(private val context: Context, private val onResult: (String) -> Unit) {

    // Chaquopy requires all Python access from one logical thread; this lock
    // serializes validation/generation calls (calls are never concurrent).
    private val engineLock = Any()

    val extDir: File = File(context.filesDir, "python_ext")
    val romsDir: File = File(context.filesDir, "roms")
    val cacheDir: File = File(context.filesDir, "f4c_cache")

    /**
     * The engine Generate will use. Every bundled engine is extracted; the
     * selected one is what the interpreter is pointed at.
     */
    fun selectedExtDir(): File = Engines.dir(context, EditorPrefs.engine(context))

    /** The interpreter's current engine dir, so a switch reloads exactly once. */
    private var pointedAt: String? = null

    /**
     * Points the interpreter at the selected engine. Switching engines in one
     * process is two steps: repoint sys.path, then drop the cached FreeEnt/f4c
     * modules (Chaquopy caches imports per process) - fe_bridge's reload_engine
     * does the dropping and also purges both engines' __pycache__.
     */
    private fun pointBridgeAt(bridge: com.chaquo.python.PyObject) {
        val path = selectedExtDir().absolutePath
        bridge.callAttr("set_ext_path", path)
        if (pointedAt != null && pointedAt != path) {
            bridge.callAttr("reload_engine")
        }
        pointedAt = path
    }

    /**
     * Unpacks the bundled randomizer engine into app storage.
     *
     * IMPORTANT: the extracted copy is stamped with the app's versionCode and
     * re-extracted whenever that changes. Without this, an engine fix shipped in
     * a new APK would never reach the device — the app would keep running the
     * engine extracted on first install (this bit us with the empty-chest bug).
     */
    fun ensureExtracted(onDone: () -> Unit, onError: (String) -> Unit) {
        val marker = File(extDir, ".engine_version")
        val wanted = BuildConfig.VERSION_CODE.toString()
        val upToDate = extDir.resolve("FreeEnt").exists() &&
            extDir.resolve("f4c").exists() &&
            marker.exists() &&
            marker.readText().trim() == wanted

        if (upToDate) {
            onDone()
            return
        }
        Thread {
            try {
                // Wipe (removes stale sources *and* any __pycache__) then unpack.
                extDir.deleteRecursively()
                extractAssetDir("python_ext", extDir)
                try {
                    marker.writeText(wanted)
                } catch (_: Exception) {
                }
                // The second engine, same stamping so APK updates refresh it too.
                // A dev-build experiment: production ships Gale alone, and a missing
                // asset dir must never break the production path.
                for (engine in Engines.all) {
                    if (engine.id == Engines.gale.id) continue
                    try {
                        val dir = Engines.dir(context, engine.id)
                        val stamp = File(dir, ".engine_version")
                        val fresh = dir.resolve("FreeEnt").exists() &&
                            stamp.exists() && stamp.readText().trim() == wanted
                        if (fresh) continue
                        dir.deleteRecursively()
                        extractAssetDir(engine.assetDir, dir)
                        stamp.writeText(wanted)
                    } catch (e: Exception) {
                        android.util.Log.w("SeedEngine", "engine ${engine.id} not bundled: ${e.message}")
                    }
                }
                onMain(onDone)
            } catch (e: Exception) {
                onMain { onError(e.message ?: "Failed to unpack the engine") }
            }
        }.start()
    }

    private fun extractAssetDir(assetPath: String, target: File) {
        val assets = context.assets
        val names = assets.list(assetPath) ?: return
        target.mkdirs()
        for (name in names) {
            val child = "$assetPath/$name"
            val dest = File(target, name)
            try {
                val stream = assets.open(child)
                stream.use { src ->
                    dest.outputStream().use { dst -> src.copyTo(dst) }
                }
            } catch (_: Exception) {
                // Not a readable file → directory
                extractAssetDir(child, dest)
            }
        }
    }

    /** Live-validate/canonicalize a flag list via the engine. JSON on main thread.
     *  The list is joined to a single string: strings convert through Chaquopy
     *  without any collection-conversion layer. */
    fun flagsFromList(flagNames: List<String>, onValidation: (String) -> Unit) {
        val joined = flagNames.joinToString(" ")
        Thread {
            try {
                val result = synchronized(engineLock) {
                    val py = python()
                    val bridge = py.getModule("fe_bridge")
                    pointBridgeAt(bridge)
                    bridge.callAttr("flags_from_list", joined).toString()
                }
                onMain { onValidation(result) }
            } catch (e: Exception) {
                val msg = e.message?.replace("\"", "'") ?: "unknown error"
                onMain { onValidation("""{"status":"error","error":"$msg"}""") }
            }
        }.start()
    }

    /**
     * Drop the interpreter's cached engine modules so a swapped-in engine takes
     * effect. Blocking — call from a worker thread (the updater does).
     */
    fun reloadEngineBlocking(): Boolean = try {
        synchronized(engineLock) {
            val py = python()
            val bridge = py.getModule("fe_bridge")
            pointBridgeAt(bridge)
            org.json.JSONObject(bridge.callAttr("reload_engine").toString())
                .optString("status") == "ok"
        }
    } catch (e: Exception) {
        false
    }

    /** Null when the engine answers correctly, otherwise a short reason. */
    fun smokeTestBlocking(): String? = try {
        synchronized(engineLock) {
            val py = python()
            val bridge = py.getModule("fe_bridge")
            pointBridgeAt(bridge)
            val json = org.json.JSONObject(
                bridge.callAttr("validate_flags", "Onone Kmain Pshop").toString()
            )
            if (json.optString("status") == "ok") null else json.optString("error")
        }
    } catch (e: Exception) {
        e.message ?: "unknown"
    }

    /** Canonicalize + logic check a flag string in one round trip. */
    fun checkFlags(flags: String, onResult: (String) -> Unit) {
        Thread {
            try {
                val result = synchronized(engineLock) {
                    val py = python()
                    val bridge = py.getModule("fe_bridge")
                    pointBridgeAt(bridge)
                    bridge.callAttr("check_flags", flags).toString()
                }
                onMain { onResult(result) }
            } catch (e: Exception) {
                val msg = e.message?.replace("\"", "'") ?: "unknown error"
                onMain { onResult("""{"status":"error","error":"$msg"}""") }
            }
        }.start()
    }

    /** Translate/drop flags that no longer exist, so old presets and flagsets work. */
    fun sanitizeFlags(flags: String, onResult: (String) -> Unit) {
        Thread {
            try {
                val result = synchronized(engineLock) {
                    val py = python()
                    val bridge = py.getModule("fe_bridge")
                    pointBridgeAt(bridge)
                    bridge.callAttr("sanitize_flags", flags).toString()
                }
                onMain { onResult(result) }
            } catch (e: Exception) {
                val msg = e.message?.replace("\"", "'") ?: "unknown error"
                onMain { onResult("""{"status":"error","error":"$msg"}""") }
            }
        }.start()
    }

    /** Expand a flag string into its flag-name list (editor initialization). */
    fun flagList(flags: String, onValidation: (String) -> Unit) {
        Thread {
            try {
                val result = synchronized(engineLock) {
                    python().getModule("fe_bridge").callAttr("flag_list", flags).toString()
                }
                onMain { onValidation(result) }
            } catch (e: Exception) {
                val msg = e.message?.replace("\"", "'") ?: "unknown error"
                onMain { onValidation("""{"status":"error","error":"$msg"}""") }
            }
        }.start()
    }

    /** Live-validate/canonicalize a flag string via the engine. JSON on main thread. */
    fun validateFlags(flags: String, onValidation: (String) -> Unit) {
        Thread {
            try {
                val result = synchronized(engineLock) {
                    val py = python()
                    val bridge = py.getModule("fe_bridge")
                    pointBridgeAt(bridge)
                    bridge.callAttr("validate_flags", flags).toString()
                }
                onMain { onValidation(result) }
            } catch (e: Exception) {
                val msg = e.message?.replace("\"", "'") ?: "unknown error"
                onMain { onValidation("""{"status":"error","error":"$msg"}""") }
            }
        }.start()
    }

    /** Runs on a worker thread; result is a JSON string delivered on the main thread. */
    fun generate(
        flags: String,
        seed: String,
        metaconfigJson: String,
        outDir: String = romsDir.absolutePath,
        progressPath: String? = null,
        onDone: (String) -> Unit = onResult,
    ) {
        Thread {
            try {
                val result = synchronized(engineLock) {
                    val py = python()
                    val bridge = py.getModule("fe_bridge")
                    pointBridgeAt(bridge)
                    bridge.callAttr(
                        "generate",
                        flags,
                        seed,
                        metaconfigJson,
                        RomStore.baseRomFile(context).absolutePath,
                        outDir,
                        cacheDir.absolutePath,
                        progressPath,
                    ).toString()
                }
                onMain { onDone(result) }
            } catch (e: Exception) {
                val msg = e.message?.replace("\"", "'") ?: "unknown error"
                onMain { onDone("""{"status":"error","error":"$msg"}""") }
            }
        }.start()
    }

    /** Obtain the interpreter, starting it explicitly if the app-level auto-start hasn't run yet. */
    private fun python(): Python {
        return try {
            Python.getInstance()
        } catch (e: Throwable) {
            Python.start(AndroidPlatform(context))
            Python.getInstance()
        }
    }

    private fun onMain(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post { block() }
    }
}