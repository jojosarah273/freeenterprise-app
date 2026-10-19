package com.freeenterprise.app.ff4ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan

/**
 * Renders the seed's four checksum tiles as the game's item icons instead of
 * words ("tent/tent/tent/sword" -> four little icons).
 *
 * The 16 icons are `assets/checksum/checksum-<tile>.png`, the same images the
 * Free Enterprise website serves, and they match the engine's 16
 * CHECKSUM_TILES names one-for-one (bow, claw, crystal, feather, glove, harp,
 * key, mini, potion, ring, shield, shirt, staff, star, sword, tent).
 *
 * Samples scaled with `filter = false` (nearest-neighbour) so the pixels stay
 * crisp at UI sizes.
 */
object GameIcons {

    private val cache = HashMap<String, Drawable>()

    /** e.g. ["tent","tent","tent","sword"] -> four icon spans. */
    fun verification(context: Context, tiles: List<String>, sizePx: Float): CharSequence {
        val sb = SpannableStringBuilder()
        for ((index, name) in tiles.withIndex()) {
            if (index > 0) sb.append("  ")
            val icon = tile(context, name, sizePx)
            if (icon == null) {
                sb.append(name)      // fall back to the word if the art is unavailable
                continue
            }
            val start = sb.length
            sb.append("\uFFFC")
            sb.setSpan(
                ImageSpan(icon, ImageSpan.ALIGN_BOTTOM),
                start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return sb
    }

    private fun tile(context: Context, name: String, sizePx: Float): Drawable? {
        val px = sizePx.toInt().coerceAtLeast(8)
        val key = "$name@$px"
        cache[key]?.let { return it }
        val source = try {
            context.assets.open("checksum/checksum-$name.png").use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        } ?: return null
        val scaled = Bitmap.createScaledBitmap(source, px, px, false)
        val drawable = BitmapDrawable(context.resources, scaled)
        drawable.setBounds(0, 0, px, px)
        cache[key] = drawable
        return drawable
    }
}
