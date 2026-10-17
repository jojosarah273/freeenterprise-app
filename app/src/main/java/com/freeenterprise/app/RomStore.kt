package com.freeenterprise.app

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** Base-ROM storage + validation (same rules as f4c/ff4bin/rom.py). */
object RomStore {
    // MD5s of Final Fantasy II (US) v1.0 / v1.1, unheadered.
    private const val FF2USV10_MD5 = "4fa9e542b954dcb954d5ce38188d9d41"
    private const val FF2USV11_MD5 = "27d02a4f03e172e029c9b82ac3db79f7"
    const val SIZE_UNHEADERED = 0x100000
    const val SIZE_HEADERED = 0x100200

    fun baseRomFile(context: Context): File = File(context.filesDir, "base_rom.smc")

    fun hasBaseRom(context: Context): Boolean = baseRomFile(context).exists()

    fun md5(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Returns null when the ROM is a valid FF2 US v1.0/v1.1, else an error message. */
    fun validate(bytes: ByteArray): String? {
        if (bytes.size != SIZE_UNHEADERED && bytes.size != SIZE_HEADERED) {
            return "Wrong file size: expected an unmodified FINAL FANTASY II (US) ROM " +
                "(1 MB unheadered, or 1 MB + 512-byte header). Got ${bytes.size} bytes."
        }
        val data = if (bytes.size == SIZE_HEADERED) bytes.copyOfRange(512, bytes.size) else bytes
        val checksum = md5(data)
        return when (checksum) {
            FF2USV10_MD5, FF2USV11_MD5 -> null
            else -> "Checksum mismatch. Free Enterprise requires an UNMODIFIED " +
                "Final Fantasy II (US) v1.0 or v1.1 ROM (this app never modifies it)."
        }
    }
}