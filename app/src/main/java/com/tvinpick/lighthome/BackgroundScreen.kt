package com.tvinpick.lighthome

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

val mediaPermissions: Array<String> =
    if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

fun hasMediaPermission(context: Context) =
    mediaPermissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

/** Экран «Фон»: последние картинки и видео из памяти ТВ и USB, новые сверху. */
@Composable
fun BackgroundScreen(state: HomeState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    var granted by remember { mutableStateOf(hasMediaPermission(context)) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasMediaPermission(context)
    }
    var reload by remember { mutableIntStateOf(0) }
    var scanning by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(granted, reload) {
        if (!granted) return@LaunchedEffect
        scanning = true
        state.media = withContext(Dispatchers.IO) { Images.listMedia(context) }
        scanning = false
    }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { first.requestFocus() }
    }

    fun apply(item: MediaItem) {
        if (status != null) return
        status = if (item.isVideo) "Копирую видео…" else "Обработка картинки…"
        Diag.log("выбран файл ${item.name} (${if (item.isVideo) "видео" else "картинка"}, папка ${item.folder})")
        scope.launch {
            val ok = withContext(Images.decodeDispatcher) {
                runCatching {
                    if (item.isVideo) Images.saveVideo(context, item.uri) else Images.saveBackground(context, item.uri)
                }.getOrDefault(false)
            }
            if (ok) {
                state.update {
                    it.copy(
                        bgType = if (item.isVideo) BgType.VIDEO else BgType.IMAGE,
                        backgroundVersion = System.currentTimeMillis(),
                    )
                }
                toast(context, "Фон установлен")
            } else {
                toast(context, "Не удалось открыть файл")
            }
            status = null
        }
    }

    val media = state.media
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp)) {
        Text("Фон: последние файлы", fontSize = 28.sp, color = Color.White)
        Text(
            "Новые файлы сверху — например, присланные через Send Files to TV. OK — поставить на фон.",
            fontSize = 14.sp,
            color = Color.White.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!granted) {
                Button(onClick = { request.launch(mediaPermissions) }, modifier = Modifier.focusRequester(first)) {
                    Text("Разрешить доступ к фото и видео")
                }
            } else {
                Button(onClick = { reload++ }, modifier = Modifier.focusRequester(first)) { Text("Обновить список") }
                Button(onClick = { state.menu = Menu.Folders }) { Text("Слайд-шоу из папки") }
            }
            val s = status ?: if (scanning) "Поиск файлов…" else null
            if (s != null) Text(s, color = Color.White)
        }
        Spacer(Modifier.height(12.dp))
        if (granted && !scanning && media.isEmpty()) {
            Text("Картинки и видео в памяти ТВ и на USB не найдены", color = Color.White.copy(alpha = 0.7f))
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(220.dp),
            contentPadding = PaddingValues(vertical = 12.dp, horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(media.size, key = { media[it].uri.toString() }) { i ->
                MediaThumb(media[i]) { apply(media[i]) }
            }
        }
    }
}

@Composable
private fun MediaThumb(item: MediaItem, onClick: () -> Unit) {
    val context = LocalContext.current
    val bitmap by produceState(Images.cachedThumb(item.uri), item.uri) {
        if (value == null) value = withContext(Images.decodeDispatcher) { Images.thumb(context, item) }
    }
    val date = remember(item.date) { SimpleDateFormat("d MMM, HH:mm", Locale.forLanguageTag("ru")).format(item.date) }
    Column {
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
            bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                ?: Box(Modifier.fillMaxSize())
            if (item.isVideo) {
                Text(
                    "▶ Видео",
                    fontSize = 12.sp,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "${item.name} · $date",
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.8f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(220.dp),
        )
    }
}

/** Меню выбора папки для слайд-шоу. */
@Composable
fun FoldersMenu(state: HomeState) {
    val context = LocalContext.current
    val folders = remember(state.media) {
        state.media.filter { !it.isVideo && !it.name.startsWith(Backup.PREFIX) }
            .groupBy { it.folder }
            .entries.sortedByDescending { e -> e.value.maxOf { it.date } }
    }
    MenuDialog(
        if (folders.isEmpty()) "Папки с картинками не найдены" else "Слайд-шоу из папки",
        folders.map { (folder, items) ->
            "${folder.ifEmpty { "Без папки" }} (${items.size})" to {
                state.menu = null
                state.update {
                    it.copy(bgType = BgType.SLIDESHOW, slideshow = items.take(300).map { m -> m.uri.toString() })
                }
                toast(context, "Слайд-шоу: смена каждые ${state.settings.slideshowMinutes} мин")
            }
        },
        onDismiss = { state.menu = null },
    )
}
