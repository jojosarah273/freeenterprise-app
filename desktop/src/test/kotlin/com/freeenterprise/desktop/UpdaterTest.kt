package com.freeenterprise.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The updater's apply and rollback paths, exercised on a synthetic bundle so they do
 * not depend on a release existing. These are the parts that can destroy an install,
 * so they are tested rather than trusted.
 */
class UpdaterTest {

    @get:Rule val temp = TemporaryFolder()

    private fun versionFile(dir: File, version: String): File =
        File(dir, "python_ext/FreeEnt/version.py").apply {
            parentFile.mkdirs()
            writeText(
                """
                MAJOR = ${version.substringBefore('.')}
                MINOR = ${version.split('.')[1]}
                PATCH = ${version.split('.')[2].substringBefore('.').substringBefore('"')}
                FORK = "Gale"
                """.trimIndent(),
            )
        }

    private fun bundle(zip: File, version: String): File {
        ZipOutputStream(zip.outputStream()).use { out ->
            val files = mapOf(
                "python_ext/FreeEnt/version.py" to versionFile(temp.root, version).readText(),
                "spec/ui_spec.json" to "[]",
                "presets/presets.json" to "[]",
            )
            for ((name, body) in files) {
                out.putNextEntry(ZipEntry(name))
                out.write(body.toByteArray())
                out.closeEntry()
            }
        }
        return zip
    }

    @Test
    fun `an engine bundle is applied, and the previous engine can be restored`() {
        val install = temp.newFolder("install")
        File(install, "engine/python_ext/FreeEnt").mkdirs()
        File(install, "engine/python_ext/FreeEnt/version.py").writeText(
            "MAJOR = 4\nMINOR = 7\nPATCH = 0\nFORK = \"Gale\"\n",
        )
        File(install, "engine/assets").mkdirs()

        assertEquals("4.7.0.Gale", Updater.engineInstalledVersion(install))
        assertFalse(Updater.canRollBackEngine(install))

        val zip = bundle(File(temp.root, "bundle.zip"), "9.9.9")
        val applied = Updater.applyEngine(install, zip, "9.9.9.Gale")

        assertEquals("9.9.9.Gale", applied)
        assertEquals("9.9.9.Gale", Updater.engineInstalledVersion(install))
        assertTrue("the old engine is kept", Updater.canRollBackEngine(install))
        assertTrue("the spec came with it", File(install, "engine/assets/ui_spec.json").isFile)
        assertTrue("and the presets", File(install, "engine/assets/presets.json").isFile)
        assertFalse("the download is cleaned up", zip.exists())

        val restored = Updater.rollbackEngine(install)
        assertEquals("4.7.0.Gale", restored)
        assertEquals("4.7.0.Gale", Updater.engineInstalledVersion(install))
    }

    @Test
    fun `a bundle whose version disagrees with the release is refused`() {
        val install = temp.newFolder("install2")
        File(install, "engine/python_ext/FreeEnt").mkdirs()
        File(install, "engine/python_ext/FreeEnt/version.py").writeText(
            "MAJOR = 4\nMINOR = 7\nPATCH = 0\nFORK = \"Gale\"\n",
        )
        val zip = bundle(File(temp.root, "wrong.zip"), "9.9.9")

        val error = runCatching { Updater.applyEngine(install, zip, "1.2.3.Gale") }.exceptionOrNull()
        assertTrue(error != null && error.message!!.contains("but the release says"))
        assertEquals("the live engine is untouched", "4.7.0.Gale", Updater.engineInstalledVersion(install))
    }

    @Test
    fun `an archive that escapes its directory is refused`() {
        val install = temp.newFolder("install3")
        val zip = File(temp.root, "evil.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("../../escape.txt"))
            out.write("nope".toByteArray())
            out.closeEntry()
        }
        val error = runCatching { Updater.applyEngine(install, zip, null) }.exceptionOrNull()
        assertTrue(error != null && error.message!!.contains("unsafe path"))
        assertFalse("nothing written outside", File(install.parentFile, "escape.txt").exists())
    }

    @Test
    fun `version comparison ignores the tag prefix and suffixes`() {
        assertTrue(Updater.isNewer("v0.1.26", "0.1.25"))
        assertFalse(Updater.isNewer("v0.1.25", "0.1.25"))
        assertFalse(Updater.isNewer("v0.1.24", "0.1.25"))
        assertTrue(Updater.isNewer("4.7.1.Gale", "4.7.0.Gale"))
        assertFalse(Updater.isNewer("0.1.25-dev", "0.1.25"))
        assertTrue(Updater.isNewer("0.2", "0.1.9"))
    }
}
