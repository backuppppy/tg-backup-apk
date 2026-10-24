package org.tgbackup.app.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.tgbackup.export.output.ImageThumbs
import java.io.File
import java.io.FileOutputStream

/**
 * QImageReader / QImage::scaled(SmoothTransformation) / QImage::save on
 * Android. The default JPEG quality of Qt is 75.
 */
class AndroidImageThumbs : ImageThumbs {
    override fun size(path: String): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return options.outWidth to options.outHeight
    }

    override fun scaleAndSave(path: String, width: Int, height: Int, target: String, format: String?, quality: Int): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val compress = when ((format ?: bounds.outMimeType ?: "").lowercase()) {
            "png", "image/png" -> Bitmap.CompressFormat.PNG
            "jpg", "jpeg", "image/jpeg" -> Bitmap.CompressFormat.JPEG
            "webp", "image/webp" -> @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
            else -> return false
        }
        // Decode with a power-of-two downsample first to keep memory low.
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2
        val source = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
        val scaled = Bitmap.createScaledBitmap(source, width, height, true)
        if (scaled !== source) source.recycle()
        return try {
            File(target).parentFile?.mkdirs()
            FileOutputStream(target).use { out ->
                scaled.compress(compress, if (quality < 0) 75 else quality, out)
            }
        } catch (e: Exception) {
            false
        } finally {
            scaled.recycle()
        }
    }
}
