package com.blackmanga.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.security.MessageDigest
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Durable cache of decoded series covers.
 *
 * Unlike an image URL cache, this stores the actual image in filesDir, keyed by
 * the manga URL. The entry survives leaving/re-entering the catalogue and a
 * changed or temporarily unavailable image URL on the source website.
 */
internal object NexusCoverFileCache {
    private const val DIRECTORY = "nexus-toons-covers"
    private const val MIN_VALID_BYTES = 128L
    private const val MAX_WIDTH = 480
    private const val MAX_HEIGHT = 720

    private fun fileFor(context: Context, mangaUrl: String): File {
        val raw = mangaUrl.trim().substringBefore("#").substringBefore("?")
        val uri = runCatching { URI(raw) }.getOrNull()
        val host = uri?.host.orEmpty().lowercase().removePrefix("www.")
        val path = uri?.path.orEmpty().trimEnd('/').ifBlank { "/" }
        val stableKey = if (host.isBlank()) raw.lowercase() else "https://$host$path"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(stableKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return File(File(context.filesDir, DIRECTORY), "$digest.jpg")
    }

    fun existingFile(context: Context, mangaUrl: String): File? {
        val file = fileFor(context, mangaUrl)
        return file.takeIf { it.isFile && it.length() > MIN_VALID_BYTES }
    }

    fun delete(context: Context, mangaUrl: String) {
        runCatching { fileFor(context, mangaUrl).delete() }
    }

    fun save(context: Context, mangaUrl: String, drawable: Drawable): Boolean {
        return runCatching {
            val destination = fileFor(context, mangaUrl)
            if (destination.isFile && destination.length() > MIN_VALID_BYTES) return true

            val sourceBitmap = (drawable as? BitmapDrawable)?.bitmap
            val sourceWidth = (sourceBitmap?.width ?: drawable.intrinsicWidth).coerceAtLeast(1)
            val sourceHeight = (sourceBitmap?.height ?: drawable.intrinsicHeight).coerceAtLeast(1)
            val scale = min(
                1f,
                min(MAX_WIDTH.toFloat() / sourceWidth, MAX_HEIGHT.toFloat() / sourceHeight)
            )
            val targetWidth = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
            val targetHeight = (sourceHeight * scale).roundToInt().coerceAtLeast(1)

            val outputBitmap = if (sourceBitmap != null) {
                Bitmap.createScaledBitmap(sourceBitmap, targetWidth, targetHeight, true)
            } else {
                val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val copy = drawable.constantState?.newDrawable()?.mutate() ?: drawable
                copy.setBounds(0, 0, targetWidth, targetHeight)
                copy.draw(canvas)
                bitmap
            }

            destination.parentFile?.mkdirs()
            val temporary = File(destination.parentFile, destination.name + ".tmp")
            FileOutputStream(temporary).use { stream ->
                if (!outputBitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)) {
                    throw IllegalStateException("Não foi possível guardar a capa")
                }
                stream.flush()
            }
            if (temporary.length() <= MIN_VALID_BYTES) {
                temporary.delete()
                return false
            }
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
            destination.isFile && destination.length() > MIN_VALID_BYTES
        }.getOrDefault(false)
    }
}
