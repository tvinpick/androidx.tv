package com.tvinpick.lighthome

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Перехватывает кнопку Home и возвращает на «Лёгкий дом».
 * Слушает только смену окна стандартного лаунчера (ограничено в home_service.xml).
 * Работает в отдельном процессе: нажатия пульта не ждут интерфейс оболочки.
 */
class HomeKeyService : AccessibilityService() {
    private var lastLaunch = 0L

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        if (!SettingsStore.fresh(this).interceptHome) return false
        if (event.action == KeyEvent.ACTION_UP) {
            Diag.log("кнопка Home")
            fallbackBlockedUntil = 0
            launchHome(force = true)
        }
        return true
    }

    /** Времена последних запусков через запасной путь — для защиты от зацикливания. */
    private val fallbackTimes = ArrayDeque<Long>()
    private var fallbackBlockedUntil = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (event.packageName != STOCK_LAUNCHER) return
        if (!SettingsStore.fresh(this).interceptHome) return
        val now = SystemClock.elapsedRealtime()
        // Во время загрузки ТВ запасной путь не трогаем: системе нужно спокойно запустить свой лаунчер.
        if (now < BOOT_GRACE_MS) return
        if (now < fallbackBlockedUntil) return
        if (now - lastLaunch < 3_000) return
        while (fallbackTimes.isNotEmpty() && now - fallbackTimes.first() > 60_000) fallbackTimes.removeFirst()
        if (fallbackTimes.size >= 3) {
            // Стандартный лаунчер упорно возвращается — не спорим с системой.
            fallbackBlockedUntil = now + 10 * 60_000
            fallbackTimes.clear()
            Diag.log("защита от зацикливания: запасной путь отключён на 10 минут")
            return
        }
        fallbackTimes.addLast(now)
        launchHome(force = false)
    }

    override fun onInterrupt() = Unit

    private val sleepReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Diag.log("выключение экрана: ${performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)}")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Diag.init(applicationContext, "service")
        Diag.log("служба подключена")
        // Команда «Выключить экран» из оболочки (служба живёт в отдельном процессе).
        val filter = IntentFilter(ACTION_SLEEP)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(sleepReceiver, filter, PERMISSION, null, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(sleepReceiver, filter, PERMISSION, null)
        }
    }

    override fun onDestroy() {
        Diag.log("служба остановлена")
        runCatching { unregisterReceiver(sleepReceiver) }
        super.onDestroy()
    }

    private fun launchHome(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunch < 700) return
        lastLaunch = now
        if (!force) Diag.log("стандартный лаунчер вышел на экран — возвращаю оболочку")
        val intent = Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
        )
        runCatching { startActivity(intent) }
    }

    companion object {
        const val STOCK_LAUNCHER = "com.google.android.apps.tv.launcherx"

        /** Первые 3 минуты после включения ТВ запасной путь не работает. */
        private const val BOOT_GRACE_MS = 3 * 60_000L

        const val ACTION_SLEEP = "com.tvinpick.lighthome.SLEEP"
        const val PERMISSION = "com.tvinpick.lighthome.permission.CONTROL"

        /** Попросить службу выключить экран. */
        fun requestSleep(context: Context) {
            context.sendBroadcast(Intent(ACTION_SLEEP).setPackage(context.packageName), PERMISSION)
        }

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val me = ComponentName(context, HomeKeyService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
