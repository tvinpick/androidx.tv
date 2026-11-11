package com.tvinpick.lighthome

import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.provider.Settings
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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

// ---------- Фон ----------

@Composable
fun Background(settings: ShellSettings) {
    val context = LocalContext.current
    val bitmap by produceState(
        initialValue = null as androidx.compose.ui.graphics.ImageBitmap?,
        settings.backgroundVersion,
    ) {
        value = withContext(Dispatchers.IO) { Images.background(context, settings.backgroundVersion) }
    }
    Box(Modifier.fillMaxSize()) {
        val bmp = bitmap
        if (bmp != null) {
            Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color(0xFF1B2433), Color(0xFF07090D)))),
            )
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = settings.dim / 100f)))
    }
}

// ---------- Домашний экран ----------

@Composable
fun HomeScreen(state: HomeState) {
    val context = LocalContext.current
    val settings = state.settings
    val byPkg = remember(state.apps) { state.apps.associateBy { it.packageName } }
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(state.focusTick) {
        delay(50)
        runCatching { firstFocus.requestFocus() }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Clock(state.resumed)
            Spacer(Modifier.weight(1f))
            Button(onClick = { state.menu = Menu.Inputs }) { Text("Входы") }
            Spacer(Modifier.width(12.dp))
            Button(onClick = {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }) { Text("Настройки ТВ") }
            Spacer(Modifier.width(12.dp))
            Button(onClick = { state.screen = Screen.Shell }) { Text("Настройки оболочки") }
        }
        Spacer(Modifier.height(16.dp))
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(settings.sections, key = { _, s -> s.id }) { index, section ->
                SectionRow(
                    state = state,
                    section = section,
                    apps = section.apps.mapNotNull { byPkg[it] },
                    firstModifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
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

@Composable
fun Clock(running: Boolean) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        while (running) {
            now = System.currentTimeMillis()
            delay(60_000 - now % 60_000)
        }
    }
    val text = remember(now) { android.text.format.DateFormat.getTimeFormat(context).format(now) }
    Text(text, fontSize = 34.sp, color = Color.White)
}

@Composable
fun SectionRow(state: HomeState, section: Section, apps: List<AppEntry>, firstModifier: Modifier) {
    val context = LocalContext.current
    val height = section.size.height()
    Column {
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
        LazyRow(
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(apps, key = { _, a -> a.packageName }) { index, app ->
                AppTile(
                    app = app,
                    height = height,
                    modifier = if (index == 0) firstModifier else Modifier,
                    onClick = { Apps.launch(context, app) },
                    onLongClick = { state.menu = Menu.Tile(section.id, app.packageName) },
                )
            }
            item(key = "+") {
                Surface(
                    onClick = { state.screen = Screen.AllApps(section.id) },
                    modifier = Modifier
                        .size(height * 16 / 9, height)
                        .then(if (apps.isEmpty()) firstModifier else Modifier),
                    shape = ClickableSurfaceDefaults.shape(TileShape),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.White.copy(alpha = 0.08f),
                        focusedContainerColor = Color.White.copy(alpha = 0.2f),
                        contentColor = Color.White,
                        focusedContentColor = Color.White,
                    ),
                    border = ClickableSurfaceDefaults.border(focusedBorder = FocusBorder),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
                ) {
                    Text("+", fontSize = 32.sp, modifier = Modifier.align(Alignment.Center))
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
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val image by produceState(Apps.cachedImage(app.packageName), app.packageName) {
        if (value == null) value = withContext(Dispatchers.IO) { Apps.loadImage(context, app) }
    }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.size(height * 16 / 9, height),
        shape = ClickableSurfaceDefaults.shape(TileShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF2A3140),
            focusedContainerColor = Color(0xFF3A4458),
            contentColor = Color.White,
            focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = FocusBorder),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
    ) {
        val img = image
        if (img != null && img.isBanner) {
            Image(img.bitmap, app.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
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
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
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
                    ) { Text(label) }
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
                add("Сдвинуть влево" to { state.shiftApp(m.sectionId, m.pkg, -1); close() })
                add("Сдвинуть вправо" to { state.shiftApp(m.sectionId, m.pkg, 1); close() })
                if (state.settings.sections.size > 1) {
                    add("Перенести в другой раздел" to { state.menu = Menu.MoveTo(m.sectionId, m.pkg) })
                }
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
    }
}

private fun passthroughInputs(context: android.content.Context): List<TvInputInfo> {
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
    val context = LocalContext.current
    val section = state.section(sectionId) ?: return
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { first.requestFocus() }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp)) {
        Text("Все приложения → «${section.name}»", fontSize = 26.sp, color = Color.White)
        Text(
            "OK — добавить или убрать. Назад — вернуться.",
            fontSize = 14.sp,
            color = Color.White.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(12.dp))
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(190.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(state.apps.size, key = { state.apps[it].packageName }) { i ->
                val app = state.apps[i]
                AppTile(
                    app = app,
                    height = 96.dp,
                    selected = app.packageName in section.apps,
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
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

fun openAccessibilitySettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = androidx.tv.material3.darkColorScheme(), content = content)
}
