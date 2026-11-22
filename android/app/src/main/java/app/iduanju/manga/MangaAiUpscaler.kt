package app.iduanju.manga

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Offline native 2× line-art restoration. One inference at a time, with priority for the visible page. */
internal object MangaAiUpscaler {
    private const val SCALE = 2
    private const val PAD = 18
    private const val MAX_INPUT = 272
    private const val CORE = MAX_INPUT - 2 * PAD
    private const val OVERLAP = 24
    private const val MAX_OUTPUT = CORE * SCALE
    private const val MAX_OUT_SIDE = 2600
    private const val MAX_BITMAP_SIDE = 8192
    private const val PAGE_MAX_WORK_PIXELS = 1080 * 1600
    private const val STRIP_MAX_WORK_PIXELS = 1_300_000

    class Preempted : Exception("yielded to the visible page")

    data class Key(val url: String, val headers: Map<String, String>, val crop: Boolean, val half: Int, val webtoon: Boolean)

    private val maxCacheBytes = (Runtime.getRuntime().maxMemory() / 5).coerceIn(48L shl 20, 192L shl 20).toInt()
    private val cache = object : LruCache<Key, Bitmap>(maxCacheBytes) {
        override fun sizeOf(key: Key, value: Bitmap) = value.byteCount
    }
    private val inferenceLock = Mutex()
    private val foregroundWaiting = AtomicInteger(0)
    var busy by mutableStateOf(false)
        private set

    private val environment by lazy { OrtEnvironment.getEnvironment() }
    private var session: OrtSession? = null
    private val inputBuffer by lazy { floatBuffer(3 * MAX_INPUT * MAX_INPUT) }
    private val outputBuffer by lazy { floatBuffer(3 * MAX_OUTPUT * MAX_OUTPUT) }

    fun getCached(key: Key): Bitmap? = cache.get(key)

    private fun workSize(source: Bitmap, fillWidth: Boolean): Pair<Int, Int>? {
        val width = source.width
        val height = source.height
        if (width < 16 || height < 16) return null
        val longSide = maxOf(width, height)
        val displaySide = if (fillWidth) width else longSide
        val maxWork = if (fillWidth) STRIP_MAX_WORK_PIXELS else PAGE_MAX_WORK_PIXELS
        val scale = minOf(
            1.0,
            MAX_OUT_SIDE.toDouble() / (displaySide.toDouble() * SCALE),
            MAX_BITMAP_SIDE.toDouble() / (longSide.toDouble() * SCALE),
            sqrt(maxWork / (width.toDouble() * height)),
        )
        if (scale <= 0.5) return null
        return (width * scale).roundToInt() to (height * scale).roundToInt()
    }

    suspend fun upscale(context: Context, key: Key, source: Bitmap, foreground: () -> Boolean): Bitmap? {
        getCached(key)?.let { return it }
        val (width, height) = workSize(source, key.webtoon) ?: return null
        return withContext(Dispatchers.Default) {
            var waiting = foreground()
            if (waiting) foregroundWaiting.incrementAndGet()
            try {
                inferenceLock.withLock {
                    if (waiting) {
                        foregroundWaiting.decrementAndGet()
                        waiting = false
                    }
                    getCached(key) ?: try {
                        busy = true
                        infer(context, source, width, height) { !foreground() && foregroundWaiting.get() > 0 }
                            .also { cache.put(key, it) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Preempted) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("IDJ", "Manga AI upscale failed", e)
                        null
                    } catch (e: OutOfMemoryError) {
                        cache.evictAll()
                        Log.w("IDJ", "Not enough memory for manga AI upscale", e)
                        null
                    } catch (e: LinkageError) {
                        Log.w("IDJ", "Manga AI runtime unavailable", e)
                        null
                    } finally {
                        busy = false
                    }
                }
            } finally {
                if (waiting) foregroundWaiting.decrementAndGet()
            }
        }
    }

    private fun obtainSession(context: Context): OrtSession = session ?: OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        options.setMemoryPatternOptimization(false)
        options.addConfigEntry("session.intra_op.allow_spinning", "0")
        val model = context.assets.open("models/waifu2x_cunet_2x.onnx").use { it.readBytes() }
        environment.createSession(model, options).also { session = it }
    }

    private data class Span(val start: Int, val length: Int)

    /** Balanced tiles avoid a full run for a sliver. Four-pixel-aligned starts keep pooling phase consistent. */
    private fun spans(length: Int): List<Span> {
        val step = CORE - OVERLAP
        val count = ((length - OVERLAP + step - 1) / step).coerceAtLeast(1)
        if (count == 1) return listOf(Span(0, length))
        val stride = 4 * ((length - OVERLAP + count * 4 - 1) / (count * 4))
        return List(count) { index ->
            val start = index * stride
            Span(start, minOf(stride + OVERLAP, length - start))
        }
    }

    private fun inputSide(length: Int) = ((length + 2 * PAD + 3) / 4 * 4).coerceAtLeast(64)

    private fun runTile(ort: OrtSession, pixels: IntArray, width: Int, height: Int, xs: Span, ys: Span): Int {
        val inWidth = inputSide(xs.length)
        val inHeight = inputSide(ys.length)
        val inPlane = inWidth * inHeight
        val outWidth = (inWidth - 2 * PAD) * SCALE
        val outHeight = (inHeight - 2 * PAD) * SCALE
        inputBuffer.clear()
        inputBuffer.limit(3 * inPlane)
        outputBuffer.clear()
        outputBuffer.limit(3 * outWidth * outHeight)
        for (row in 0 until inHeight) {
            val sy = (ys.start - PAD + row).coerceIn(0, height - 1) * width
            for (col in 0 until inWidth) {
                val pixel = pixels[sy + (xs.start - PAD + col).coerceIn(0, width - 1)]
                val index = row * inWidth + col
                inputBuffer.put(index, (pixel ushr 16 and 255) / 255f)
                inputBuffer.put(inPlane + index, (pixel ushr 8 and 255) / 255f)
                inputBuffer.put(2 * inPlane + index, (pixel and 255) / 255f)
            }
        }
        OnnxTensor.createTensor(environment, inputBuffer, longArrayOf(1, 3, inHeight.toLong(), inWidth.toLong())).use { input ->
            OnnxTensor.createTensor(environment, outputBuffer, longArrayOf(1, 3, outHeight.toLong(), outWidth.toLong())).use { output ->
                ort.run(mapOf("x" to input), mapOf("y" to output)).close()
            }
        }
        return outWidth
    }

    private suspend fun infer(context: Context, source: Bitmap, width: Int, height: Int, shouldYield: () -> Boolean): Bitmap {
        if (shouldYield()) throw Preempted()
        val work = Bitmap.createScaledBitmap(source, width, height, true)
        val pixels = try {
            IntArray(width * height).also { work.getPixels(it, 0, width, 0, 0, width, height) }
        } finally {
            if (work !== source) work.recycle()
        }
        val ort = obtainSession(context)
        val columns = spans(width)
        val rows = spans(height)
        val outWidth = width * SCALE
        val outHeight = height * SCALE
        val out = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        try {
            // The network's global channel statistics vary between tiles. Feather its luma in a rolling band.
            val band = FloatArray(outWidth * rows.maxOf { it.length * SCALE })
            val packed = IntArray(band.size)
            for ((rowIndex, ys) in rows.withIndex()) {
                val yStart = ys.start * SCALE
                val tileHeight = ys.length * SCALE
                val flushHeight = (rows.getOrNull(rowIndex + 1)?.start?.times(SCALE) ?: outHeight) - yStart
                for (xs in columns) {
                    currentCoroutineContext().ensureActive()
                    if (shouldYield()) throw Preempted()
                    val tileStride = runTile(ort, pixels, width, height, xs, ys)
                    val plane = tileStride * ((inputSide(ys.length) - 2 * PAD) * SCALE)
                    val xStart = xs.start * SCALE
                    val tileWidth = xs.length * SCALE
                    for (y in 0 until tileHeight) {
                        val weightY = weight(y, tileHeight, yStart, outHeight)
                        for (x in 0 until tileWidth) {
                            val offset = y * tileStride + x
                            val luma = luma(outputBuffer[offset], outputBuffer[plane + offset], outputBuffer[2 * plane + offset])
                            check(luma.isFinite()) { "Nonfinite manga AI output" }
                            band[y * outWidth + xStart + x] += luma * weightY * weight(x, tileWidth, xStart, outWidth)
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                if (shouldYield()) throw Preempted()
                for (y in 0 until flushHeight) {
                    val sourceY = (yStart + y) / SCALE
                    val otherY = neighbour(yStart + y, sourceY, height)
                    for (x in 0 until outWidth) {
                        val sourceX = x / SCALE
                        val otherX = neighbour(x, sourceX, width)
                        val index = y * outWidth + x
                        packed[index] = restoreLuma(band[index], pixels[sourceY * width + sourceX],
                            pixels[sourceY * width + otherX], pixels[otherY * width + sourceX], pixels[otherY * width + otherX])
                    }
                }
                out.setPixels(packed, 0, outWidth, 0, yStart, outWidth, flushHeight)
                val carry = (tileHeight - flushHeight) * outWidth
                band.copyInto(band, 0, flushHeight * outWidth, tileHeight * outWidth)
                band.fill(0f, carry)
            }
        } catch (e: Throwable) {
            out.recycle()
            throw e
        }
        return out
    }

    private fun weight(index: Int, length: Int, start: Int, full: Int): Float {
        val overlap = OVERLAP * SCALE
        return when {
            start > 0 && index < overlap -> (index + 0.5f) / overlap
            start + length < full && index >= length - overlap -> (length - index - 0.5f) / overlap
            else -> 1f
        }
    }

    private fun neighbour(out: Int, source: Int, limit: Int): Int =
        (source + if (out % SCALE == 0) -1 else 1).coerceIn(0, limit - 1)

    /** Native neural luma over bilinear source chroma; a common delta keeps channels from clipping independently. */
    private fun restoreLuma(neural: Float, near: Int, across: Int, below: Int, diagonal: Int): Int {
        val red = interpolate(16, near, across, below, diagonal)
        val green = interpolate(8, near, across, below, diagonal)
        val blue = interpolate(0, near, across, below, diagonal)
        val alpha = interpolate(24, near, across, below, diagonal)
        val change = neural * 255f - luma(red, green, blue)
        val delta = if (abs(change) < 0.5f) 0f else change.coerceIn(-minOf(red, green, blue), 255f - maxOf(red, green, blue))
        return (alpha.roundToInt() shl 24) or ((red + delta).roundToInt() shl 16) or
            ((green + delta).roundToInt() shl 8) or (blue + delta).roundToInt()
    }

    private fun interpolate(shift: Int, near: Int, across: Int, below: Int, diagonal: Int): Float =
        (9 * (near ushr shift and 255) + 3 * (across ushr shift and 255) +
            3 * (below ushr shift and 255) + (diagonal ushr shift and 255)) / 16f

    private fun luma(red: Float, green: Float, blue: Float) = 0.299f * red + 0.587f * green + 0.114f * blue

    private fun floatBuffer(floats: Int): FloatBuffer =
        ByteBuffer.allocateDirect(floats * Float.SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()
}
