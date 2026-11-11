package com.tvinpick.lighthome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val slideMinutes = listOf(1, 5, 15, 30, 60)
private val spacings = listOf(8 to "Узкие", 14 to "Обычные", 22 to "Широкие")

@Composable
fun ShellScreen(state: HomeState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = state.settings
    val first = remember { FocusRequester() }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(50)
        runCatching { first.requestFocus() }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp),
        contentPadding = PaddingValues(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Настройки оболочки", fontSize = 28.sp, color = Color.White) }

        // --- Фон ---
        item { Header("Фон") }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { state.screen = Screen.Background },
                    modifier = Modifier.focusRequester(first),
                ) { Text("Фон: последние файлы (фото и видео)") }
                Spacer(Modifier.width(12.dp))
                Button(onClick = {
                    Images.deleteBackground(context)
                    state.update { it.copy(bgType = BgType.NONE, backgroundVersion = 0, slideshow = emptyList()) }
                }) { Text("Сбросить фон") }
            }
        }
        item {
            Text(
                "Сейчас: " + when (settings.background) {
                    BgType.NONE -> "тёмный градиент"
                    BgType.IMAGE -> "картинка"
                    BgType.VIDEO -> "видео"
                    BgType.SLIDESHOW -> "слайд-шоу (${settings.slideshow.size} шт.)"
                },
                fontSize = 16.sp,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
        item {
            StepperRow(
                "Затемнение: ${settings.dim}%",
                onMinus = { state.update { it.copy(dim = (it.dim - 10).coerceIn(0, 70)) } },
                onPlus = { state.update { it.copy(dim = (it.dim + 10).coerceIn(0, 70)) } },
            )
        }
        item {
            StepperRow(
                "Смена слайдов: ${settings.slideshowMinutes} мин",
                onMinus = { state.update { it.copy(slideshowMinutes = stepList(slideMinutes, it.slideshowMinutes, -1)) } },
                onPlus = { state.update { it.copy(slideshowMinutes = stepList(slideMinutes, it.slideshowMinutes, 1)) } },
            )
        }

        // --- Внешний вид ---
        item { Header("Внешний вид") }
        item {
            StepperRow(
                "Масштаб интерфейса: ${settings.scale}%",
                onMinus = { state.update { it.copy(scale = (it.scale - 10).coerceIn(80, 140)) } },
                onPlus = { state.update { it.copy(scale = (it.scale + 10).coerceIn(80, 140)) } },
            )
        }
        item {
            val idx = spacings.indexOfFirst { it.first == settings.spacing }.coerceAtLeast(0)
            StepperRow(
                "Отступы между плитками: ${spacings[idx].second}",
                onMinus = { state.update { it.copy(spacing = spacings[(idx - 1).coerceAtLeast(0)].first) } },
                onPlus = { state.update { it.copy(spacing = spacings[(idx + 1).coerceAtMost(spacings.lastIndex)].first) } },
            )
        }
        item {
            SwitchRow("Скруглённые углы плиток", settings.rounded) { v -> state.update { it.copy(rounded = v) } }
        }
        item {
            SwitchRow("Подписи под баннерами", settings.showLabels) { v -> state.update { it.copy(showLabels = v) } }
        }
        item {
            SwitchRow("Показывать дату", settings.showDate) { v -> state.update { it.copy(showDate = v) } }
        }
        item {
            SwitchRow("Скрывать название, если раздел один", settings.hideSingleTitle) { v ->
                state.update { it.copy(hideSingleTitle = v) }
            }
        }
        item {
            SwitchRow("Заблокировать редактирование разделов", settings.locked) { v ->
                state.update { it.copy(locked = v) }
            }
        }

        // --- Кнопка Home ---
        item { Header("Кнопка Home") }
        item {
            SwitchRow("Перехватывать кнопку Home", settings.interceptHome) { v ->
                state.update { it.copy(interceptHome = v) }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (state.serviceEnabled) "Служба Home: включена" else "Служба Home: выключена",
                    fontSize = 18.sp,
                    color = if (state.serviceEnabled) Color(0xFF81C784) else Color(0xFFE57373),
                )
                Spacer(Modifier.width(16.dp))
                Button(onClick = { openAccessibilitySettings(context) }) { Text("Открыть спец. возможности") }
            }
        }

        // --- Резервная копия ---
        item { Header("Резервная копия") }
        item {
            Text(
                "Сохраняется картинкой в Pictures/LightHome — в память ТВ и на флешку. " +
                    "Нужен доступ к фото и видео (выдаётся на экране «Фон»).",
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    if (busy) return@Button
                    busy = true
                    scope.launch {
                        val n = withContext(Dispatchers.IO) { Backup.export(context, state.settings) }
                        toast(context, if (n > 0) "Сохранено копий: $n" else "Не удалось сохранить")
                        busy = false
                    }
                }) { Text("Экспорт настроек") }
                Spacer(Modifier.width(12.dp))
                Button(onClick = {
                    if (busy) return@Button
                    if (!hasMediaPermission(context)) {
                        toast(context, "Сначала разрешите доступ на экране «Фон»")
                        return@Button
                    }
                    busy = true
                    scope.launch {
                        val files = withContext(Dispatchers.IO) {
                            Images.listMedia(context).filter { !it.isVideo && it.name.startsWith(Backup.PREFIX) }
                        }
                        state.menu = Menu.Import(files)
                        busy = false
                    }
                }) { Text("Импорт настроек") }
                if (busy) {
                    Spacer(Modifier.width(16.dp))
                    Text("Подождите…", color = Color.White)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private fun stepList(list: List<Int>, current: Int, delta: Int): Int {
    val i = list.indexOf(current).takeIf { it >= 0 } ?: list.indexOfFirst { it >= current }.coerceAtLeast(0)
    return list[(i + delta).coerceIn(0, list.lastIndex)]
}

@Composable
private fun Header(text: String) {
    Text(text, fontSize = 22.sp, color = Color.White, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 18.sp, color = Color.White, modifier = Modifier.width(420.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun StepperRow(
    title: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    firstModifier: Modifier = Modifier,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 18.sp, color = Color.White, modifier = Modifier.width(420.dp))
        Button(onClick = onMinus, modifier = firstModifier) { Text("−") }
        Spacer(Modifier.width(12.dp))
        Button(onClick = onPlus) { Text("+") }
    }
}
