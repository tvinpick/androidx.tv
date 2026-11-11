package com.tvinpick.lighthome

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

sealed interface Screen {
    data object Home : Screen
    data object Onboarding : Screen
    data object Shell : Screen
    data class AllApps(val sectionId: Long) : Screen
}

sealed interface Menu {
    data class Tile(val sectionId: Long, val pkg: String) : Menu
    data class MoveTo(val sectionId: Long, val pkg: String) : Menu
    data class SectionMenu(val sectionId: Long) : Menu
    data class SectionSize(val sectionId: Long) : Menu
    data class Rename(val sectionId: Long) : Menu
    data object Inputs : Menu
}

class HomeState(private val context: Context) {
    var settings by mutableStateOf(SettingsStore.get(context))
        private set
    var apps by mutableStateOf<List<AppEntry>>(emptyList())
    var serviceEnabled by mutableStateOf(false)
    var resumed by mutableStateOf(false)
    var screen by mutableStateOf<Screen>(Screen.Home)
    var menu by mutableStateOf<Menu?>(null)

    /** Увеличивается, когда нужно вернуть фокус на первую плитку. */
    var focusTick by mutableIntStateOf(0)

    fun update(block: (ShellSettings) -> ShellSettings) {
        settings = block(settings)
        SettingsStore.save(context, settings)
    }

    private fun updateSection(id: Long, block: (Section) -> Section) = update { s ->
        s.copy(sections = s.sections.map { if (it.id == id) block(it) else it })
    }

    fun section(id: Long) = settings.sections.firstOrNull { it.id == id }

    fun shiftApp(sectionId: Long, pkg: String, delta: Int) = updateSection(sectionId) { sec ->
        val list = sec.apps.toMutableList()
        val i = list.indexOf(pkg)
        val j = i + delta
        if (i < 0 || j !in list.indices) return@updateSection sec
        list.removeAt(i)
        list.add(j, pkg)
        sec.copy(apps = list)
    }

    fun moveAppTo(fromId: Long, toId: Long, pkg: String) = update { s ->
        s.copy(sections = s.sections.map {
            when (it.id) {
                fromId -> it.copy(apps = it.apps - pkg)
                toId -> if (pkg in it.apps) it else it.copy(apps = it.apps + pkg)
                else -> it
            }
        })
    }

    fun removeApp(sectionId: Long, pkg: String) =
        updateSection(sectionId) { it.copy(apps = it.apps - pkg) }

    fun toggleApp(sectionId: Long, pkg: String) = updateSection(sectionId) {
        if (pkg in it.apps) it.copy(apps = it.apps - pkg) else it.copy(apps = it.apps + pkg)
    }

    fun renameSection(id: Long, name: String) =
        updateSection(id) { it.copy(name = name.trim().ifEmpty { it.name }) }

    fun setSize(id: Long, size: TileSize) = updateSection(id) { it.copy(size = size) }

    fun moveSection(id: Long, delta: Int) = update { s ->
        val list = s.sections.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in list.indices) return@update s
        list.add(j, list.removeAt(i))
        s.copy(sections = list)
    }

    fun deleteSection(id: Long) = update { s -> s.copy(sections = s.sections.filter { it.id != id }) }

    fun addSection() = update { s ->
        val id = (s.sections.maxOfOrNull { it.id } ?: 0) + 1
        s.copy(sections = s.sections + Section(id = id, name = "Новый раздел"))
    }
}
