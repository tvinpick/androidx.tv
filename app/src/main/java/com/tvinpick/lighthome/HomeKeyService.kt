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
        if (event.action == KeyEvent.ACTION_UP) launchHome(force = true)
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (event.packageName != STOCK_LAUNCHER) return
        if (!SettingsStore.fresh(this).interceptHome) return
        launchHome(force = false)
    }

    override fun onInterrupt() = Unit

    private val sleepReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Команда «Выключить экран» из оболочки (служба живёт в отдельном процессе).
        val filter = IntentFilter(ACTION_SLEEP)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(sleepReceiver, filter, PERMISSION, null, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(sleepReceiver, filter, PERMISSION, null)
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(sleepReceiver) }
        super.onDestroy()
    }

    private fun launchHome(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastLaunch < 700) return
        lastLaunch = now
        val intent = Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
        )
        runCatching { startActivity(intent) }
    }

    companion object {
        const val STOCK_LAUNCHER = "com.google.android.apps.tv.launcherx"

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
