package app.iduanju.manga

import android.graphics.Bitmap
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.IntSize
import coil.size.Size
import coil.transform.Transformation
import kotlin.math.abs

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
