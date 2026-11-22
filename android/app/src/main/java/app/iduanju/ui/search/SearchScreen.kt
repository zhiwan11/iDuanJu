package app.iduanju.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NorthWest
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.IdjApp
import app.iduanju.core.hongguo.HGRankGenre
import app.iduanju.core.hongguo.HGRankSub
import app.iduanju.core.hongguo.HGSeries
import app.iduanju.ui.components.LocalLayoutMetrics
import app.iduanju.ui.components.SizeTier
import app.iduanju.ui.components.SearchDockState
import app.iduanju.ui.components.glassSurface
import app.iduanju.ui.home.HomeFmt
import app.iduanju.ui.home.HomePosterCard
import app.iduanju.ui.theme.IdjThemeColors
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private object HotCache { var hot: List<HGSeries> = emptyList() }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    onOpenDetail: (String) -> Unit,
    initialQuery: String = "",
    dockState: SearchDockState? = null,
) {
    val c = IdjThemeColors
    val metrics = LocalLayoutMetrics.current
    val useBottomSearchDock = dockState != null && !metrics.useRail
    val lib = IdjApp.instance.library
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(initialQuery.ifBlank { dockState?.query.orEmpty() }) }
    var submitted by remember { mutableStateOf("") }
    var hot by remember { mutableStateOf(HotCache.hot) }
    var hotErr by remember { mutableStateOf<String?>(null) }
    var seed by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    val items = remember { mutableStateListOf<HGSeries>() }
    var cursor by remember { mutableStateOf<String?>(null) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var verify by remember { mutableStateOf<app.iduanju.core.util.VerificationRequiredException?>(null) }
    // iOS SourceGate.searchSources: [红果, 黄果] + visible sites (黄果 only behind the adult gate on Android)
    val ia = IdjApp.instance
    val sources: List<Pair<String, String>> = remember {
        listOf("hongguo" to "红果") +
            ia.cms.visibleSites.map { "cms:" + it.key to it.name } +
            ia.plugins.visible(false).filter { it.type == "video" || it.type == "shortdrama" }.map { "plugin:" + it.id to it.name }
    }
    var source by remember { mutableStateOf("hongguo") }
    var pageNo by remember { mutableIntStateOf(1) }

    LaunchedEffect(Unit) {
        if (hot.isEmpty()) try {
            hot = withContext(Dispatchers.IO) { IdjApp.instance.hongguo.rank(HGRankSub.hotSearch, HGRankGenre.all).items.take(23) }
            HotCache.hot = hot
        } catch (e: Exception) { hotErr = e.message }
    }
    fun loadMore(reset: Boolean) {
        if (loading) return
        val q = submitted; if (q.isEmpty()) return
        loading = true; err = null; verify = null
        scope.launch {
            try {
                if (reset) pageNo = 1
                val src = source
                val p = withContext(Dispatchers.IO) {
                    when {
                        src == "hongguo" -> ia.hongguo.search(q, cursor = if (reset) null else cursor)
                        src.startsWith("cms:") -> ia.cms.site(src.removePrefix("cms:"))?.let { app.iduanju.core.cms.MacCMS.search(it, q, pageNo) } ?: app.iduanju.core.hongguo.HGPage(emptyList(), false)
                        else -> try { ia.plugins.video(src.removePrefix("plugin:"))?.search(q, pageNo) ?: app.iduanju.core.hongguo.HGPage(emptyList(), false) }
                                catch (e: app.iduanju.core.plugin.PluginException) { throw Exception(e.friendly) }
                    }
                }
                pageNo += 1
                if (reset) items.clear()
                val seen = items.map { it.seriesId }.toSet()
                items.addAll(p.items.filter { it.seriesId !in seen })
                cursor = p.nextCursor; hasMore = p.hasMore && p.items.isNotEmpty()
            } catch (e: app.iduanju.core.util.VerificationRequiredException) { verify = e; err = "需要验证" }
            catch (e: Exception) { err = e.message ?: "搜索失败" }
            loading = false
        }
    }
    fun submit(q0: String = query) {
        val q = q0.trim(); if (q.isEmpty()) return
        query = q
        if (dockState != null) {
            dockState.query = q
            dockState.submitted = q
        }
        lib.addSearch(q); submitted = q; cursor = null; items.clear(); loadMore(true)
    }
    LaunchedEffect(initialQuery) { if (initialQuery.isNotBlank()) submit(initialQuery) }

    if (dockState != null) {
        LaunchedEffect(dockState.query) {
            if (query != dockState.query) query = dockState.query
        }
        LaunchedEffect(dockState.submitVersion) {
            if (dockState.submitVersion > 0 && dockState.submitted.isNotBlank()) {
                submit(dockState.submitted)
            }
        }
        LaunchedEffect(dockState.clearVersion) {
            if (dockState.clearVersion > 0) {
                query = ""
                submitted = ""
                cursor = null
                items.clear()
            }
        }
    }

    BackHandler(enabled = useBottomSearchDock && submitted.isNotEmpty()) {
        dockState?.clear()
    }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        if (!useBottomSearchDock) {
            // Standalone / Tablet Rail SearchField: elevated capsule 38 high + 取消/搜索
            Row(Modifier.padding(horizontal = 16.dp).padding(top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                // On the tablet rail the field matches the rail's 46 dp search button so both tops line up.
                Row(Modifier.weight(1f).height(if (metrics.useRail) 46.dp else 38.dp).clip(RoundedCornerShape(50)).background(c.elevated).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Search, null, tint = c.secondary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text("搜索短剧 / 演员", color = c.secondary, fontSize = 15.sp)
                        BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(color = c.text, fontSize = 15.sp),
                            cursorBrush = SolidColor(c.accent), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { submit() }), modifier = Modifier.fillMaxWidth())
                    }
                    if (query.isNotEmpty()) Icon(Icons.Filled.Cancel, "清除", tint = c.secondary, modifier = Modifier.size(16.dp).clickable { query = "" })
                }
                Spacer(Modifier.width(12.dp))
                if (submitted.isNotEmpty()) Text("取消", color = c.text, fontSize = 15.sp, modifier = Modifier.clickable { query = ""; submitted = ""; items.clear() })
                else Text("搜索", color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { submit() })
            }
        } else if (submitted.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 10.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "「$submitted」的搜索结果",
                    color = c.text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "返回热榜",
                    color = c.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(c.accentSoft)
                        .clickable { dockState?.clear() }
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
        }
        val grid = rememberLazyGridState()
        val bottomInsetDp = with(androidx.compose.ui.platform.LocalDensity.current) {
            WindowInsets.navigationBars.union(WindowInsets.ime).getBottom(this).toDp()
        }
        val gridBottomPadding = if (useBottomSearchDock) (bottomInsetDp + 96.dp) else 110.dp
        val focusManager = LocalFocusManager.current
        val pick: (String) -> Unit = { t ->
            focusManager.clearFocus()
            if (dockState != null) dockState.submit(t) else submit(t)
        }
        // While typing (query not blank, nothing submitted yet) the page focuses on suggestions + history;
        // 搜索发现 / 搜索实时排行 come back as soon as the query is cleared.
        val typing = submitted.isEmpty() && query.isNotBlank()
        val discoverGrid = rememberLazyGridState()
        if (submitted.isEmpty()) androidx.compose.animation.Crossfade(typing, label = "searchMode") { isTyping ->
          if (isTyping) {
            SearchSuggestions(
                query = query.trim(),
                history = lib.searchHistory,
                hot = hot,
                bottomPadding = gridBottomPadding,
                onPick = pick,
                onOpenDetail = onOpenDetail,
            )
          } else {
            LazyVerticalGrid(
                // Section blocks span the full width; the #N rank rows flow into 1 / 2 / 3 columns.
                GridCells.Fixed(metrics.listCols),
                Modifier.fillMaxSize(),
                state = discoverGrid,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = gridBottomPadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (lib.searchHistory.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("历史搜索", color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.weight(1f))
                            if (editing) {
                                Text("全部删除", color = c.accent, fontSize = 15.sp, modifier = Modifier.clickable { lib.clearSearch(); editing = false })
                                Spacer(Modifier.width(16.dp)); Text("完成", color = c.text, fontSize = 15.sp, modifier = Modifier.clickable { editing = false })
                            } else Icon(Icons.Filled.Delete, "编辑", tint = c.secondary, modifier = Modifier.size(20.dp).clickable { editing = true })
                        }
                        // iOS ChipFlow: one horizontally scrolling row
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            lib.searchHistory.forEach { t ->
                                Chip(t, null, editing) {
                                    if (editing) lib.removeSearch(t)
                                    else if (dockState != null) dockState.submit(t)
                                    else submit(t)
                                }
                            }
                        }
                    }
                }
                val discover = if (hot.size > 3) hot.drop(3).map { it.title }.shuffled(java.util.Random(seed + 1L)).take(8) else emptyList()
                if (discover.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("搜索发现", color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.weight(1f))
                            Icon(Icons.Filled.Refresh, "换一批", tint = c.secondary, modifier = Modifier.size(20.dp).clickable { seed++ })
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            discover.forEachIndexed { i, t ->
                                Chip(t, if (i == 0) "热" else if (i == 1) "荐" else null, false) {
                                    if (dockState != null) dockState.submit(t) else submit(t)
                                }
                            }
                        }
                    }
                }
                // Tablets show 4 / 5 hero rank cards so each card stays poster-sized instead of 400 dp wide.
                val topCount = when (metrics.tier) { SizeTier.Compact -> 3; SizeTier.Medium -> 4; SizeTier.Expanded -> 5 }
                val gap = if (metrics.tier == SizeTier.Compact) 8.dp else 12.dp
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("搜索实时排行", color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        when {
                            hot.isEmpty() && hotErr != null -> Text(hotErr!!, color = c.secondary, fontSize = 13.sp)
                            hot.isEmpty() -> Row(horizontalArrangement = Arrangement.spacedBy(gap)) { repeat(topCount) { Box(Modifier.weight(1f).aspectRatio(0.6f).clip(RoundedCornerShape(12.dp)).background(c.elevated.copy(0.6f))) } }
                            else -> Row(horizontalArrangement = Arrangement.spacedBy(gap)) { hot.take(topCount).forEachIndexed { i, s -> Box(Modifier.weight(1f)) { TopRankCard(s, i + 1, onOpenDetail) } } }
                        }
                    }
                }
                // #4…: full-width rows so cover / title / tags / heat / 集数 always stay on single clean lines
                val rest = hot.drop(topCount)
                items(rest.size) { i -> CompactRankRow(rest[i], i + topCount + 1, onOpenDetail) }
            }
          }
        } else {
            if (sources.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                sources.forEach { (id, name) ->
                    val on = id == source
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { if (!on) { source = id; cursor = null; items.clear(); loadMore(true) } }) {
                        Text(name, color = if (on) c.text else c.secondary, fontSize = if (on) 17.sp else 15.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                        Box(Modifier.padding(top = 3.dp).size(16.dp, 3.dp).clip(RoundedCornerShape(50)).background(if (on) c.accent else Color.Transparent))
                    }
                }
            }
            LaunchedEffect(grid, items.size) {
                snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
                    if (hasMore && !loading && last >= items.size - 4) loadMore(false)
                }
            }
            LazyVerticalGrid(
                GridCells.Fixed(metrics.posterCols),
                Modifier.fillMaxSize(),
                state = grid,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = gridBottomPadding),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(items, key = { it.seriesId + it.title }) { s -> HomePosterCard(s, onOpenDetail) }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        when {
                            loading -> CircularProgressIndicator(color = c.secondary, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                            verify != null -> app.iduanju.huangguo.VerifyRequiredState(verify!!) { loadMore(items.isEmpty()) }
                            err != null -> Text("$err · 点击重试", color = c.secondary, fontSize = 13.sp, modifier = Modifier.clickable { loadMore(items.isEmpty()) })
                            items.isEmpty() -> Text("没有找到「$submitted」", color = c.secondary, fontSize = 14.sp)
                            !hasMore -> Text("没有更多了", color = c.secondary, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(t: String, accent: String?, removable: Boolean, onClick: () -> Unit) {
    val c = IdjThemeColors
    Row(Modifier.glassSurface(RoundedCornerShape(50), shadowRadius = 0.dp).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(t, color = c.text, fontSize = 15.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
        if (accent != null) Text(" ·$accent", color = c.accentWarm, fontSize = 15.sp)
        if (removable) { Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.Cancel, null, tint = c.secondary, modifier = Modifier.size(13.dp)) }
    }
}

/**
 * Typing mode: "搜索「q」" + matching history + matching hot titles. Replaces 搜索发现 / 搜索实时排行 while the
 * user is typing so the screen stays focused; falls back to the history chips when nothing matches.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchSuggestions(
    query: String,
    history: List<String>,
    hot: List<HGSeries>,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onPick: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
) {
    val c = IdjThemeColors
    // history is a snapshot list (mutated in place) — derive directly so edits are always reflected
    val historyHits = history.filter { it.contains(query, ignoreCase = true) && !it.equals(query, ignoreCase = true) }.take(6)
    val hotHits = remember(query, hot) { hot.filter { it.title.contains(query, ignoreCase = true) }.take(8) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = bottomPadding),
    ) {
        item(key = "q") { SuggestionRow(Icons.Filled.Search, buildQueryLabel(query, c.accent), c.accent) { onPick(query) } }
        items(historyHits, key = { "h:$it" }) { t ->
            SuggestionRow(Icons.Filled.History, androidx.compose.ui.text.AnnotatedString(t), c.secondary) { onPick(t) }
        }
        items(hotHits, key = { "s:" + it.seriesId }) { s ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onOpenDetail(s.seriesId) }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(36.dp, 48.dp).clip(RoundedCornerShape(6.dp)).background(c.elevated)) {
                    AsyncImage(s.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.title, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    Text(HomeFmt.metaLine(s), color = c.secondary, fontSize = 12.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (historyHits.isEmpty() && hotHits.isEmpty() && history.isNotEmpty()) item(key = "hist") {
            Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("历史搜索", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    history.take(12).forEach { t -> Chip(t, null, false) { onPick(t) } }
                }
            }
        }
    }
}

private fun buildQueryLabel(q: String, accent: Color) = androidx.compose.ui.text.buildAnnotatedString {
    append("搜索「")
    pushStyle(androidx.compose.ui.text.SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)); append(q); pop()
    append("」")
}

@Composable
private fun SuggestionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: androidx.compose.ui.text.AnnotatedString, iconTint: Color, onClick: () -> Unit) {
    val c = IdjThemeColors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, color = c.text, fontSize = 15.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Icon(Icons.Filled.NorthWest, null, tint = c.secondary.copy(0.6f), modifier = Modifier.size(16.dp))
    }
}

private fun rankGradient(r: Int): List<Color> = when (r) {
    1 -> listOf(Color(0.95f, 0.25f, 0.3f), Color(0.75f, 0.1f, 0.2f))
    2 -> listOf(Color(0.25f, 0.35f, 0.95f), Color(0.15f, 0.2f, 0.7f))
    else -> listOf(Color(0.98f, 0.45f, 0.25f), Color(0.85f, 0.25f, 0.15f))
}
private fun rankColor(r: Int): Color = when (r) {
    1 -> Color(1f, 0.27f, 0.2f); 2 -> Color(1f, 0.55f, 0.15f); 3 -> Color(1f, 0.78f, 0.2f); else -> Color(0.8f, 0.15f, 0.25f)
}

@Composable
private fun TopRankCard(s: HGSeries, rank: Int, onOpen: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().aspectRatio(0.6f).clip(RoundedCornerShape(12.dp)).clickable { onOpen(s.seriesId) }, contentAlignment = Alignment.BottomCenter) {
        AsyncImage(s.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        val g = rankGradient(rank)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 0.6f to g[0], 1f to g[1])))
        Column(Modifier.padding(horizontal = 6.dp).padding(bottom = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("TOP$rank", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
            Text(s.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(HomeFmt.metaLine(s), color = Color.White.copy(0.8f), fontSize = 11.sp, maxLines = 1)
        }
    }
}

@Composable
private fun CompactRankRow(s: HGSeries, rank: Int, onOpen: (String) -> Unit) {
    val c = IdjThemeColors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.card)
            .border(0.6.dp, c.cardBorder, RoundedCornerShape(12.dp))
            .clickable { onOpen(s.seriesId) }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(52.dp, 70.dp).clip(RoundedCornerShape(8.dp)).background(c.elevated)) {
            AsyncImage(s.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Text(
                "$rank",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .clip(RoundedCornerShape(bottomEnd = 6.dp))
                    .background(rankColor(rank))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(s.title, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = s.tags.take(2).joinToString(" · ").ifBlank { s.subtitle.orEmpty() }
            if (meta.isNotBlank()) {
                Text(meta, color = c.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (s.playCount > 0) {
                    Text(
                        HomeFmt.count(s.playCount) + "热度",
                        color = Color(0.95f, 0.30f, 0.30f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Text(
                    if (s.episodeCount > 0) "全${s.episodeCount}集" else "热播短剧",
                    color = c.secondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}
