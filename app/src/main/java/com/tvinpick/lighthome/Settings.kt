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
data class ShellSettings(
    val sections: List<Section> = listOf(Section(id = 1, name = "Избранное")),
    /** Версия кэшированного фона в filesDir; 0 — фона нет (тёмный градиент). */
    val backgroundVersion: Long = 0,
    /** Затемнение фона, 0–70 %. */
    val dim: Int = 30,
    /** Общий масштаб интерфейса, 80–140 %. */
    val scale: Int = 100,
    val interceptHome: Boolean = true,
)

/** Хранит настройки JSON-файлом в filesDir. Общий для активности и службы (один процесс). */
object SettingsStore {
    private const val FILE = "settings.json"
    const val BACKGROUND_FILE = "background.jpg"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile
    private var cached: ShellSettings? = null

    fun get(context: Context): ShellSettings {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val file = File(context.filesDir, FILE)
            val loaded = runCatching { json.decodeFromString<ShellSettings>(file.readText()) }
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
            tmp.writeText(json.encodeToString(ShellSettings.serializer(), settings))
            tmp.renameTo(file)
        }
    }

    fun backgroundFile(context: Context) = File(context.filesDir, BACKGROUND_FILE)
}
