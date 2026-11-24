package app.iduanju.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
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
import app.iduanju.ui.components.ExpressiveMotion
import app.iduanju.ui.components.ExpressiveNav
import app.iduanju.ui.components.expressivePress
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    // ---- Native predictive back ----
    // NavHost listens on a private child dispatcher, so its built-in seekable transition (which keeps BOTH the top
    // and the previous entry composed) only runs when we forward the system gesture to it. Our handler sits on the
    // real activity dispatcher *below* every screen's own BackHandler (player / search / readers win first, since
    // destinations get the real dispatcher back), records progress, edge and touch Y, and drives the card physics
    // drawn by [PredictiveBackScene].
    val predictiveBack = remember { PredictiveBackState() }
    val realBackOwner = LocalOnBackPressedDispatcherOwner.current
    // activity-compose 1.9.3's PredictiveBackHandler reads this (ui.platform) local, so it is the one to provide.
    @Suppress("DEPRECATION") val lifecycleLocal = androidx.compose.ui.platform.LocalLifecycleOwner
    val hostLifecycleOwner = lifecycleLocal.current
    val navBackOwner = remember(hostLifecycleOwner) { ChildBackDispatcherOwner(hostLifecycleOwner) }
    val predictiveBackHost = remember(realBackOwner) { PredictiveBackHost(predictiveBack, realBackOwner) }
    val handlerLifecycleOwner = remember { AlwaysResumedLifecycleOwner() }
    val backScope = rememberCoroutineScope()
    val settle = remember { arrayOfNulls<Job>(1) }
    val canPop = back != null && nav.previousBackStackEntry != null
    val child = navBackOwner.onBackPressedDispatcher

    // Registered with an always-resumed owner so it is never re-added on top of the screens' own handlers after
    // the activity restarts (lifecycle-bound callbacks re-register in observer order on every ON_START).
    androidx.compose.runtime.CompositionLocalProvider(lifecycleLocal provides handlerLifecycleOwner) {
        PredictiveBackHandler(enabled = canPop) { events ->
            val pb = predictiveBack
            if (pb.committing) {
                // The previous swipe is already popping this route: swallow this one.
                events.collect { }
                return@PredictiveBackHandler
            }
            // A new swipe while the previous one is still springing home: finish that revert right away.
            settle[0]?.cancel()
            settle[0] = null
            if (pb.isPredictingBack) {
                child.dispatchOnBackCancelled()
                pb.reset()
            }
            val token = ++pb.gestureToken
            val topRoute = nav.currentBackStackEntry?.destination?.route.orEmpty()
            // SurfaceView-based players (TVBox) cannot be transformed by a graphics layer: plain pop on release.
            val cardPhysics = !topRoute.startsWith("tvbox/vod/") && !topRoute.startsWith("tvbox/live/")
            var started = false
            try {
                events.collect { e ->
                    if (!cardPhysics) return@collect
                    if (!started) {
                        started = true
                        pb.topEntryId = nav.currentBackStackEntry?.id
                        pb.previousEntryId = nav.previousBackStackEntry?.id
                        pb.backSwipeEdge = e.swipeEdge
                        pb.backStartTouchY = e.touchY
                        pb.backTouchY = e.touchY
                        pb.backProgress = 0f
                        pb.commitProgress = 0f
                        // Must be set before NavHost seeks, so its transitions resolve to the neutral pair.
                        pb.isPredictingBack = true
                        pb.neutralTransitions = true
                        child.dispatchOnBackStarted(e)
                    }
                    pb.backProgress = e.progress
                    pb.backTouchY = e.touchY
                    pb.backSwipeEdge = e.swipeEdge
                    child.dispatchOnBackProgressed(e)
                }
                if (!started) {
                    // Button / 3-button back (or no gesture preview): regular directional pop transition.
                    child.onBackPressed()
                    return@PredictiveBackHandler
                }
                // Released: the card finishes leaving while the previous page settles to 1.0, then the route pops
                // underneath an already invisible card: no flicker, no snap back.
                pb.committing = true
                withContext(NonCancellable) {
                    animate(0f, 1f, animationSpec = ExpressiveMotion.effects()) { v, _ -> pb.commitProgress = v }
                    val dismissed = pb.topEntryId
                    pb.dismissedEntryId = dismissed
                    child.onBackPressed()
                    pb.awaitSettleFrames()
                    pb.reset()
                    // Safety net: if nothing popped (should not happen), never leave the page hidden.
                    backScope.launch {
                        delay(900)
                        if (dismissed != null && nav.currentBackStackEntry?.id == dismissed) pb.dismissedEntryId = null
                    }
                }
            } catch (e: CancellationException) {
                if (started && !pb.committing) {
                    // The handler's own coroutine is cancelled: spring home from a separate scope, then let
                    // NavHost un-seek (the back stack is untouched).
                    settle[0] = backScope.launch {
                        try {
                            animate(pb.backProgress, 0f, animationSpec = ExpressiveMotion.spatialDefault()) { v, _ -> pb.backProgress = v }
                        } finally {
                            if (pb.gestureToken == token && pb.isPredictingBack) {
                                child.dispatchOnBackCancelled()
                                backScope.launch { pb.resetAfterFrames(token) }
                            }
                        }
                    }
                }
                throw e
            }
        }
    }

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
        val tabShiftPx = with(LocalDensity.current) { 24.dp.roundToPx() }
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
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalRootBackdrop provides null,
                    LocalOnBackPressedDispatcherOwner provides navBackOwner,
                    LocalPredictiveBackHost provides predictiveBackHost,
                ) {
                NavHost(
                    nav,
                    startDestination = "home",
                    modifier = Modifier.weight(1f).fillMaxHeight().then(if (showChromeNav) Modifier.layerBackdrop(backdrop) else Modifier),
                    // Directional depth: push slides the new page in from +10 % over a receding page, pop reverses
                    // it, and root tab switches glide 24 dp in the tab-index direction (same axis as the dock
                    // swipe). While a predictive back gesture runs NavHost only holds both pages composed; the card
                    // physics are drawn by PredictiveBackScene.
                    enterTransition = {
                        if (predictiveBack.neutralTransitions) EnterTransition.None
                        else tabDirection(initialState, targetState)?.let { ExpressiveNav.tabEnter(it, tabShiftPx) } ?: ExpressiveNav.pushEnter()
                    },
                    exitTransition = {
                        if (predictiveBack.neutralTransitions) ExitTransition.None
                        else tabDirection(initialState, targetState)?.let { ExpressiveNav.tabExit(it, tabShiftPx) } ?: ExpressiveNav.pushExit()
                    },
                    popEnterTransition = {
                        if (predictiveBack.neutralTransitions) EnterTransition.None
                        else tabDirection(initialState, targetState)?.let { ExpressiveNav.tabEnter(it, tabShiftPx) } ?: ExpressiveNav.popEnter()
                    },
                    popExitTransition = {
                        if (predictiveBack.neutralTransitions) ExitTransition.None
                        else tabDirection(initialState, targetState)?.let { ExpressiveNav.tabExit(it, tabShiftPx) } ?: ExpressiveNav.popExit()
                    },
                ) {
                screen("home") { HomeScreen(onOpenDetail = { nav.navigate("detail/$it") }, onSearch = { nav.navigate("search") }, onPlay = { nav.navigate("player/$it/0") }, onOpenManga = { nav.navigate("manga/" + android.net.Uri.encode(it)) }, onImmersiveChanged = { novelImmersive = it }) }
                screen("tvbox") {
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
                screen("tvbox/config") {
                    app.iduanju.tvbox.TVBoxConfigScreen(
                        onBack = { nav.popBackStack() },
                        onOpenLive = { repoId, liveId ->
                            nav.navigate("tvbox/live/${android.net.Uri.encode(repoId)}/${android.net.Uri.encode(liveId)}/0")
                        },
                    )
                }
                screen(
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
                screen(
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
                screen("search") {
                    SearchScreen(
                        onOpenDetail = { nav.navigate("detail/$it") },
                        dockState = searchDockState,
                    )
                }
                screen("category") { CategoryScreen(onOpenDetail = { nav.navigate("detail/$it") }) }
                screen("library/{kind}") { e -> app.iduanju.ui.library.LibraryListScreen(e.arguments!!.getString("kind")!!, onBack = { nav.popBackStack() }, onOpenDetail = { nav.navigate("detail/$it") }) }
                screen("profile") { ProfileScreen(onOpenDetail = { nav.navigate("detail/$it") }) }
                screen("hongguo-login") { app.iduanju.ui.profile.HongguoLoginScreen(onBack = { nav.popBackStack() }) }
                screen("sources") { app.iduanju.ui.profile.SourcesScreen(onBack = { nav.popBackStack() }) }
                screen("plugins") { app.iduanju.ui.profile.PluginsScreen(onBack = { nav.popBackStack() }) }
                screen("novel") { app.iduanju.novel.NovelScreen(onBack = { nav.popBackStack() }) }
                screen("licenses") { app.iduanju.ui.profile.LicensesScreen(onBack = { nav.popBackStack() }) }
                screen("actorRank") { app.iduanju.ui.profile.ActorRankScreen(onBack = { nav.popBackStack() }) }
                screen("actor/{id}?name={name}&avatar={avatar}&role={role}&intro={intro}",
                    arguments = listOf("name", "avatar", "role", "intro").map { navArgument(it) { defaultValue = "" } }) { entry ->
                    val args = requireNotNull(entry.arguments)
                    val actor = app.iduanju.core.hongguo.HGCelebrity(requireNotNull(args.getString("id")),
                        args.getString("name").orEmpty(), args.getString("avatar").orEmpty(),
                        args.getString("role").orEmpty(), args.getString("intro").orEmpty())
                    app.iduanju.ui.profile.ActorProfileScreen(actor, onBack = { nav.popBackStack() }, onOpen = { nav.navigate("detail/$it") })
                }
                screen("actorSearch/{name}") { e -> SearchScreen(onOpenDetail = { nav.navigate("detail/$it") }, initialQuery = e.arguments!!.getString("name")!!) }
                screen("board/{genre}/{sub}") { e -> app.iduanju.ui.category.BoardScreen(app.iduanju.ui.home.BoardSpec(app.iduanju.core.hongguo.HGRankGenre.valueOf(e.arguments!!.getString("genre")!!), app.iduanju.core.hongguo.HGRankSub.valueOf(e.arguments!!.getString("sub")!!)), onBack = { nav.popBackStack() }, onOpenDetail = { nav.navigate("detail/$it") }) }
                screen("detail/{sid}", arguments = listOf(navArgument("sid") { type = NavType.StringType })) { e ->
                    val sid = e.arguments!!.getString("sid")!!
                    DetailScreen(
                        seriesId = sid,
                        onBack = { nav.popBackStack() },
                        onPlay = { s, ep -> nav.navigate("player/$s/$ep") },
                        onOpenSeries = { nav.navigate("detail/$it") },
                    )
                }
                screen("manga/{id}") { e -> app.iduanju.manga.MangaDetailScreen(e.arguments!!.getString("id")!!, onBack = { nav.popBackStack() },
                        onRead = { id, i, p -> nav.navigate("mangaRead/" + android.net.Uri.encode(id) + "/$i/$p") }) }
                screen("mangaRead/{id}/{i}/{p}", arguments = listOf(navArgument("i") { type = NavType.IntType }, navArgument("p") { type = NavType.IntType })) { e ->
                    app.iduanju.manga.MangaReaderScreen(e.arguments!!.getString("id")!!, e.arguments!!.getInt("i"), e.arguments!!.getInt("p"), onBack = { nav.popBackStack() }) }
                screen(
                    "player/{sid}/{ep}",
                    arguments = listOf(
                        navArgument("sid") { type = NavType.StringType },
                        navArgument("ep") { type = NavType.IntType },
                    ),
                    enterTransition = { if (predictiveBack.neutralTransitions) EnterTransition.None else androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) },
                    exitTransition = { if (predictiveBack.neutralTransitions) ExitTransition.None else androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)) },
                    popEnterTransition = { if (predictiveBack.neutralTransitions) EnterTransition.None else androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) },
                    popExitTransition = { if (predictiveBack.neutralTransitions) ExitTransition.None else androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)) },
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
            enter = androidx.compose.animation.fadeIn(ExpressiveMotion.effects()) +
                androidx.compose.animation.slideInVertically(ExpressiveMotion.spatialDefault()) { it / 2 },
            exit = androidx.compose.animation.fadeOut(ExpressiveMotion.effects()) +
                androidx.compose.animation.slideOutVertically(ExpressiveMotion.spatialFast()) { it / 2 },
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
 *
 * Expressive touches: the indicator follows the finger on [ExpressiveMotion.spatialFast] with a subtle jelly
 * stretch, and every tab dips with a crisp tactile press scale.
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
    val expressive = GlassStylePref.style == GlassStyle.EXPRESSIVE
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
    val indicatorTarget = if (isDragging) dragFraction else selected.toFloat()
    val indicatorFraction by animateFloatAsState(
        targetValue = indicatorTarget,
        animationSpec = ExpressiveMotion.spatialFast(),
        label = "dockIndicator",
    )
    val indicatorScale by animateFloatAsState(
        targetValue = if (isDragging) 1.04f else 1f,
        animationSpec = ExpressiveMotion.spatialFast(),
        label = "dockIndicatorScale",
    )
    // Jelly: the pill stretches horizontally (and slims a touch) while it lags behind its target.
    val stretch = (kotlin.math.abs(indicatorTarget - indicatorFraction) * 0.22f).coerceAtMost(0.16f)

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
                                            scaleX = indicatorScale * (1f + stretch)
                                            scaleY = indicatorScale * (1f - stretch * 0.35f)
                                        }
                                        .then(
                                            if (liquid || expressive) Modifier.glassPill(DockPillShape, selected = true, neutralSelected = true)
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
                                            .expressivePress(0.92f, enabled = !isDragging)
                                            .clip(DockPillShape)
                                            .pointerInput(i) {
                                                detectTapGestures(onTap = { latestOnSelect(i) })
                                            }
                                            .semantics {
                                                role = androidx.compose.ui.semantics.Role.Tab
                                                this.selected = on
                                                onClick(label) { latestOnSelect(i); true }
                                            },
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

// MARK: navigation motion + native predictive back

/** Order of the root destinations along the dock (search sits right of the tabs). */
private val RootTabOrder = mapOf("home" to 0, "tvbox" to 1, "category" to 2, "profile" to 3, "search" to 4)

/** +1 / -1 when both ends are root tabs (a tab switch, even when it is technically a pop), else null (push / pop). */
private fun tabDirection(from: NavBackStackEntry, to: NavBackStackEntry): Int? {
    val a = RootTabOrder[from.destination.route] ?: return null
    val b = RootTabOrder[to.destination.route] ?: return null
    return if (b >= a) 1 else -1
}

/**
 * Live state of an in-flight system back swipe on the navigation stack.
 *
 * [backProgress] 0..1 (springs back below 0 slightly on cancel), [backSwipeEdge] is [androidx.activity.BackEventCompat.EDGE_LEFT]
 * or `EDGE_RIGHT`, [backTouchY] the finger's live Y, [isPredictingBack] true while both pages are shown.
 */
@Stable
internal class PredictiveBackState {
    var backProgress by mutableFloatStateOf(0f)
    var backSwipeEdge by mutableIntStateOf(androidx.activity.BackEventCompat.EDGE_LEFT)
    var backTouchY by mutableFloatStateOf(0f)
    var backStartTouchY by mutableFloatStateOf(0f)
    var isPredictingBack by mutableStateOf(false)
    /** 0..1 exit of the released card before the route pops underneath it. */
    var commitProgress by mutableFloatStateOf(0f)
    /** NavHost transitions resolve to None while set: the pages are only held, never animated by NavHost. */
    var neutralTransitions by mutableStateOf(false)
    var committing by mutableStateOf(false)
    var topEntryId by mutableStateOf<String?>(null)
    var previousEntryId by mutableStateOf<String?>(null)
    /** The entry a committed swipe popped: stays invisible until NavHost disposes it. */
    var dismissedEntryId by mutableStateOf<String?>(null)
    var gestureToken = 0

    fun reset() {
        isPredictingBack = false
        neutralTransitions = false
        committing = false
        backProgress = 0f
        commitProgress = 0f
        topEntryId = null
        previousEntryId = null
    }

    /** NavHost pops / un-seeks a frame or two after the dispatch; keep the neutral transitions until it has. */
    suspend fun awaitSettleFrames() = repeat(4) { androidx.compose.runtime.withFrameNanos { } }

    suspend fun resetAfterFrames(token: Int) {
        awaitSettleFrames()
        if (gestureToken == token) reset()
    }
}

internal class PredictiveBackHost(val state: PredictiveBackState, val realOwner: OnBackPressedDispatcherOwner?)

internal val LocalPredictiveBackHost = androidx.compose.runtime.staticCompositionLocalOf<PredictiveBackHost?> { null }

/** NavHost's private dispatcher: only receives the gestures RootScreen forwards to it. */
private class ChildBackDispatcherOwner(private val host: LifecycleOwner) : OnBackPressedDispatcherOwner {
    override val onBackPressedDispatcher = OnBackPressedDispatcher()
    override val lifecycle: Lifecycle get() = host.lifecycle
}

/** Keeps RootScreen's back callback registered once, at the bottom of the activity's callback stack. */
private class AlwaysResumedLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
    override val lifecycle: Lifecycle get() = registry
}

/** [composable] whose content is wrapped in [PredictiveBackScene]. */
private fun NavGraphBuilder.screen(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    enterTransition: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition?)? = null,
    exitTransition: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition?)? = null,
    popEnterTransition: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition?)? = enterTransition,
    popExitTransition: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition?)? = exitTransition,
    sizeTransform: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> SizeTransform?)? = null,
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) = composable(
    route = route,
    arguments = arguments,
    enterTransition = enterTransition,
    exitTransition = exitTransition,
    popEnterTransition = popEnterTransition,
    popExitTransition = popExitTransition,
    sizeTransform = sizeTransform,
) { entry ->
    val scope = this
    PredictiveBackScene(entry) { scope.content(entry) }
}

/**
 * One destination of the stack. During a predictive back swipe the top page becomes a shrinking, rounded, shadowed
 * card (1.0 -> 0.88, 0 -> 32 dp corners, 28 dp drift away from the swipe edge, a subtle tilt following the finger)
 * while the previous page underneath rises 0.95 -> 1.0 and its 22 % scrim lifts. Everything is read in the layer /
 * draw phase, so a swipe never recomposes the pages. The screen gets the real activity dispatcher back, so its own
 * BackHandlers (open panels, players, readers) take precedence over the stack pop.
 */
@Composable
private fun PredictiveBackScene(entry: NavBackStackEntry, content: @Composable () -> Unit) {
    val host = LocalPredictiveBackHost.current
    if (host == null) { content(); return }
    val pb = host.state
    val id = entry.id
    val bg = IdjThemeColors.bg
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val dismissed = pb.dismissedEntryId == id
                val isTop = dismissed || (pb.isPredictingBack && pb.topEntryId == id)
                val isPrev = !dismissed && pb.isPredictingBack && pb.previousEntryId == id
                if (dismissed && !pb.committing) {
                    alpha = 0f
                } else if (isTop) {
                    val p = pb.backProgress
                    val pc = p.coerceIn(0f, 1f)
                    val k = pb.commitProgress.coerceIn(0f, 1f)
                    val dir = if (pb.backSwipeEdge == androidx.activity.BackEventCompat.EDGE_LEFT) 1f else -1f
                    val s = 1f - p * 0.12f - k * 0.06f
                    scaleX = s
                    scaleY = s
                    translationX = dir * (p * 28.dp.toPx() + k * 40.dp.toPx())
                    // Vertical follow: the card leans toward the finger, capped at (height / 20 - 8 dp).
                    val maxShift = (size.height / 20f - 8.dp.toPx()).coerceAtLeast(0f)
                    val follow = ((pb.backTouchY - pb.backStartTouchY) * 0.16f).coerceIn(-maxShift, maxShift) *
                        (pc * 4f).coerceAtMost(1f)
                    translationY = follow
                    if (maxShift > 0f) rotationX = -(follow / maxShift) * 1.6f
                    cameraDistance = 14f * density
                    alpha = 1f - k
                    shape = RoundedCornerShape((pc * 32f + k * 4f).dp)
                    clip = pc > 0.001f || k > 0f
                    shadowElevation = (pc / 0.3f).coerceAtMost(1f) * 18.dp.toPx() * (1f - k)
                    ambientShadowColor = Color.Black
                    spotShadowColor = Color.Black
                } else if (isPrev) {
                    val pc = pb.backProgress.coerceIn(0f, 1f)
                    val k = pb.commitProgress.coerceIn(0f, 1f)
                    val s = 0.95f + pc * 0.05f
                    val settled = s + (1f - s) * k
                    scaleX = settled
                    scaleY = settled
                }
            }
            .drawWithContent {
                val isTop = pb.dismissedEntryId == id || (pb.isPredictingBack && pb.topEntryId == id)
                val isPrev = pb.dismissedEntryId != id && pb.isPredictingBack && pb.previousEntryId == id
                // Pages are transparent over the window ground: the floating card carries its own page colour.
                if (isTop) drawRect(bg)
                drawContent()
                if (isPrev) {
                    val scrim = (1f - pb.backProgress.coerceIn(0f, 1f)) * 0.22f * (1f - pb.commitProgress.coerceIn(0f, 1f))
                    if (scrim > 0.001f) drawRect(Color.Black.copy(alpha = scrim))
                }
            },
    ) {
        val owner = host.realOwner
        if (owner != null) {
            androidx.compose.runtime.CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides owner) { content() }
        } else {
            content()
        }
    }
}
