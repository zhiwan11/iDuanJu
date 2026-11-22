package app.iduanju.manga

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.size.Size
import coil.transform.Transformation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Pinch (1x..4x) / pan / double-tap zoom shared by the 条漫 list and the paged modes (可达阅读器 双击 2.2x / 1x). */
@Stable
class ReaderZoomState {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    var size by mutableStateOf(IntSize.Zero)
    val zoomed get() = scale > 1.01f

    private fun center() = Offset(size.width / 2f, size.height / 2f)
    private fun clamp(o: Offset, s: Float): Offset {
        val mx = (s - 1f) * size.width / 2f
        val my = (s - 1f) * size.height / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }
    fun reset() { scale = 1f; offset = Offset.Zero }

    /** Applies one gesture frame keeping [centroid] under the fingers; returns the vertical pan that hit the bounds
     *  (the 条漫 list scrolls by it, so a zoomed webtoon still pans continuously through the chapter). */
    fun transform(zoom: Float, pan: Offset, centroid: Offset): Float {
        val c = center()
        val ns = (scale * zoom).coerceIn(1f, 4f)
        val layoutPoint = (centroid - c - offset) / scale
        val wanted = centroid - c - layoutPoint * ns + pan
        val clamped = clamp(wanted, ns)
        scale = ns; offset = clamped
        return wanted.y - clamped.y
    }

    suspend fun animateTo(target: Float, focus: Offset) {
        val c = center(); val s0 = scale; val o0 = offset
        val layoutPoint = (focus - c - o0) / s0
        val o1 = clamp(focus - c - layoutPoint * target, target)
        animate(0f, 1f, animationSpec = tween(220)) { v, _ ->
            scale = s0 + (target - s0) * v
            offset = Offset(o0.x + (o1.x - o0.x) * v, o0.y + (o1.y - o0.y) * v)
        }
    }
}

/** Two-finger pinch always wins (Initial pass, ahead of the pager / list); one-finger pans only while zoomed.
 *  Taps go to [onTap] (九宫格) and double-taps to [onDoubleTap]. */
fun Modifier.readerGestures(
    zoom: ReaderZoomState,
    onTap: (Offset) -> Unit,
    onDoubleTap: (Offset) -> Unit,
    onOverflowY: (Float) -> Unit,
): Modifier = this
    .pointerInput(zoom) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var panning = false
            var acc = Offset.Zero
            do {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = ev.changes.count { it.pressed }
                if (pressed >= 2 || zoom.zoomed) {
                    val z = ev.calculateZoom(); val pan = ev.calculatePan()
                    if (pressed >= 2) panning = true else { acc += pan; if (acc.getDistance() > viewConfiguration.touchSlop) panning = true }
                    if (panning && (z != 1f || pan != Offset.Zero)) {
                        val left = zoom.transform(z, pan, ev.calculateCentroid(useCurrent = true))
                        if (abs(left) > 0.01f) onOverflowY(left)
                        ev.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                }
            } while (ev.changes.any { it.pressed })
        }
    }
    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onDoubleTap(it) }, onTap = { onTap(it) }) }

/** 反色 / 灰度 colour matrix for the page images (null when both are off). */
fun readerColorFilter(invert: Boolean, gray: Boolean): ColorFilter? {
    if (!invert && !gray) return null
    val m = ColorMatrix()
    if (gray) m.setToSaturation(0f)
    if (invert) m.timesAssign(ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)))
    return ColorFilter.colorMatrix(m)
}

/** 自动裁白边：trims uniform white (or black) margins. In 条漫 mode only the sides are trimmed so panel gutters survive. */
class AutoCropTransformation(private val sidesOnly: Boolean) : Transformation {
    override val cacheKey = "keda.autocrop.v1.$sidesOnly"
    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val w = input.width; val h = input.height
        if (w < 48 || h < 48) return input
        val ref = input.getPixel(2, 2)
        val rl = (ref shr 16) and 0xFF; val gl = (ref shr 8) and 0xFF; val bl = ref and 0xFF
        val lum = (rl + gl + bl) / 3
        if (lum in 31..224) return input // only white / black borders
        fun bg(c: Int) = abs(((c shr 16) and 0xFF) - rl) < 28 && abs(((c shr 8) and 0xFF) - gl) < 28 && abs((c and 0xFF) - bl) < 28
        val row = IntArray(w); val col = IntArray(h)
        fun rowBg(y: Int): Boolean { input.getPixels(row, 0, w, 0, y, w, 1); var bad = 0; var x = 0; while (x < w) { if (!bg(row[x])) bad++; x += 2 }; return bad <= w / 200 + 1 }
        fun colBg(x: Int): Boolean { input.getPixels(col, 0, 1, x, 0, 1, h); var bad = 0; var y = 0; while (y < h) { if (!bg(col[y])) bad++; y += 2 }; return bad <= h / 200 + 1 }
        var l = 0; var r = w - 1; var t = 0; var b = h - 1
        while (l < w / 3 && colBg(l)) l++
        while (r > w * 2 / 3 && colBg(r)) r--
        if (!sidesOnly) { while (t < h / 3 && rowBg(t)) t++; while (b > h * 2 / 3 && rowBg(b)) b-- }
        val m = 4
        l = (l - m).coerceAtLeast(0); t = (t - m).coerceAtLeast(0); r = (r + m).coerceAtMost(w - 1); b = (b + m).coerceAtMost(h - 1)
        val nw = r - l + 1; val nh = b - t + 1
        if (nw >= w * 0.98f && nh >= h * 0.98f) return input
        return Bitmap.createBitmap(input, l, t, nw, nh)
    }
}

/** 宽页切分：returns the left or right half of a landscape spread. */
class HalfCropTransformation(private val right: Boolean) : Transformation {
    override val cacheKey = "keda.half.$right"
    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (input.width <= input.height) return input
        val half = input.width / 2
        return Bitmap.createBitmap(input, if (right) input.width - half else 0, 0, half, input.height)
    }
}

/** One pager slot: a single page, a half of a wide page (1 = left, 2 = right) or a 双页 spread. */
data class ReaderSlot(val pages: List<Int>, val half: Int = 0)

fun buildReaderSlots(n: Int, mode: MangaReadMode, wide: Map<Int, Boolean>, split: Boolean, coverOffset: Boolean): List<ReaderSlot> {
    val out = ArrayList<ReaderSlot>(n)
    if (mode == MangaReadMode.DUAL) {
        var i = 0
        if (coverOffset && n > 0) { out += ReaderSlot(listOf(0)); i = 1 }
        while (i < n) {
            if (wide[i] == true || i + 1 >= n || wide[i + 1] == true) { out += ReaderSlot(listOf(i)); i++ }
            else { out += ReaderSlot(listOf(i, i + 1)); i += 2 }
        }
    } else {
        val rtl = mode == MangaReadMode.RTL
        for (i in 0 until n) {
            if (split && wide[i] == true) { out += ReaderSlot(listOf(i), if (rtl) 2 else 1); out += ReaderSlot(listOf(i), if (rtl) 1 else 2) }
            else out += ReaderSlot(listOf(i))
        }
    }
    return out
}

// ── 翻页效果：仿真翻页 / 覆盖 / 淡入 (平滑 = HorizontalPager) ──

/**
 * Page state for [BookPageCurlViewer]. One turn at a time: [turnDir] (+1 next, −1 previous, 0 idle) and [progress]
 * 0..1 are driven by the finger while dragging and by a 340 ms FastOutSlowIn tween for tap / volume-key turns.
 * A new turn first commits any turn still in flight, so rapid taps never lose a page.
 */
@Stable
class BookPagerState(initialPage: Int, private val count: () -> Int) {
    var currentPage by mutableIntStateOf(initialPage)
        private set
    var turnDir by mutableIntStateOf(0)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    /** Bumped on every drag start (the reader hides its HUD on it, like the pager's DragInteraction). */
    var dragStarts by mutableIntStateOf(0)
        private set
    val pageCount get() = count()
    val isTurning get() = turnDir != 0

    private var dragging = false
    /** Where the motion in flight will land (== currentPage when idle or snapping back). */
    private var goal = initialPage
    private var job: Job? = null

    private fun finishNow() {
        job?.cancel(); job = null
        val last = (pageCount - 1).coerceAtLeast(0)
        if (goal != currentPage) currentPage = goal.coerceIn(0, last)
        goal = currentPage; turnDir = 0; progress = 0f; dragging = false
    }

    private fun settle(scope: CoroutineScope, target: Int, toEnd: Boolean, ms: Int) {
        goal = target
        val from = progress
        job = scope.launch {
            animate(from, if (toEnd) 1f else 0f, animationSpec = tween(ms, easing = FastOutSlowInEasing)) { v, _ -> progress = v }
            currentPage = target; goal = target; turnDir = 0; progress = 0f; job = null
        }
    }

    /** Turns one page (animated unless [animate] is off); false when there is no page that way. */
    fun turn(scope: CoroutineScope, forward: Boolean, animate: Boolean): Boolean {
        val target = goal + if (forward) 1 else -1
        if (target !in 0 until pageCount) return false
        finishNow()
        if (!animate) { currentPage = target; goal = target; return true }
        turnDir = if (forward) 1 else -1
        settle(scope, target, toEnd = true, ms = 340)
        return true
    }

    fun jumpTo(page: Int) {
        finishNow()
        currentPage = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0)); goal = currentPage
    }

    internal fun dragStart(dir: Int): Boolean {
        finishNow()
        if (currentPage + dir !in 0 until pageCount) return false
        turnDir = dir; progress = 0f; dragging = true; dragStarts++
        return true
    }

    internal fun dragBy(delta: Float) { if (dragging) progress = (progress + delta).coerceIn(0f, 1f) }

    /** [velocity] in page widths / s along the turn direction (positive = keep turning). */
    internal fun dragEnd(scope: CoroutineScope, velocity: Float) {
        if (!dragging) return
        dragging = false
        val complete = velocity > 0.9f || (velocity > -0.5f && progress > 0.38f)
        val remain = if (complete) 1f - progress else progress
        settle(scope, if (complete) currentPage + turnDir else currentPage, complete, (remain * 340).toInt().coerceIn(120, 340))
    }
}

@Composable
fun rememberBookPagerState(initialPage: Int, pageCount: () -> Int): BookPagerState {
    val count by rememberUpdatedState(pageCount)
    return remember { BookPagerState(initialPage) { count() } }
}

private enum class BookRole { HIDDEN, UNDER, TOP }

/**
 * 可达阅读器-style paged viewer with physical page turns for 左翻 / 日漫 / 双页.
 *
 * Pages current−1..current+1 stay composed under stable keys (so the revealed page never reloads when a turn
 * commits). During a turn the revealed page sits crisp underneath, and the turning page is
 * - [MangaPageAnim.CURL] clipped along the moving fold, with a widening soft cast shadow on the revealed page, a
 *   bend shadow on the page itself and the curled-over back flap (crease shadow → cylindrical specular highlight →
 *   paper back tint → outer curl shadow);
 * - [MangaPageAnim.COVER] sliding off with a crisp 20 dp cast shadow;
 * - [MangaPageAnim.FADE] cross-fading out.
 * A backward turn is drawn as the forward turn of the previous page played in reverse; [rtl] mirrors the geometry
 * (the left edge lifts), never the artwork.
 */
@Composable
fun BookPageCurlViewer(
    state: BookPagerState,
    effect: MangaPageAnim,
    rtl: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    paper: Color = Color(0xFFF3F0EA),
    page: @Composable (Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val cur = state.currentPage
    val dir = state.turnDir
    val count = state.pageCount
    val topPage = if (dir < 0) cur - 1 else cur
    val underPage = when (dir) { 1 -> cur + 1; -1 -> cur; else -> -1 }
    // forward-equivalent turn progress: a backward turn is the previous page's forward turn in reverse
    fun t() = if (state.turnDir < 0) 1f - state.progress else state.progress
    val mirror = rtl
    Box(
        modifier.fillMaxSize().clipToBounds()
            .pointerInput(enabled, rtl) { if (enabled) coroutineScope { bookDragGestures(state, scope, rtl) } }
    ) {
        for (i in (cur - 1)..(cur + 1)) {
            if (i !in 0 until count) continue
            key(i) {
                val role = when (i) { topPage -> BookRole.TOP; underPage -> BookRole.UNDER; else -> BookRole.HIDDEN }
                val roleMod = when (role) {
                    // composed (= preloaded) but invisible; never receives touches (covered by the visible page)
                    BookRole.HIDDEN -> Modifier.zIndex(0f).graphicsLayer { alpha = 0f }
                    BookRole.UNDER -> Modifier.zIndex(1f)
                    BookRole.TOP -> Modifier.zIndex(2f).then(
                        if (dir == 0) Modifier else when (effect) {
                            MangaPageAnim.COVER -> Modifier.graphicsLayer { translationX = -t() * size.width * (if (mirror) -1f else 1f) }
                            MangaPageAnim.FADE -> Modifier.graphicsLayer { alpha = 1f - t() }
                            else -> Modifier.drawWithContent {
                                val fold = size.width * (1f - t())
                                if (mirror) clipRect(left = size.width - fold) { this@drawWithContent.drawContent() }
                                else clipRect(right = fold) { this@drawWithContent.drawContent() }
                            }
                        }
                    )
                }
                Box(
                    Modifier.fillMaxSize().then(roleMod)
                        // visible pages are opaque and swallow hit-testing for the hidden ones below them
                        .then(if (role == BookRole.HIDDEN) Modifier else Modifier.background(Color.Black).pointerInput(Unit) {})
                ) { page(i) }
            }
        }
        if (dir != 0 && effect != MangaPageAnim.FADE) {
            Canvas(Modifier.fillMaxSize().zIndex(3f)) {
                val tt = t()
                if (effect == MangaPageAnim.COVER) drawCoverShadow(tt, mirror) else drawPageCurl(tt, mirror, paper)
            }
        }
    }
}

private suspend fun PointerInputScope.bookDragGestures(state: BookPagerState, scope: CoroutineScope, rtl: Boolean) {
    val tracker = VelocityTracker()
    var decided = false
    var active = false
    fun end(fling: Boolean) {
        if (active) {
            val w = size.width.coerceAtLeast(1).toFloat()
            val vx = if (fling) tracker.calculateVelocity().x else 0f
            state.dragEnd(scope, (if (rtl) vx else -vx) * state.turnDir / w)
        }
        decided = false; active = false
    }
    detectHorizontalDragGestures(
        onDragStart = { tracker.resetTracking(); decided = false; active = false },
        onDragEnd = { end(fling = true) },
        onDragCancel = { end(fling = false) },
    ) { change, dx ->
        tracker.addPointerInputChange(change)
        // reading-space delta: positive = toward the next page (finger leftward in 左翻, rightward in 日漫)
        val fwd = if (rtl) dx else -dx
        if (!decided) {
            if (fwd == 0f) return@detectHorizontalDragGestures
            decided = true
            active = state.dragStart(if (fwd > 0f) 1 else -1)
        }
        if (!active) return@detectHorizontalDragGestures
        change.consume()
        state.dragBy(fwd * state.turnDir / size.width.coerceAtLeast(1))
    }
}

/** Fades in over the first ~12 % and out over the last ~20 % of a turn, so no shadow pops on commit. */
private fun turnEnvelope(t: Float) = (t * 8f).coerceAtMost(1f) * ((1f - t) * 5f).coerceAtMost(1f)

private fun DrawScope.mirrorRect(mirror: Boolean, x0: Float, x1: Float, brush: Brush) {
    val a = if (mirror) size.width - x0 else x0
    val b = if (mirror) size.width - x1 else x1
    val l = minOf(a, b); val r = maxOf(a, b)
    if (r - l < 0.5f) return
    drawRect(brush, topLeft = Offset(l, 0f), size = GeoSize(r - l, size.height))
}

/** Horizontal gradient from reading-space [x0] to [x1] (mirrored for 日漫). */
private fun DrawScope.mirrorBrush(mirror: Boolean, x0: Float, x1: Float, vararg stops: Pair<Float, Color>): Brush =
    Brush.horizontalGradient(*stops, startX = if (mirror) size.width - x0 else x0, endX = if (mirror) size.width - x1 else x1)

private fun DrawScope.drawPageCurl(t: Float, mirror: Boolean, paper: Color) {
    if (t <= 0.001f || t >= 0.999f) return
    val w = size.width
    val fold = w * (1f - t)
    val lifted = w - fold
    val flapW = minOf(w * 0.22f, lifted * 0.65f)
    val flapL = fold - flapW
    val env = turnEnvelope(t)

    // cast drop shadow of the lifting page on the revealed page: widens and softens as the page lifts
    val shW = w * 0.03f + lifted * 0.24f
    val shA = 0.55f * (1f - 0.5f * t) * env
    mirrorRect(mirror, fold, fold + shW, mirrorBrush(mirror, fold, fold + shW,
        0f to Color.Black.copy(alpha = shA), 0.3f to Color.Black.copy(alpha = shA * 0.45f), 1f to Color.Transparent))

    // bend shading on the current page where it starts to rise toward the flap
    val bendW = flapW * 0.55f + w * 0.02f
    mirrorRect(mirror, flapL - bendW, flapL, mirrorBrush(mirror, flapL - bendW, flapL,
        0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.24f * env)))

    // curled back flap: crease shadow → cylindrical specular highlight → paper back tint → outer edge curl shadow
    if (flapW > 0.5f) {
        mirrorRect(mirror, flapL, fold, mirrorBrush(mirror, fold, flapL,
            0f to lerp(paper, Color.Black, 0.46f),
            0.06f to lerp(paper, Color.Black, 0.24f),
            0.2f to lerp(paper, Color.White, 0.65f),
            0.34f to paper,
            0.7f to lerp(paper, Color.Black, 0.07f),
            0.9f to lerp(paper, Color.Black, 0.2f),
            1f to lerp(paper, Color.Black, 0.36f)))
        val x = if (mirror) w - fold else fold
        drawLine(Color.Black.copy(alpha = 0.3f * env), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
    }
}

private fun DrawScope.drawCoverShadow(t: Float, mirror: Boolean) {
    if (t <= 0.001f || t >= 0.999f) return
    val edge = size.width * (1f - t)
    val sw = 20.dp.toPx()
    val a = 0.5f * turnEnvelope(t)
    mirrorRect(mirror, edge, edge + sw, mirrorBrush(mirror, edge, edge + sw,
        0f to Color.Black.copy(alpha = a), 0.4f to Color.Black.copy(alpha = a * 0.35f), 1f to Color.Transparent))
}

// ── 沉浸氛围：雨天窗景 / 列车车窗 / 新年烟火 / 雪夜 ──

/**
 * Keda-style immersive reading atmosphere drawn over the pages (below the HUD). Draw-only: no pointer handlers, so
 * every touch reaches the reader. A 60 fps [withFrameNanos] clock is read inside the draw scope only, so the
 * animation redraws one canvas without recomposing anything. Particles are fixed seeded tables (no per-frame
 * allocation of particle state).
 */
@Composable
fun MangaAtmosphereOverlay(atmosphere: MangaAtmosphere, modifier: Modifier = Modifier) {
    if (atmosphere == MangaAtmosphere.OFF) return
    val clock = remember { mutableFloatStateOf(0f) }
    val fadeIn = remember(atmosphere) { Animatable(0f) }
    LaunchedEffect(atmosphere) { fadeIn.animateTo(1f, tween(700)) }
    LaunchedEffect(atmosphere) {
        val start = withFrameNanos { it }
        while (isActive) withFrameNanos { clock.floatValue = (it - start) / 1_000_000_000f }
    }
    val table = remember(atmosphere) { AtmosphereTable(atmosphere) }
    Canvas(modifier.fillMaxSize().graphicsLayer { alpha = fadeIn.value }) {
        val t = clock.floatValue
        when (atmosphere) {
            MangaAtmosphere.RAIN -> drawRain(t, table)
            MangaAtmosphere.TRAIN -> drawTrain(t)
            MangaAtmosphere.FIREWORK -> drawFirework(t, table)
            MangaAtmosphere.SNOW -> drawSnow(t, table)
            MangaAtmosphere.OFF -> {}
        }
    }
}

private class AtmosphereTable(a: MangaAtmosphere) {
    private val rnd = Random(a.ordinal * 7919 + 17)
    private fun arr(n: Int, lo: Float, hi: Float) = FloatArray(n) { lo + rnd.nextFloat() * (hi - lo) }
    // rain streaks
    val sX = arr(28, -0.05f, 1.05f); val sSpeed = arr(28, 0.9f, 1.7f); val sLen = arr(28, 38f, 96f)
    val sAlpha = arr(28, 0.07f, 0.2f); val sPhase = arr(28, 0f, 1.3f); val sWidth = arr(28, 0.8f, 1.6f)
    // sliding droplets on the glass
    val dX = arr(14, 0.03f, 0.97f); val dSpeed = arr(14, 0.05f, 0.14f); val dR = arr(14, 3f, 7f); val dPhase = arr(14, 0f, 3f)
    // static condensation beads
    val bX = arr(40, 0f, 1f); val bY = arr(40, 0f, 1f); val bR = arr(40, 0.8f, 2.2f)
    // snowflakes (depth-linked: larger = nearer = faster + brighter)
    val fDepth = arr(64, 0f, 1f); val fX = arr(64, 0f, 1.1f); val fPhase = arr(64, 0f, 1.1f)
    val fSway = arr(64, 0.2f, 0.75f); val fSwayPh = arr(64, 0f, 6.283f)
    // firework shells: launch cadence + per-spark burst direction / speed
    val fwPeriod = arr(FW_SHELLS, 2.8f, 4.4f); val fwPhase = arr(FW_SHELLS, 0f, 1f)
    val fwAng = FloatArray(FW_SHELLS * FW_SPARKS) { (it % FW_SPARKS) * (2f * PI.toFloat() / FW_SPARKS) + rnd.nextFloat() * 0.18f }
    val fwSpd = arr(FW_SHELLS * FW_SPARKS, 0.7f, 1f)
}

private fun fract(x: Float) = x - floor(x)

private fun DrawScope.vignette(edge: Color, strength: Float, inner: Float = 0.5f) {
    drawRect(Brush.radialGradient(inner to Color.Transparent, 1f to edge.copy(alpha = strength),
        center = center, radius = maxOf(size.width, size.height) * 0.72f))
}

private fun DrawScope.drawRain(t: Float, s: AtmosphereTable) {
    val w = size.width; val h = size.height; val dp = density
    // cool overcast glass tint + rainy vignette
    drawRect(Color(0xFF16304A).copy(alpha = 0.10f))
    vignette(Color(0xFF06111D), 0.55f)
    // falling streaks behind the glass
    val streak = Color(0xFFDCEAF8)
    for (i in 0 until 28) {
        val y = (fract((s.sPhase[i] + t * s.sSpeed[i]) / 1.3f) * 1.3f - 0.15f) * h
        val len = s.sLen[i] * dp
        val x = s.sX[i] * w + y * 0.08f
        drawLine(streak.copy(alpha = s.sAlpha[i]), Offset(x, y), Offset(x + len * 0.08f, y + len), strokeWidth = s.sWidth[i] * dp, cap = StrokeCap.Round)
    }
    // condensation beads
    for (i in 0 until 40) {
        val c = Offset(s.bX[i] * w, s.bY[i] * h); val r = s.bR[i] * dp
        drawCircle(Color.White.copy(alpha = 0.09f), r, c)
        drawCircle(Color.White.copy(alpha = 0.32f), r * 0.38f, c + Offset(-r * 0.3f, -r * 0.3f))
    }
    // droplets beading down the window: stick, then slide (cubic ease inside each step), with a wet trail
    for (i in 0 until 14) {
        val k = s.dPhase[i] + t * s.dSpeed[i] * 3f
        val fk = fract(k)
        val stepped = (floor(k) + fk * fk * fk) / 3f
        val y = (fract(stepped / 1.12f) * 1.12f - 0.06f) * h
        val r = s.dR[i] * dp
        val x = s.dX[i] * w + sin(t * 0.6f + i) * 1.5f * dp
        val c = Offset(x, y)
        drawLine(Color.White.copy(alpha = 0.06f), Offset(x, y - r * 9f), c, strokeWidth = r * 0.8f, cap = StrokeCap.Round)
        drawCircle(Color(0xFF0B1826).copy(alpha = 0.16f), r, c + Offset(0f, r * 0.18f)) // refracted dark rim
        drawCircle(Color(0xFFC4D6E8).copy(alpha = 0.16f), r * 0.9f, c)                 // lens body
        drawCircle(Color.White.copy(alpha = 0.62f), r * 0.26f, c + Offset(-r * 0.32f, -r * 0.36f)) // specular
        drawCircle(Color.White.copy(alpha = 0.18f), r * 0.2f, c + Offset(r * 0.24f, r * 0.44f))    // caustic
    }
}

private val TrainPeriods = floatArrayOf(3.1f, 4.7f, 2.3f)
private val TrainOffsets = floatArrayOf(0f, 0.37f, 0.71f)
private val TrainGains = floatArrayOf(1f, 0.6f, 0.8f)

private fun DrawScope.drawTrain(t: Float) {
    val w = size.width; val h = size.height; val dp = density
    val warm = Color(0xFFFFC07A)
    // cosy warm cabin + window frame shading
    drawRect(Color(0xFF3A2410).copy(alpha = 0.08f))
    vignette(Color(0xFF1A0F06), 0.6f, inner = 0.45f)
    drawRect(Brush.verticalGradient(0f to Color(0xFF120B05).copy(alpha = 0.5f), 0.12f to Color.Transparent, 0.88f to Color.Transparent, 1f to Color(0xFF120B05).copy(alpha = 0.5f)))
    // rail cadence "ta-dum … ta-dum": the lights dip slightly on each rail joint
    val ph = fract(t / 1.15f)
    fun bump(c: Float): Float { val z = (ph - c) / 0.05f; return exp(-z * z) }
    val cadence = 1f - 0.07f * (bump(0.04f) + 0.75f * bump(0.24f))
    // streetlights sweeping past (scenery moves right → left), three distances / speeds
    for (k in 0 until 3) {
        val p = fract(t / TrainPeriods[k] + TrainOffsets[k])
        val cx = w * (1.35f - 1.7f * p)
        val g = TrainGains[k] * cadence
        drawRect(Brush.radialGradient(0f to warm.copy(alpha = 0.30f * g), 1f to Color.Transparent, center = Offset(cx, -h * 0.02f), radius = w * 0.45f))
        drawRect(Brush.horizontalGradient(0f to Color.Transparent, 0.5f to warm.copy(alpha = 0.05f * g), 1f to Color.Transparent, startX = cx - w * 0.28f, endX = cx + w * 0.28f))
        // the side window edges flare as the light passes them
        val left = if (cx > -w * 0.3f) (1f - cx / (w * 0.3f)).coerceIn(0f, 1f) else 0f
        val right = if (cx < w * 1.3f) (1f - (w - cx) / (w * 0.3f)).coerceIn(0f, 1f) else 0f
        if (left > 0.01f) drawRect(Brush.horizontalGradient(0f to warm.copy(alpha = 0.18f * left * g), 1f to Color.Transparent, startX = 0f, endX = w * 0.16f))
        if (right > 0.01f) drawRect(Brush.horizontalGradient(0f to Color.Transparent, 1f to warm.copy(alpha = 0.18f * right * g), startX = w * 0.84f, endX = w))
    }
    // catenary poles flicking past the top of the window
    val px = w * (1.2f - 1.4f * fract(t / 0.95f))
    val pw = 16f * dp
    drawRect(Brush.horizontalGradient(0f to Color.Transparent, 0.5f to Color.Black.copy(alpha = 0.16f), 1f to Color.Transparent, startX = px - pw, endX = px + pw),
        topLeft = Offset(px - pw, 0f), size = GeoSize(pw * 2f, h * 0.2f))
}

private const val FW_SHELLS = 5
private const val FW_SPARKS = 28
/** Share of each shell's cycle spent rising before it bursts. */
private const val FW_LAUNCH = 0.26f
private val FireworkColors = arrayOf(
    Color(0xFFFFD27A), Color(0xFFFF6B6B), Color(0xFFFF8AD8), Color(0xFF7FE7FF), Color(0xFFB79CFF), Color(0xFFFFF1D6),
)

/** Deterministic 0..1 hash so every burst cycle gets its own position / colour without per-frame allocation. */
private fun hash01(n: Int): Float {
    var x = n * 374761393 + 668265263
    x = (x xor (x ushr 13)) * 1274126177
    return ((x xor (x ushr 16)) and 0xFFFFFF) / 16777216f
}

private fun DrawScope.drawFirework(t: Float, s: AtmosphereTable) {
    val w = size.width; val h = size.height; val dp = density
    // dark new-year night sky over the page
    drawRect(Color(0xFF060A1C).copy(alpha = 0.22f))
    drawRect(Brush.verticalGradient(0f to Color(0xFF02040E).copy(alpha = 0.45f), 0.55f to Color.Transparent))
    vignette(Color(0xFF02030A), 0.55f)
    for (i in 0 until FW_SHELLS) {
        val cycle = t / s.fwPeriod[i] + s.fwPhase[i]
        val u = fract(cycle)
        val seed = floor(cycle).toInt() * 31 + i * 7
        val x = w * (0.15f + 0.7f * hash01(seed))
        val burstY = h * (0.12f + 0.3f * hash01(seed + 1))
        val color = FireworkColors[(hash01(seed + 2) * FireworkColors.size).toInt().coerceAtMost(FireworkColors.lastIndex)]
        val radius = minOf(w, h) * (0.16f + 0.1f * hash01(seed + 3))
        if (u < FW_LAUNCH) {
            // rising rocket: ease-out climb with a short fading tail
            val k = u / FW_LAUNCH
            val y = h * 1.02f + (burstY - h * 1.02f) * (1f - (1f - k) * (1f - k))
            val rx = x + sin(k * 18f + i) * 1.2f * dp
            val tail = 36f * dp
            drawLine(Brush.verticalGradient(0f to color.copy(alpha = 0.6f), 1f to Color.Transparent, startY = y, endY = y + tail),
                Offset(rx, y), Offset(x, y + tail), strokeWidth = 1.6f * dp, cap = StrokeCap.Round)
            drawCircle(Color.White.copy(alpha = 0.85f), 1.8f * dp, Offset(rx, y))
        } else {
            val k = (u - FW_LAUNCH) / (1f - FW_LAUNCH)
            val fade = (1f - k).pow(1.6f)
            val c = Offset(x, burstY)
            // soft flash lighting the sky right after the burst
            if (k < 0.25f) drawCircle(Brush.radialGradient(0f to color.copy(alpha = 0.3f * (1f - k / 0.25f)), 1f to Color.Transparent,
                center = c, radius = radius * 1.5f), radius * 1.5f, c)
            // sparks: fast radial burst that slows down, sagging under gravity, with a short streak behind each
            val kt = (k - 0.07f).coerceAtLeast(0f)
            val spread = 1f - (1f - k).pow(3); val spreadT = 1f - (1f - kt).pow(3)
            val drop = k * k * h * 0.07f; val dropT = kt * kt * h * 0.07f
            for (j in 0 until FW_SPARKS) {
                val idx = i * FW_SPARKS + j
                val r = radius * s.fwSpd[idx]
                val dx = cos(s.fwAng[idx]); val dy = sin(s.fwAng[idx])
                val head = Offset(x + dx * r * spread, burstY + dy * r * spread + drop)
                val tail = Offset(x + dx * r * spreadT, burstY + dy * r * spreadT + dropT)
                val a = fade * if (k > 0.6f) 0.65f + 0.35f * sin(t * 28f + j) else 1f // twinkle while dying out
                drawLine(color.copy(alpha = 0.45f * a), tail, head, strokeWidth = 1.4f * dp, cap = StrokeCap.Round)
                drawCircle(color.copy(alpha = a), 1.5f * dp, head)
            }
        }
    }
}

private fun DrawScope.drawSnow(t: Float, s: AtmosphereTable) {
    val w = size.width; val h = size.height; val dp = density
    drawRect(Color(0xFF0C1830).copy(alpha = 0.14f))
    vignette(Color(0xFF040A16), 0.58f)
    for (i in 0 until 64) {
        val d = s.fDepth[i]
        val vy = 0.03f + 0.08f * d
        val r = (1.2f + 2.6f * d) * dp
        val a = 0.32f + 0.48f * d
        val y = (fract((s.fPhase[i] + t * vy) / 1.1f) * 1.1f - 0.05f) * h
        // diagonal drift with the wind plus a gentle sway
        val xBase = fract((s.fX[i] + t * vy * 0.35f) / 1.1f) * 1.1f - 0.05f
        val x = xBase * w + sin(t * s.fSway[i] + s.fSwayPh[i]) * (6f + 16f * d) * dp
        val c = Offset(x, y)
        if (d > 0.45f) drawCircle(Color.White.copy(alpha = a * 0.2f), r * 2.2f, c)
        drawCircle(Color.White.copy(alpha = a), r, c)
    }
}
