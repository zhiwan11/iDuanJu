package app.iduanju.manga

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Pinch (1x..4x) / pan / double-tap zoom shared by the 条漫 list and the paged modes (双击 2.2x / 1x). */
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

/** 反色 / 灰度 colour matrix for the page images; null when both off (超分 never alters the page colours). */
fun readerColorFilter(invert: Boolean, gray: Boolean): ColorFilter? {
    if (!invert && !gray) return null
    val m = ColorMatrix()
    if (gray) m.setToSaturation(0f)
    if (invert) m.timesAssign(ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)))
    return ColorFilter.colorMatrix(m)
}

/** 自动裁白边：trims uniform white (or black) margins. In 条漫 mode only the sides are trimmed so panel gutters survive. */
class AutoCropTransformation(private val sidesOnly: Boolean) : Transformation {
    override val cacheKey = "manga.autocrop.v1.$sidesOnly"
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
    override val cacheKey = "manga.half.$right"
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

/** True for the page the reader is actually looking at (false for the preloaded neighbours a pager keeps composed). */
val LocalReaderPageActive = compositionLocalOf { true }

/**
 * Page state for [BookPageCurlViewer]. One turn at a time: [turnDir] (+1 next, −1 previous, 0 idle) and [progress]
 * 0..1 (share of the turn done, in the turn's direction) drive 覆盖 / 淡入 and the commit decision.
 *
 * 仿真翻页 additionally tracks the grabbed page point in *forward* reading space (spine at x = 0), normalised to
 * the page: [curlX] / [curlY] is where the lifted edge point is now (x = 1 flat at rest, x = −1 laid over the spine)
 * and [anchorY] is where that point sits on the free edge of the flat page (0 = top corner, 1 = bottom corner).
 * The anchor follows the live finger height through a continuous saturating curve ([cornerAnchor]), so holding a
 * curl and sliding top → middle → bottom smoothly rotates the fold through vertical with no zones or snapping.
 * A backward turn is the previous page's forward turn played in reverse.
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
    var curlX by mutableFloatStateOf(1f)
        private set
    var curlY by mutableFloatStateOf(1f)
        private set
    var anchorY by mutableFloatStateOf(1f)
        private set
    /** Bumped on every drag start (the reader hides its HUD on it, like the pager's DragInteraction). */
    var dragStarts by mutableIntStateOf(0)
        private set
    val pageCount get() = count()
    val isTurning get() = turnDir != 0
    val isDragging get() = dragging

    private var dragging by mutableStateOf(false)
    private var downX = 0f
    private var gainX = 1f
    private var targetAnchor = 1f
    /** Where the motion in flight will land (== currentPage when idle or snapping back). */
    private var goal = initialPage
    private var job: Job? = null

    private fun rest() { curlX = 1f; curlY = 1f; anchorY = 1f; targetAnchor = 1f }

    private fun finishNow() {
        job?.cancel(); job = null
        val last = (pageCount - 1).coerceAtLeast(0)
        if (goal != currentPage) currentPage = goal.coerceIn(0, last)
        goal = currentPage; turnDir = 0; progress = 0f; dragging = false
        rest()
    }

    /** Animates [progress] to 0 / 1 and the grabbed point along [path] (s = 0..1, may overshoot with a spring). */
    private fun settle(scope: CoroutineScope, target: Int, toEnd: Boolean, spec: AnimationSpec<Float>, path: (Float) -> Offset) {
        goal = target
        val from = progress
        val to = if (toEnd) 1f else 0f
        job = scope.launch {
            animate(0f, 1f, animationSpec = spec) { s, _ ->
                progress = (from + (to - from) * s).coerceIn(0f, 1f)
                val o = path(s); curlX = o.x; curlY = o.y
            }
            currentPage = target; goal = target; turnDir = 0; progress = 0f
            rest(); job = null
        }
    }

    /** Turns one page (animated unless [animate] is off); false when there is no page that way. */
    fun turn(scope: CoroutineScope, forward: Boolean, animate: Boolean): Boolean {
        val target = goal + if (forward) 1 else -1
        if (target !in 0 until pageCount) return false
        finishNow()
        if (!animate) { currentPage = target; goal = target; return true }
        turnDir = if (forward) 1 else -1
        // Like flipping a real book by its lower free corner: the corner lifts, sweeps over in a graceful arc and
        // lays the page flat over the spine. A backward turn plays the same arc in reverse.
        anchorY = 1f; targetAnchor = 1f
        settle(scope, target, toEnd = true, tween(520, easing = FastOutSlowInEasing)) { s -> TapArc.at(if (forward) s else 1f - s) }
        return true
    }

    fun jumpTo(page: Int) {
        finishNow()
        currentPage = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0)); goal = currentPage
    }

    /** [x], [y]: finger position in forward reading space, normalised 0..1 to the page. */
    internal fun dragStart(dir: Int, x: Float, y: Float): Boolean {
        finishNow()
        if (currentPage + dir !in 0 until pageCount) return false
        turnDir = dir; progress = 0f; dragging = true; dragStarts++
        downX = x
        // forward: the free edge starts under the finger and reaches the spine together with it;
        // backward: the turned-over page is pulled back so it lies flat when the finger reaches the free edge.
        gainX = if (dir > 0) 1f / x.coerceIn(0.45f, 1f) else 2f / (1f - x).coerceIn(0.55f, 1f)
        val a = cornerAnchor(y)
        anchorY = a; targetAnchor = a
        curlX = if (dir > 0) 1f else -1f; curlY = y
        return true
    }

    internal fun dragTo(x: Float, y: Float) {
        if (!dragging) return
        val dx = x - downX
        progress = (dx * -turnDir).coerceIn(0f, 1f)
        curlY = y.coerceIn(-0.15f, 1.15f)
        targetAnchor = cornerAnchor(y)
        curlX = ((if (turnDir > 0) 1f else -1f) + dx * gainX).coerceIn(-1f, 1f)
    }

    /** Frame-rate independent exponential smoothing of the anchor so the fold axis rotates supplely. */
    internal fun stepAnchor(dtSeconds: Float) {
        if (!dragging) return
        val diff = targetAnchor - anchorY
        if (abs(diff) < 1e-4f) {
            if (anchorY != targetAnchor) anchorY = targetAnchor
            return
        }
        anchorY += diff * (1f - exp(-dtSeconds.coerceIn(0f, 0.05f) / 0.045f))
    }

    /** [velocity] in page widths / s along the turn direction (positive = keep turning). */
    internal fun dragEnd(scope: CoroutineScope, velocity: Float) {
        if (!dragging) return
        dragging = false
        targetAnchor = anchorY
        val complete = velocity > 0.9f || (velocity > -0.5f && progress > 0.3f)
        val x0 = curlX; val y0 = curlY; val a = anchorY
        // forward-equivalent destinations: −1 = page laid over the spine, 1 = page flat in place; the grabbed point
        // glides back onto its anchor height so the page lands exactly flat (no sliver left at the end).
        val endX = if ((turnDir > 0) == complete) -1f else 1f
        val target = if (complete) currentPage + turnDir else currentPage
        val path = { s: Float -> Offset(x0 + (endX - x0) * s, y0 + (a - y0) * s) }
        if (complete) settle(scope, target, true, tween(340, easing = FastOutSlowInEasing), path)
        else settle(scope, target, false, spring(dampingRatio = 0.82f, stiffness = 380f), path)
    }
}

private fun smoothstep01(t: Float) = t * t * (3f - 2f * t)

/**
 * Free-edge anchor height (0..1) for a finger at height [y] (0..1): a C¹ saturating curve — the top corner for
 * y ≲ 0.3, the bottom corner for y ≳ 0.7, and an odd-symmetric sine in between that passes 0.5 at the centre,
 * so a held curl sliding vertically rotates its fold continuously through vertical.
 */
private fun cornerAnchor(y: Float): Float {
    val u = ((y - 0.5f) * 2f).coerceIn(-1f, 1f)
    val a = (abs(u) * 2.5f).coerceAtMost(1f)
    val v = sin(a * (PI / 2).toFloat())
    return 0.5f + 0.5f * (if (u < 0f) -v else v)
}

/** Tap / volume-key turn path of the bottom free corner: lifts, arcs across the page and lands over the spine. */
private object TapArc {
    private const val X0 = 1f; private const val Y0 = 1f
    private const val X1 = 0.52f; private const val Y1 = 0.80f
    private const val X2 = -0.42f; private const val Y2 = 0.84f
    private const val X3 = -1f; private const val Y3 = 1f
    fun at(s: Float): Offset {
        val u = 1f - s
        val a = u * u * u; val b = 3f * u * u * s; val c = 3f * u * s * s; val d = s * s * s
        return Offset(a * X0 + b * X1 + c * X2 + d * X3, a * Y0 + b * Y1 + c * Y2 + d * Y3)
    }
}

@Composable
fun rememberBookPagerState(initialPage: Int, pageCount: () -> Int): BookPagerState {
    val count by rememberUpdatedState(pageCount)
    return remember { BookPagerState(initialPage) { count() } }
}

private enum class BookRole { HIDDEN, UNDER, TOP }

/**
 * Book-style paged viewer with physical page turns for 左翻 / 日漫 / 双页.
 *
 * Pages current−1..current+1 stay composed under stable keys (so the revealed page never reloads when a turn
 * commits). During a turn the revealed page sits crisp underneath, and the turning page is
 * - [MangaPageAnim.CURL] a true 2D page curl following the finger in x *and* y ([drawCurlPage]);
 * - [MangaPageAnim.COVER] sliding off with a crisp 20 dp cast shadow;
 * - [MangaPageAnim.FADE] cross-fading out.
 * [rtl] mirrors the geometry (the left edge lifts), never the artwork. [pageBackground] fills each visible page.
 */
@Composable
fun BookPageCurlViewer(
    state: BookPagerState,
    effect: MangaPageAnim,
    rtl: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    paper: Color = Color(0xFFF3F0EA),
    pageBackground: Color = Color.Black,
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
    LaunchedEffect(state.isDragging) {
        if (!state.isDragging) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (state.isDragging && isActive) {
            val now = withFrameNanos { it }
            state.stepAnchor((now - last) / 1_000_000_000f)
            last = now
        }
    }
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
                                drawCurlPage(state.curlX, state.curlY, state.anchorY, mirror, paper)
                            }
                        }
                    )
                }
                Box(
                    Modifier.fillMaxSize().then(roleMod)
                        // visible pages are opaque and swallow hit-testing for the hidden ones below them
                        .then(if (role == BookRole.HIDDEN) Modifier else Modifier.background(pageBackground).pointerInput(Unit) {})
                ) {
                    CompositionLocalProvider(LocalReaderPageActive provides (role == BookRole.TOP || (role == BookRole.UNDER && i == cur))) { page(i) }
                }
            }
        }
        if (dir != 0 && effect == MangaPageAnim.COVER) {
            Canvas(Modifier.fillMaxSize().zIndex(3f)) { drawCoverShadow(t(), mirror) }
        }
    }
}

private suspend fun PointerInputScope.bookDragGestures(state: BookPagerState, scope: CoroutineScope, rtl: Boolean) {
    val tracker = VelocityTracker()
    val slop = viewConfiguration.touchSlop
    fun fx(x: Float) = (if (rtl) size.width - x else x) / size.width.coerceAtLeast(1)
    fun fy(y: Float) = y / size.height.coerceAtLeast(1)
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        tracker.resetTracking()
        tracker.addPointerInputChange(down)
        val pointerId = down.id
        var decided = false
        var active = false
        var cancelled = false
        while (true) {
            val ev = awaitPointerEvent()
            if (ev.changes.count { it.pressed } >= 2) {
                cancelled = true
                break
            }
            val change = ev.changes.firstOrNull { it.id == pointerId } ?: ev.changes.firstOrNull() ?: break
            if (!change.pressed) {
                tracker.addPointerInputChange(change)
                break
            }
            if (!decided) {
                if (change.isConsumed) {
                    cancelled = true
                    break
                }
                val totalDx = change.position.x - down.position.x
                if (abs(totalDx) > slop) {
                    decided = true
                    // reading-space delta: positive = toward the next page (finger leftward in 左翻, rightward in 日漫)
                    val fwd = if (rtl) totalDx else -totalDx
                    active = state.dragStart(if (fwd > 0f) 1 else -1, fx(down.position.x), fy(down.position.y))
                    if (!active) break
                }
            }
            if (active && change.positionChanged()) {
                tracker.addPointerInputChange(change)
                change.consume()
                state.dragTo(fx(change.position.x), fy(change.position.y))
            } else if (!decided && change.positionChanged()) {
                tracker.addPointerInputChange(change)
            }
        }
        if (active) {
            val w = size.width.coerceAtLeast(1).toFloat()
            val vx = if (!cancelled) tracker.calculateVelocity().x else 0f
            state.dragEnd(scope, (if (rtl) vx else -vx) * state.turnDir / w)
        }
    }
}

/** Fades in over the first ~12 % and out over the last ~20 % of a turn, so no shadow pops on commit. */
private fun turnEnvelope(t: Float) = (t * 8f).coerceIn(0f, 1f) * ((1f - t) * 5f).coerceIn(0f, 1f)

/** Sutherland–Hodgman half-plane clip of convex [poly] against `p · (nx, ny) <= limit` (or `>= limit` when ![keepBelow]). */
private fun clipHalfPlane(poly: List<Offset>, nx: Float, ny: Float, limit: Float, keepBelow: Boolean): List<Offset> {
    if (poly.isEmpty()) return emptyList()
    val out = ArrayList<Offset>(poly.size + 2)
    fun inside(p: Offset): Boolean {
        val d = p.x * nx + p.y * ny - limit
        return if (keepBelow) d <= 0f else d >= 0f
    }
    var a = poly.last()
    var aIn = inside(a)
    for (b in poly) {
        val bIn = inside(b)
        if (aIn != bIn) {
            val sa = a.x * nx + a.y * ny
            val sb = b.x * nx + b.y * ny
            val denom = sb - sa
            val t = if (abs(denom) > 1e-5f) ((limit - sa) / denom).coerceIn(0f, 1f) else 0.5f
            out.add(Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
        }
        if (bIn) out.add(b)
        a = b; aIn = bIn
    }
    return out
}

/**
 * Projects the page polygon [poly] (forward space) through the curl map [phi] — identity on the flat page, a
 * cylinder wrap inside `(sC, sEnd)` and a flat mirror beyond — subdividing the stretches that lie on the cylinder so
 * the rolled outline stays round, then into screen space via [m].
 */
private inline fun curlPath(
    poly: List<Offset>, nx: Float, ny: Float, sC: Float, sEnd: Float,
    phi: (Offset) -> Offset, m: (Offset) -> Offset,
): Path {
    val path = Path()
    if (poly.size < 3) return path
    val start = m(phi(poly[0]))
    path.moveTo(start.x, start.y)
    val ts = FloatArray(4)
    for (idx in poly.indices) {
        val a = poly[idx]; val b = poly[(idx + 1) % poly.size]
        val sa = a.x * nx + a.y * ny; val sb = b.x * nx + b.y * ny
        var n = 0
        ts[n++] = 0f
        if (abs(sb - sa) > 1e-4f) {
            val t0 = (sC - sa) / (sb - sa); if (t0 > 1e-4f && t0 < 1f - 1e-4f) ts[n++] = t0
            val t1 = (sEnd - sa) / (sb - sa); if (t1 > 1e-4f && t1 < 1f - 1e-4f) ts[n++] = t1
            if (n == 3 && ts[1] > ts[2]) { val k = ts[1]; ts[1] = ts[2]; ts[2] = k }
        }
        ts[n++] = 1f
        for (seg in 0 until n - 1) {
            val t0 = ts[seg]; val t1 = ts[seg + 1]
            val sMid = sa + (sb - sa) * (t0 + t1) * 0.5f
            val steps = if (sMid > sC && sMid < sEnd && abs(sb - sa) * (t1 - t0) > 0.5f) 10 else 1
            for (k in 1..steps) {
                val t = t0 + (t1 - t0) * (k / steps.toFloat())
                val q = m(phi(Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)))
                path.lineTo(q.x, q.y)
            }
        }
    }
    path.close()
    return path
}

/** Affine matrix (screen space) taking the three [src] points onto [dst]. */
private fun affine(src: Array<Offset>, dst: Array<Offset>): android.graphics.Matrix = android.graphics.Matrix().apply {
    setPolyToPoly(
        floatArrayOf(src[0].x, src[0].y, src[1].x, src[1].y, src[2].x, src[2].y), 0,
        floatArrayOf(dst[0].x, dst[0].y, dst[1].x, dst[1].y, dst[2].x, dst[2].y), 0, 3,
    )
}

/**
 * 仿真翻页: a physical corner-peel page curl by true geometric folding.
 *
 * In forward space (spine at x = 0, free edge at x = w) the grabbed point A = (w, [anchor]·h) of the flat page has
 * been carried to T = ([ux]·w, y): y glides from the anchor height to the finger height [uy] as the curl opens.
 * T is kept within reach of both spine corners (the page never tears off the spine). The fold is perpendicular to
 * A − T: paper with fold coordinate s ≤ sC stays flat, `sC..sC + πr` wraps round a cylinder of radius r, and
 * beyond it lies flat again *mirrored over onto the near side*, where its far point A lands exactly on T. So the
 * real 90° corner(s) and straight page edges of the peeled-away part fold over the current page, like paper.
 *
 * Layers: 0. the next page underneath ([BookRole.UNDER]); 1. the current page's flat part; 2. the front of the
 * roll rising off the page; 3. the soft shadow on the revealed next page along the crest and the flap's soft drop
 * shadow on the current page; 4. the flap — paper back with faint mirrored bleed-through, cylindrical roll shading,
 * specular highlight along the crease and a crisp paper edge. [mirror] (日漫) flips the geometry, never the artwork.
 */
private fun ContentDrawScope.drawCurlPage(ux: Float, uy: Float, anchor: Float, mirror: Boolean, paper: Color) {
    val w = size.width; val h = size.height
    if (w < 2f || h < 2f || ux >= 0.999f) {
        drawContent()
        return
    }
    val p = ((1f - ux) * 0.5f).coerceIn(0f, 1f)
    val env = turnEnvelope(p)

    // ── fold geometry ──
    val ax = w
    val ay = anchor.coerceIn(0f, 1f) * h
    val follow = smoothstep01(((1f - ux) / 0.5f).coerceIn(0f, 1f))
    var tx = ux.coerceIn(-1f, 1f) * w
    var ty = ay + (uy * h - ay) * follow
    // spine constraint: folding is an isometry and the spine corners stay put, so T may never be farther from
    // either spine corner than A is (otherwise the page would have to detach from the spine).
    repeat(2) {
        for (sy in floatArrayOf(0f, h)) {
            val reach = hypot(ax, ay - sy)
            val dy = ty - sy
            val d = hypot(tx, dy)
            if (d > reach && d > 1e-3f) { tx *= reach / d; ty = sy + dy * reach / d }
        }
    }
    val dist = hypot(ax - tx, ay - ty)
    if (dist < 0.75f) {
        drawContent()
        return
    }
    val nx = (ax - tx) / dist; val ny = (ay - ty) / dist
    val pi = PI.toFloat()
    // roll radius: grows from a tight crease as the corner lifts, capped at a slim paper tube
    val r = minOf(dist * 0.12f, 0.045f * w, 26.dp.toPx()).coerceAtLeast(0.5f)
    val sT = tx * nx + ty * ny; val sA = ax * nx + ay * ny
    val sC = (sT + sA - pi * r) * 0.5f // where the paper leaves the page (A then maps exactly onto T)
    val sM = sC + pi * r * 0.5f       // top of the roll: the paper turns from front-facing to back-facing
    val sEnd = sC + pi * r            // end of the roll: flat, mirrored flap beyond
    val sCrest = sC + r               // projected outer edge of the roll

    fun s(q: Offset) = q.x * nx + q.y * ny
    fun phi(q: Offset): Offset {
        val sq = s(q)
        val ds = when {
            sq <= sC -> 0f
            sq < sEnd -> sC + r * sin((sq - sC) / r) - sq
            else -> 2f * sC + pi * r - 2f * sq
        }
        return Offset(q.x + nx * ds, q.y + ny * ds)
    }
    fun m(q: Offset) = Offset(if (mirror) w - q.x else q.x, q.y)
    fun at(sv: Float) = m(Offset(sv * nx, sv * ny))
    fun band(v0: Float, v1: Float): Path {
        val far = (w + h) * 4f
        fun pt(sv: Float, along: Float) = m(Offset(sv * nx - ny * along, sv * ny + nx * along))
        val p1 = pt(v0, -far); val p2 = pt(v0, far); val p3 = pt(v1, far); val p4 = pt(v1, -far)
        return Path().apply { moveTo(p1.x, p1.y); lineTo(p2.x, p2.y); lineTo(p3.x, p3.y); lineTo(p4.x, p4.y); close() }
    }

    val rect = listOf(Offset(0f, 0f), Offset(w, 0f), Offset(w, h), Offset(0f, h))
    val polyFlat = clipHalfPlane(rect, nx, ny, sC, keepBelow = true)
    val polyRise = clipHalfPlane(clipHalfPlane(rect, nx, ny, sC, keepBelow = false), nx, ny, sM, keepBelow = true)
    val polyFlap = clipHalfPlane(rect, nx, ny, sM, keepBelow = false)
    if (polyRise.size < 3 && polyFlap.size < 3) {
        drawContent()
        return
    }
    val pathFlat = curlPath(polyFlat, nx, ny, sC, sEnd, ::phi, ::m)
    val pathRise = curlPath(polyRise, nx, ny, sC, sEnd, ::phi, ::m)
    val pathFlap = curlPath(polyFlap, nx, ny, sC, sEnd, ::phi, ::m)
    val hasFlap = polyFlap.size >= 3

    // crease line of the flap (its edge on the roll top, projected onto the crest)
    var crest0: Offset? = null; var crest1: Offset? = null
    for (idx in polyFlap.indices) {
        val a = polyFlap[idx]; val b = polyFlap[(idx + 1) % polyFlap.size]
        if (abs(s(a) - sM) < 0.05f && abs(s(b) - sM) < 0.05f && hypot(b.x - a.x, b.y - a.y) > 0.5f) {
            crest0 = m(phi(a)); crest1 = m(phi(b)); break
        }
    }
    // how far the mirrored flap reaches back over the page (for its shading span)
    var flapFar = sCrest
    for (q in polyFlap) flapFar = minOf(flapFar, s(phi(q)))

    // 1. the current page's flat part, darkening softly where the paper starts lifting
    clipPath(pathFlat) {
        this@drawCurlPage.drawContent()
        if (r >= 1f) {
            drawPath(band(sC - 2.5f * r, sC), Brush.linearGradient(
                0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.10f * env),
                start = at(sC - 2.5f * r), end = at(sC)))
        }
    }

    // 2. front of the roll rising off the page: the page compressed onto the roll's projection, shaded to the crest
    if (polyRise.size >= 3) {
        val k = 1f - 2f / pi
        fun squash(q: Offset): Offset { val d = (s(q) - sC) * k; return Offset(q.x - nx * d, q.y - ny * d) }
        val ref = arrayOf(Offset(0f, 0f), Offset(w, 0f), Offset(0f, h))
        val mat = affine(Array(3) { m(ref[it]) }, Array(3) { m(squash(ref[it])) })
        clipPath(pathRise) {
            drawIntoCanvas { it.save(); it.nativeCanvas.concat(mat) }
            this@drawCurlPage.drawContent()
            drawIntoCanvas { it.restore() }
            drawPath(band(sC, sCrest), Brush.linearGradient(
                0f to Color.Black.copy(alpha = 0.06f), 0.6f to Color.Black.copy(alpha = 0.22f), 1f to Color.Black.copy(alpha = 0.48f),
                start = at(sC), end = at(sCrest)))
        }
    }

    // 3. shadows: along the crest onto the revealed next page, and the lifted flap's soft drop shadow on the page
    clipPath(pathFlap, ClipOp.Difference) {
        clipPath(pathRise, ClipOp.Difference) {
            clipPath(pathFlat, ClipOp.Difference) {
                val reach = maxOf(r * 3.2f, 20.dp.toPx()) * (0.6f + 0.8f * p)
                drawPath(band(sCrest - 1f, sCrest + reach), Brush.linearGradient(
                    0f to Color.Black.copy(alpha = 0.46f * env), 0.3f to Color.Black.copy(alpha = 0.20f * env), 1f to Color.Transparent,
                    start = at(sCrest), end = at(sCrest + reach)))
            }
            if (hasFlap) {
                val lift = (0.5f + 0.5f * smoothstep01((dist / (0.35f * w)).coerceIn(0f, 1f))) * env
                val base = 4.dp.toPx()
                for (i in 1..4) {
                    drawPath(pathFlap, Color.Black.copy(alpha = 0.055f * lift),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = base * i * i * 0.55f + base,
                            join = androidx.compose.ui.graphics.StrokeJoin.Round))
                }
            }
        }
    }

    // 4. the flap: back of the paper lying over the page
    if (hasFlap) clipPath(pathFlap) {
        drawRect(paper)
        // faint bleed-through of the page's own print, mirrored across the fold (exact on the flat flap)
        val ref = arrayOf(Offset(0f, 0f), Offset(w, 0f), Offset(0f, h))
        fun mirrorFold(q: Offset): Offset { val d = 2f * (s(q) - sM); return Offset(q.x - nx * d, q.y - ny * d) }
        val mat = affine(Array(3) { m(ref[it]) }, Array(3) { m(mirrorFold(ref[it])) })
        drawIntoCanvas { canvas ->
            canvas.saveLayer(pathFlap.getBounds().intersect(Rect(0f, 0f, w, h)), Paint().apply { alpha = 0.09f })
            canvas.nativeCanvas.concat(mat)
        }
        this@drawCurlPage.drawContent()
        drawIntoCanvas { it.restore() }
        // gentle falloff across the flat flap (lit near the roll, a touch darker toward its far corner)
        val span = (sCrest - flapFar).coerceAtLeast(2f)
        drawRect(Brush.linearGradient(
            0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.07f),
            start = at(sC), end = at(sCrest - span)))
        // cylindrical roll: dark at the grazing crest, specular highlight, then the bright top of the tube
        if (r >= 1f) {
            drawPath(band(sC - r, sCrest), Brush.linearGradient(
                0f to Color.Black.copy(alpha = 0.40f),
                0.10f to Color.Black.copy(alpha = 0.22f),
                0.26f to Color.White.copy(alpha = 0.06f),
                0.38f to Color.White.copy(alpha = 0.50f),
                0.52f to Color.White.copy(alpha = 0.14f),
                0.75f to Color.Black.copy(alpha = 0.04f),
                1f to Color.Transparent,
                start = at(sCrest), end = at(sC - r)))
        }
        // crisp paper edge all round + the crease line on the crest
        drawPath(pathFlap, Color.Black.copy(alpha = 0.16f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx() * 0.75f))
        val c0 = crest0; val c1 = crest1
        if (c0 != null && c1 != null) drawLine(Color.Black.copy(alpha = 0.28f * env), c0, c1, strokeWidth = 1.dp.toPx())
    }
}

private fun DrawScope.drawCoverShadow(t: Float, mirror: Boolean) {
    if (t <= 0.001f || t >= 0.999f) return
    val edge = size.width * (1f - t)
    val sw = 20.dp.toPx()
    val a = 0.5f * turnEnvelope(t)
    val x0 = if (mirror) size.width - edge else edge
    val x1 = if (mirror) x0 - sw else x0 + sw
    drawRect(Brush.horizontalGradient(0f to Color.Black.copy(alpha = a), 0.4f to Color.Black.copy(alpha = a * 0.35f), 1f to Color.Transparent, startX = x0, endX = x1),
        topLeft = Offset(minOf(x0, x1), 0f), size = GeoSize(sw, size.height))
}

// ── 沉浸氛围：雨天窗景 / 列车车窗 / 新年烟火 / 雪夜 ──

/**
 * Immersive reading atmosphere drawn over the pages (below the HUD). Draw-only: no pointer handlers, so
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
