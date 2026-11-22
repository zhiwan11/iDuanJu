package app.iduanju.tvbox

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.IdjApp
import app.iduanju.core.cms.MacCMS
import app.iduanju.core.hongguo.HGSeries
import app.iduanju.ui.theme.IdjThemeColors
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 02 · TVBox 点播展示页 + 03 · TVBox 直播展示页
 * 位于独立「TVBox」底部 Tab，不污染首页短剧频道。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TVBoxHubScreen(
    onOpenConfig: () -> Unit,
    onOpenVodPlayer: (siteKey: String, seriesId: String, title: String, cover: String, startEp: Int) -> Unit,
    onOpenLivePlayer: (repoId: String, liveId: String, channelIndex: Int) -> Unit,
) {
    val c = IdjThemeColors
    val store = IdjApp.instance.tvbox
    val activeRepo = store.activeRepo

    var mode by remember { mutableIntStateOf(0) } // 0 = 点播, 1 = 直播
    var selectedSiteKey by remember { mutableStateOf<String?>(null) }
    var selectedLiveId by remember { mutableStateOf<String?>(null) }
    var showPickerSheet by remember { mutableStateOf(false) }

    val sites = remember(activeRepo) {
        activeRepo?.sites?.filter { it.enabled && !it.adult }.orEmpty()
    }
    val lives = remember(activeRepo) {
        activeRepo?.lives?.filter { it.enabled }.orEmpty()
    }

    val currentSite = sites.firstOrNull { it.key == selectedSiteKey } ?: sites.firstOrNull()
    val currentLive = lives.firstOrNull { it.id == selectedLiveId } ?: lives.firstOrNull()

    LaunchedEffect(activeRepo?.id, sites.size, lives.size) {
        if (currentSite == null && lives.isNotEmpty()) mode = 1
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .background(
                Brush.verticalGradient(
                    listOf(
                        (if (mode == 0) c.accent else c.accentWarm).copy(alpha = 0.11f),
                        Color.Transparent,
                    ),
                    endY = 500f,
                ),
            ),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // 顶部：点播 / 直播 分段 + 右侧站点/直播源切换胶囊 + 配置齿轮
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HubSegmentTab("点播", selected = mode == 0) { mode = 0 }
                Spacer(Modifier.width(18.dp))
                HubSegmentTab("直播", selected = mode == 1) { mode = 1 }
                Spacer(Modifier.weight(1f))

                val pillLabel = if (mode == 0) currentSite?.name ?: "选择站点" else currentLive?.name ?: "选择直播源"
                val dotColor = if (mode == 0) Color(0xFF5FD08A) else Color(0xFFFF6B6B)
                if (activeRepo != null) {
                    Row(
                        Modifier
                            .height(32.dp)
                            .widthIn(max = 156.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(c.text.copy(0.07f))
                            .border(0.7.dp, c.text.copy(0.11f), RoundedCornerShape(16.dp))
                            .clickable { showPickerSheet = true }
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(dotColor))
                        Text(pillLabel, color = c.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Icon(Icons.Filled.KeyboardArrowDown, null, tint = c.secondary, modifier = Modifier.size(15.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(c.text.copy(0.07f))
                        .border(0.7.dp, c.text.copy(0.11f), CircleShape)
                        .clickable(onClick = onOpenConfig),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Settings, null, tint = c.text, modifier = Modifier.size(16.dp))
                }
            }

            if (activeRepo == null) {
                EmptyTVBoxState(onOpenConfig)
            } else if (mode == 0) {
                if (currentSite == null) {
                    EmptySectionState("当前配置仓没有可用的点播站点", "去配置片源", onOpenConfig)
                } else {
                    TVBoxVodCatalogPane(
                        repoId = activeRepo.id,
                        site = currentSite,
                        allSites = sites,
                        onOpenVod = onOpenVodPlayer,
                    )
                }
            } else {
                if (currentLive == null) {
                    EmptySectionState("当前配置仓没有可用的直播列表", "去配置直播源", onOpenConfig)
                } else {
                    TVBoxLiveCatalogPane(
                        repoId = activeRepo.id,
                        live = currentLive,
                        onOpenLivePlayer = { idx -> onOpenLivePlayer(activeRepo.id, currentLive.id, idx) },
                    )
                }
            }
        }
    }

    if (showPickerSheet && activeRepo != null) {
        ModalBottomSheet(
            onDismissRequest = { showPickerSheet = false },
            containerColor = c.surface,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (mode == 0) "切换点播站点 · ${activeRepo.name}" else "切换直播源 · ${activeRepo.name}",
                        color = c.text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "切换配置仓",
                        color = c.accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable {
                            showPickerSheet = false
                            onOpenConfig()
                        },
                    )
                }
                if (mode == 0) {
                    sites.forEach { s ->
                        val on = s.key == currentSite?.key
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (on) c.accent.copy(0.14f) else Color.Transparent)
                                .clickable {
                                    selectedSiteKey = s.key
                                    showPickerSheet = false
                                }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(if (on) c.accent else Color(0xFF5FD08A)))
                            Spacer(Modifier.width(10.dp))
                            Text(s.name, color = if (on) c.accent else c.text, fontSize = 15.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.weight(1f))
                            Text("${s.categories.size} 分类", color = c.secondary, fontSize = 12.sp)
                        }
                    }
                } else {
                    lives.forEach { l ->
                        val on = l.id == currentLive?.id
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (on) c.accent.copy(0.14f) else Color.Transparent)
                                .clickable {
                                    selectedLiveId = l.id
                                    showPickerSheet = false
                                }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(if (on) c.accent else Color(0xFFFF6B6B)))
                            Spacer(Modifier.width(10.dp))
                            Text(l.name, color = if (on) c.accent else c.text, fontSize = 15.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.weight(1f))
                            if (l.channels.isNotEmpty()) Text("${l.channels.size} 频道", color = c.secondary, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HubSegmentTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = IdjThemeColors
    Column(
        Modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            label,
            color = if (selected) c.text else c.secondary,
            fontSize = if (selected) 21.sp else 18.sp,
            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .width(22.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (selected) c.accent else Color.Transparent),
        )
    }
}

@Composable
private fun TVBoxVodCatalogPane(
    repoId: String,
    site: TVBoxRepoSite,
    allSites: List<TVBoxRepoSite>,
    onOpenVod: (siteKey: String, seriesId: String, title: String, cover: String, startEp: Int) -> Unit,
) {
    val c = IdjThemeColors
    val store = IdjApp.instance.tvbox
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var submittedQuery by remember { mutableStateOf("") }
    var searchAllSites by remember { mutableStateOf(false) }

    val cats = remember(site.key, site.categories) {
        listOf("" to "最新") + site.categories.map { it.id to it.name }
    }
    var selCat by remember(site.key) { mutableIntStateOf(0) }
    val items = remember(site.key, selCat, submittedQuery, searchAllSites) { mutableStateListOf<Pair<TVBoxRepoSite, HGSeries>>() }
    var page by remember(site.key, selCat, submittedQuery, searchAllSites) { mutableIntStateOf(1) }
    var hasMore by remember(site.key, selCat, submittedQuery, searchAllSites) { mutableStateOf(true) }
    var loading by remember(site.key, selCat, submittedQuery, searchAllSites) { mutableStateOf(false) }
    var error by remember(site.key, selCat, submittedQuery, searchAllSites) { mutableStateOf<String?>(null) }
    val gridState = rememberLazyGridState()

    suspend fun loadMore() {
        if (loading || !hasMore) return
        loading = true
        try {
            if (submittedQuery.isNotBlank() && searchAllSites) {
                val results = withContext(Dispatchers.IO) {
                    coroutineScope {
                        allSites.take(10).map { s ->
                            async {
                                runCatching { MacCMS.search(s.toCMSSite(), submittedQuery, 1).items.map { s to it } }.getOrDefault(emptyList())
                            }
                        }.awaitAll().flatten()
                    }
                }
                items.clear()
                items.addAll(results)
                hasMore = false
                error = null
            } else if (submittedQuery.isNotBlank()) {
                val p = withContext(Dispatchers.IO) { MacCMS.search(site.toCMSSite(), submittedQuery, page) }
                val seen = items.map { it.second.seriesId }.toHashSet()
                items.addAll(p.items.filter { seen.add(it.seriesId) }.map { site to it })
                hasMore = p.hasMore
                page += 1
                error = null
            } else {
                val catId = cats.getOrNull(selCat)?.first.orEmpty()
                val p = withContext(Dispatchers.IO) {
                    val o = MacCMS.fetch(site.api, buildMap { put("ac", "detail"); put("pg", page.toString()); if (catId.isNotEmpty()) put("t", catId) })
                    if (catId.isEmpty() && page == 1) {
                        val parsedCats = MacCMS.parseCategories(o)
                        if (parsedCats.isNotEmpty()) withContext(Dispatchers.Main) { store.cacheSiteCategories(repoId, site.key, parsedCats) }
                    }
                    MacCMS.page(o, site.key, page)
                }
                val seen = items.map { it.second.seriesId }.toHashSet()
                items.addAll(p.items.filter { seen.add(it.seriesId) }.map { site to it })
                hasMore = p.hasMore
                page += 1
                error = null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "加载失败"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(site.key, selCat, submittedQuery, searchAllSites) { loadMore() }
    val nearEnd by remember {
        derivedStateOf { (gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= gridState.layoutInfo.totalItemsCount - 6 }
    }
    LaunchedEffect(nearEnd, items.size) { if (nearEnd && items.isNotEmpty()) loadMore() }

    val latestWatch = store.recentWatches.firstOrNull()

    LazyVerticalGrid(
        columns = GridCells.Fixed(app.iduanju.ui.components.LocalLayoutMetrics.current.posterCols),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 112.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        // 搜索胶囊（支持站内搜 / 全站聚合搜）
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(c.text.copy(0.065f))
                    .border(0.7.dp, c.text.copy(0.11f), RoundedCornerShape(20.dp))
                    .padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, null, tint = c.secondary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text("搜索 ${site.name}，或聚合全站片源", color = c.secondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = {
                            query = it
                            if (it.isBlank()) submittedQuery = ""
                        },
                        singleLine = true,
                        textStyle = TextStyle(color = c.text, fontSize = 13.sp),
                        cursorBrush = SolidColor(c.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submittedQuery = query.trim() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Box(
                    Modifier
                        .height(28.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (searchAllSites) c.accent.copy(0.20f) else c.text.copy(0.10f))
                        .clickable {
                            searchAllSites = !searchAllSites
                            if (query.isNotBlank()) submittedQuery = query.trim()
                        }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("全站搜", color = if (searchAllSites) c.accent else c.text, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // 分类胶囊
        if (submittedQuery.isBlank()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    itemsIndexed(cats) { idx, (_, name) ->
                        val on = idx == selCat
                        Box(
                            Modifier
                                .height(30.dp)
                                .clip(RoundedCornerShape(15.dp))
                                .background(if (on) c.text else c.text.copy(0.068f))
                                .clickable { selCat = idx }
                                .padding(horizontal = 13.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(name, color = if (on) c.bg else c.secondary, fontSize = 12.5.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium)
                        }
                    }
                }
            }
        }

        // 轻量「继续观看」单行条
        if (latestWatch != null && submittedQuery.isBlank()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(c.text.copy(0.06f))
                        .border(0.7.dp, c.text.copy(0.08f), RoundedCornerShape(14.dp))
                        .clickable {
                            onOpenVod(latestWatch.siteKey, latestWatch.seriesId, latestWatch.title, latestWatch.cover, latestWatch.episodeIndex)
                        }
                        .padding(9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = latestWatch.cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(width = 56.dp, height = 38.dp).clip(RoundedCornerShape(8.dp)).background(c.elevated),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${latestWatch.title} · ${latestWatch.episodeTitle}", color = c.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${latestWatch.siteName}${if (latestWatch.lineName.isNotBlank()) " · ${latestWatch.lineName}" else ""}",
                            color = c.secondary,
                            fontSize = 11.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (latestWatch.durationMs > 0L) {
                            val pct = (latestWatch.positionMs.toFloat() / latestWatch.durationMs.toFloat()).coerceIn(0.05f, 1f)
                            Spacer(Modifier.height(4.dp))
                            Box(Modifier.fillMaxWidth().height(2.5.dp).clip(RoundedCornerShape(2.dp)).background(c.text.copy(0.12f))) {
                                Box(Modifier.fillMaxWidth(pct).fillMaxHeight().background(c.accent))
                            }
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.size(30.dp).clip(CircleShape).background(c.text), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.PlayArrow, null, tint = c.bg, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        // 区块标题
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (submittedQuery.isNotBlank()) "搜索「$submittedQuery」" else cats.getOrNull(selCat)?.second ?: "最新更新",
                    color = c.text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (submittedQuery.isNotBlank() && searchAllSites) "聚合 ${allSites.size} 站" else "${site.name} · ${site.categories.size} 分类",
                    color = c.secondary,
                    fontSize = 12.sp,
                )
            }
        }

        // 3 列无框海报网格
        items(items, key = { (s, item) -> "${s.key}:${item.seriesId}" }) { (itemSite, series) ->
            Column(
                Modifier.clickable {
                    onOpenVod(itemSite.key, series.seriesId, series.title, series.cover, 0)
                },
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 2.86f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(c.elevated),
                ) {
                    AsyncImage(
                        model = series.cover,
                        contentDescription = series.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (!series.subtitle.isNullOrBlank()) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(Color(0xB80E1016))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(series.subtitle!!, color = Color(0xFFEEF0F3), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                    if (searchAllSites && submittedQuery.isNotBlank()) {
                        Box(
                            Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC0A0C10))))
                                .padding(horizontal = 6.dp, vertical = 5.dp),
                        ) {
                            Text(itemSite.name, color = Color(0xFFEEF0F3), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(series.title, color = c.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = series.tags.take(2).joinToString(" · ").ifBlank { series.subtitle ?: itemSite.name }
                Text(sub, color = c.secondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        // 加载/空状态尾项
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                when {
                    loading -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = c.secondary)
                    error != null -> Text("$error · 点击重试", color = c.secondary, fontSize = 13.sp, modifier = Modifier.clickable { scope.launch { loadMore() } })
                    items.isEmpty() -> Text("暂无内容，试试切换分类或站点", color = c.secondary, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun TVBoxLiveCatalogPane(
    repoId: String,
    live: TVBoxRepoLive,
    onOpenLivePlayer: (channelIndex: Int) -> Unit,
) {
    val c = IdjThemeColors
    val store = IdjApp.instance.tvbox
    val scope = rememberCoroutineScope()

    var channels by remember(repoId, live.id) { mutableStateOf(live.channels) }
    var loading by remember(repoId, live.id) { mutableStateOf(false) }
    var error by remember(repoId, live.id) { mutableStateOf<String?>(null) }
    var selectedGroup by remember(repoId, live.id) { mutableStateOf("全部") }
    var activeChannelName by remember(repoId, live.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(repoId, live.id) {
        if (channels.isEmpty()) {
            loading = true
            try {
                channels = store.loadLiveChannels(repoId, live.id)
                error = null
            } catch (e: Exception) {
                error = e.message ?: "拉取直播列表失败"
            } finally {
                loading = false
            }
        }
    }

    val groups = remember(channels) {
        listOf("全部", "收藏") + channels.map { it.group }.distinct()
    }
    val filtered = remember(channels, selectedGroup, store.favoriteChannels.toList()) {
        when (selectedGroup) {
            "全部" -> channels
            "收藏" -> channels.filter { store.isFavoriteChannel(it.name) }
            else -> channels.filter { it.group == selectedGroup }
        }
    }
    val activeCh = channels.firstOrNull { it.name == activeChannelName } ?: filtered.firstOrNull() ?: channels.firstOrNull()
    val activeGlobalIndex = activeCh?.let { ch -> channels.indexOfFirst { it.name == ch.name }.coerceAtLeast(0) } ?: 0

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 112.dp),
    ) {
        // 唯一焦点直播预览卡
        if (activeCh != null) {
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .height(172.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF101624), Color(0xFF1D263B), Color(0xFF0E1118)),
                            ),
                        )
                        .clickable { onOpenLivePlayer(activeGlobalIndex) }
                        .padding(14.dp),
                ) {
                    Row(Modifier.fillMaxWidth().align(Alignment.TopStart), horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(99.dp))
                                .background(Color(0xA60E1016))
                                .border(0.7.dp, Color.White.copy(0.14f), RoundedCornerShape(99.dp))
                                .padding(horizontal = 9.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFFFF6B6B)))
                            Text("LIVE · HLS", color = Color(0xFFEEF0F3), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(99.dp))
                                .background(Color(0xA60E1016))
                                .border(0.7.dp, Color.White.copy(0.14f), RoundedCornerShape(99.dp))
                                .padding(horizontal = 9.dp, vertical = 3.dp),
                        ) {
                            Text("线路 1 / ${activeCh.urls.size}", color = Color(0xFFEEF0F3), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Row(Modifier.fillMaxWidth().align(Alignment.BottomStart), verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            Text(activeCh.name, color = Color(0xFFEEF0F3), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${live.name} · ${activeCh.group}", color = Color(0xB8EEF0F3), fontSize = 11.5.sp)
                        }
                        Row(
                            Modifier
                                .height(32.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0xFFEEF0F3))
                                .padding(horizontal = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(Icons.Filled.Fullscreen, null, tint = Color(0xFF111317), modifier = Modifier.size(16.dp))
                            Text("全屏观看", color = Color(0xFF111317), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // 分组胶囊
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                items(groups) { g ->
                    val on = g == selectedGroup
                    val label = if (g == "全部") "全部 ${channels.size}" else g
                    Box(
                        Modifier
                            .height(30.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(if (on) c.text else c.text.copy(0.068f))
                            .clickable { selectedGroup = g }
                            .padding(horizontal = 13.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, color = if (on) c.bg else c.secondary, fontSize = 12.5.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium)
                    }
                }
            }
        }

        if (loading) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = c.accent)
                }
            }
        } else if (error != null) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "$error · 点击重试",
                        color = c.secondary,
                        fontSize = 13.sp,
                        modifier = Modifier.clickable {
                            scope.launch {
                                loading = true
                                try {
                                    channels = store.loadLiveChannels(repoId, live.id, forceRefresh = true)
                                    error = null
                                } catch (e: Exception) {
                                    error = e.message ?: "拉取失败"
                                } finally {
                                    loading = false
                                }
                            }
                        },
                    )
                }
            }
        } else {
            itemsIndexed(filtered, key = { idx, ch -> "${ch.name}:$idx" }) { idx, ch ->
                val isPlaying = ch.name == activeCh?.name
                val fav = store.isFavoriteChannel(ch.name)
                val globalIdx = channels.indexOfFirst { it.name == ch.name }.coerceAtLeast(0)
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                activeChannelName = ch.name
                                onOpenLivePlayer(globalIdx)
                            }
                            .padding(vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "%02d".format(idx + 1),
                            color = if (isPlaying) c.accent else c.secondary.copy(0.65f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.width(24.dp),
                        )
                        Box(
                            Modifier
                                .size(width = 46.dp, height = 30.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isPlaying) c.accent.copy(0.16f) else c.text.copy(0.065f))
                                .border(0.7.dp, if (isPlaying) c.accent.copy(0.42f) else c.text.copy(0.08f), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                ch.name.take(5),
                                color = if (isPlaying) c.accent else c.text,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                        }
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                ch.name,
                                color = if (isPlaying) c.accent else c.text,
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${ch.group} · ${ch.urls.size} 条信号源${if (isPlaying) " · 正在预览" else ""}",
                                color = c.secondary,
                                fontSize = 11.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { store.toggleFavoriteChannel(ch.name) }, modifier = Modifier.size(34.dp)) {
                            Icon(
                                if (fav) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                null,
                                tint = if (fav) c.accentWarm else c.secondary.copy(0.6f),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    HorizontalDivider(thickness = 0.6.dp, color = c.text.copy(0.07f))
                }
            }
        }
    }
}

@Composable
private fun EmptyTVBoxState(onOpenConfig: () -> Unit) {
    val c = IdjThemeColors
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("TVBox 点播与直播", color = c.text, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "导入你的 HTTPS TVBox 配置或 M3U/TXT 直播源，支持多配置仓一键切换、多线路测速与横屏秒切台。",
            color = c.secondary,
            fontSize = 13.5.sp,
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(22.dp))
        Box(
            Modifier
                .height(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(c.text)
                .clickable(onClick = onOpenConfig)
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("配置 TVBox 片源", color = c.bg, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun EmptySectionState(msg: String, cta: String, onClick: () -> Unit) {
    val c = IdjThemeColors
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(msg, color = c.secondary, fontSize = 14.sp)
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier
                .height(38.dp)
                .clip(RoundedCornerShape(19.dp))
                .background(c.text)
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(cta, color = c.bg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
