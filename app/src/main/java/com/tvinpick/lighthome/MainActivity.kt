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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
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
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) Images.trimThumbs()
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) Apps.trimCache()
    }
}
