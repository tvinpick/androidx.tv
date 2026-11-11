package com.tvinpick.lighthome

import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var state: HomeState
    private var memoryJob: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        Diag.init(applicationContext, "ui")
        state = HomeState(applicationContext)
        state.serviceEnabled = HomeKeyService.isEnabled(this)
        if (!state.serviceEnabled && state.settings.interceptHome) state.screen = Screen.Onboarding

        setContent {
            val base = LocalDensity.current
            val scale = state.settings.scale / 100f
            CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale)) {
                AppTheme {
                    Box(Modifier.fillMaxSize()) {
                        Background(state.settings, state.resumed)
                        BackHandler {
                            if (state.moving != null) {
                                state.moving = null
                                return@BackHandler
                            }
                            if (state.screen != Screen.Home) {
                                state.screen = Screen.Home
                                state.focusTick++
                            }
                        }
                        androidx.compose.runtime.LaunchedEffect(state.screen) { Diag.log("экран: ${state.screen}") }
                        when (val s = state.screen) {
                            Screen.Home -> HomeScreen(state)
                            Screen.Onboarding -> OnboardingScreen(state)
                            Screen.Shell -> ShellScreen(state)
                            Screen.Background -> BackgroundScreen(state)
                            is Screen.AllApps -> AllAppsScreen(state, s.sectionId)
                        }
                        Menus(state)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        // Повторное нажатие Home — вернуться на главный экран.
        state.menu = null
        state.moving = null
        if (state.screen != Screen.Onboarding) state.screen = Screen.Home
        state.focusTick++
    }

    override fun onResume() {
        super.onResume()
        state.resumed = true
        Diag.log("оболочка на экране; ${Diag.memory(this)}")
        memoryJob?.cancel()
        memoryJob = lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(60_000)
                Diag.log(Diag.memory(this@MainActivity))
            }
        }
        state.serviceEnabled = HomeKeyService.isEnabled(this)
        if (state.serviceEnabled && state.screen == Screen.Onboarding) {
            state.screen = Screen.Home
            state.focusTick++
        }
        lifecycleScope.launch {
            state.apps = withContext(Dispatchers.IO) { Apps.load(applicationContext) }
        }
    }

    override fun onPause() {
        super.onPause()
        state.resumed = false
        memoryJob?.cancel()
        Diag.log("оболочка скрыта")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Diag.log("система просит освободить память, уровень $level; ${Diag.memory(this)}")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) Images.trimThumbs()
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) Apps.trimCache()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Diag.log("СИСТЕМЕ НЕ ХВАТАЕТ ПАМЯТИ (onLowMemory); ${Diag.memory(this)}")
    }

    override fun onDestroy() {
        Diag.log("оболочка закрыта (onDestroy)")
        super.onDestroy()
    }
}
