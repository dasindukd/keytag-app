package com.keytag.app

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build

/**
 * Turns a thumbnail into a 64x64 black & white image for the OLED.
 * Output: 512 bytes, 1 bit per pixel, rows top to bottom, MSB = leftmost pixel
 * (the format Adafruit_GFX drawBitmap expects).
 */
object ArtProcessor {
    private const val S = 64

    fun toMono64(source: Bitmap): ByteArray? {
        return try {
            val src = if (Build.VERSION.SDK_INT >= 26 && source.config == Bitmap.Config.HARDWARE)
                source.copy(Bitmap.Config.ARGB_8888, false) else source

            // centre-crop to a square (YouTube thumbnails are 16:9)
            val side = minOf(src.width, src.height)
            val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
            val small = Bitmap.createScaledBitmap(square, S, S, true)

            val px = IntArray(S * S)
            small.getPixels(px, 0, S, 0, 0, S, S)

            // grayscale
            val g = FloatArray(S * S) { i ->
                val c = px[i]
                0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
            }

            // stretch contrast so dark thumbnails still show detail
            var mn = 255f
            var mx = 0f
            for (v in g) { if (v < mn) mn = v; if (v > mx) mx = v }
            if (mx - mn > 10f) for (i in g.indices) g[i] = (g[i] - mn) * 255f / (mx - mn)

            // Floyd-Steinberg dithering
            val out = ByteArray(S * S / 8)
            for (y in 0 until S) {
                for (x in 0 until S) {
                    val i = y * S + x
                    val old = g[i]
                    val white = old > 127f
                    val err = old - if (white) 255f else 0f
                    if (white) {
                        val b = y * (S / 8) + x / 8
                        out[b] = (out[b].toInt() or (0x80 ushr (x % 8))).toByte()
                    }
                    if (x < S - 1) g[i + 1] += err * 7f / 16f
                    if (y < S - 1) {
                        if (x > 0) g[i + S - 1] += err * 3f / 16f
                        g[i + S] += err * 5f / 16f
                        if (x < S - 1) g[i + S + 1] += err * 1f / 16f
                    }
                }
            }
            out
        } catch (e: Exception) {
            null
        }
    }
}
