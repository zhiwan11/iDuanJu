package app.iduanju.manga

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.IdjApp
import app.iduanju.core.manga.*
import app.iduanju.ui.components.GlassChip
import app.iduanju.ui.theme.IdjThemeColors
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
private fun Cover(url: String, headers: Map<String, String>, mod: Modifier, corner: Int = 10) {
    val ctx = LocalContext.current
    val req = remember(url) { ImageRequest.Builder(ctx).data(url).apply { headers.forEach { (k, v) -> addHeader(k, v) } }.crossfade(true).build() }
    AsyncImage(req, null, mod.clip(RoundedCornerShape(corner.dp)).background(IdjThemeColors.elevated), contentScale = ContentScale.Crop)
}

/** 「读至第 X 话」 label for a saved progress (falls back to the chapter title for progress saved before indices were kept). */
internal fun MangaStore.Progress.readLabel(): String = if (index >= 0) "读至第 ${index + 1} 话" else "读至 " + chapterTitle.take(10)

private object MangaUiPrefs {
    private val sp by lazy { IdjApp.instance.getSharedPreferences("idj", android.content.Context.MODE_PRIVATE) }
    var homeList by mutableStateOf(runCatching { sp.getBoolean("manga.home.list", false) }.getOrDefault(false))
    var chapterGrid by mutableStateOf(runCatching { sp.getBoolean("manga.detail.grid", true) }.getOrDefault(true))
    fun toggleHomeList(v: Boolean) { homeList = v; sp.edit().putBoolean("manga.home.list", v).apply() }
    fun toggleChapterGrid(v: Boolean) { chapterGrid = v; sp.edit().putBoolean("manga.detail.grid", v).apply() }
}

/** Screen-local source strip: labels never consume the next source's width or grow into the catalog.
 * Keep GlassChip elsewhere unchanged. Full labels remain available to accessibility despite ellipsis. */
@Composable
internal fun MangaSourceStrip(
    sources: List<Pair<String, String>>,
    selected: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = IdjThemeColors
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup().testTag("manga.source.strip"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        sources.forEach { (id, label) ->
            val active = id == selected
            Box(
                Modifier.widthIn(min = 48.dp, max = 240.dp).heightIn(min = 48.dp)
                    .clip(CircleShape)
                    .background(if (active) c.text else c.elevated)
                    .then(if (!active) Modifier.border(0.6.dp, c.text.copy(0.12f), CircleShape) else Modifier)
                    .selectable(active, role = Role.Tab, onClick = { onSelected(id) })
                    .testTag("manga.source." + id)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (active) c.bg else c.secondary, fontSize = 14.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ViewToggle(list: Boolean, onToggle: () -> Unit) {
    val c = IdjThemeColors
    Box(Modifier.size(40.dp).clip(CircleShape).background(c.elevated).clickable(onClick = onToggle).testTag("manga.view.toggle"), contentAlignment = Alignment.Center) {
        Icon(if (list) Icons.Filled.GridView else Icons.AutoMirrored.Filled.ViewList, if (list) "网格视图" else "列表视图", tint = c.text, modifier = Modifier.size(20.dp))
    }
}

/** Grid cell with an optional reading-progress ribbon on the cover. */
@Composable
private fun MangaGridCell(m: MangaItem, headers: Map<String, String>, badge: String?, sub: String, onClick: () -> Unit) {
    val c = IdjThemeColors
    Column(Modifier.testTag("manga.cell." + m.id).clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box {
            Cover(m.cover, headers, Modifier.fillMaxWidth().aspectRatio(3f / 4))
            if (badge != null) Text(badge, color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().clip(RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp))
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.75f)))).padding(horizontal = 6.dp, vertical = 4.dp))
        }
        Text(m.title, color = c.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(sub, color = c.secondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MangaListRow(m: MangaItem, headers: Map<String, String>, badge: String?, progress: Float?, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    val c = IdjThemeColors
    Row(Modifier.fillMaxWidth().testTag("manga.row." + m.id).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(m.cover, headers, Modifier.size(66.dp, 88.dp), 8)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(m.title, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (m.author.isNotEmpty()) Text(m.author, color = c.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val tags = m.tags.take(3).joinToString(" · ")
            if (tags.isNotEmpty()) Text(tags, color = c.secondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (badge != null) Text(badge, color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            else if (m.latest.isNotEmpty()) Text("更新至 " + m.latest, color = c.secondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (progress != null) LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth(0.8f).height(3.dp).clip(CircleShape), color = c.accent, trackColor = c.text.copy(0.08f))
        }
        trailing?.invoke()
    }
}

/** 「漫画」 home page: 发现 (sources, search, categories, grid / list) and 书架 (继续阅读 CTA, 收藏 / 历史 with progress badges). */
@Composable
fun MangaHomePage(onOpen: (String) -> Unit) {
    val c = IdjThemeColors
    var shelf by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.clip(CircleShape).background(c.elevated).padding(3.dp)) {
                listOf(false to "发现", true to "书架").forEach { (v, t) ->
                    val on = shelf == v
                    Text(t, color = if (on) c.bg else c.secondary, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.clip(CircleShape).background(if (on) c.text else Color.Transparent).clickable { shelf = v }
                            .testTag(if (v) "manga.tab.shelf" else "manga.tab.discover").padding(horizontal = 16.dp, vertical = 7.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            ViewToggle(MangaUiPrefs.homeList) { MangaUiPrefs.toggleHomeList(!MangaUiPrefs.homeList) }
        }
        if (shelf) MangaShelf(onOpen) else MangaDiscover(onOpen)
    }
}

@Composable
private fun MangaDiscover(onOpen: (String) -> Unit) {
    val c = IdjThemeColors
    val store = IdjApp.instance.manga
    val listMode = MangaUiPrefs.homeList
    var source by remember { mutableStateOf(store.providers[0].source) }
    val p = store.provider(source)
    var cat by remember(source) { mutableStateOf(p.categories[0]) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf<String?>(null) }
    val items = remember(source, cat, searching) { mutableStateListOf<MangaItem>() }
    var page by remember(source, cat, searching) { mutableIntStateOf(0) }
    var hasMore by remember(source, cat, searching) { mutableStateOf(true) }
    var loading by remember(source, cat, searching) { mutableStateOf(false) }
    var error by remember(source, cat, searching) { mutableStateOf<String?>(null) }
    suspend fun more() {
        if (loading || !hasMore) return
        loading = true
        try {
            val r = withContext(Dispatchers.IO) { searching?.let { p.search(it, page + 1) } ?: p.list(cat, page + 1) }
            val seen = items.map { it.id }.toHashSet(); items.addAll(r.items.filter { seen.add(it.id) }); page += 1; hasMore = r.hasMore; error = null
        } catch (e: Exception) { error = e.message ?: "加载失败"; hasMore = false } finally { loading = false }
    }
    LaunchedEffect(source, cat, searching) { more() }
    val grid = rememberLazyGridState()
    val nearEnd by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= grid.layoutInfo.totalItemsCount - 6 } }
    LaunchedEffect(nearEnd, items.size) { if (nearEnd && items.isNotEmpty()) more() }

    LazyVerticalGrid(GridCells.Fixed(app.iduanju.ui.components.LocalLayoutMetrics.current.posterCols), state = grid, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(if (listMode) 8.dp else 14.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
                MangaSourceStrip(store.providers.map { it.source to it.label }, source, { next ->
                    if (next != source) { source = next; searching = null; query = "" }
                })
                Row(Modifier.fillMaxWidth().height(40.dp).clip(CircleShape).background(c.elevated).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Search, null, tint = c.secondary, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text("搜索${p.label}", color = c.secondary, fontSize = 15.sp)
                        BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(color = c.text, fontSize = 15.sp), cursorBrush = SolidColor(c.accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { searching = query.trim().ifEmpty { null } }), modifier = Modifier.fillMaxWidth())
                    }
                    if (searching != null) Text("取消", color = c.accent, fontSize = 14.sp, modifier = Modifier.clickable { query = ""; searching = null })
                }
                if (searching == null) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(p.categories) { k -> GlassChip(k.title, k.id == cat.id) { cat = k } } }
                val recent = store.recent.take(10)
                if (recent.isNotEmpty() && searching == null) {
                    Text("继续阅读", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(recent, key = { it.item.id }) { r ->
                            Column(Modifier.width(84.dp).clickable { onOpen(store.put(r.item)) }) {
                                Box {
                                    Cover(r.item.cover, store.provider(r.item.source).imageHeaders, Modifier.size(84.dp, 112.dp), 8)
                                    if (r.pages > 0) LinearProgressIndicator(progress = { (r.page + 1f) / r.pages }, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                                        color = c.accent, trackColor = Color.Black.copy(0.4f))
                                }
                                Text(r.item.title, color = c.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(r.readLabel(), color = c.secondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        items(items, key = { it.id }, span = { if (listMode) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { m ->
            val prog = store.progress[m.id]
            if (listMode) MangaListRow(m, p.imageHeaders, prog?.readLabel(), null, { onOpen(store.put(m)) })
            else MangaGridCell(m, p.imageHeaders, prog?.readLabel(), m.tags.take(2).joinToString(" · ").ifEmpty { m.author }) { onOpen(store.put(m)) }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when { loading -> CircularProgressIndicator(color = c.secondary, strokeWidth = 2.dp, modifier = Modifier.size(22.dp)); error != null -> Text(error!!, color = c.accentWarm, fontSize = 12.sp) }
                Text("来源：${p.note}", color = c.secondary, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        }
    }
}

/** 书架：prominent 继续阅读 card, then 收藏 / 历史 in grid or list with 「读至第 X 话」 badges. */
@Composable
private fun MangaShelf(onOpen: (String) -> Unit) {
    val c = IdjThemeColors
    val store = IdjApp.instance.manga
    val listMode = MangaUiPrefs.homeList
    var history by rememberSaveable { mutableStateOf(false) }
    val recent = store.recent
    val shown: List<MangaItem> = if (history) recent.map { it.item } else store.favorites.toList()
    LazyVerticalGrid(GridCells.Fixed(app.iduanju.ui.components.LocalLayoutMetrics.current.posterCols), Modifier.fillMaxSize().testTag("manga.shelf"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(if (listMode) 8.dp else 14.dp)) {
        recent.firstOrNull()?.let { last ->
            item(span = { GridItemSpan(maxLineSpan) }, key = "cta") {
                val headers = store.provider(last.item.source).imageHeaders
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Brush.horizontalGradient(listOf(c.accent.copy(0.22f), c.elevated)))
                    .clickable { onOpen(store.put(last.item)) }.padding(12.dp).testTag("manga.shelf.continue"),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(last.item.cover, headers, Modifier.size(72.dp, 96.dp), 10)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("继续阅读", color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(last.item.title, color = c.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(last.readLabel() + " · " + last.chapterTitle, color = c.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (last.pages > 0) LinearProgressIndicator(progress = { (last.page + 1f) / last.pages }, Modifier.fillMaxWidth().height(4.dp).clip(CircleShape), color = c.accent, trackColor = c.text.copy(0.08f))
                    }
                    Box(Modifier.size(44.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.PlayArrow, "继续阅读", tint = Color.White)
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }, key = "tabs") {
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassChip("收藏 ${store.favorites.size}", !history) { history = false }
                GlassChip("历史 ${recent.size}", history) { history = true }
            }
        }
        if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
            Column(Modifier.fillMaxWidth().padding(vertical = 60.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.AutoMirrored.Filled.MenuBook, null, tint = c.secondary, modifier = Modifier.size(40.dp))
                Text(if (history) "还没有阅读记录" else "书架空空，去「发现」收藏几本吧", color = c.secondary, fontSize = 13.sp)
            }
        }
        items(shown, key = { (if (history) "h" else "f") + it.id }, span = { if (listMode) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { m ->
            val prog = store.progress[m.id]
            val headers = store.provider(m.source).imageHeaders
            val frac = prog?.takeIf { it.pages > 0 }?.let { (it.page + 1f) / it.pages }
            if (listMode) MangaListRow(m, headers, prog?.readLabel() ?: "未读", frac, { onOpen(store.put(m)) }) {
                if (history) IconButton({ store.removeProgress(m.id) }) { Icon(Icons.Filled.Close, "删除记录", tint = c.secondary, modifier = Modifier.size(18.dp)) }
            }
            else MangaGridCell(m, headers, prog?.readLabel() ?: "未读", prog?.chapterTitle ?: m.author) { onOpen(store.put(m)) }
        }
    }
}

/** 漫画详情：blurred hero, meta + 读至第 X 话, 收藏, expandable desc, 正序/倒序 + grid/list chapter selector, sticky 继续阅读 CTA. */
@Composable
fun MangaDetailScreen(id: String, onBack: () -> Unit, onRead: (String, Int, Int) -> Unit) {
    val c = IdjThemeColors
    val store = IdjApp.instance.manga
    val item = store.cache[id] ?: store.progress[id]?.item ?: store.favorites.firstOrNull { it.id == id } ?: run { LaunchedEffect(Unit) { onBack() }; return }
    val p = store.provider(item.source)
    var detail by remember { mutableStateOf<MangaDetail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var asc by rememberSaveable { mutableStateOf(true) }
    var descOpen by remember { mutableStateOf(false) }
    LaunchedEffect(id, reload) {
        error = null
        runCatching { withContext(Dispatchers.IO) { p.detail(item) } }.onSuccess { detail = it; store.cache[id] = it.item; MangaReaderState.detail = it }.onFailure { error = it.message ?: "加载失败" }
    }
    val shown = detail?.item ?: item
    val prog = store.progress[id]
    val d = detail
    val readIdx = d?.let { dd -> prog?.let { pr -> dd.chapters.indexOfFirst { it.chapterId == pr.chapterId } } } ?: -1
    fun open(i: Int) { d?.let { MangaReaderState.detail = it }; onRead(id, i, if (i == readIdx && prog != null) prog.page else 0) }
    val gridMode = MangaUiPrefs.chapterGrid
    val cols = 4

    Box(Modifier.fillMaxSize().background(c.bg)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
            item(key = "hero") {
                Box(Modifier.fillMaxWidth()) {
                    Cover(shown.cover, p.imageHeaders, Modifier.matchParentSize().then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(28.dp) else Modifier.alpha(0.35f)), 0)
                    Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(c.bg.copy(0.35f), c.bg.copy(0.85f), c.bg))))
                    Column(Modifier.statusBarsPadding()) {
                        IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = c.text) }
                        Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Cover(shown.cover, p.imageHeaders, Modifier.size(112.dp, 150.dp), 12)
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(shown.title, color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (shown.author.isNotEmpty()) Text(shown.author, color = c.secondary, fontSize = 14.sp)
                                Text((listOf(d?.status ?: "") + shown.tags).filter { it.isNotEmpty() }.take(5).joinToString(" · "), color = c.secondary, fontSize = 12.sp, maxLines = 2)
                                Text(p.label + (d?.let { " · 共 ${it.chapters.size} 话" } ?: ""), color = c.accent, fontSize = 11.sp)
                                if (prog != null) Text(prog.readLabel() + (if (prog.pages > 0) " · ${prog.page + 1}/${prog.pages}" else ""), color = c.text, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                    modifier = Modifier.clip(CircleShape).background(c.accent.copy(0.16f)).padding(horizontal = 10.dp, vertical = 4.dp).testTag("manga.detail.progress"))
                                val fav = store.isFavorite(shown)
                                OutlinedButton({ store.toggleFavorite(shown) }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                                    Icon(if (fav) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, null, tint = c.accent, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                                    Text(if (fav) "已加入书架" else "加入书架", color = c.accent, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
            d?.desc?.takeIf { it.isNotEmpty() }?.let { desc ->
                item(key = "desc") {
                    Text(desc, color = c.secondary, fontSize = 13.sp, maxLines = if (descOpen) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp).clickable { descOpen = !descOpen })
                }
            }
            error?.let { e ->
                item(key = "err") {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(e, color = c.accentWarm, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        TextButton({ reload++ }) { Text("重试", color = c.accent) }
                    }
                }
            }
            if (d == null && error == null) item(key = "loading") { Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.secondary) } }
            if (d != null) {
                item(key = "chHeader") {
                    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("章节（${d.chapters.size}）", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.weight(1f))
                        Text(if (asc) "正序" else "倒序", color = c.accent, fontSize = 14.sp, modifier = Modifier.clip(CircleShape).clickable { asc = !asc }.padding(horizontal = 10.dp, vertical = 6.dp).testTag("manga.detail.sort"))
                        IconButton({ MangaUiPrefs.toggleChapterGrid(!gridMode) }) {
                            Icon(if (gridMode) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView, if (gridMode) "列表" else "网格", tint = c.text, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                val order = if (asc) d.chapters.indices.toList() else d.chapters.indices.reversed().toList()
                if (gridMode) {
                    val rows = order.chunked(cols)
                    items(rows.size, key = { "r$it" + (if (asc) "a" else "d") }) { r ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rows[r].forEach { i ->
                                val ch = d.chapters[i]; val reading = i == readIdx
                                Box(Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(10.dp)).background(if (reading) c.accent.copy(0.16f) else c.elevated)
                                    .then(if (reading) Modifier.border(1.dp, c.accent, RoundedCornerShape(10.dp)) else Modifier)
                                    .clickable { open(i) }.padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                                    Text(ch.title, color = if (reading) c.accent else c.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            repeat(cols - rows[r].size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                } else {
                    items(order, key = { d.chapters[it].chapterId + "#" + it }) { i ->
                        val ch = d.chapters[i]; val reading = i == readIdx
                        Row(Modifier.fillMaxWidth().clickable { open(i) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(ch.title, color = if (reading) c.accent else c.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (reading) Text("上次读到", color = c.accent, fontSize = 11.sp)
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = c.text.copy(0.06f))
                    }
                }
            }
        }
        // Sticky 继续阅读 / 开始阅读 CTA.
        if (d != null && d.chapters.isNotEmpty()) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, c.bg.copy(0.94f), c.bg)))
                .navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 12.dp)) {
                Button({ if (readIdx >= 0) open(readIdx) else open(0) }, Modifier.fillMaxWidth().height(50.dp).testTag("manga.detail.read"), shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = Color.White)) {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(if (readIdx >= 0) "继续阅读 · 第 ${readIdx + 1} 话" else "开始阅读 · " + d.chapters[0].title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** The chapter list travels from detail to reader in memory. */
object MangaReaderState { var detail: MangaDetail? = null }
