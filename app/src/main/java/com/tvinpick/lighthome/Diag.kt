package com.tvinpick.lighthome

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.provider.Settings
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Журнал диагностики. Пишет короткие строки в filesDir/diag.log сразу на диск (без буфера),
 * чтобы последняя запись сохранилась даже при аварийной перезагрузке ТВ.
 * Отчёт сохраняется в Download/LightHome для передачи на телефон.
 */
object Diag {
    private const val FILE = "diag.log"
    private const val MAX_SIZE = 512 * 1024L
    private val timeFmt = SimpleDateFormat("dd.MM HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var file: File? = null

    @Volatile
    var enabled = true

    private var processTag = "ui"

    fun init(context: Context, tag: String) {
        if (file != null) return
        val f = File(context.filesDir, FILE)
        file = f
        processTag = tag
        enabled = SettingsStore.fresh(context).diagnostics
        if (tag == "ui" && f.length() > MAX_SIZE) rotate(f)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { write("ПАДЕНИЕ в потоке ${thread.name}: ${e.stackTraceToString().take(4000)}") }
            previous?.uncaughtException(thread, e)
        }
        log(
            "старт процесса $tag, pid ${Process.myPid()}, ТВ работает ${uptime()}, " +
                "загрузок ТВ: ${bootCount(context)}, ${memory(context)}",
        )
    }

    fun log(message: String) {
        if (!enabled) return
        write(message)
    }

    @Synchronized
    private fun write(message: String) {
        val f = file ?: return
        runCatching {
            if (f.length() > MAX_SIZE * 2) rotate(f)
            FileOutputStream(f, true).use {
                it.write("${timeFmt.format(Date())} [$processTag] $message\n".toByteArray())
            }
        }
    }

    private fun rotate(f: File) {
        runCatching {
            val bytes = f.readBytes()
            val tail = bytes.copyOfRange(bytes.size - (MAX_SIZE / 2).toInt(), bytes.size)
            f.writeBytes(tail)
        }
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
        write("журнал очищен")
    }

    fun uptime(): String {
        val m = SystemClock.elapsedRealtime() / 60_000
        return if (m < 60) "$m мин" else "${m / 60} ч ${m % 60} мин"
    }

    private fun bootCount(context: Context): String =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).toString() }
            .getOrDefault("?")

    /** Краткая сводка памяти: свободно у ТВ и занято приложением. */
    fun memory(context: Context): String {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val rt = Runtime.getRuntime()
        val heap = (rt.totalMemory() - rt.freeMemory()) / MB
        val native = Debug.getNativeHeapAllocatedSize() / MB
        return "ОЗУ ТВ: свободно ${mi.availMem / MB} из ${mi.totalMem / MB} МБ" +
            (if (mi.lowMemory) " (МАЛО ПАМЯТИ)" else "") +
            ", приложение: java $heap МБ, native $native МБ"
    }

    private const val MB = 1024 * 1024

    private fun storage(context: Context): String = buildString {
        val data = StatFs(context.filesDir.path)
        append("Внутренняя память: свободно ${data.availableBytes / MB} из ${data.totalBytes / MB} МБ\n")
        val sm = context.getSystemService(StorageManager::class.java)
        for (v in runCatching { sm.storageVolumes }.getOrDefault(emptyList())) {
            val dir = v.directory ?: continue
            runCatching {
                val st = StatFs(dir.path)
                append("Том «${v.getDescription(context)}» ($dir): свободно ${st.availableBytes / MB} из ${st.totalBytes / MB} МБ\n")
            }
        }
        val files = context.filesDir.listFiles().orEmpty()
        append("Файлы приложения: " + files.joinToString { "${it.name} ${it.length() / 1024} КБ" } + "\n")
    }

    private fun exitReasons(context: Context): String = buildString {
        val am = context.getSystemService(ActivityManager::class.java)
        val list = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 20) }
            .getOrDefault(emptyList())
        if (list.isEmpty()) append("нет данных\n")
        for (info in list) {
            append("— ${timeFmt.format(Date(info.timestamp))} ${info.processName} pid ${info.pid}: ")
            append(reasonName(info.reason))
            append(", статус ${info.status}, важность ${info.importance}")
            append(", PSS ${info.pss / 1024} МБ, RSS ${info.rss / 1024} МБ")
            info.description?.let { append(", «$it»") }
            append("\n")
            if (info.reason == ApplicationExitInfo.REASON_ANR || info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                runCatching {
                    info.traceInputStream?.bufferedReader()?.use { r ->
                        r.lineSequence().take(150).forEach { append("    $it\n") }
                    }
                }
            }
        }
    }

    private fun reasonName(reason: Int) = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ЗАВИСАНИЕ (ANR)"
        ApplicationExitInfo.REASON_CRASH -> "ПАДЕНИЕ (Java)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "ПАДЕНИЕ (native)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "НЕХВАТКА ПАМЯТИ (система)"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "превышение ресурсов"
        ApplicationExitInfo.REASON_EXIT_SELF -> "завершилось само"
        ApplicationExitInfo.REASON_SIGNALED -> "убито сигналом"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "остановлено пользователем"
        ApplicationExitInfo.REASON_USER_STOPPED -> "пользователь остановлен"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "умерла зависимость"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "смена разрешений"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "ошибка запуска"
        ApplicationExitInfo.REASON_OTHER -> "другое (в т.ч. перезагрузка ТВ)"
        else -> "неизвестно ($reason)"
    }

    private fun ownLogcat(): String = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "threadtime", "-t", "1500"))
        val text = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        text
    }.getOrDefault("недоступен\n")

    /** Собирает отчёт и сохраняет в Download/LightHome. Возвращает путь или null. */
    fun saveReport(context: Context): String? {
        log("сохранение отчёта")
        val settings = SettingsStore.get(context)
        val pi = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val report = buildString {
            append("=== Лёгкий дом: отчёт диагностики ===\n")
            append("Создан: ${timeFmt.format(Date())}\n")
            append("Версия: ${pi?.versionName} (${pi?.longVersionCode})\n")
            append("ТВ: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n")
            append("Сборка: ${Build.FINGERPRINT}\n")
            append("ТВ работает с последнего включения: ${uptime()}, загрузок ТВ: ${bootCount(context)}\n")
            append(memory(context)).append("\n")
            append("Служба Home включена: ${HomeKeyService.isEnabled(context)}, перехват: ${settings.interceptHome}\n")
            append("Фон: ${settings.background}, затемнение ${settings.dim}%, разделов ${settings.sections.size}\n\n")
            append("=== Память и диски ===\n").append(storage(context)).append("\n")
            append("=== Почему Android завершал процессы приложения ===\n").append(exitReasons(context)).append("\n")
            append("=== Журнал ===\n")
            append(runCatching { File(context.filesDir, FILE).readText() }.getOrDefault("пуст\n"))
            append("\n=== Системный лог приложения ===\n").append(ownLogcat())
        }
        val name = "lighthome-diag-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".txt"
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LightHome")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
            context.contentResolver.openOutputStream(uri)!!.use { it.write(report.toByteArray()) }
            "Download/LightHome/$name"
        }.onFailure { log("ошибка сохранения отчёта: $it") }.getOrNull()
    }
}
