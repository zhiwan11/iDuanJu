package app.iduanju.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.chrisbanes.haze.hazeSource
import app.iduanju.ui.category.CategoryScreen
import app.iduanju.ui.components.GlassNavigationRail
import app.iduanju.ui.components.GlassTabBar
import app.iduanju.ui.components.LayoutMetrics
import app.iduanju.ui.components.LocalLayoutMetrics
import app.iduanju.ui.detail.DetailScreen
import app.iduanju.ui.home.HomeScreen
import app.iduanju.ui.player.PlayerScreen
import app.iduanju.ui.profile.ProfileScreen
import app.iduanju.ui.search.SearchScreen
import app.iduanju.ui.theme.IdjThemeColors

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
    val back = nav.currentBackStackEntryAsState().value
    val route = back?.destination?.route ?: "home"
    LaunchedEffect(route) { when (route) { "home" -> tab = 0; "tvbox" -> tab = 1; "category" -> tab = 2; "profile" -> tab = 3 } }
    val atRoot = route in setOf("home", "tvbox", "search", "category", "profile")
    /** Backdrop for the floating tab bar's blur (Haze): everything the NavHost draws. */
    val haze = androidx.compose.runtime.remember { dev.chrisbanes.haze.HazeState() }
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
        val onSelectTab: (Int) -> Unit = {
            tab = it
            nav.navigate(listOf("home", "tvbox", "category", "profile")[it]) {
                popUpTo(nav.graph.startDestinationId) { saveState = true }
                launchSingleTop = true
                restoreState = true
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
                nav.navigate("home") { popUpTo(nav.graph.startDestinationId) { saveState = true }; launchSingleTop = true; restoreState = true }
            } else nav.navigate(r)
        }
        androidx.compose.runtime.CompositionLocalProvider(
            LocalNavigate provides navigate,
            LocalLayoutMetrics provides metrics,
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
                        haze = haze,
                    )
                }
                NavHost(nav, startDestination = "home", modifier = Modifier.weight(1f).fillMaxHeight().hazeSource(haze)) {
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
                composable("search") { SearchScreen(onOpenDetail = { nav.navigate("detail/$it") }) }
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
        if (showChromeNav && !metrics.useRail) {
            GlassTabBar(
                selected = tab,
                items = navItems,
                onSelect = onSelectTab,
                searchSelected = route == "search",
                onSearch = onSelectSearch,
                searchIcon = searchIcon,
                haze = haze,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 4.dp),
            )
        }
        app.iduanju.update.UpdateDialog(updater)
    }
}
