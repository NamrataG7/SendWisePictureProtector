package com.safekeyboard.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlin.math.max
import kotlin.math.min

/**
 * ImageBlurUtils - pure-Kotlin/Android blur + safe decode helpers.
 *
 * Deliberately avoids RenderScript (deprecated as of API 31+) in favor of a
 * simple, dependency-free stack-blur implementation. All operations work on
 * downscaled copies — we never hold a full-resolution bitmap in memory.
 */
object ImageBlurUtils {

    private const val BLUR_MAX_DIM = 512

    /**
     * Decodes [uri] via [android.content.ContentResolver] using
     * [BitmapFactory.Options.inSampleSize] so the full-resolution image is
     * never loaded into memory. Returns a bitmap whose longest side is at
     * most [maxDim], or null if decoding fails.
     */
    fun decodeAndDownscale(context: Context, uri: Uri, maxDim: Int = 768): Bitmap? {
        return try {
            val resolver = context.contentResolver

            // Pass 1: bounds only (no pixel allocation).
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            } ?: return null

            val (srcWidth, srcHeight) = boundsOptions.outWidth to boundsOptions.outHeight
            if (srcWidth <= 0 || srcHeight <= 0) return null

            val sampleSize = calculateInSampleSize(srcWidth, srcHeight, maxDim)

            // Pass 2: actual decode at the reduced sample size.
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return null

            // Final safety clamp in case inSampleSize rounding left us over maxDim.
            clampToMaxDim(decoded, maxDim)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun calculateInSampleSize(srcWidth: Int, srcHeight: Int, maxDim: Int): Int {
        var sampleSize = 1
        var longest = max(srcWidth, srcHeight)
        while (longest / (sampleSize * 2) >= maxDim) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun clampToMaxDim(bitmap: Bitmap, maxDim: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxDim) return bitmap
        val scale = maxDim.toFloat() / longest
        val newWidth = max(1, (bitmap.width * scale).toInt())
        val newHeight = max(1, (bitmap.height * scale).toInt())
        val scaled = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    /**
     * Returns a blurred copy of [src]. For performance/memory, the source is
     * first downscaled so its longest side is at most [BLUR_MAX_DIM]px, then
     * a stack-blur is applied at [radius]. The result is a new bitmap — the
     * caller may discard [src] afterwards.
     */
    fun blurBitmap(src: Bitmap, radius: Int = 25): Bitmap {
        val downscaled = clampToMaxDim(src, BLUR_MAX_DIM)
        // Work on a mutable ARGB_8888 copy — never mutate the caller's bitmap.
        val working = downscaled.copy(Bitmap.Config.ARGB_8888, true)
        if (downscaled !== src && downscaled !== working) downscaled.recycle()
        return stackBlur(working, radius.coerceIn(1, 100))
    }

    /**
     * Classic stack-blur algorithm (Mario Klingemann, public domain).
     * Operates in-place on [bitmap]'s pixel array and returns it.
     */
    private fun stackBlur(bitmap: Bitmap, radius: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 1 || h < 1 || radius < 1) return bitmap

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1

        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        var rSum: Int
        var gSum: Int
        var bSum: Int
        var x: Int
        var y: Int
        var i: Int
        var p: Int
        var yp: Int
        var yi: Int
        var yw: Int

        val vMin = IntArray(max(w, h))
        var divSum = (div + 1) shr 1
        divSum *= divSum
        val dv = IntArray(256 * divSum)
        for (j in dv.indices) {
            dv[j] = j / divSum
        }

        yw = 0
        yi = 0

        val stack = Array(div) { IntArray(3) }
        var stackPointer: Int
        var stackStart: Int
        var sir: IntArray
        var rbs: Int
        val r1 = radius + 1
        var routsum: Int
        var goutsum: Int
        var boutsum: Int
        var rinsum: Int
        var ginsum: Int
        var binsum: Int

        y = 0
        while (y < h) {
            rSum = 0; gSum = 0; bSum = 0
            routsum = 0; goutsum = 0; boutsum = 0
            rinsum = 0; ginsum = 0; binsum = 0

            i = -radius
            while (i <= radius) {
                p = pixels[yi + min(wm, max(i, 0))]
                sir = stack[i + radius]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = (p and 0x0000ff)
                rbs = r1 - kotlin.math.abs(i)
                rSum += sir[0] * rbs
                gSum += sir[1] * rbs
                bSum += sir[2] * rbs
                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
                i++
            }
            stackPointer = radius

            x = 0
            while (x < w) {
                r[yi] = dv[rSum]
                g[yi] = dv[gSum]
                b[yi] = dv[bSum]

                rSum -= routsum
                gSum -= goutsum
                bSum -= boutsum

                stackStart = stackPointer - radius + div
                sir = stack[stackStart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (y == 0) {
                    vMin[x] = min(x + radius + 1, wm)
                }
                p = pixels[yw + vMin[x]]

                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = (p and 0x0000ff)

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rSum += rinsum
                gSum += ginsum
                bSum += binsum

                stackPointer = (stackPointer + 1) % div
                sir = stack[stackPointer % div]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

                yi++
                x++
            }
            yw += w
            y++
        }

        x = 0
        while (x < w) {
            rSum = 0; gSum = 0; bSum = 0
            routsum = 0; goutsum = 0; boutsum = 0
            rinsum = 0; ginsum = 0; binsum = 0

            yp = -radius * w
            i = -radius
            while (i <= radius) {
                yi = max(0, yp) + x
                sir = stack[i + radius]
                sir[0] = r[yi]
                sir[1] = g[yi]
                sir[2] = b[yi]
                rbs = r1 - kotlin.math.abs(i)
                rSum += r[yi] * rbs
                gSum += g[yi] * rbs
                bSum += b[yi] * rbs
                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
                if (i < hm) {
                    yp += w
                }
                i++
            }
            yi = x
            stackPointer = radius
            y = 0
            while (y < h) {
                pixels[yi] = (-0x1000000 and pixels[yi]) or (dv[rSum] shl 16) or (dv[gSum] shl 8) or dv[bSum]

                rSum -= routsum
                gSum -= goutsum
                bSum -= boutsum

                stackStart = stackPointer - radius + div
                sir = stack[stackStart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (x == 0) {
                    vMin[y] = min(y + r1, hm) * w
                }
                p = x + vMin[y]

                sir[0] = r[p]
                sir[1] = g[p]
                sir[2] = b[p]

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rSum += rinsum
                gSum += ginsum
                bSum += binsum

                stackPointer = (stackPointer + 1) % div
                sir = stack[stackPointer % div]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

                yi += w
                y++
            }
            x++
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap
    }
}
