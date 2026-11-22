package app.iduanju.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import app.iduanju.ui.category.CategoryScreen
import app.iduanju.ui.components.GlassNavigationRail
import app.iduanju.ui.components.GlassStyle
import app.iduanju.ui.components.GlassStylePref
import app.iduanju.ui.components.LayoutMetrics
import app.iduanju.ui.components.LocalRootBackdrop
import app.iduanju.ui.components.LocalLayoutMetrics
import app.iduanju.ui.components.SearchDockState
import app.iduanju.ui.components.glassPill
import app.iduanju.ui.components.glassSurface
import app.iduanju.ui.detail.DetailScreen
import app.iduanju.ui.home.HomeScreen
import app.iduanju.ui.player.PlayerScreen
import app.iduanju.ui.profile.ProfileScreen
import app.iduanju.ui.search.SearchScreen
import app.iduanju.ui.theme.IdjThemeColors
import kotlin.math.roundToInt

/** Lets deep rows (继续观看 · 全部) open a route without threading callbacks through every page. */
val LocalNavigate = androidx.compose.runtime.staticCompositionLocalOf<(String) -> Unit> { {} }

@Composable
fun RootScreen(initialRoute: String? = null) {
    val nav = rememberNavController()
    LaunchedEffect(initialRoute) { initialRoute?.takeUnless { it == "home" }?.let { runCatching { nav.navigate(it) } } }
    val updater = app.iduanju.IdjApp.instance.updater
    LaunchedEffect(Unit) { updater.autoCheck() }
    var tab by remember { mutableIntStateOf(0) }
    var novelImmersive by remember { mutableStateOf(false) }
    val searchDockState = remember { SearchDockState() }
    val back = nav.currentBackStackEntryAsState().value
    val route = back?.destination?.route ?: "home"
    LaunchedEffect(route) {
        when (route) { "home" -> tab = 0; "tvbox" -> tab = 1; "category" -> tab = 2; "profile" -> tab = 3 }
        if (route != "search") searchDockState.isTyping = false
    }
    val atRoot = route in setOf("home", "tvbox", "search", "category", "profile")
    /** Backdrop for the floating liquid-glass navigation bar: everything the NavHost draws. */
    val bgColor = IdjThemeColors.bg
    val backdrop = rememberLayerBackdrop {
        drawRect(bgColor)
        drawContent()
    }
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val metrics = remember(maxWidth, maxHeight, cfg.smallestScreenWidthDp) {
            LayoutMetrics(
                widthDp = maxWidth.value.toInt(),
                heightDp = maxHeight.value.toInt(),
                smallestWidthDp = cfg.smallestScreenWidthDp,
            )
        }
        val showChromeNav = atRoot && !(route == "home" && novelImmersive)
        val navItems = listOf<Pair<String, @Composable (Boolean) -> Unit>>(
            "首页" to { on -> Icon(Icons.Filled.Home, null, Modifier.size(20.dp), tint = if (on) IdjThemeColors.text else IdjThemeColors.secondary) },
            "TVBox" to { on -> Icon(Icons.Filled.LiveTv, null, Modifier.size(20.dp), tint = if (on) IdjThemeColors.text else IdjThemeColors.secondary) },
            "找剧" to { on -> Icon(Icons.Filled.GridView, null, Modifier.size(20.dp), tint = if (on) IdjThemeColors.text else IdjThemeColors.secondary) },
            "我的" to { on -> Icon(Icons.Filled.Person, null, Modifier.size(20.dp), tint = if (on) IdjThemeColors.text else IdjThemeColors.secondary) },
        )
        val tabRoutes = listOf("home", "tvbox", "category", "profile")
        /**
         * "home" is the graph's start destination. A non-inclusive `popUpTo(home) { saveState = true }` maps the
         * popped stack (e.g. [search]) onto home's own id, so `navigate("home") { restoreState = true }` restored
         * the search page right back and 首页 looked dead. Home is therefore reached by popping back to it (or by
         * rebuilding the graph from it), never through restoreState; transient search routes are dropped first so
         * they are never saved into another tab's state.
         */
        val goHome: () -> Unit = {
            if (nav.currentBackStackEntry?.destination?.route != "home" && !nav.popBackStack("home", inclusive = false)) {
                nav.navigate("home") {
                    popUpTo(nav.graph.id) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
        val onSelectTab: (Int) -> Unit = { i ->
            tab = i
            val target = tabRoutes[i]
            val cur = nav.currentBackStackEntry?.destination?.route
            if (target == "home") {
                goHome()
            } else if (cur != target) {
                if (cur == "search" || cur?.startsWith("actorSearch/") == true) nav.popBackStack()
                nav.navigate(target) {
                    popUpTo(nav.graph.startDestinationId) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
        val onSelectSearch: () -> Unit = {
            nav.navigate("search") {
                popUpTo(nav.graph.startDestinationId) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
        val searchIcon: @Composable (Boolean) -> Unit = { on ->
            Icon(Icons.Filled.Search, null, Modifier.size(22.dp), tint = if (on) IdjThemeColors.accent else IdjThemeColors.text)
        }

        // "home/<source>" (我的 → Telegram 频道 / 漫画) switches to the 首页 tab and shows that source page.
        val navigate: (String) -> Unit = { r ->
            if (r.startsWith("home/")) {
                app.iduanju.ui.home.HomeJump.label = mapOf("channels" to "频道", "manga" to "漫画")[r.removePrefix("home/")]
                tab = 0
                goHome()
            } else nav.navigate(r)
        }
        androidx.compose.runtime.CompositionLocalProvider(
            LocalNavigate provides navigate,
            LocalLayoutMetrics provides metrics,
            // Root backdrop is only active while the NavHost is recorded into it (chrome nav visible).
            LocalRootBackdrop provides (if (showChromeNav) backdrop else null),
        ) {
            Row(Modifier.fillMaxSize()) {
                if (showChromeNav && metrics.useRail) {
                    GlassNavigationRail(
                        selected = tab,
                        items = navItems,
                        onSelect = onSelectTab,
                        searchSelected = route == "search",
                        onSearch = onSelectSearch,
                        searchIcon = searchIcon,
                        backdrop = LocalRootBackdrop.current,
                    )
                }
                // NavHost content is drawn INSIDE layerBackdrop(backdrop): never hand it the root backdrop (feedback loop).
                androidx.compose.runtime.CompositionLocalProvider(LocalRootBackdrop provides null) {
                NavHost(
                    nav,
                    startDestination = "home",
                    modifier = Modifier.weight(1f).fillMaxHeight().then(if (showChromeNav) Modifier.layerBackdrop(backdrop) else Modifier),
                ) {
                composable("home") { HomeScreen(onOpenDetail = { nav.navigate("detail/$it") }, onSearch = { nav.navigate("search") }, onPlay = { nav.navigate("player/$it/0") }, onOpenManga = { nav.navigate("manga/" + android.net.Uri.encode(it)) }, onImmersiveChanged = { novelImmersive = it }) }
                composable("tvbox") {
                    app.iduanju.tvbox.TVBoxHubScreen(
                        onOpenConfig = { nav.navigate("tvbox/config") },
                        onOpenVodPlayer = { siteKey, sid, title, cover, ep ->
                            nav.navigate(
                                "tvbox/vod/${android.net.Uri.encode(siteKey)}/${android.net.Uri.encode(sid)}/$ep?title=${android.net.Uri.encode(title)}&cover=${android.net.Uri.encode(cover)}",
                            )
                        },
                        onOpenLivePlayer = { repoId, liveId, chIdx ->
                            nav.navigate("tvbox/live/${android.net.Uri.encode(repoId)}/${android.net.Uri.encode(liveId)}/$chIdx")
                        },
                    )
                }
                composable("tvbox/config") {
                    app.iduanju.tvbox.TVBoxConfigScreen(
                        onBack = { nav.popBackStack() },
                        onOpenLive = { repoId, liveId ->
                            nav.navigate("tvbox/live/${android.net.Uri.encode(repoId)}/${android.net.Uri.encode(liveId)}/0")
                        },
                    )
                }
                composable(
                    "tvbox/vod/{siteKey}/{sid}/{ep}?title={title}&cover={cover}",
                    arguments = listOf(
                        navArgument("siteKey") { type = NavType.StringType },
                        navArgument("sid") { type = NavType.StringType },
                        navArgument("ep") { type = NavType.IntType },
                        navArgument("title") { defaultValue = "" },
                        navArgument("cover") { defaultValue = "" },
                    ),
                ) { e ->
                    val args = requireNotNull(e.arguments)
                    app.iduanju.tvbox.TVBoxVodPlayerScreen(
                        siteKey = args.getString("siteKey").orEmpty(),
                        seriesId = args.getString("sid").orEmpty(),
                        initialTitle = args.getString("title").orEmpty(),
                        initialCover = args.getString("cover").orEmpty(),
                        startEp = args.getInt("ep"),
                        onBack = { nav.popBackStack() },
                        onSwitchSiteSeries = { newSiteKey, newSid, title, cover ->
                            nav.navigate(
                                "tvbox/vod/${android.net.Uri.encode(newSiteKey)}/${android.net.Uri.encode(newSid)}/0?title=${android.net.Uri.encode(title)}&cover=${android.net.Uri.encode(cover)}",
                            ) {
                                popUpTo("tvbox/vod/{siteKey}/{sid}/{ep}?title={title}&cover={cover}") { inclusive = true }
                            }
                        },
                    )
                }
                composable(
                    "tvbox/live/{repoId}/{liveId}/{chIdx}",
                    arguments = listOf(
                        navArgument("repoId") { type = NavType.StringType },
                        navArgument("liveId") { type = NavType.StringType },
                        navArgument("chIdx") { type = NavType.IntType },
                    ),
                ) { e ->
                    val args = requireNotNull(e.arguments)
                    app.iduanju.tvbox.TVBoxLivePlayerScreen(
                        repoId = args.getString("repoId").orEmpty(),
                        liveId = args.getString("liveId").orEmpty(),
                        startChannelIndex = args.getInt("chIdx"),
                        onBack = { nav.popBackStack() },
                    )
                }
                composable("search") {
                    SearchScreen(
                        onOpenDetail = { nav.navigate("detail/$it") },
                        dockState = searchDockState,
                    )
                }
                composable("category") { CategoryScreen(onOpenDetail = { nav.navigate("detail/$it") }) }
                composable("library/{kind}") { e -> app.iduanju.ui.library.LibraryListScreen(e.arguments!!.getString("kind")!!, onBack = { nav.popBackStack() }, onOpenDetail = { nav.navigate("detail/$it") }) }
                composable("profile") { ProfileScreen(onOpenDetail = { nav.navigate("detail/$it") }) }
                composable("hongguo-login") { app.iduanju.ui.profile.HongguoLoginScreen(onBack = { nav.popBackStack() }) }
                composable("sources") { app.iduanju.ui.profile.SourcesScreen(onBack = { nav.popBackStack() }) }
                composable("plugins") { app.iduanju.ui.profile.PluginsScreen(onBack = { nav.popBackStack() }) }
                composable("novel") { app.iduanju.novel.NovelScreen(onBack = { nav.popBackStack() }) }
                composable("licenses") { app.iduanju.ui.profile.LicensesScreen(onBack = { nav.popBackStack() }) }
                composable("actorRank") { app.iduanju.ui.profile.ActorRankScreen(onBack = { nav.popBackStack() }) }
                composable("actor/{id}?name={name}&avatar={avatar}&role={role}&intro={intro}",
                    arguments = listOf("name", "avatar", "role", "intro").map { navArgument(it) { defaultValue = "" } }) { entry ->
                    val args = requireNotNull(entry.arguments)
                    val actor = app.iduanju.core.hongguo.HGCelebrity(requireNotNull(args.getString("id")),
                        args.getString("name").orEmpty(), args.getString("avatar").orEmpty(),
                        args.getString("role").orEmpty(), args.getString("intro").orEmpty())
                    app.iduanju.ui.profile.ActorProfileScreen(actor, onBack = { nav.popBackStack() }, onOpen = { nav.navigate("detail/$it") })
                }
                composable("actorSearch/{name}") { e -> SearchScreen(onOpenDetail = { nav.navigate("detail/$it") }, initialQuery = e.arguments!!.getString("name")!!) }
                composable("board/{genre}/{sub}") { e -> app.iduanju.ui.category.BoardScreen(app.iduanju.ui.home.BoardSpec(app.iduanju.core.hongguo.HGRankGenre.valueOf(e.arguments!!.getString("genre")!!), app.iduanju.core.hongguo.HGRankSub.valueOf(e.arguments!!.getString("sub")!!)), onBack = { nav.popBackStack() }, onOpenDetail = { nav.navigate("detail/$it") }) }
                composable("detail/{sid}", arguments = listOf(navArgument("sid") { type = NavType.StringType })) { e ->
                    val sid = e.arguments!!.getString("sid")!!
                    DetailScreen(
                        seriesId = sid,
                        onBack = { nav.popBackStack() },
                        onPlay = { s, ep -> nav.navigate("player/$s/$ep") },
                        onOpenSeries = { nav.navigate("detail/$it") },
                    )
                }
                composable("manga/{id}") { e -> app.iduanju.manga.MangaDetailScreen(e.arguments!!.getString("id")!!, onBack = { nav.popBackStack() },
                        onRead = { id, i, p -> nav.navigate("mangaRead/" + android.net.Uri.encode(id) + "/$i/$p") }) }
                composable("mangaRead/{id}/{i}/{p}", arguments = listOf(navArgument("i") { type = NavType.IntType }, navArgument("p") { type = NavType.IntType })) { e ->
                    app.iduanju.manga.MangaReaderScreen(e.arguments!!.getString("id")!!, e.arguments!!.getInt("i"), e.arguments!!.getInt("p"), onBack = { nav.popBackStack() }) }
                composable(
                    "player/{sid}/{ep}",
                    arguments = listOf(
                        navArgument("sid") { type = NavType.StringType },
                        navArgument("ep") { type = NavType.IntType },
                    ),
                    enterTransition = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) },
                    exitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)) },
                    popEnterTransition = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) },
                    popExitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)) },
                ) { e ->
                    PlayerScreen(
                        seriesId = e.arguments!!.getString("sid")!!,
                        startEp = e.arguments!!.getInt("ep"),
                        onBack = { nav.popBackStack() },
                        onSwitchSeries = { sid -> nav.navigate("player/$sid/0") { popUpTo("player/{sid}/{ep}") { inclusive = true } } },
                    )
                }
            }
            }
            }
        }
        // While typing, SearchScreen owns the input bar at the top, so the dock steps aside instead of floating
        // over the content above the keyboard.
        if (showChromeNav && !metrics.useRail) androidx.compose.animation.AnimatedVisibility(
            visible = !(route == "search" && searchDockState.isTyping),
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) +
                androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(220)) { it / 2 },
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(140)) +
                androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(180)) { it / 2 },
        ) {
            SwipeableGlassTabBar(
                selected = tab,
                items = navItems,
                onSelect = onSelectTab,
                searchSelected = route == "search",
                onSearch = onSelectSearch,
                searchIcon = searchIcon,
                searchDockState = searchDockState,
                onCloseSearch = { onSelectTab(tab) },
                backdrop = backdrop,
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    .padding(bottom = 6.dp),
            )
        }
        app.iduanju.update.UpdateDialog(updater)
    }
}

private val DockPillShape = RoundedCornerShape(percent = 50)

/**
 * Floating bottom Liquid Glass dock with horizontal finger-slide tab scrubbing,
 * sliding glass indicator pill, haptic ticks on slot boundaries, and morphing search dock.
 */
@Composable
private fun SwipeableGlassTabBar(
    selected: Int,
    items: List<Pair<String, @Composable (Boolean) -> Unit>>,
    onSelect: (Int) -> Unit,
    backdrop: Backdrop? = null,
    modifier: Modifier = Modifier,
    searchSelected: Boolean = false,
    onSearch: (() -> Unit)? = null,
    searchIcon: (@Composable (Boolean) -> Unit)? = null,
    searchDockState: SearchDockState? = null,
    onCloseSearch: () -> Unit = {},
) {
    val c = IdjThemeColors
    val liquid = GlassStylePref.style == GlassStyle.LIQUID
    val focusManager = LocalFocusManager.current
    val haptic = LocalHapticFeedback.current
    val hasSearch = onSearch != null && searchIcon != null
    val morph by animateFloatAsState(
        targetValue = if (hasSearch && searchSelected) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 360f),
        label = "dockMorph",
    )

    LaunchedEffect(searchSelected) { if (!searchSelected) focusManager.clearFocus() }

    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(selected.toFloat()) }
    var hoveredIdx by remember { mutableIntStateOf(selected) }
    val latestSelected by rememberUpdatedState(selected)
    val latestOnSelect by rememberUpdatedState(onSelect)

    LaunchedEffect(selected, searchSelected) {
        if (!isDragging) {
            dragFraction = selected.toFloat()
            hoveredIdx = selected
        }
    }

    val activeTab = if (isDragging) hoveredIdx else selected
    val indicatorFraction by animateFloatAsState(
        targetValue = if (isDragging) dragFraction else selected.toFloat(),
        animationSpec = if (isDragging) snap() else spring(dampingRatio = 0.78f, stiffness = 420f),
        label = "dockIndicator",
    )
    val indicatorScale by animateFloatAsState(
        targetValue = if (isDragging) 1.04f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 480f),
        label = "dockIndicatorScale",
    )

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        BoxWithConstraints(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            val gap = 10.dp
            val circleSize = 58.dp
            val totalW = maxWidth
            val expandedW = if (hasSearch) (totalW - gap - circleSize).coerceAtLeast(circleSize) else totalW
            val t = morph.coerceIn(0f, 1f)
            val leftW = if (hasSearch) lerp(expandedW, circleSize, t) else totalW
            val rightW = lerp(circleSize, expandedW, t)

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                Box(
                    Modifier
                        .width(leftW)
                        .height(circleSize)
                        .glassSurface(shape = DockPillShape, backdrop = backdrop, shadowRadius = 18.dp)
                        .then(
                            if (searchSelected) {
                                Modifier.clickable {
                                    focusManager.clearFocus()
                                    onCloseSearch()
                                }
                            } else {
                                Modifier
                            },
                        )
                        .padding(if (t < 0.5f) 5.dp else 0.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (t < 0.5f) {
                        val tabsAlpha = (1f - t * 2f).coerceIn(0f, 1f)
                        BoxWithConstraints(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = tabsAlpha },
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            val count = items.size.coerceAtLeast(1)
                            val slotWidth = maxWidth / count
                            val slotWidthPx = with(LocalDensity.current) { slotWidth.toPx() }.coerceAtLeast(1f)
                            val maxIdx = (count - 1).toFloat()

                            if (!searchSelected) {
                                Box(
                                    Modifier
                                        .offset {
                                            IntOffset((indicatorFraction.coerceIn(0f, maxIdx) * slotWidthPx).roundToInt(), 0)
                                        }
                                        .width(slotWidth)
                                        .height(48.dp)
                                        .graphicsLayer {
                                            scaleX = indicatorScale
                                            scaleY = indicatorScale
                                        }
                                        .then(
                                            if (liquid) Modifier.glassPill(DockPillShape, selected = true, neutralSelected = true)
                                            else Modifier.clip(DockPillShape).background(if (c.dark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f)),
                                        ),
                                )
                            }

                            Row(
                                Modifier
                                    .fillMaxSize()
                                    .pointerInput(count, searchSelected, slotWidthPx) {
                                        if (searchSelected || count <= 1) return@pointerInput
                                        detectHorizontalDragGestures(
                                            onDragStart = { startOffset ->
                                                isDragging = true
                                                val idx = (startOffset.x / slotWidthPx).toInt().coerceIn(0, count - 1)
                                                dragFraction = (startOffset.x / slotWidthPx - 0.5f).coerceIn(0f, maxIdx)
                                                if (idx != hoveredIdx) {
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                }
                                                hoveredIdx = idx
                                            },
                                            onHorizontalDrag = { change, _ ->
                                                change.consume()
                                                val x = change.position.x
                                                dragFraction = (x / slotWidthPx - 0.5f).coerceIn(0f, maxIdx)
                                                val idx = (x / slotWidthPx).toInt().coerceIn(0, count - 1)
                                                if (idx != hoveredIdx) {
                                                    hoveredIdx = idx
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                }
                                            },
                                            onDragEnd = {
                                                val target = hoveredIdx
                                                isDragging = false
                                                if (target != latestSelected) latestOnSelect(target)
                                            },
                                            onDragCancel = {
                                                isDragging = false
                                                hoveredIdx = latestSelected
                                                dragFraction = latestSelected.toFloat()
                                            },
                                        )
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                items.forEachIndexed { i, (label, icon) ->
                                    val on = i == activeTab && !searchSelected
                                    Box(
                                        Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .clip(DockPillShape)
                                            .clickable { latestOnSelect(i) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            icon(on)
                                            Spacer(Modifier.height(2.dp))
                                            Text(
                                                label,
                                                color = if (on) c.text else c.secondary,
                                                fontSize = 11.sp,
                                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        val backAlpha = ((t - 0.5f) * 2f).coerceIn(0f, 1f)
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = c.text,
                            modifier = Modifier.size(22.dp).graphicsLayer { alpha = backAlpha },
                        )
                    }
                }

                if (hasSearch) {
                    val searchQuery = searchDockState?.query.orEmpty()
                    Box(
                        Modifier
                            .width(rightW)
                            .height(circleSize)
                            .glassSurface(
                                shape = DockPillShape,
                                backdrop = backdrop,
                                shadowRadius = 18.dp,
                                activeOverlay = if (searchSelected) c.accent.copy(alpha = if (c.dark) 0.06f else 0.04f) else Color.Transparent,
                            )
                            .clickable {
                                if (!searchSelected) onSearch?.invoke() else searchDockState?.isTyping = true
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (t < 0.38f && !searchSelected) {
                            val iconAlpha = (1f - t / 0.38f).coerceIn(0f, 1f)
                            Box(Modifier.graphicsLayer { alpha = iconAlpha }, contentAlignment = Alignment.Center) {
                                searchIcon?.invoke(false)
                            }
                        } else {
                            val fieldAlpha = ((t - 0.25f) / 0.75f).coerceIn(0f, 1f)
                            Row(
                                Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp)
                                    .graphicsLayer { alpha = fieldAlpha },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Filled.Search,
                                    contentDescription = "搜索",
                                    tint = c.accent,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    searchQuery.ifEmpty { "搜索短剧 / 演员 / 频道…" },
                                    color = if (searchQuery.isEmpty()) c.secondary else c.text,
                                    fontSize = 15.sp,
                                    fontWeight = if (searchQuery.isEmpty()) FontWeight.Normal else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (searchQuery.isNotEmpty()) {
                                    Spacer(Modifier.width(6.dp))
                                    Icon(
                                        Icons.Filled.Cancel,
                                        contentDescription = "清除",
                                        tint = c.secondary,
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clickable { searchDockState?.clear() },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
