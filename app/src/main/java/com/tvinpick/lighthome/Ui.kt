package com.tvinpick.lighthome

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/** Оформление плиток из настроек. */
data class TileStyle(val corner: Dp, val spacing: Dp, val showLabels: Boolean) {
    val shape get() = RoundedCornerShape(corner)
    val focusBorder get() = Border(BorderStroke(3.dp, Color.White), shape = shape)
    val moveBorder get() = Border(BorderStroke(4.dp, Color(0xFFFFC107)), shape = shape)
}

val LocalTileStyle = compositionLocalOf { TileStyle(10.dp, 14.dp, false) }

val FocusBorder = Border(BorderStroke(3.dp, Color.White), shape = RoundedCornerShape(10.dp))
val TileShape = RoundedCornerShape(10.dp)

fun TileSize.height(): Dp = when (this) {
    TileSize.S -> 68.dp
    TileSize.M -> 96.dp
    TileSize.L -> 132.dp
}

fun TileSize.label(): String = when (this) {
    TileSize.S -> "Маленькие (S)"
    TileSize.M -> "Средние (M)"
    TileSize.L -> "Большие (L)"
}

fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

fun openSettings(context: Context, action: String) {
    val ok = runCatching {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
    if (!ok && action != Settings.ACTION_SETTINGS) openSettings(context, Settings.ACTION_SETTINGS)
}

// ---------- Фон ----------

@Composable
fun Background(settings: ShellSettings, playing: Boolean) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize()) {
        when (settings.background) {
            BgType.IMAGE -> {
                val bitmap by produceState(null as ImageBitmap?, settings.backgroundVersion) {
                    value = withContext(Dispatchers.IO) { Images.background(context, settings.backgroundVersion) }
                }
                bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    ?: Gradient()
            }
            BgType.VIDEO -> {
                val file = remember { SettingsStore.videoFile(context) }
                if (file.exists()) VideoBackground(file, settings.backgroundVersion, playing) else Gradient()
            }
            BgType.SLIDESHOW -> Slideshow(settings.slideshow, settings.slideshowMinutes, playing)
            BgType.NONE -> Gradient()
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = settings.dim / 100f)))
    }
}

@Composable
private fun Gradient() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1B2433), Color(0xFF07090D)))),
    )
}

@Composable
private fun Slideshow(uris: List<String>, minutes: Int, playing: Boolean) {
    val context = LocalContext.current
    var index by remember(uris) { mutableIntStateOf(0) }
    var shown by remember(uris) { mutableStateOf<Pair<Int, ImageBitmap>?>(null) }
    LaunchedEffect(uris, minutes, playing) {
        if (!playing || uris.isEmpty()) return@LaunchedEffect
        while (true) {
            val i = index % uris.size
            if (shown?.first != i) {
                val bmp = withContext(Dispatchers.IO) {
                    runCatching { Images.decodeForScreen(context, Uri.parse(uris[i])) }.getOrNull()
                }
                if (bmp != null) shown = i to bmp.asImageBitmap()
            }
            delay(minutes.coerceAtLeast(1) * 60_000L)
            index = (i + 1) % uris.size
        }
    }
    shown?.let { Image(it.second, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } ?: Gradient()
}

// ---------- Домашний экран ----------

@Composable
private fun SmallButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        scale = ButtonDefaults.scale(focusedScale = 1.05f),
    ) { Text(text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable
fun HomeScreen(state: HomeState) {
    val context = LocalContext.current
    val settings = state.settings
    val byPkg = remember(state.apps) { state.apps.associateBy { it.packageName } }
    val firstFocus = remember { FocusRequester() }
    val topFocus = remember { FocusRequester() }
    val style = TileStyle(
        corner = if (settings.rounded) 10.dp else 0.dp,
        spacing = settings.spacing.dp,
        showLabels = settings.showLabels,
    )

    LaunchedEffect(state.focusTick) {
        delay(50)
        if (runCatching { firstFocus.requestFocus() }.isFailure) runCatching { topFocus.requestFocus() }
    }

    CompositionLocalProvider(LocalTileStyle provides style) {
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Clock(state.resumed, settings.showDate)
                Spacer(Modifier.weight(1f))
                Button(onClick = { state.menu = Menu.Inputs }, modifier = Modifier.focusRequester(topFocus)) {
                    Text("Входы")
                }
                Spacer(Modifier.width(12.dp))
                Button(onClick = { openSettings(context, Settings.ACTION_SETTINGS) }) { Text("Настройки ТВ") }
                Spacer(Modifier.width(12.dp))
                Button(onClick = { state.screen = Screen.Shell }) { Text("Настройки оболочки") }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val last = settings.lastApp?.let { byPkg[it] }
                if (last != null) {
                    SmallButton("▶ Продолжить: ${last.label}", Modifier.width(260.dp)) { state.launch(last) }
                }
                SmallButton("Wi-Fi") { openSettings(context, Settings.ACTION_WIFI_SETTINGS) }
                SmallButton("Звук") { openSettings(context, Settings.ACTION_SOUND_SETTINGS) }
                SmallButton("Выключить экран") {
                    val service = HomeKeyService.instance
                    if (service == null || !service.sleep()) {
                        toast(context, "Нужна включённая служба «Лёгкий дом» в спец. возможностях")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val hideTitle = settings.hideSingleTitle && settings.sections.size == 1
                itemsIndexed(settings.sections, key = { _, s -> s.id }) { index, section ->
                    SectionRow(
                        state = state,
                        section = section,
                        apps = section.apps.mapNotNull { byPkg[it] },
                        showTitle = !hideTitle,
                        firstModifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                    )
                }
                if (!settings.locked) {
                    item(key = "add") {
                        Button(
                            onClick = { state.addSection() },
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .then(if (settings.sections.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier),
                        ) { Text("Добавить раздел") }
                    }
                }
            }
        }
    }
}

@Composable
fun Clock(running: Boolean, showDate: Boolean) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        while (running) {
            now = System.currentTimeMillis()
            delay(60_000 - now % 60_000)
        }
    }
    val time = remember(now) { android.text.format.DateFormat.getTimeFormat(context).format(now) }
    Row(verticalAlignment = Alignment.Bottom) {
        Text(time, fontSize = 34.sp, color = Color.White)
        if (showDate) {
            val date = remember(now) { SimpleDateFormat("EE, d MMMM", Locale.forLanguageTag("ru")).format(now) }
            Spacer(Modifier.width(14.dp))
            Text(date, fontSize = 18.sp, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(bottom = 5.dp))
        }
    }
}

@Composable
fun SectionRow(
    state: HomeState,
    section: Section,
    apps: List<AppEntry>,
    showTitle: Boolean,
    firstModifier: Modifier,
) {
    val height = section.size.height()
    val style = LocalTileStyle.current
    val locked = state.settings.locked
    Column {
        if (showTitle) {
            if (locked) {
                Text(
                    section.name, fontSize = 20.sp, color = Color.White,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            } else {
                Surface(
                    onClick = { state.menu = Menu.SectionMenu(section.id) },
                    onLongClick = { state.menu = Menu.SectionMenu(section.id) },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.Transparent,
                        focusedContainerColor = Color.White.copy(alpha = 0.15f),
                        contentColor = Color.White,
                        focusedContentColor = Color.White,
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                ) {
                    Text(section.name, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(style.spacing),
        ) {
            itemsIndexed(apps, key = { _, a -> a.packageName }) { index, app ->
                val moving = state.moving == section.id to app.packageName
                AppTile(
                    app = app,
                    height = height,
                    modifier = if (index == 0) firstModifier else Modifier,
                    index = index,
                    moving = moving,
                    onMove = { delta -> state.shiftApp(section.id, app.packageName, delta) },
                    onMoveDone = { state.moving = null },
                    onClick = { state.launch(app) },
                    onLongClick = if (locked) null else ({ state.menu = Menu.Tile(section.id, app.packageName) }),
                )
            }
            if (!locked || apps.isEmpty()) item(key = "+") {
                Surface(
                    onClick = { if (!locked) state.screen = Screen.AllApps(section.id) },
                    modifier = Modifier
                        .size(height * 16 / 9, height)
                        .then(if (apps.isEmpty()) firstModifier else Modifier),
                    shape = ClickableSurfaceDefaults.shape(style.shape),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.White.copy(alpha = 0.08f),
                        focusedContainerColor = Color.White.copy(alpha = 0.2f),
                        contentColor = Color.White,
                        focusedContentColor = Color.White,
                    ),
                    border = ClickableSurfaceDefaults.border(focusedBorder = style.focusBorder),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
                ) {
                    Text(if (locked) "—" else "+", fontSize = 32.sp, modifier = Modifier.align(Alignment.Center))
                }
            }
        }
    }
}

@Composable
fun AppTile(
    app: AppEntry,
    height: Dp,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    forceLabel: Boolean = false,
    index: Int = 0,
    moving: Boolean = false,
    onMove: (Int) -> Unit = {},
    onMoveDone: () -> Unit = {},
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val style = LocalTileStyle.current
    val image by produceState(Apps.cachedImage(app.packageName), app.packageName) {
        if (value == null) value = withContext(Dispatchers.IO) { Apps.loadImage(context, app) }
    }
    val bring = remember { BringIntoViewRequester() }
    LaunchedEffect(moving, index) { if (moving) runCatching { bring.bringIntoView() } }

    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .size(height * 16 / 9, height)
            .bringIntoViewRequester(bring)
            .onPreviewKeyEvent { e ->
                if (!moving) return@onPreviewKeyEvent false
                val down = e.type == KeyEventType.KeyDown
                when (e.key) {
                    Key.DirectionLeft -> { if (down) onMove(-1); true }
                    Key.DirectionRight -> { if (down) onMove(1); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Back -> {
                        if (!down) onMoveDone(); true
                    }
                    else -> true
                }
            },
        shape = ClickableSurfaceDefaults.shape(style.shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF2A3140),
            focusedContainerColor = Color(0xFF3A4458),
            contentColor = Color.White,
            focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = if (moving) style.moveBorder else style.focusBorder,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (moving) 1.15f else 1.1f),
    ) {
        val img = image
        if (img != null && img.isBanner) {
            Image(img.bitmap, app.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (style.showLabels || forceLabel) {
                Text(
                    app.label,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (img != null) {
                    Image(img.bitmap, null, Modifier.size(height * 0.45f))
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    app.label,
                    fontSize = if (height < 80.dp) 11.sp else 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (moving) {
            Text(
                "◀  ▶",
                fontSize = 18.sp,
                color = Color.Black,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xFFFFC107), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 2.dp),
            )
        }
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(26.dp)
                    .background(Color(0xFF2E7D32), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center,
            ) { Text("✓", color = Color.White, fontSize = 15.sp) }
        }
    }
}

// ---------- Меню ----------

@Composable
fun MenuDialog(title: String, items: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    // Меню часто открывается долгим нажатием OK: отпускание этой же кнопки
    // не должно нажимать первый пункт. Принимаем OK только после нажатия внутри меню.
    var armed by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .onPreviewKeyEvent { e ->
                    val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                    if (!ok) return@onPreviewKeyEvent false
                    if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) armed = true
                    !armed
                }
                .width(420.dp)
                .background(Color(0xFF1C2230), RoundedCornerShape(14.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, fontSize = 20.sp, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            if (items.isEmpty()) {
                Text("Пусто", color = Color.White.copy(alpha = 0.7f))
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(items) { index, (label, action) ->
                    Button(
                        onClick = action,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (index == 0) Modifier.focusRequester(first) else Modifier),
                    ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { first.requestFocus() }
    }
}

@Composable
fun Menus(state: HomeState) {
    val context = LocalContext.current
    val close = { state.menu = null }
    when (val m = state.menu) {
        null -> Unit
        is Menu.Tile -> {
            val label = state.apps.firstOrNull { it.packageName == m.pkg }?.label ?: m.pkg
            val items = buildList<Pair<String, () -> Unit>> {
                add("Переместить стрелками" to { close(); state.moving = m.sectionId to m.pkg })
                add("Сдвинуть влево" to { state.shiftApp(m.sectionId, m.pkg, -1); close() })
                add("Сдвинуть вправо" to { state.shiftApp(m.sectionId, m.pkg, 1); close() })
                if (state.settings.sections.size > 1) {
                    add("Перенести в другой раздел" to { state.menu = Menu.MoveTo(m.sectionId, m.pkg) })
                }
                add("Сведения о приложении" to { close(); Apps.openInfo(context, m.pkg) })
                add("Удалить из избранного" to { state.removeApp(m.sectionId, m.pkg); close() })
            }
            MenuDialog(label, items, close)
        }
        is Menu.MoveTo -> MenuDialog(
            "Перенести в раздел",
            state.settings.sections.filter { it.id != m.sectionId }.map { s ->
                s.name to { state.moveAppTo(m.sectionId, s.id, m.pkg); close() }
            },
            close,
        )
        is Menu.SectionMenu -> {
            val section = state.section(m.sectionId) ?: return close()
            MenuDialog(
                section.name,
                listOf(
                    "Переименовать" to { state.menu = Menu.Rename(m.sectionId) },
                    "Размер плиток: ${section.size.name}" to { state.menu = Menu.SectionSize(m.sectionId) },
                    "Вверх" to { state.moveSection(m.sectionId, -1); close() },
                    "Вниз" to { state.moveSection(m.sectionId, 1); close() },
                    "Удалить раздел" to { state.deleteSection(m.sectionId); close(); state.focusTick++ },
                ),
                close,
            )
        }
        is Menu.SectionSize -> MenuDialog(
            "Размер плиток",
            TileSize.entries.map { size -> size.label() to { state.setSize(m.sectionId, size); close() } },
            close,
        )
        is Menu.Rename -> {
            val section = state.section(m.sectionId) ?: return close()
            RenameDialog(section.name, onDone = { state.renameSection(m.sectionId, it); close() }, onDismiss = close)
        }
        Menu.Inputs -> {
            val inputs = remember { passthroughInputs(context) }
            MenuDialog(
                if (inputs.isEmpty()) "Входы не найдены" else "Входы",
                inputs.map { info ->
                    info.loadLabel(context).toString() to {
                        close()
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.id))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                        Unit
                    }
                },
                close,
            )
        }
        Menu.Folders -> FoldersMenu(state)
        is Menu.Import -> MenuDialog(
            if (m.files.isEmpty()) "Резервные копии не найдены" else "Импорт настроек",
            m.files.map { item ->
                item.name to {
                    close()
                    val imported = Backup.import(context, item)
                    if (imported == null) {
                        toast(context, "Не удалось прочитать файл")
                    } else {
                        val cur = state.settings
                        // Фон хранится локально — оставляем текущий.
                        state.replace(
                            imported.copy(
                                bgType = cur.bgType,
                                backgroundVersion = cur.backgroundVersion,
                                slideshow = cur.slideshow,
                            ),
                        )
                        toast(context, "Настройки загружены")
                    }
                }
            },
            close,
        )
    }
}

private fun passthroughInputs(context: Context): List<TvInputInfo> {
    val tim = context.getSystemService(TvInputManager::class.java) ?: return emptyList()
    return runCatching { tim.tvInputList }.getOrDefault(emptyList()).filter { it.isPassthroughInput }
}

@Composable
fun RenameDialog(initial: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(480.dp)
                .background(Color(0xFF1C2230), RoundedCornerShape(14.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Название раздела", fontSize = 20.sp, color = Color.White)
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 20.sp),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone(text) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                    .padding(12.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onDone(text) }) { Text("Сохранить") }
                Button(onClick = onDismiss) { Text("Отмена") }
            }
        }
    }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { focus.requestFocus() }
    }
}

// ---------- Все приложения ----------

@Composable
fun AllAppsScreen(state: HomeState, sectionId: Long) {
    val section = state.section(sectionId) ?: return
    var byDate by remember { mutableStateOf(false) }
    val apps = remember(state.apps, byDate) {
        if (byDate) state.apps.sortedByDescending { it.installed } else state.apps
    }
    val letters = remember(apps, byDate) {
        if (byDate) emptyList()
        else apps.mapIndexedNotNull { i, a ->
            val c = a.label.firstOrNull()?.uppercaseChar() ?: return@mapIndexedNotNull null
            c to i
        }.distinctBy { it.first }
    }
    val gridState = rememberLazyGridState()
    val itemFocus = remember { FocusRequester() }
    var focusIndex by remember { mutableIntStateOf(0) }
    var focusTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(focusTick) {
        if (focusTick > 0) gridState.scrollToItem(focusIndex)
        delay(50)
        runCatching { itemFocus.requestFocus() }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Все приложения → «${section.name}»", fontSize = 26.sp, color = Color.White)
                Text(
                    "OK — добавить или убрать. Назад — вернуться.",
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
            SmallButton(if (byDate) "Сортировка: новые сверху" else "Сортировка: по имени") {
                byDate = !byDate
                focusIndex = 0
            }
        }
        if (letters.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(letters, key = { it.first }) { (letter, index) ->
                    Button(
                        onClick = { focusIndex = index; focusTick++ },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        scale = ButtonDefaults.scale(focusedScale = 1.1f),
                    ) { Text(letter.toString(), fontSize = 14.sp) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(190.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(apps.size, key = { apps[it].packageName }) { i ->
                val app = apps[i]
                AppTile(
                    app = app,
                    height = 96.dp,
                    selected = app.packageName in section.apps,
                    forceLabel = true,
                    modifier = if (i == focusIndex) Modifier.focusRequester(itemFocus) else Modifier,
                    onClick = { state.toggleApp(sectionId, app.packageName) },
                )
            }
        }
    }
}

// ---------- Первый запуск ----------

@Composable
fun OnboardingScreen(state: HomeState) {
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { first.requestFocus() }
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Добро пожаловать в «Лёгкий дом»", fontSize = 32.sp, color = Color.White)
        Spacer(Modifier.height(16.dp))
        Text(
            "Чтобы кнопка Home на пульте открывала эту оболочку, включите службу специальных возможностей:",
            fontSize = 18.sp,
            color = Color.White,
        )
        Spacer(Modifier.height(12.dp))
        for (step in listOf(
            "1. Откройте Настройки → Система → Спец. возможности.",
            "2. Выберите «Лёгкий дом».",
            "3. Включите службу и подтвердите.",
            "4. Нажмите Назад, чтобы вернуться сюда.",
        )) {
            Text(step, fontSize = 18.sp, color = Color.White, modifier = Modifier.padding(vertical = 2.dp))
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(
                onClick = { openAccessibilitySettings(context) },
                modifier = Modifier.focusRequester(first),
            ) { Text("Открыть спец. возможности") }
            Button(onClick = { state.screen = Screen.Home; state.focusTick++ }) { Text("Позже") }
        }
    }
}

fun openAccessibilitySettings(context: Context) = openSettings(context, Settings.ACTION_ACCESSIBILITY_SETTINGS)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = androidx.tv.material3.darkColorScheme(), content = content)
}
