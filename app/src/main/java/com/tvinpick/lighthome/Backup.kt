package com.tvinpick.lighthome

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Экспорт/импорт настроек. Файл — PNG-картинка с JSON, дописанным после конца изображения:
 * картинки видны через MediaStore на любом ТВ без дополнительных разрешений,
 * даже после переустановки приложения.
 */
object Backup {
    const val PREFIX = "lighthome-settings"
    private val MAGIC = "\nLIGHTHOME-SETTINGS\n".toByteArray()

    /** Сохраняет файл на все тома (память ТВ и USB). Возвращает число записанных копий. */
    fun export(context: Context, settings: ShellSettings): Int {
        val bytes = buildFile(settings)
        val name = "$PREFIX-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".png"
        var written = 0
        val volumes = runCatching { MediaStore.getExternalVolumeNames(context) }.getOrDefault(emptySet())
        for (volume in volumes) {
            runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LightHome")
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.getContentUri(volume), values)
                    ?: return@runCatching
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return@runCatching
                written++
            }
        }
        return written
    }

    /** Читает настройки из ранее экспортированного файла. */
    fun import(context: Context, item: MediaItem): ShellSettings? = runCatching {
        val bytes = context.contentResolver.openInputStream(item.uri)?.use { it.readBytes() } ?: return null
        val at = indexOf(bytes, MAGIC)
        if (at < 0) return null
        SettingsStore.decode(String(bytes, at + MAGIC.size, bytes.size - at - MAGIC.size))
    }.getOrNull()

    private fun buildFile(settings: ShellSettings): ByteArray {
        val bmp = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(0x1E, 0x2A, 0x3A))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 40f }
        canvas.drawText("Лёгкий дом", 40f, 160f, paint)
        paint.textSize = 28f
        canvas.drawText("Резервная копия настроек", 40f, 210f, paint)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        out.write(MAGIC)
        out.write(SettingsStore.encode(settings).toByteArray())
        return out.toByteArray()
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray): Int {
        outer@ for (i in data.size - pattern.size downTo 0) {
            for (j in pattern.indices) if (data[i + j] != pattern[j]) continue@outer
            return i
        }
        return -1
    }
}
