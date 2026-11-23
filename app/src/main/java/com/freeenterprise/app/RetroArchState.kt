package com.freeenterprise.app

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Inflater

/**
 * Reads a RetroArch savestate.
 *
 * RetroArch does not store a core's state raw. A state file is a "#RZIP" container:
 * a 24-byte header followed by SEVERAL zlib streams (seven in the states seen here),
 * and the inflated bytes are RetroArch's own "RASTATE" record of tag/size blocks -
 * "MEM " holds the actual core payload (snes9x's, which starts "#!s9xsnp"), plus
 * "SRAM" and "END ". Handing any of that to retro_unserialize is rejected without a
 * word, so a state copied from RetroArch made "Load State" appear to do nothing.
 *
 * This turns such a file into the raw core state the bridge wants.
 */
object RetroArchState {

    private fun indexOfZlib(bytes: ByteArray, from: Int = 0): Int {
        var i = from
        while (i + 1 < bytes.size) {
            val a = bytes[i].toInt() and 0xFF
            val b = bytes[i + 1].toInt() and 0xFF
            if (a == 0x78 && (b == 0x01 || b == 0x9C || b == 0xDA)) return i
            i++
        }
        return -1
    }

    /** Every zlib chunk in a #RZIP file, concatenated. */
    fun inflateAll(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var pos = indexOfZlib(bytes)
        while (pos != -1) {
            val inflater = Inflater()
            inflater.setInput(bytes, pos, bytes.size - pos)
            val buffer = ByteArray(64 * 1024)
            var read = 0
            try {
                while (!inflater.finished()) {
                    val n = inflater.inflate(buffer)
                    if (n == 0) break
                    out.write(buffer, 0, n)
                }
                read = inflater.bytesRead.toInt()
            } catch (t: Throwable) {
                break
            } finally {
                inflater.end()
            }
            if (read <= 0) break
            pos = indexOfZlib(bytes, pos + read)
        }
        return out.toByteArray()
    }

    /** The "MEM " block of a RASTATE record, or null if this is not one. */
    fun coreState(state: ByteArray): ByteArray? {
        val magic = "RASTATE".toByteArray()
        if (state.size < 16 || !state.copyOfRange(0, magic.size).contentEquals(magic)) return null
        var p = magic.size + 1
        while (p + 8 <= state.size) {
            val tag = String(state, p, 4, Charsets.US_ASCII)
            val size = (state[p + 4].toInt() and 0xFF) or
                ((state[p + 5].toInt() and 0xFF) shl 8) or
                ((state[p + 6].toInt() and 0xFF) shl 16) or
                ((state[p + 7].toInt() and 0xFF) shl 24)
            val body = p + 8
            if (size < 0 || body + size > state.size) return null
            if (tag == "MEM ") return state.copyOfRange(body, body + size)
            if (tag.startsWith("END")) break
            p = body + size
        }
        return null
    }

    /** The bytes to hand the core for [file]: raw, or unwrapped from RetroArch's format. */
    fun forCore(file: File): ByteArray? {
        val bytes = file.readBytes()
        return try {
            if (bytes.size > 24 && bytes.copyOfRange(0, 5).contentEquals("#RZIP".toByteArray())) {
                coreState(inflateAll(bytes))
            } else {
                bytes
            }
        } catch (t: Throwable) {
            null
        }
    }
}
