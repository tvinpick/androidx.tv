package com.tvinpick.lighthome

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

object Images {
    // --- Фон ---

    private var bgVersion = 0L
    private var bgBitmap: ImageBitmap? = null

    /** Фон из кэша в filesDir (уже уменьшен до размера экрана). Вызывать не в главном потоке. */
    fun background(context: Context, version: Long): ImageBitmap? {
        if (version == 0L) return null
        synchronized(this) {
            if (bgVersion == version) return bgBitmap
        }
        val file = SettingsStore.backgroundFile(context)
        val bmp = if (file.exists()) BitmapFactory.decodeFile(file.path)?.asImageBitmap() else null
        synchronized(this) {
            bgVersion = version
            bgBitmap = bmp
        }
        return bmp
    }

    /** Уменьшает выбранную картинку до размера экрана и сохраняет в filesDir. */
    fun saveBackground(context: Context, uri: Uri): Boolean {
        val dm = context.resources.displayMetrics
        val screenW = maxOf(dm.widthPixels, dm.heightPixels)
        val screenH = minOf(dm.widthPixels, dm.heightPixels)
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return false
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= screenW && bounds.outHeight / (sample * 2) >= screenH) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val src = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return false
        // Обрезка по центру под пропорции экрана.
        val k = maxOf(screenW.toFloat() / src.width, screenH.toFloat() / src.height)
        val cropW = (screenW / k).toInt().coerceAtMost(src.width)
        val cropH = (screenH / k).toInt().coerceAtMost(src.height)
        val left = (src.width - cropW) / 2
        val top = (src.height - cropH) / 2
        val out = Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(
            src, Rect(left, top, left + cropW, top + cropH), Rect(0, 0, screenW, screenH),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        src.recycle()
        val file = SettingsStore.backgroundFile(context)
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        out.recycle()
        return tmp.renameTo(file)
    }

    fun deleteBackground(context: Context) {
        SettingsStore.backgroundFile(context).delete()
        synchronized(this) {
            bgVersion = 0
            bgBitmap = null
        }
    }

    // --- Картинки из памяти ТВ и USB ---

    /** Картинки со всех внешних томов (встроенная память, USB). Вызывать не в главном потоке. */
    fun listImages(context: Context): List<Uri> {
        val result = ArrayList<Uri>()
        val volumes = runCatching { MediaStore.getExternalVolumeNames(context) }.getOrDefault(emptySet())
        for (volume in volumes) {
            val collection = MediaStore.Images.Media.getContentUri(volume)
            runCatching {
                context.contentResolver.query(
                    collection,
                    arrayOf(MediaStore.Images.Media._ID),
                    null, null,
                    "${MediaStore.Images.Media.DATE_MODIFIED} DESC",
                )?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    while (c.moveToNext()) {
                        result += ContentUris.withAppendedId(collection, c.getLong(idCol))
                    }
                }
            }
        }
        return result
    }

    private val thumbs = object : LruCache<Uri, ImageBitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: Uri, value: ImageBitmap) = value.width * value.height * 4
    }

    fun cachedThumb(uri: Uri): ImageBitmap? = thumbs.get(uri)

    fun thumb(context: Context, uri: Uri): ImageBitmap? {
        thumbs.get(uri)?.let { return it }
        val bmp = runCatching {
            context.contentResolver.loadThumbnail(uri, Size(256, 144), null).asImageBitmap()
        }.getOrNull() ?: return null
        thumbs.put(uri, bmp)
        return bmp
    }

    fun trimThumbs() = thumbs.evictAll()
}
