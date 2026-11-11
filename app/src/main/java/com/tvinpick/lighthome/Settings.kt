package com.tvinpick.lighthome

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
enum class TileSize { S, M, L }

@Serializable
data class Section(
    val id: Long,
    val name: String,
    val size: TileSize = TileSize.M,
    val apps: List<String> = emptyList(),
)

@Serializable
enum class BgType { NONE, IMAGE, VIDEO, SLIDESHOW }

@Serializable
data class ShellSettings(
    val sections: List<Section> = listOf(Section(id = 1, name = "Избранное")),
    /** Тип фона; null — старые настройки: картинка, если есть backgroundVersion. */
    val bgType: BgType? = null,
    /** Версия кэшированного фона (картинка/видео) в filesDir. */
    val backgroundVersion: Long = 0,
    /** Слайд-шоу: адреса картинок и интервал смены в минутах. */
    val slideshow: List<String> = emptyList(),
    val slideshowMinutes: Int = 5,
    /** Затемнение фона, 0–70 %. */
    val dim: Int = 30,
    /** Общий масштаб интерфейса, 80–140 %. */
    val scale: Int = 100,
    val interceptHome: Boolean = true,
    /** Последнее приложение, запущенное из оболочки (кнопка «Продолжить»). */
    val lastApp: String? = null,
    val showDate: Boolean = true,
    val hideSingleTitle: Boolean = false,
    val showLabels: Boolean = false,
    val rounded: Boolean = true,
    /** Отступ между плитками, dp. */
    val spacing: Int = 14,
    /** Защита от случайного изменения разделов. */
    val locked: Boolean = false,
) {
    val background: BgType
        get() = bgType ?: if (backgroundVersion != 0L) BgType.IMAGE else BgType.NONE
}

/** Хранит настройки JSON-файлом в filesDir. Общий для активности и службы (один процесс). */
object SettingsStore {
    private const val FILE = "settings.json"
    const val BACKGROUND_FILE = "background.jpg"
    const val VIDEO_FILE = "background.mp4"

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile
    private var cached: ShellSettings? = null

    fun get(context: Context): ShellSettings {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val file = File(context.filesDir, FILE)
            val loaded = runCatching { decode(file.readText()) }
                .getOrNull() ?: ShellSettings()
            cached = loaded
            return loaded
        }
    }

    fun save(context: Context, settings: ShellSettings) {
        synchronized(this) {
            cached = settings
            val file = File(context.filesDir, FILE)
            val tmp = File(context.filesDir, "$FILE.tmp")
            tmp.writeText(encode(settings))
            tmp.renameTo(file)
        }
    }

    fun backgroundFile(context: Context) = File(context.filesDir, BACKGROUND_FILE)

    fun videoFile(context: Context) = File(context.filesDir, VIDEO_FILE)

    fun encode(settings: ShellSettings): String = json.encodeToString(ShellSettings.serializer(), settings)

    fun decode(text: String): ShellSettings = json.decodeFromString(ShellSettings.serializer(), text)
}
