package com.tvinpick.lighthome

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

/** Картинка или видео из памяти ТВ / USB. */
data class MediaItem(
    val uri: Uri,
    val name: String,
    val folder: String,
    val isVideo: Boolean,
    /** Время добавления, мс. */
    val date: Long,
)

object Images {
    // --- Фон-картинка ---

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

    /** Ограниченный пул для декодирования: на слабом ТВ не больше двух картинок одновременно. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val decodeDispatcher = kotlinx.coroutines.Dispatchers.IO.limitedParallelism(2)

    /**
     * Декодирует картинку сразу в уменьшенном виде (ImageDecoder с целевым размером):
     * даже 8K-фото не загружается в память целиком. Поддерживает JPEG, PNG, WEBP, HEIF.
     * cover = true — минимальная сторона не меньше заданной (для обрезки), иначе вписать.
     */
    fun decodeScaled(context: Context, uri: Uri, w: Int, h: Int, cover: Boolean): Bitmap? = try {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val sw = info.size.width
            val sh = info.size.height
            if (sw > 0 && sh > 0) {
                val k = if (cover) maxOf(w.toFloat() / sw, h.toFloat() / sh)
                else minOf(w.toFloat() / sw, h.toFloat() / sh)
                if (k < 1f) {
                    decoder.setTargetSize(
                        (sw * k).toInt().coerceAtLeast(1),
                        (sh * k).toInt().coerceAtLeast(1),
                    )
                }
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
        }
    } catch (t: Throwable) {
        null
    }

    /** Картинка, уменьшенная и обрезанная по центру под размер экрана. */
    fun decodeForScreen(context: Context, uri: Uri): Bitmap? {
        val dm = context.resources.displayMetrics
        val screenW = maxOf(dm.widthPixels, dm.heightPixels)
        val screenH = minOf(dm.widthPixels, dm.heightPixels)
        val src = decodeScaled(context, uri, screenW, screenH, cover = true) ?: return null
        if (src.width == screenW && src.height == screenH) return src
        val k = maxOf(screenW.toFloat() / src.width, screenH.toFloat() / src.height)
        val cropW = (screenW / k).toInt().coerceIn(1, src.width)
        val cropH = (screenH / k).toInt().coerceIn(1, src.height)
        val left = (src.width - cropW) / 2
        val top = (src.height - cropH) / 2
        val out = Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(
            src, Rect(left, top, left + cropW, top + cropH), Rect(0, 0, screenW, screenH),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        src.recycle()
        return out
    }

    /** Уменьшает выбранную картинку до размера экрана и сохраняет в filesDir. */
    fun saveBackground(context: Context, uri: Uri): Boolean {
        val out = decodeForScreen(context, uri) ?: return false
        val file = SettingsStore.backgroundFile(context)
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        out.recycle()
        return tmp.renameTo(file)
    }

    /** Копирует видео в filesDir, чтобы фон не пропал при извлечении флешки. */
    fun saveVideo(context: Context, uri: Uri): Boolean {
        val file = SettingsStore.videoFile(context)
        val tmp = File(file.path + ".tmp")
        val input = context.contentResolver.openInputStream(uri) ?: return false
        input.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
        return tmp.renameTo(file)
    }

    fun deleteBackground(context: Context) {
        SettingsStore.backgroundFile(context).delete()
        SettingsStore.videoFile(context).delete()
        synchronized(this) {
            bgVersion = 0
            bgBitmap = null
        }
    }

    // --- Картинки и видео из памяти ТВ и USB ---

    private val imageExt = setOf("jpg", "jpeg", "png", "webp", "bmp")
    private val videoExt = setOf("mp4", "mkv", "webm", "3gp", "mov")

    /**
     * Картинки и видео со всех внешних томов, новые сверху. Вызывать не в главном потоке.
     * Сначала индекс MediaStore, затем прямой обход томов — на случай, если флешка
     * ещё не проиндексирована.
     */
    fun listMedia(context: Context): List<MediaItem> {
        val result = ArrayList<MediaItem>()
        val knownPaths = HashSet<String>()
        val volumes = runCatching { MediaStore.getExternalVolumeNames(context) }.getOrDefault(emptySet())
        for (volume in volumes) {
            queryCollection(context, MediaStore.Images.Media.getContentUri(volume), false, result, knownPaths)
            queryCollection(context, MediaStore.Video.Media.getContentUri(volume), true, result, knownPaths)
        }
        val sm = context.getSystemService(StorageManager::class.java)
        val roots = runCatching { sm.storageVolumes.mapNotNull { it.directory } }.getOrDefault(emptyList())
        val found = ArrayList<File>()
        for (root in roots) scanDir(root, 0, found)
        for (f in found) {
            if (f.path in knownPaths) continue
            result += MediaItem(
                uri = Uri.fromFile(f),
                name = f.name,
                folder = f.parentFile?.name ?: "",
                isVideo = f.extension.lowercase() in videoExt,
                date = f.lastModified(),
            )
        }
        result.sortByDescending { it.date }
        return result
    }

    @Suppress("DEPRECATION")
    private fun queryCollection(
        context: Context,
        collection: Uri,
        isVideo: Boolean,
        out: MutableList<MediaItem>,
        knownPaths: MutableSet<String>,
    ) {
        runCatching {
            context.contentResolver.query(
                collection,
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DATA,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                    MediaStore.MediaColumns.DATE_ADDED,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val path = c.getString(1)
                    if (path != null) knownPaths += path
                    out += MediaItem(
                        uri = ContentUris.withAppendedId(collection, c.getLong(0)),
                        name = c.getString(2) ?: path?.substringAfterLast('/') ?: "",
                        folder = c.getString(3) ?: "",
                        isVideo = isVideo,
                        date = c.getLong(4) * 1000,
                    )
                }
            }
        }
    }

    private fun scanDir(dir: File, depth: Int, out: MutableList<File>) {
        if (depth > 5 || out.size >= 2000) return
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (f in children) {
            if (f.name.startsWith(".")) continue
            if (f.isDirectory) {
                if (depth == 0 && f.name == "Android") continue
                scanDir(f, depth + 1, out)
            } else {
                val ext = f.extension.lowercase()
                if (ext in imageExt || ext in videoExt) {
                    out += f
                    if (out.size >= 2000) return
                }
            }
        }
    }

    private val thumbs = object : LruCache<Uri, ImageBitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: Uri, value: ImageBitmap) = value.width * value.height * 4
    }

    fun cachedThumb(uri: Uri): ImageBitmap? = thumbs.get(uri)

    fun thumb(context: Context, item: MediaItem): ImageBitmap? {
        thumbs.get(item.uri)?.let { return it }
        val size = Size(256, 144)
        val bmp = runCatching {
            if (item.uri.scheme == "file") {
                val file = File(item.uri.path!!)
                if (item.isVideo) ThumbnailUtils.createVideoThumbnail(file, size, null)
                else decodeScaled(context, item.uri, size.width, size.height, cover = true)
            } else {
                context.contentResolver.loadThumbnail(item.uri, size, null)
            }
        }.getOrNull()?.asImageBitmap() ?: return null
        thumbs.put(item.uri, bmp)
        return bmp
    }

    fun trimThumbs() = thumbs.evictAll()
}
