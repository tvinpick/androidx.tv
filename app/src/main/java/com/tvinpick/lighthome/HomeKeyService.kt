package com.tvinpick.lighthome

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Перехватывает кнопку Home и возвращает на «Лёгкий дом».
 * Слушает только смену окна стандартного лаунчера (ограничено в home_service.xml).
 */
class HomeKeyService : AccessibilityService() {
    private var lastLaunch = 0L

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        if (!SettingsStore.get(this).interceptHome) return false
        if (event.action == KeyEvent.ACTION_UP) launchHome(force = true)
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (event.packageName != STOCK_LAUNCHER) return
        if (!SettingsStore.get(this).interceptHome) return
        launchHome(force = false)
    }

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** Выключить экран (как кнопка питания). */
    fun sleep(): Boolean = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)

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

        @Volatile
        var instance: HomeKeyService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val me = ComponentName(context, HomeKeyService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
