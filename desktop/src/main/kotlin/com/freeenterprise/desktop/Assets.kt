package com.freeenterprise.desktop

import java.awt.Font
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * The app's own assets, copied into this module's resources at build time by the
 * `copyFf4Assets` task, so there is one copy of the font and the cursor and no
 * path assumptions.
 */
object Assets {
    /** The game font, unscaled: metrics come from derived instances per size. */
    val baseFont: Font by lazy {
        Assets::class.java.getResourceAsStream("/fonts/ff4.ttf")!!.use {
            Font.createFont(Font.TRUETYPE_FONT, it)
        }
    }

    /** The game's POINTER.png, drawn beside the selected menu row. */
    val cursor: BufferedImage by lazy {
        Assets::class.java.getResourceAsStream("/ff4/pointer.png")!!.use { ImageIO.read(it) }
    }
}
