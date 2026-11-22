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
import kotlin.math.atan2
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

/** 反色 / 灰度 (+ the 标准 ink / paper levels below Android 13) colour matrix for the page images; null when all off. */
fun readerColorFilter(invert: Boolean, gray: Boolean, inkLevels: Boolean = false): ColorFilter? {
    if (!invert && !gray && !inkLevels) return null
    val m = ColorMatrix()
    if (gray) m.setToSaturation(0f)
    if (inkLevels) m.timesAssign(ColorMatrix(mangaInkLevels()))
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
 * 仿真翻页 additionally tracks the continuous curl coordinates ([curlX], [curlY]) in *forward* reading space
 * normalised to the page: x = 1 is flat at rest, x = −1 is fully turned over the spine; y ∈ 0..1 tracks the
 * live vertical finger position (0 = top edge, 0.5 = vertical center roll, 1 = bottom edge) with zero discrete
 * zone locking. A backward turn is the previous page's forward turn played in reverse.
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
    var curlY by mutableFloatStateOf(0.5f)
        private set
    /** Bumped on every drag start (the reader hides its HUD on it, like the pager's DragInteraction). */
    var dragStarts by mutableIntStateOf(0)
        private set
    val pageCount get() = count()
    val isTurning get() = turnDir != 0
    /** While a finger holds a forward curl the roll stays inside the spine edge (see [drawCurlPage]). */
    val isDragging get() = dragging

    private var dragging = false
    private var downX = 0f
    private var targetCurlY = 0.5f
    /** Where the motion in flight will land (== currentPage when idle or snapping back). */
    private var goal = initialPage
    private var job: Job? = null

    private fun finishNow() {
        job?.cancel(); job = null
        val last = (pageCount - 1).coerceAtLeast(0)
        if (goal != currentPage) currentPage = goal.coerceIn(0, last)
        goal = currentPage; turnDir = 0; progress = 0f; dragging = false
        curlX = 1f; curlY = 0.5f; targetCurlY = 0.5f
    }

    /** Animates [progress] to 0 / 1 and the curl coordinates along [path] (s = 0..1, may overshoot with a spring). */
    private fun settle(scope: CoroutineScope, target: Int, toEnd: Boolean, spec: AnimationSpec<Float>, path: (Float) -> Offset) {
        goal = target
        val from = progress
        val to = if (toEnd) 1f else 0f
        job = scope.launch {
            animate(0f, 1f, animationSpec = spec) { s, _ ->
                progress = (from + (to - from) * s).coerceIn(0f, 1f)
                val o = path(s); curlX = o.x; curlY = o.y; targetCurlY = o.y
            }
            currentPage = target; goal = target; turnDir = 0; progress = 0f
            curlX = 1f; curlY = 0.5f; targetCurlY = 0.5f; job = null
        }
    }

    /** Turns one page (animated unless [animate] is off); false when there is no page that way. */
    fun turn(scope: CoroutineScope, forward: Boolean, animate: Boolean): Boolean {
        val target = goal + if (forward) 1 else -1
        if (target !in 0 until pageCount) return false
        finishNow()
        if (!animate) { currentPage = target; goal = target; return true }
        turnDir = if (forward) 1 else -1
        // Like flipping a real book from the lower-right corner: the corner lifts, sweeps over in a graceful
        // parabolic arc and lays the page flat on the left. A backward turn plays the same arc in reverse.
        settle(scope, target, toEnd = true, tween(460, easing = FastOutSlowInEasing)) { s -> TapArc.at(if (forward) s else 1f - s) }
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
        val cy = y.coerceIn(0f, 1f)
        curlX = if (dir > 0) 1f else -1f; curlY = cy; targetCurlY = cy
        return true
    }

    internal fun dragTo(x: Float, y: Float) {
        if (!dragging) return
        val dx = x - downX
        progress = (dx * -turnDir).coerceIn(0f, 1f)
        targetCurlY = y.coerceIn(0f, 1f)
        curlX = if (turnDir > 0) (1f + dx * 1.15f).coerceIn(-1f, 1f) else (-1f + dx * 2f).coerceIn(-1f, 1f)
    }

    /** Frame-rate independent exponential smoothing on [curlY] for supple vertical sweeps. */
    internal fun stepCurlY(dtSeconds: Float) {
        if (!dragging) return
        val diff = targetCurlY - curlY
        if (abs(diff) < 1e-4f) {
            if (curlY != targetCurlY) curlY = targetCurlY
            return
        }
        val alpha = 1f - exp(-dtSeconds.coerceIn(0f, 0.05f) / 0.022f)
        curlY += diff * alpha
    }

    /** [velocity] in page widths / s along the turn direction (positive = keep turning). */
    internal fun dragEnd(scope: CoroutineScope, velocity: Float) {
        if (!dragging) return
        dragging = false
        val complete = velocity > 0.9f || (velocity > -0.5f && progress > 0.3f)
        val x0 = curlX; val y0 = curlY
        // forward-equivalent destinations: −1 = page laid over the spine, 1 = page flat in place
        val toTurned = (turnDir > 0) == complete
        val endX = if (toTurned) -1f else 1f
        val target = if (complete) currentPage + turnDir else currentPage
        if (complete) {
            // released past threshold / flicked: sails smoothly off-screen along the current tilt
            settle(scope, target, true, tween(300, easing = FastOutSlowInEasing)) { s ->
                Offset(x0 + (endX - x0) * s, y0)
            }
        } else {
            // cancelled: springs smoothly back to flat along the current tilt
            settle(scope, target, false, spring(dampingRatio = 0.78f, stiffness = 420f)) { s ->
                Offset(x0 + (endX - x0) * s, y0)
            }
        }
    }
}

private fun smoothstep01(t: Float) = t * t * (3f - 2f * t)

/** Tap / volume-key turn path: flat → lifted lower-right corner → smooth diagonal sweep → laid flat. */
private object TapArc {
    private const val X0 = 1f; private const val Y0 = 0.90f
    private const val X1 = 0.58f; private const val Y1 = 0.85f
    private const val X2 = -0.35f; private const val Y2 = 0.72f
    private const val X3 = -1f; private const val Y3 = 0.62f
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
            state.stepCurlY((now - last) / 1_000_000_000f)
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
                                drawCurlPage(state.curlX, state.curlY, mirror, paper,
                                    restrict = state.isDragging && state.turnDir > 0)
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
 * Builds the 2D projection of a convex clipped page polygon wrapped over a cylinder of radius [rCyl]
 * starting at normal coordinate [sC] (with full 180° wrap ending at `sC + PI * rCyl`), mapped to screen via [m].
 */
private inline fun buildCylinderPath(
    poly: List<Offset>,
    nx: Float,
    ny: Float,
    sC: Float,
    s1: Float,
    sRef: Float,
    phi: (Offset) -> Offset,
    m: (Offset) -> Offset,
): Path {
    val path = Path()
    if (poly.size < 3) return path
    val first = m(phi(poly[0]))
    path.moveTo(first.x, first.y)
    val n = poly.size
    for (idx in 0 until n) {
        val a = poly[idx]
        val b = poly[(idx + 1) % n]
        val sa = a.x * nx + a.y * ny
        val sb = b.x * nx + b.y * ny
        val onCrest = abs(sa - sRef) < 0.05f && abs(sb - sRef) < 0.05f
        val allFlat = (sa <= sC && sb <= sC) || (sa >= s1 && sb >= s1)
        if (onCrest || allFlat || abs(sb - sa) < 0.05f) {
            val p = m(phi(b))
            path.lineTo(p.x, p.y)
        } else {
            val ts = FloatArray(4)
            var count = 0
            ts[count++] = 0f
            val tc = (sC - sa) / (sb - sa)
            if (tc > 1e-4f && tc < 1f - 1e-4f) ts[count++] = tc
            val t1 = (s1 - sa) / (sb - sa)
            if (t1 > 1e-4f && t1 < 1f - 1e-4f) ts[count++] = t1
            ts[count++] = 1f
            // Sort the up-to-4 split parameters
            for (i in 1 until count) {
                val key = ts[i]; var j = i - 1
                while (j >= 0 && ts[j] > key) { ts[j + 1] = ts[j]; j-- }
                ts[j + 1] = key
            }
            for (seg in 0 until count - 1) {
                val tStart = ts[seg]; val tEnd = ts[seg + 1]
                val sMidSeg = sa + (sb - sa) * ((tStart + tEnd) * 0.5f)
                if (sMidSeg > sC && sMidSeg < s1) {
                    val steps = 8
                    for (k in 1..steps) {
                        val t = tStart + (tEnd - tStart) * (k / steps.toFloat())
                        val p = m(phi(Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)))
                        path.lineTo(p.x, p.y)
                    }
                } else {
                    val p = m(phi(Offset(a.x + (b.x - a.x) * tEnd, a.y + (b.y - a.y) * tEnd)))
                    path.lineTo(p.x, p.y)
                }
            }
        }
    }
    path.close()
    return path
}

/**
 * 仿真翻页: continuous $C^1$ cylindrical page-curl draw pass driven smoothly by live `(ux, uy)`:
 *
 * The fold axis rotates continuously from top-corner peel (`uy = 0`) through a perfectly vertical cylinder roll
 * (`uy = 0.5`) to bottom-corner peel (`uy = 1`) with zero discrete zones or discontinuities. Drawn in 4 layers:
 *  0. the revealed next page underneath ([BookRole.UNDER]),
 *  1. the current page clipped to the flat + front-roll region (`pathFront`) with inner crease bend shading and
 *     soft flap shadow cast beneath the curled edge,
 *  2. soft diagonal drop shadows cast onto the revealed next page outside both `pathFront` and `pathBack`,
 *  3. the curled-back underside (`pathBack`) sitting over `pathFront`: paper base + mirrored page bleed-through
 *     reflected across the fold axis + cylindrical crease shadow → specular highlight → paper shading + crest hairline.
 */
private fun ContentDrawScope.drawCurlPage(ux: Float, uy: Float, mirror: Boolean, paper: Color, restrict: Boolean) {
    val w = size.width; val h = size.height
    if (w < 2f || h < 2f || ux >= 0.999f) {
        drawContent()
        return
    }
    if (ux <= -0.999f) return

    val p = ((1f - ux) * 0.5f).coerceIn(0f, 1f)
    val env = turnEnvelope(p)

    // Continuous fold angle: 0 at vertical center (uy = 0.5), smoothly tilting toward top (-theta) or bottom (+theta),
    // modulated smoothly by horizontal turn progress so the page rests & lands flat along the vertical edges.
    val v = (uy * 2f - 1f).coerceIn(-1f, 1f)
    val maxTilt = atan2(1.25f * w, h).coerceIn(0.42f, 0.72f)
    val tiltGain = smoothstep01((p / 0.06f).coerceIn(0f, 1f)) * smoothstep01(((0.96f - p) / 0.24f).coerceIn(0f, 1f))
    val theta = v * maxTilt * tiltGain
    val nx = cos(theta); val ny = sin(theta)

    val sMin = minOf(0f, h * ny)
    val sMax = w * nx + maxOf(0f, h * ny)
    val pivotX = w * (1f - p)
    val pivotY = uy.coerceIn(0f, 1f) * h
    var sRef = pivotX * nx + pivotY * ny
    if (restrict) sRef = maxOf(sRef, sMin + 0.02f * w)

    val tail = (sMax - sRef).coerceAtLeast(0f)
    val head = (sRef - sMin).coerceAtLeast(0f)
    if (tail <= 0.5f) {
        drawContent()
        return
    }
    if (head <= 0.5f) return

    val rollRamp = smoothstep01((tail / (0.28f * w)).coerceIn(0f, 1f)) * smoothstep01((head / (0.20f * w)).coerceIn(0f, 1f))
    val rCyl = minOf(0.125f * w * rollRamp, 0.38f * tail, 0.55f * head).coerceAtLeast(0.25f)
    val halfPi = (PI * 0.5).toFloat()
    val sC = sRef - halfPi * rCyl
    val sD = sC + rCyl
    val s1 = sC + PI.toFloat() * rCyl

    fun s(q: Offset) = q.x * nx + q.y * ny
    fun phi(q: Offset): Offset {
        val sq = s(q)
        val ds = when {
            sq <= sC -> 0f
            sq < s1 -> sC + rCyl * sin((sq - sC) / rCyl) - sq
            else -> 2f * sRef - 2f * sq
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
    val polyFront = clipHalfPlane(rect, nx, ny, sRef, keepBelow = true)
    val polyBack = clipHalfPlane(rect, nx, ny, sRef, keepBelow = false)
    if (polyFront.size < 3) return
    if (polyBack.size < 3) {
        drawContent()
        return
    }

    val pathFront = buildCylinderPath(polyFront, nx, ny, sC, s1, sRef, ::phi, ::m)
    val pathBack = buildCylinderPath(polyBack, nx, ny, sC, s1, sRef, ::phi, ::m)

    // Mapped vertices & centroid of the curled-back flap (used for shading span, crest hairline, and outward edge shadows).
    val mappedBack = Array(polyBack.size) { idx -> phi(polyBack[idx]) }
    var sA = sD
    var cx = 0f; var cy = 0f
    for (pt in mappedBack) {
        sA = minOf(sA, s(pt))
        cx += pt.x; cy += pt.y
    }
    val backCenter = Offset(cx / mappedBack.size, cy / mappedBack.size)

    var crest0: Offset? = null; var crest1: Offset? = null
    for (idx in polyBack.indices) {
        val a = polyBack[idx]; val b = polyBack[(idx + 1) % polyBack.size]
        if (abs(s(a) - sRef) < 0.1f && abs(s(b) - sRef) < 0.1f && hypot(b.x - a.x, b.y - a.y) > 0.5f) {
            crest0 = mappedBack[idx]; crest1 = mappedBack[(idx + 1) % polyBack.size]
            break
        }
    }

    fun drawFlapEdgeShadows(alphaPeak: Float, widthPx: Float) {
        if (alphaPeak <= 0.002f || widthPx < 1f) return
        for (idx in polyBack.indices) {
            val rawA = polyBack[idx]; val rawB = polyBack[(idx + 1) % polyBack.size]
            if (abs(s(rawA) - sRef) < 0.1f && abs(s(rawB) - sRef) < 0.1f) continue
            val pA = mappedBack[idx]; val pB = mappedBack[(idx + 1) % polyBack.size]
            val ex = pB.x - pA.x; val ey = pB.y - pA.y
            val len = hypot(ex, ey)
            if (len < 1.5f) continue
            var ox = -ey / len; var oy = ex / len
            if ((backCenter.x - pA.x) * ox + (backCenter.y - pA.y) * oy > 0f) { ox = -ox; oy = -oy }
            val q1 = m(pA); val q2 = m(pB)
            val q3 = m(Offset(pB.x + ox * widthPx, pB.y + oy * widthPx))
            val q4 = m(Offset(pA.x + ox * widthPx, pA.y + oy * widthPx))
            val strip = Path().apply { moveTo(q1.x, q1.y); lineTo(q2.x, q2.y); lineTo(q3.x, q3.y); lineTo(q4.x, q4.y); close() }
            val mid = Offset((pA.x + pB.x) * 0.5f, (pA.y + pB.y) * 0.5f)
            drawPath(strip, Brush.linearGradient(0f to Color.Black.copy(alpha = alphaPeak), 1f to Color.Transparent,
                start = m(mid), end = m(Offset(mid.x + ox * widthPx, mid.y + oy * widthPx))))
        }
    }

    // 1. current page clipped to its un-curled + front-roll region, with inner crease shading and flap shadow
    clipPath(pathFront) {
        this@drawCurlPage.drawContent()
        if (rCyl >= 1f) {
            val bendStart = sC - 0.35f * rCyl
            drawPath(band(bendStart, sD), Brush.linearGradient(
                0f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.24f * env),
                start = at(bendStart), end = at(sD)))
        }
        clipPath(pathBack, ClipOp.Difference) {
            drawFlapEdgeShadows(alphaPeak = 0.15f * env, widthPx = 10.dp.toPx())
        }
    }

    // 2. soft diagonal drop shadow cast onto the revealed next page (outside both pathFront and pathBack)
    clipPath(pathFront, ClipOp.Difference) {
        clipPath(pathBack, ClipOp.Difference) {
            val reach = maxOf(tail * 0.30f, rCyl * 1.4f, 16.dp.toPx())
            val knee = ((sD - sC) / (sD + reach - sC)).coerceIn(0f, 0.9f)
            drawPath(band(sC, sD + reach), Brush.linearGradient(
                0f to Color.Black.copy(alpha = 0.50f * env), knee to Color.Black.copy(alpha = 0.50f * env),
                knee + (1f - knee) * 0.35f to Color.Black.copy(alpha = 0.20f * env), 1f to Color.Transparent,
                start = at(sC), end = at(sD + reach)))
            drawFlapEdgeShadows(alphaPeak = 0.26f * env, widthPx = 12.dp.toPx() + 16.dp.toPx() * p)
        }
    }

    // 3. the curled-back underside sitting above pathFront: paper + mirrored bleed-through + cylindrical roll shading
    clipPath(pathBack) {
        drawRect(paper)
        // Reflection across the fold axis (s = sRef) in screen space.
        val g = Offset(sRef * nx, sRef * ny)
        val ga = m(g); val ua = m(Offset(g.x - ny, g.y + nx)) - ga
        val ul = hypot(ua.x, ua.y).coerceAtLeast(1e-3f)
        val vx = ua.x / ul; val vy = ua.y / ul
        val r00 = 2f * vx * vx - 1f; val r01 = 2f * vx * vy; val r11 = 2f * vy * vy - 1f
        val matrix = android.graphics.Matrix().apply {
            setValues(floatArrayOf(r00, r01, ga.x - (r00 * ga.x + r01 * ga.y), r01, r11, ga.y - (r01 * ga.x + r11 * ga.y), 0f, 0f, 1f))
        }
        drawIntoCanvas { canvas ->
            canvas.saveLayer(Rect(0f, 0f, w, h), Paint().apply { alpha = 0.16f })
            canvas.nativeCanvas.concat(matrix)
        }
        this@drawCurlPage.drawContent()
        drawIntoCanvas { it.restore() }
        val span = (sD - sA).coerceAtLeast(2f)
        val hl = (rCyl / span).coerceIn(0.04f, 0.4f)
        drawRect(Brush.linearGradient(
            0f to Color.Black.copy(alpha = 0.34f),
            hl * 0.25f to Color.Black.copy(alpha = 0.12f),
            hl * 0.7f to Color.White.copy(alpha = 0.55f),
            hl * 1.3f to Color.White.copy(alpha = 0.12f),
            (hl * 2.2f).coerceAtMost(0.9f) to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.10f),
            start = at(sD), end = at(sD - span)))
        if (crest0 != null && crest1 != null) {
            val c0 = m(crest0); val c1 = m(crest1)
            if (hypot(c1.x - c0.x, c1.y - c0.y) > 1f) {
                drawLine(Color.Black.copy(alpha = 0.18f * env), c0, c1, strokeWidth = 1.dp.toPx())
            }
        }
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
