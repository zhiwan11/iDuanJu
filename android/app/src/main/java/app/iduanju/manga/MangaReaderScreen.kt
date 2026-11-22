package app.iduanju.manga

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.BatteryManager
import android.os.Build
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import app.iduanju.IdjApp
import app.iduanju.core.manga.MangaDetail
import app.iduanju.ui.components.glassPill
import app.iduanju.ui.components.glassSurface
import app.iduanju.ui.theme.IdjThemeColors
import coil.compose.SubcomposeAsyncImage
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val HudCard = Color(0xFF232327)
private val HudShape = RoundedCornerShape(24.dp)
private val HudDim = Color.White.copy(0.62f)
/** Text colour for loading / error / 本话完 drawn on the page background (dark on 白色 / 纹理 / light 跟随系统). */
private val LocalPageInk = compositionLocalOf { Color.White }

private enum class ReaderPanel(val label: String) { CATALOG("目录"), IMAGE("画面"), TURN("翻页"), SETTINGS("设置") }
private enum class TapAction { PREV, NEXT, MENU }

/** Live handles of whichever reading surface is composed, so taps / volume keys / 自动阅读 / the scrubber can drive it. */
private class ReaderNav {
    var list: LazyListState? = null
    var pager: PagerState? = null
    /** 仿真翻页 / 覆盖 / 淡入 viewer (平滑 uses [pager]). */
    var book: BookPagerState? = null
    val pagedPage: Int? get() = book?.currentPage ?: pager?.currentPage
    val pagedCount: Int? get() = book?.pageCount ?: pager?.pageCount
}

private tailrec fun Context.findActivity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.findActivity(); else -> null }

private fun tapAction(layout: MangaTapLayout, x: Float, y: Float, w: Float, h: Float): TapAction {
    val col = (x / w * 3).toInt().coerceIn(0, 2); val row = (y / h * 3).toInt().coerceIn(0, 2)
    return when (layout) {
        MangaTapLayout.OFF -> TapAction.MENU
        MangaTapLayout.LR -> when (col) { 0 -> TapAction.PREV; 2 -> TapAction.NEXT; else -> TapAction.MENU }
        MangaTapLayout.L_SHAPE -> when { col == 1 && row == 1 -> TapAction.MENU; col == 0 || (col == 1 && row == 0) -> TapAction.PREV; else -> TapAction.NEXT }
    }
}

/**
 * Full-featured manga reader: 条漫 / 左翻页 / 右翻页(日漫) / 双页 modes, pinch + double-tap zoom, 九宫格 tap zones,
 * 音量键翻页, 自动阅读, floating HUD with page scrubber + preview, and 目录 / 画面 / 翻页 / 设置 console tabs.
 */
@Composable
fun MangaReaderScreen(id: String, startIndex: Int, startPage: Int, onBack: () -> Unit) {
    val store = IdjApp.instance.manga
    val d = MangaReaderState.detail?.takeIf { it.item.id == id && it.chapters.isNotEmpty() } ?: run { LaunchedEffect(Unit) { onBack() }; return }
    val P = MangaReaderPrefs
    val p = store.provider(d.item.source)
    val ctx = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val loader = ctx.imageLoader

    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, d.chapters.lastIndex)) }
    /** Page to land on when a chapter / mode (re)opens; −1 = last page (turning back into the previous chapter). */
    var resume by remember { mutableIntStateOf(startPage) }
    var reload by remember { mutableIntStateOf(0) }
    var pages by remember(index) { mutableStateOf<List<String>>(emptyList()) }
    var error by remember(index) { mutableStateOf<String?>(null) }
    var current by remember(index) { mutableIntStateOf(0) }
    val wide = remember(index) { mutableStateMapOf<Int, Boolean>() }
    var chrome by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf<ReaderPanel?>(null) }
    var autoRead by remember { mutableStateOf(false) }
    val zoom = remember { ReaderZoomState() }
    val zoomed by remember { derivedStateOf { zoom.zoomed } }
    val nav = remember { ReaderNav() }
    val focus = remember { FocusRequester() }
    // Local backdrop of the pages: the floating HUD / console are siblings on top, so they refract the real artwork
    // (液态玻璃) or blur it (毛玻璃) exactly like the rest of the app's glass.
    val backdrop = rememberLayerBackdrop { drawRect(Color.Black); drawContent() }
    val chapter = d.chapters[index]
    val mode = P.mode
    val rtl = mode == MangaReadMode.RTL || (mode == MangaReadMode.DUAL && P.dualRtl)
    val filter = remember(P.invert, P.grayscale) { readerColorFilter(P.invert, P.grayscale) }
    // 图片背景
    val darkTheme = IdjThemeColors.dark
    val pageBg = P.pageBg
    val pageColor = readerPageColor(pageBg, darkTheme, P.paperStyle)
    val pageInk = if (readerPageIsLight(pageBg, darkTheme)) Color(0xFF26262A) else Color.White
    val curlPaper = when {
        P.invert -> Color(0xFF1C1C20)
        pageBg == MangaPageBg.TEXTURE -> Color(P.paperStyle.tint)
        else -> Color(0xFFF3F0EA)
    }
    val slots by remember(pages, mode, P.splitWide, P.dualCoverOffset) {
        derivedStateOf { buildReaderSlots(pages.size, mode, wide, P.splitWide && mode != MangaReadMode.DUAL, P.dualCoverOffset) }
    }

    fun goChapter(i: Int, last: Boolean = false) {
        if (i !in d.chapters.indices) return
        resume = if (last) -1 else 0; index = i; zoom.reset()
    }
    fun setMode(m: MangaReadMode) { if (m != P.mode) { resume = current; zoom.reset(); P.updateMode(m) } }

    suspend fun seek(page: Int) {
        if (pages.isEmpty()) return
        val t = page.coerceIn(0, pages.lastIndex)
        if (mode == MangaReadMode.WEBTOON) { nav.list?.scrollToItem(t); return }
        val slot = slots.indexOfFirst { t in it.pages }.takeIf { it >= 0 } ?: return
        val book = nav.book
        if (book != null) book.jumpTo(slot) else nav.pager?.scrollToPage(slot)
    }
    suspend fun turn(forward: Boolean) {
        if (pages.isEmpty()) return
        if (mode == MangaReadMode.WEBTOON) {
            val l = nav.list ?: return
            if (forward && !l.canScrollForward) { goChapter(index + 1); return }
            if (!forward && !l.canScrollBackward) { goChapter(index - 1, last = true); return }
            val step = zoom.size.height * 0.85f / zoom.scale * if (forward) 1 else -1
            if (P.turnAnim) l.animateScrollBy(step) else l.scrollBy(step)
        } else {
            nav.book?.let { book ->
                // 仿真翻页 / 覆盖 / 淡入: animated 340 ms turn; past either end changes chapter
                if (!book.turn(scope, forward, animate = P.turnAnim)) {
                    if (forward) goChapter(index + 1) else goChapter(index - 1, last = true)
                }
                return
            }
            val pg = nav.pager ?: return
            val target = pg.currentPage + if (forward) 1 else -1
            when {
                target >= pg.pageCount -> goChapter(index + 1)
                target < 0 -> goChapter(index - 1, last = true)
                P.turnAnim -> pg.animateScrollToPage(target)
                else -> pg.scrollToPage(target)
            }
        }
    }
    fun onTap(pos: Offset) {
        if (panel != null) { panel = null; return }
        if (zoom.size.width == 0) return
        var a = tapAction(P.tapLayout, pos.x, pos.y, zoom.size.width.toFloat(), zoom.size.height.toFloat())
        if (P.tapInvert xor (rtl && mode.paged)) a = when (a) { TapAction.PREV -> TapAction.NEXT; TapAction.NEXT -> TapAction.PREV; else -> a }
        when (a) {
            TapAction.MENU -> chrome = !chrome
            else -> { chrome = false; scope.launch { turn(a == TapAction.NEXT) } }
        }
    }

    LaunchedEffect(index, reload) {
        error = null
        runCatching { withContext(Dispatchers.IO) { p.pages(d.item, chapter) } }
            .onSuccess { pages = it; if (it.isEmpty()) error = "本话没有图片" }
            .onFailure { error = it.message ?: "加载失败" }
    }
    LaunchedEffect(current, pages.size) { if (pages.isNotEmpty()) store.record(d.item, chapter, current, pages.size) }
    // 预加载：warm the disk cache for the next N pages and learn which ones are wide spreads (宽页切分 / 双页).
    LaunchedEffect(current, pages, P.preload) {
        for (k in 1..P.preload) {
            val i = current + k
            if (i !in pages.indices) break
            val req = ImageRequest.Builder(ctx).data(pages[i]).apply { p.imageHeaders.forEach { (h, v) -> addHeader(h, v) } }
                .size(720).memoryCachePolicy(CachePolicy.DISABLED)
                .listener(onSuccess = { _, r -> val dr = r.drawable; wide[i] = dr.intrinsicWidth > dr.intrinsicHeight * 1.05f })
                .build()
            loader.enqueue(req)
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(panel, chrome) { runCatching { focus.requestFocus() } }

    // 沉浸氛围 环境音效: one player for the reader's lifetime; paused with the activity, released on exit.
    val ambientAudio = remember { MangaAmbientAudioPlayer() }
    DisposableEffect(ambientAudio) {
        ambientAudio.attachContext(ctx)
        onDispose { ambientAudio.release() }
    }
    /** Plays page_flip.wav on every paged turn when 翻页音效 or the 沉浸氛围 环境音效 is on. */
    fun pageFlipSound() {
        if (P.pageFlipSound || (P.atmosphere != MangaAtmosphere.OFF && P.atmosphereSound)) ambientAudio.playPageFlip()
    }
    LaunchedEffect(P.atmosphere, P.atmosphereSound, P.atmosphereVolume) {
        ambientAudio.setState(P.atmosphere, P.atmosphereSound, P.atmosphereVolume)
    }
    val lifecycle = view.findViewTreeLifecycleOwner()?.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> ambientAudio.pause()
                Lifecycle.Event.ON_RESUME -> ambientAudio.setState(P.atmosphere, P.atmosphereSound, P.atmosphereVolume)
                else -> {}
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    // 屏幕常亮
    DisposableEffect(P.keepScreenOn) { view.keepScreenOn = P.keepScreenOn; onDispose { view.keepScreenOn = false } }
    // 沉浸式：hide the system bars with the HUD unless 显示系统状态栏 is on.
    DisposableEffect(chrome, P.showSystemBar) {
        val w = ctx.findActivity()?.window
        val ctl = w?.let { WindowCompat.getInsetsController(it, view) }
        ctl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (chrome) ctl?.show(WindowInsetsCompat.Type.systemBars())
        else { ctl?.hide(WindowInsetsCompat.Type.navigationBars()); if (P.showSystemBar) ctl?.show(WindowInsetsCompat.Type.statusBars()) else ctl?.hide(WindowInsetsCompat.Type.statusBars()) }
        onDispose { ctl?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    // 自动阅读 — pauses while the HUD is open.
    LaunchedEffect(autoRead, chrome, mode, index, pages.isEmpty()) {
        if (!autoRead || chrome || pages.isEmpty()) return@LaunchedEffect
        if (mode == MangaReadMode.WEBTOON) {
            var last = 0L
            while (isActive) {
                val atEnd = withFrameNanos { t ->
                    val l = nav.list
                    if (last != 0L && l != null) {
                        val dt = (t - last) / 1e9f
                        l.dispatchRawDelta((30f + 570f * P.autoSpeed) * density.density * dt)
                    }
                    last = t
                    l != null && !l.canScrollForward
                }
                if (atEnd) { if (index + 1 < d.chapters.size) goChapter(index + 1) else autoRead = false; break }
            }
        } else {
            while (isActive) {
                delay((12000 - 10000 * P.autoSpeed).toLong())
                val pgNow = nav.pagedPage ?: break
                val pgCount = nav.pagedCount ?: break
                if (pgNow + 1 >= pgCount && index + 1 >= d.chapters.size) { autoRead = false; break }
                turn(true)
            }
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black).testTag("manga.reader")
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent { e ->
                if (!P.volumeKeys) return@onPreviewKeyEvent false
                when (e.nativeKeyEvent.keyCode) {
                    AndroidKeyEvent.KEYCODE_VOLUME_DOWN, AndroidKeyEvent.KEYCODE_VOLUME_UP -> {
                        if (e.type == KeyEventType.KeyDown) scope.launch { turn(e.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_VOLUME_DOWN) }
                        true
                    }
                    else -> false
                }
            }
    ) {
        if (pages.isEmpty()) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (error != null) {
                    Text(error!!, color = Color.White)
                    TextButton({ reload++ }) { Text("重试", color = IdjThemeColors.accent) }
                } else {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                    Text(chapter.title, color = HudDim, fontSize = 12.sp)
                }
            }
            HudCircle(Icons.AutoMirrored.Filled.ArrowBack, "返回", Modifier.statusBarsPadding().padding(14.dp), onClick = onBack)
        } else {
            Box(
                Modifier.fillMaxSize()
                    .layerBackdrop(backdrop)
                    .background(pageColor)
                    .onSizeChanged { zoom.size = it }
                    .readerGestures(zoom,
                        onTap = ::onTap,
                        onDoubleTap = { pos -> scope.launch { zoom.animateTo(if (zoom.zoomed) 1f else 2.2f, pos) } },
                        onOverflowY = { dy -> nav.list?.dispatchRawDelta(-dy / zoom.scale) })
            ) {
                Box(Modifier.fillMaxSize().graphicsLayer { scaleX = zoom.scale; scaleY = zoom.scale; translationX = zoom.offset.x; translationY = zoom.offset.y }) {
                    CompositionLocalProvider(LocalPageInk provides pageInk) {
                    key(index, mode) {
                        if (mode == MangaReadMode.WEBTOON) {
                            val list = rememberLazyListState(initialFirstVisibleItemIndex = (if (resume < 0) pages.lastIndex else resume).coerceIn(0, pages.lastIndex))
                            DisposableEffect(list) { nav.list = list; nav.pager = null; nav.book = null; onDispose { if (nav.list === list) nav.list = null } }
                            LaunchedEffect(list) { snapshotFlow { list.firstVisibleItemIndex }.collect { current = it.coerceAtMost(pages.lastIndex) } }
                            LaunchedEffect(list) { list.interactionSource.interactions.filterIsInstance<DragInteraction.Start>().collect { chrome = false; panel = null } }
                            LazyColumn(Modifier.fillMaxSize(), state = list, verticalArrangement = Arrangement.spacedBy(if (P.webtoonGap) 6.dp else 0.dp)) {
                                itemsIndexed(pages, key = { i, u -> "$i$u" }) { i, u ->
                                    ReaderPageFrame(pageBg, Modifier.fillMaxWidth()) {
                                        ReaderImage(u, p.imageHeaders, Modifier.fillMaxWidth(), webtoon = true, filter = filter, pageNo = i + 1)
                                    }
                                }
                                item(key = "end") { ChapterEnd(d, index) { goChapter(it) } }
                            }
                        } else if (P.pageAnim != MangaPageAnim.SLIDE) {
                            // 仿真翻页 / 覆盖 / 淡入
                            val initial = remember { val target = if (resume < 0) pages.lastIndex else resume; slots.indexOfFirst { target in it.pages }.coerceAtLeast(0) }
                            val slotsNow by rememberUpdatedState(slots)
                            val book = rememberBookPagerState(initial) { slotsNow.size + 1 }
                            DisposableEffect(book) { nav.book = book; nav.pager = null; nav.list = null; onDispose { if (nav.book === book) nav.book = null } }
                            LaunchedEffect(book) { snapshotFlow { book.currentPage }.collect { pg -> slotsNow.getOrNull(pg)?.let { current = it.pages.first() }; zoom.reset() } }
                            LaunchedEffect(book) { snapshotFlow { book.currentPage }.drop(1).collect { pageFlipSound() } }
                            LaunchedEffect(book) { snapshotFlow { book.dragStarts }.collect { if (it > 0) { chrome = false; panel = null } } }
                            // Keep the reader on the same picture when a wide page is discovered and the slots re-flow.
                            LaunchedEffect(slots) {
                                if (book.currentPage >= slots.size || book.isTurning) return@LaunchedEffect
                                val t = slots.indexOfFirst { current in it.pages }
                                if (t >= 0 && t != book.currentPage && slots.getOrNull(book.currentPage)?.pages?.contains(current) != true) book.jumpTo(t)
                            }
                            BookPageCurlViewer(book, P.pageAnim, rtl, enabled = !zoomed, paper = curlPaper, pageBackground = pageColor) { pg ->
                                val slot = slots.getOrNull(pg)
                                ReaderPageFrame(pageBg, Modifier.fillMaxSize(), immersiveUrl = slot?.let { pages[it.pages.first()] }, headers = p.imageHeaders) {
                                    if (slot == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ChapterEnd(d, index) { goChapter(it) } }
                                    else SlotView(slot, pages, p.imageHeaders, rtl, filter) { i, w, h -> if (slot.half == 0) wide[i] = w > h * 1.05f }
                                }
                            }
                        } else {
                            val initial = remember { val target = if (resume < 0) pages.lastIndex else resume; slots.indexOfFirst { target in it.pages }.coerceAtLeast(0) }
                            val pager = rememberPagerState(initialPage = initial) { slots.size + 1 }
                            val slotsNow by rememberUpdatedState(slots)
                            DisposableEffect(pager) { nav.pager = pager; nav.list = null; nav.book = null; onDispose { if (nav.pager === pager) nav.pager = null } }
                            LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { pg -> slotsNow.getOrNull(pg)?.let { current = it.pages.first() } } }
                            LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.drop(1).collect { pageFlipSound() } }
                            LaunchedEffect(pager) { pager.interactionSource.interactions.filterIsInstance<DragInteraction.Start>().collect { chrome = false; panel = null } }
                            LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { zoom.reset() } }
                            // Keep the reader on the same picture when a wide page is discovered and the slots re-flow.
                            LaunchedEffect(slots) {
                                if (pager.currentPage >= slots.size || pager.isScrollInProgress) return@LaunchedEffect
                                val t = slots.indexOfFirst { current in it.pages }
                                if (t >= 0 && t != pager.currentPage && slots.getOrNull(pager.currentPage)?.pages?.contains(current) != true) pager.scrollToPage(t)
                            }
                            HorizontalPager(pager, Modifier.fillMaxSize(), reverseLayout = rtl, userScrollEnabled = !zoomed, beyondViewportPageCount = 1) { pg ->
                                val slot = slots.getOrNull(pg)
                                CompositionLocalProvider(LocalReaderPageActive provides (pg == pager.currentPage)) {
                                    ReaderPageFrame(pageBg, Modifier.fillMaxSize().background(pageColor), immersiveUrl = slot?.let { pages[it.pages.first()] }, headers = p.imageHeaders) {
                                        if (slot == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ChapterEnd(d, index) { goChapter(it) } }
                                        else SlotView(slot, pages, p.imageHeaders, rtl, filter) { i, w, h -> if (slot.half == 0) wide[i] = w > h * 1.05f }
                                    }
                                }
                            }
                        }
                    }
                    }
                }
                // 护眼 / 亮度 overlays sit over the pages but under the HUD.
                if (P.warm > 0.01f) Box(Modifier.fillMaxSize().background(Color(0xFFFF9A2E).copy(alpha = P.warm * 0.32f)))
                if (P.brightness < 0.99f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = (1f - P.brightness) * 0.85f)))
                // 沉浸氛围: draw-only, inside the backdrop so the glass HUD refracts the rain / snow too.
                MangaAtmosphereOverlay(P.atmosphere)
            }
        }

        if (P.showFooter && !chrome && pages.isNotEmpty()) ReaderFooter(chapter.title, current, pages.size, Modifier.align(Alignment.BottomEnd))

        // ── Top HUD ──
        AnimatedVisibility(chrome, enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp)
                    .glassSurface(HudShape, backdrop = backdrop, dark = true, base = HudCard, shadowRadius = 14.dp, activeOverlay = Color.Black.copy(0.22f))
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HudCircle(Icons.AutoMirrored.Filled.ArrowBack, "返回", onClick = onBack)
                Column(Modifier.weight(1f)) {
                    Text(d.item.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(chapter.title, color = HudDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (pages.isNotEmpty()) Text("${current + 1} / ${pages.size}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.glassPill(CircleShape, dark = true).padding(horizontal = 10.dp, vertical = 5.dp).testTag("manga.reader.page"))
                val fav = store.isFavorite(d.item)
                HudCircle(if (fav) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, if (fav) "取消收藏" else "收藏", tint = if (fav) IdjThemeColors.accent else Color.White) { store.toggleFavorite(d.item) }
                Box {
                    var open by remember { mutableStateOf(false) }
                    Text(mode.short, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.glassPill(CircleShape, selected = true, dark = true).clickable { open = true }.padding(horizontal = 11.dp, vertical = 6.dp).testTag("manga.reader.mode"))
                    DropdownMenu(open, { open = false }) {
                        MangaReadMode.entries.forEach { m ->
                            DropdownMenuItem(text = { Text(m.label) }, onClick = { open = false; setMode(m) },
                                trailingIcon = { if (m == mode) Icon(Icons.Filled.Check, null) })
                        }
                    }
                }
            }
        }

        // ── Bottom console ──
        AnimatedVisibility(chrome, Modifier.align(Alignment.BottomCenter), enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp)
                    // An open panel gets a denser tint so its switches stay readable over busy artwork.
                    .glassSurface(HudShape, backdrop = backdrop, dark = true, base = HudCard, shadowRadius = 18.dp,
                        activeOverlay = Color.Black.copy(if (panel != null) 0.48f else 0.24f))
                    .clickable(interactionSource = null, indication = null) {}.padding(top = 6.dp)
            ) {
                panel?.let { pn ->
                    Box(Modifier.fillMaxWidth().heightIn(max = 380.dp).padding(horizontal = 16.dp, vertical = 6.dp)) {
                        when (pn) {
                            ReaderPanel.CATALOG -> CatalogPanel(d, index) { panel = null; goChapter(it) }
                            ReaderPanel.IMAGE -> ImagePanel()
                            ReaderPanel.TURN -> TurnPanel(mode, autoRead, ::setMode, setPageAnim = { a -> if (a != P.pageAnim) { resume = current; zoom.reset(); P.updatePageAnim(a) } }) { on -> autoRead = on; if (on) { panel = null; chrome = false } }
                            ReaderPanel.SETTINGS -> SettingsPanel()
                        }
                    }
                    HorizontalDivider(color = Color.White.copy(0.08f))
                }
                Scrubber(pages, current, index, d, rtl, p.imageHeaders, filter, onPrev = { goChapter(index - 1) }, onNext = { goChapter(index + 1) }) { t -> scope.launch { seek(t) } }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    ReaderPanel.entries.forEach { pn ->
                        val icon = when (pn) {
                            ReaderPanel.CATALOG -> Icons.AutoMirrored.Filled.FormatListBulleted
                            ReaderPanel.IMAGE -> Icons.Filled.Brightness6
                            ReaderPanel.TURN -> Icons.Filled.TouchApp
                            ReaderPanel.SETTINGS -> Icons.Filled.Settings
                        }
                        val on = panel == pn
                        Column(Modifier.weight(1f).padding(horizontal = 3.dp)
                            .then(if (on) Modifier.glassPill(RoundedCornerShape(14.dp), selected = true, dark = true) else Modifier.clip(RoundedCornerShape(14.dp)))
                            .clickable { panel = if (on) null else pn }.padding(vertical = 6.dp).testTag("manga.reader.tab." + pn.name.lowercase()),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(icon, null, tint = if (on) IdjThemeColors.accent else Color.White, modifier = Modifier.size(22.dp))
                            Text(pn.label, color = if (on) IdjThemeColors.accent else HudDim, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        if (autoRead && !chrome) {
            Box(Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(14.dp).size(38.dp)
                .glassSurface(CircleShape, backdrop = backdrop, dark = true, base = HudCard, shadowRadius = 8.dp, activeOverlay = Color.Black.copy(0.2f)).clickable { autoRead = false },
                contentAlignment = Alignment.Center) { Icon(Icons.Filled.Pause, "停止自动阅读", tint = Color.White, modifier = Modifier.size(18.dp)) }
        }
    }
}

@Composable
private fun HudCircle(icon: ImageVector, desc: String, modifier: Modifier = Modifier, tint: Color = Color.White, onClick: () -> Unit) {
    Box(modifier.size(40.dp).glassPill(CircleShape, dark = true).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = tint, modifier = Modifier.size(21.dp))
    }
}

/** Page scrubber with 上一话 / 下一话 and a floating thumbnail preview while dragging; mirrored in 日漫 order. */
@Composable
private fun Scrubber(
    pages: List<String>, current: Int, index: Int, d: MangaDetail, rtl: Boolean, headers: Map<String, String>, filter: ColorFilter?,
    onPrev: () -> Unit, onNext: () -> Unit, onSeek: (Int) -> Unit,
) {
    var drag by remember(index) { mutableStateOf<Float?>(null) }
    val n = pages.size
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        val dv = drag
        if (dv != null && n > 0) {
            val t = dv.roundToInt().coerceIn(0, n - 1)
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 44.dp)) {
                val frac = if (n > 1) t / (n - 1f) else 0f
                val f = if (rtl) 1f - frac else frac
                val cardW = 86.dp
                Column(Modifier.offset(x = (maxWidth - cardW) * f).width(cardW).glassPill(RoundedCornerShape(12.dp), dark = true).background(Color.Black.copy(0.45f)).padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    ReaderImage(pages[t], headers, Modifier.fillMaxWidth().aspectRatio(0.7f).clip(RoundedCornerShape(6.dp)), webtoon = false, filter = filter, thumb = true)
                    Text("${t + 1} / $n", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val hasPrev = index > 0; val hasNext = index + 1 < d.chapters.size
            IconButton(onPrev, enabled = hasPrev) { Icon(Icons.Filled.SkipPrevious, "上一话", tint = if (hasPrev) Color.White else Color.White.copy(0.3f)) }
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                Slider(
                    value = (drag ?: current.toFloat()).coerceIn(0f, (n - 1).coerceAtLeast(1).toFloat()),
                    onValueChange = { drag = it },
                    onValueChangeFinished = { drag?.let { onSeek(it.roundToInt()) }; drag = null },
                    valueRange = 0f..(n - 1).coerceAtLeast(1).toFloat(),
                    enabled = n > 1,
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = IdjThemeColors.accent, inactiveTrackColor = Color.White.copy(0.18f)),
                    modifier = Modifier.weight(1f).testTag("manga.reader.scrubber"),
                )
            }
            IconButton(onNext, enabled = hasNext) { Icon(Icons.Filled.SkipNext, "下一话", tint = if (hasNext) Color.White else Color.White.copy(0.3f)) }
        }
    }
}

@Composable
private fun SlotView(slot: ReaderSlot, pages: List<String>, headers: Map<String, String>, rtl: Boolean, filter: ColorFilter?, onSize: (Int, Int, Int) -> Unit) {
    if (slot.pages.size == 1) {
        val i = slot.pages[0]
        ReaderImage(pages[i], headers, Modifier.fillMaxSize(), webtoon = false, half = slot.half, filter = filter, pageNo = i + 1) { w, h -> onSize(i, w, h) }
    } else {
        val order = if (rtl) slot.pages.reversed() else slot.pages
        Row(Modifier.fillMaxSize()) {
            order.forEachIndexed { k, i ->
                ReaderImage(pages[i], headers, Modifier.weight(1f).fillMaxHeight(), webtoon = false, filter = filter, pageNo = i + 1,
                    alignment = if (k == 0) Alignment.CenterEnd else Alignment.CenterStart) { w, h -> onSize(i, w, h) }
            }
        }
    }
}

@Composable
internal fun ReaderImage(
    url: String, headers: Map<String, String>, modifier: Modifier, webtoon: Boolean,
    half: Int = 0, alignment: Alignment = Alignment.Center, filter: ColorFilter? = null, pageNo: Int = 0, thumb: Boolean = false,
    onSize: ((Int, Int) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    var retry by remember(url) { mutableIntStateOf(0) }
    val crop = MangaReaderPrefs.autoCrop && !thumb
    val sizeCb by rememberUpdatedState(onSize)
    val ink = LocalPageInk.current
    /** Decoded page size (what is actually drawn) — the 标准 pass sizes its kernel from the on-screen magnification. */
    var contentSize by remember(url, half, crop) { mutableStateOf(IntSize.Zero) }
    /** The page's on-screen box (the 标准 pass sizes from it; 增强 starts once the page is laid out). */
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    fun request() = ImageRequest.Builder(ctx).data(url).apply { headers.forEach { (k, v) -> addHeader(k, v) } }
        .transformations(buildList { if (crop) add(AutoCropTransformation(sidesOnly = webtoon)); if (half != 0) add(HalfCropTransformation(right = half == 2)) })
    val req = remember(url, retry, crop, half, webtoon, thumb) {
        request().apply { if (thumb) size(240) }
            .listener(onSuccess = { _, r ->
                contentSize = IntSize(r.drawable.intrinsicWidth, r.drawable.intrinsicHeight)
                sizeCb?.invoke(r.drawable.intrinsicWidth, r.drawable.intrinsicHeight)
            })
            .build()
    }
    val contentScale = if (webtoon) ContentScale.FillWidth else if (thumb) ContentScale.Crop else ContentScale.Fit
    // 漫画超分 on the real pages (all modes); never on scrubber thumbnails. 1 标准 = instant AGSL pass;
    // 2 增强 = Real-ESRGAN page from MangaAiUpscaler, previewed with the 标准 pass until it lands.
    val srLevel = if (thumb) 0 else MangaReaderPrefs.superResLevel
    val aiKey = "$url|$crop|$half|$webtoon"
    var aiPage by remember(aiKey, srLevel) { mutableStateOf(if (srLevel == 2) MangaAiUpscaler.getCached(aiKey) else null) }
    val active by rememberUpdatedState(LocalReaderPageActive.current)
    LaunchedEffect(aiKey, srLevel) {
        if (srLevel != 2 || aiPage != null) return@LaunchedEffect
        snapshotFlow { boxSize.width > 0 }.first { it }
        while (aiPage == null) {
            // The page on screen goes first; a preloaded neighbour (the next page) is prefetched only once the
            // upscaler is idle, and gives way again between tiles as soon as a visible page is waiting.
            if (!active) {
                delay(350)
                snapshotFlow { active || !MangaAiUpscaler.busy }.first { it }
            }
            // Full-resolution software decode (Size.ORIGINAL, no hardware bitmap) of the same (cropped) page; leaving
            // composition — fast scrolling / flipping — cancels the job between tiles.
            val result = ctx.imageLoader.execute(request().size(coil.size.Size.ORIGINAL).allowHardware(false).build())
            val source = ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap ?: return@LaunchedEffect
            try {
                aiPage = MangaAiUpscaler.upscale(ctx, aiKey, source, fillWidth = webtoon) { active } ?: return@LaunchedEffect
            } catch (_: MangaAiUpscaler.Preempted) {
                delay(200) // yielded to the visible page: resume the prefetch when the upscaler is idle again
            }
        }
    }
    val measured = modifier.onSizeChanged { boxSize = it }
    aiPage?.let { page ->
        LaunchedEffect(page) { sizeCb?.invoke(page.width, page.height) }
        val aiFilter = remember(MangaReaderPrefs.invert, MangaReaderPrefs.grayscale) {
            readerColorFilter(MangaReaderPrefs.invert, MangaReaderPrefs.grayscale)
        }
        // Mipmapped + high filter quality: the big page is minified on screen without aliasing.
        Image(page.asImageBitmap(), null, measured, alignment = alignment, contentScale = contentScale, colorFilter = aiFilter, filterQuality = FilterQuality.High)
        return
    }
    val srModifier = if (srLevel == 0) measured else measured.mangaSuperRes(1, contentSize, fillWidth = webtoon, viewSize = boxSize)
    SubcomposeAsyncImage(
        req, null, srModifier,
        contentScale = contentScale,
        alignment = alignment, colorFilter = filter,
        filterQuality = if (thumb) FilterQuality.Low else FilterQuality.High,
        loading = {
            Box(if (webtoon) Modifier.fillMaxWidth().height(420.dp) else Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (thumb) Box(Modifier.fillMaxSize().background(HudCard))
                else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = ink.copy(0.7f), strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
                    if (pageNo > 0) Text("$pageNo", color = ink.copy(0.35f), fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        error = {
            Box((if (webtoon) Modifier.fillMaxWidth().height(300.dp) else Modifier.fillMaxSize()).clickable { retry++ }, contentAlignment = Alignment.Center) {
                Text(if (thumb) "×" else "第 $pageNo 页加载失败，点我重试", color = ink.copy(0.6f), fontSize = 13.sp)
            }
        },
    )
}

@Composable
private fun ChapterEnd(d: MangaDetail, index: Int, go: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 260.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) {
        val ink = LocalPageInk.current
        Text("本话完", color = ink.copy(0.75f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(d.chapters[index].title, color = ink.copy(0.45f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (index + 1 < d.chapters.size) Button({ go(index + 1) }, colors = ButtonDefaults.buttonColors(containerColor = IdjThemeColors.accent, contentColor = Color.White)) {
            Text("下一话：" + d.chapters[index + 1].title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else Text("已是最新一话", color = ink.copy(0.5f), fontSize = 12.sp)
    }
}

/** 底部信息栏：章节 · 页码 · 时间 · 电量 (shown while the HUD is hidden). */
@Composable
private fun ReaderFooter(chapter: String, current: Int, total: Int, modifier: Modifier) {
    val ctx = LocalContext.current
    val clock by produceState("") {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        while (true) {
            val bat = runCatching { bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }.getOrNull()?.takeIf { it in 0..100 }
            value = fmt.format(Date()) + (bat?.let { "  电量 $it%" } ?: "")
            delay(30_000)
        }
    }
    Text("${chapter.take(14)}  ${current + 1}/$total  $clock", color = Color.White.copy(0.85f), fontSize = 10.sp, maxLines = 1,
        modifier = modifier.navigationBarsPadding().padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(0.55f)).padding(horizontal = 8.dp, vertical = 3.dp))
}

// ── Console panels ──

@Composable
private fun PanelTitle(text: String, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
private fun PanelSwitch(label: String, checked: Boolean, sub: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = if (enabled) Color.White else Color.White.copy(0.35f), fontSize = 14.sp)
            if (sub != null) Text(sub, color = Color.White.copy(0.45f), fontSize = 11.sp)
        }
        Switch(checked, onChange, enabled = enabled, colors = SwitchDefaults.colors(checkedTrackColor = IdjThemeColors.accent, checkedThumbColor = Color.White))
    }
}

@Composable
private fun PanelSlider(label: String, value: Float, valueText: String, range: ClosedFloatingPointRange<Float> = 0f..1f, steps: Int = 0, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.width(72.dp))
        Slider(value, onChange, Modifier.weight(1f), valueRange = range, steps = steps,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = IdjThemeColors.accent, inactiveTrackColor = Color.White.copy(0.18f)))
        Text(valueText, color = HudDim, fontSize = 12.sp, modifier = Modifier.width(44.dp).padding(start = 6.dp))
    }
}

@Composable
private fun <T> PanelSegments(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().glassPill(RoundedCornerShape(14.dp), dark = true).padding(3.dp)) {
        options.forEach { o ->
            val on = o == selected
            Box(Modifier.weight(1f).then(if (on) Modifier.glassPill(RoundedCornerShape(11.dp), selected = true, dark = true) else Modifier.clip(RoundedCornerShape(11.dp)))
                .clickable { onSelect(o) }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center) {
                Text(label(o), color = if (on) Color.White else HudDim, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}

@Composable
private fun CatalogPanel(d: MangaDetail, index: Int, go: (Int) -> Unit) {
    var asc by remember { mutableStateOf(true) }
    val list = remember(asc) { if (asc) d.chapters.indices.toList() else d.chapters.indices.reversed().toList() }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (list.indexOf(index) - 3).coerceAtLeast(0))
    Column {
        PanelTitle("目录 · 共 ${d.chapters.size} 话") {
            Text(if (asc) "正序" else "倒序", color = IdjThemeColors.accent, fontSize = 13.sp, modifier = Modifier.clip(CircleShape).clickable { asc = !asc }.padding(horizontal = 10.dp, vertical = 4.dp))
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp), state = state) {
            itemsIndexed(list, key = { _, i -> d.chapters[i].chapterId + "#" + i }) { _, i ->
                val on = i == index
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (on) IdjThemeColors.accent.copy(0.18f) else Color.Transparent).clickable { go(i) }.padding(horizontal = 10.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(d.chapters[i].title, color = if (on) IdjThemeColors.accent else Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (on) Text("阅读中", color = IdjThemeColors.accent, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun ImagePanel() {
    val P = MangaReaderPrefs
    Column(Modifier.verticalScroll(rememberScrollState())) {
        PanelTitle("画面")
        PageBackgroundRow()
        PanelSlider("亮度", P.brightness, "${(P.brightness * 100).roundToInt()}%", 0.15f..1f) { P.updateBrightness(it) }
        PanelSlider("护眼暖色", P.warm, "${(P.warm * 100).roundToInt()}%") { P.updateWarm(it) }
        PanelSwitch("自动裁白边", P.autoCrop, "去除页面四周的白边 / 黑边") { P.updateAutoCrop(it) }
        PanelSwitch("宽页切分", P.splitWide, "横向跨页拆成两页（左右翻页模式）", enabled = P.mode == MangaReadMode.LTR || P.mode == MangaReadMode.RTL) { P.updateSplitWide(it) }
        PanelSwitch("反色（夜间）", P.invert) { P.updateInvert(it) }
        PanelSwitch("灰度", P.grayscale) { P.updateGrayscale(it) }
        PanelTitle("漫画超分")
        PanelSegments(listOf(0, 1, 2), P.superResLevel, { when (it) { 1 -> "标准"; 2 -> "增强"; else -> "关闭" } }) { P.updateSuperResLevel(it) }
        Text(when (P.superResLevel) {
            1 -> if (Build.VERSION.SDK_INT >= 33) "极速超分：Anime4K DoG 去模糊 + 边缘收紧，只锐化线条与文字，原色原纸色不变，零延迟" else "标准需 Android 13+（GPU 着色器）；本机可选「增强」"
            2 -> "AI 精修：Real-ESRGAN 4× 神经网络重建后 2× 输出，色彩不偏移；当前页优先，空闲时预取下一页"
            else -> "标准 = 极速 Anime4K GPU 锐化 · 增强 = 离线 Real-ESRGAN 神经网络"
        }, color = Color.White.copy(0.45f), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp, start = 2.dp))
        PanelTitle("沉浸氛围")
        PanelSegments(MangaAtmosphere.entries, P.atmosphere, { it.label }) { P.updateAtmosphere(it) }
        if (P.atmosphere != MangaAtmosphere.OFF) {
            PanelSwitch("环境音效", P.atmosphereSound, if (P.atmosphereSound) "开" else "关") { P.updateAtmosphereSound(it) }
            if (P.atmosphereSound) PanelSlider("音量", P.atmosphereVolume, "${(P.atmosphereVolume * 100).roundToInt()}%") { P.updateAtmosphereVolume(it) }
        }
    }
}

/** 「图片背景」: a value row with a check-marked popup (跟随系统 / 白色 / 黑色 / 沉浸式 / 纹理); 纹理 adds 样式 + 透明度. */
@Composable
private fun PageBackgroundRow() {
    val P = MangaReaderPrefs
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("图片背景", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Box {
                var open by remember { mutableStateOf(false) }
                Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable { open = true }.padding(horizontal = 8.dp, vertical = 6.dp).testTag("manga.reader.pageBg"),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(P.pageBg.label, color = IdjThemeColors.accent, fontSize = 14.sp)
                    Icon(Icons.Filled.UnfoldMore, null, tint = IdjThemeColors.accent, modifier = Modifier.size(16.dp))
                }
                DropdownMenu(open, { open = false }) {
                    MangaPageBg.entries.forEach { bg ->
                        DropdownMenuItem(text = { Text(bg.label) }, onClick = { open = false; P.updatePageBg(bg) },
                            leadingIcon = { if (bg == P.pageBg) Icon(Icons.Filled.Check, null) else Spacer(Modifier.size(24.dp)) })
                    }
                }
            }
        }
        Text(when (P.pageBg) {
            MangaPageBg.SYSTEM -> "浅色模式白底，深色模式黑底"
            MangaPageBg.WHITE -> "纯白页边"
            MangaPageBg.BLACK -> "纯黑页边（OLED 省电）"
            MangaPageBg.IMMERSIVE -> "以当前页面模糊铺满页边，色彩无缝延展"
            MangaPageBg.TEXTURE -> "纸张纹理覆盖页面与页边，如同印刷在纸上"
        }, color = Color.White.copy(0.45f), fontSize = 11.sp, modifier = Modifier.padding(start = 2.dp, bottom = 4.dp))
        if (P.pageBg == MangaPageBg.TEXTURE) {
            PanelSegments(MangaPaperStyle.entries, P.paperStyle, { it.label }) { P.updatePaperStyle(it) }
            PanelSlider("透明度", P.textureOpacity, "${(P.textureOpacity * 100).roundToInt()}%") { P.updateTextureOpacity(it) }
        }
    }
}

@Composable
private fun TurnPanel(mode: MangaReadMode, autoRead: Boolean, setMode: (MangaReadMode) -> Unit, setPageAnim: (MangaPageAnim) -> Unit, setAuto: (Boolean) -> Unit) {
    val P = MangaReaderPrefs
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PanelTitle("阅读模式")
        PanelSegments(MangaReadMode.entries, mode, { it.short }, setMode)
        when (mode) {
            MangaReadMode.DUAL -> {
                PanelSwitch("封面单独成页", P.dualCoverOffset, "跨页错位时打开 / 关闭") { P.updateDualCover(it) }
                PanelSwitch("日漫顺序（从右到左）", P.dualRtl) { P.updateDualRtl(it) }
            }
            MangaReadMode.WEBTOON -> PanelSwitch("页间留缝", P.webtoonGap, "关闭为无缝卷纸") { P.updateGap(it) }
            else -> {}
        }
        PanelTitle("点击翻页")
        PanelSegments(MangaTapLayout.entries, P.tapLayout, { it.label }) { P.updateTapLayout(it) }
        TapZonePreview(P.tapLayout, P.tapInvert xor (mode == MangaReadMode.RTL || (mode == MangaReadMode.DUAL && P.dualRtl)))
        PanelSwitch("左右反转", P.tapInvert, "左手单手阅读") { P.updateTapInvert(it) }
        PanelSwitch("音量键翻页", P.volumeKeys, "音量下 = 下一页，音量上 = 上一页") { P.updateVolumeKeys(it) }
        if (mode.paged) {
            PanelTitle("翻页效果")
            PanelSegments(MangaPageAnim.entries, P.pageAnim, { it.label }, setPageAnim)
        }
        PanelSwitch("翻页动画", P.turnAnim, "关闭后点击 / 音量键直接换页") { P.updateTurnAnim(it) }
        if (mode.paged) PanelSwitch("翻页音效", P.pageFlipSound, "真实纸张翻页音效") { P.updatePageFlipSound(it) }
        PanelTitle("自动阅读")
        PanelSwitch(if (autoRead) "自动阅读中" else "开始自动阅读", autoRead, if (mode == MangaReadMode.WEBTOON) "匀速向下滚动" else "定时翻页，呼出菜单时暂停") { setAuto(it) }
        val sec = 12f - 10f * P.autoSpeed
        PanelSlider("速度", P.autoSpeed, if (mode == MangaReadMode.WEBTOON) "${(P.autoSpeed * 100).roundToInt()}%" else "%.0fs".format(sec)) { P.updateAutoSpeed(it) }
    }
}

/** 3×3 九宫格 diagram of what each tap region does. */
@Composable
private fun TapZonePreview(layout: MangaTapLayout, swap: Boolean) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.width(150.dp).height(96.dp).clip(RoundedCornerShape(8.dp)).border(0.6.dp, Color.White.copy(0.2f), RoundedCornerShape(8.dp))) {
            for (r in 0..2) Row(Modifier.weight(1f)) {
                for (c in 0..2) {
                    var a = tapAction(layout, c + 0.5f, r + 0.5f, 3f, 3f)
                    if (swap) a = when (a) { TapAction.PREV -> TapAction.NEXT; TapAction.NEXT -> TapAction.PREV; else -> a }
                    val (bg, t) = when (a) { TapAction.PREV -> Color(0xFF3A6FD8) to "上"; TapAction.NEXT -> Color(0xFF2FAE6B) to "下"; TapAction.MENU -> Color(0xFF55555C) to "菜单" }
                    Box(Modifier.weight(1f).fillMaxHeight().background(bg.copy(0.55f)).border(0.3.dp, Color.Black.copy(0.4f)), contentAlignment = Alignment.Center) {
                        Text(t, color = Color.White, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsPanel() {
    val P = MangaReaderPrefs
    Column(Modifier.verticalScroll(rememberScrollState())) {
        PanelTitle("阅读设置")
        PanelSwitch("屏幕常亮", P.keepScreenOn) { P.updateKeepScreenOn(it) }
        PanelSwitch("显示系统状态栏", P.showSystemBar, "隐藏菜单时仍保留时间 / 电量栏") { P.updateShowSystemBar(it) }
        PanelSwitch("底部信息栏", P.showFooter, "阅读时显示章节、页码、时间、电量") { P.updateShowFooter(it) }
        PanelSlider("预加载", P.preload.toFloat(), "${P.preload} 页", 0f..10f, steps = 9) { P.updatePreload(it.roundToInt()) }
    }
}
