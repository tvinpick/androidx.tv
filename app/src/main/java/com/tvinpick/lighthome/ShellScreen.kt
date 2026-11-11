package com.tvinpick.lighthome

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val imagePermission =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
    else Manifest.permission.READ_EXTERNAL_STORAGE

@Composable
fun ShellScreen(state: HomeState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = state.settings
    val first = remember { FocusRequester() }

    var granted by remember {
        mutableStateOf(context.checkSelfPermission(imagePermission) == PackageManager.PERMISSION_GRANTED)
    }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    var reload by remember { mutableIntStateOf(0) }
    var scanning by remember { mutableStateOf(false) }
    val images by produceState(emptyList<Uri>(), granted, reload) {
        if (granted) {
            scanning = true
            value = withContext(Dispatchers.IO) { Images.listImages(context) }
            scanning = false
        }
    }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(50)
        runCatching { first.requestFocus() }
    }

    val full: androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(200.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp),
        contentPadding = PaddingValues(vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = full) { Text("Настройки оболочки", fontSize = 28.sp, color = Color.White) }

        item(span = full) {
            StepperRow(
                "Масштаб интерфейса: ${settings.scale}%",
                onMinus = { state.update { it.copy(scale = (it.scale - 10).coerceIn(80, 140)) } },
                onPlus = { state.update { it.copy(scale = (it.scale + 10).coerceIn(80, 140)) } },
                firstModifier = Modifier.focusRequester(first),
            )
        }

        item(span = full) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Перехватывать кнопку Home", fontSize = 18.sp, color = Color.White)
                Spacer(Modifier.width(16.dp))
                Switch(
                    checked = settings.interceptHome,
                    onCheckedChange = { v -> state.update { it.copy(interceptHome = v) } },
                )
            }
        }

        item(span = full) {
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

        item(span = full) {
            Column {
                Spacer(Modifier.height(8.dp))
                Text("Фон", fontSize = 22.sp, color = Color.White)
            }
        }

        item(span = full) {
            StepperRow(
                "Затемнение: ${settings.dim}%",
                onMinus = { state.update { it.copy(dim = (it.dim - 10).coerceIn(0, 70)) } },
                onPlus = { state.update { it.copy(dim = (it.dim + 10).coerceIn(0, 70)) } },
            )
        }

        item(span = full) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    Images.deleteBackground(context)
                    state.update { it.copy(backgroundVersion = 0) }
                }) { Text("Сбросить фон") }
                if (granted) {
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = { reload++ }) { Text("Обновить список") }
                }
                if (busy) {
                    Spacer(Modifier.width(16.dp))
                    Text("Загрузка…", color = Color.White)
                }
            }
        }

        if (!granted) {
            item(span = full) {
                Button(onClick = { requestPermission.launch(imagePermission) }) {
                    Text("Разрешить доступ к картинкам")
                }
            }
        } else if (images.isEmpty()) {
            item(span = full) {
                Text(
                    if (scanning) "Поиск картинок…" else "Картинки в памяти ТВ и на USB не найдены",
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
        }

        items(images.size, key = { images[it].toString() }) { i ->
            val uri = images[i]
            ImageThumb(uri) {
                if (busy) return@ImageThumb
                busy = true
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { runCatching { Images.saveBackground(context, uri) }.getOrDefault(false) }
                    if (ok) state.update { it.copy(backgroundVersion = System.currentTimeMillis()) }
                    busy = false
                }
            }
        }
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
        Text(title, fontSize = 18.sp, color = Color.White, modifier = Modifier.width(300.dp))
        Button(onClick = onMinus, modifier = firstModifier) { Text("−") }
        Spacer(Modifier.width(12.dp))
        Button(onClick = onPlus) { Text("+") }
    }
}

@Composable
private fun ImageThumb(uri: Uri, onClick: () -> Unit) {
    val context = LocalContext.current
    val bitmap by produceState(Images.cachedThumb(uri), uri) {
        if (value == null) value = withContext(Dispatchers.IO) { Images.thumb(context, uri) }
    }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        shape = ClickableSurfaceDefaults.shape(TileShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF2A3140),
            focusedContainerColor = Color(0xFF3A4458),
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = FocusBorder),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.fillMaxSize())
        }
    }
}
