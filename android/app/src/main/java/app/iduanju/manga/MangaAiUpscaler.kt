package app.iduanju.manga

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 漫画 AI 超分「增强」: the bundled Real-ESRGAN anime network (`realesr_anime_v3_x4.onnx`) run for real on real pages.
 *
 * - Pages up to ~1080×1600 (strips up to ~1.3 MP) go through the network; bigger pages are area-downscaled first so
 *   the 2× result stays ≤ ~2600 px on its long side (strips: on their width). Only absurdly large images, which
 *   would have to shrink below half size first, are skipped (the 标准 GPU pass keeps covering them).
 * - The model is pinned to a fixed `1×3×272×272` input (256 px tiles + 8 px context each side, edge-replicated at
 *   the page borders), which lets the NNAPI execution provider compile it once. On first use NNAPI (if present) and
 *   the 4-thread CPU kernels each run the first tile; NNAPI is kept only if its result matches the CPU's and it is
 *   actually faster, otherwise the CPU session is used for good.
 * - Output is the pure network output: packed as RGB planar NCHW float32 in [0, 1] (R plane, then G, then B), the
 *   ×4 result is box-averaged 2×2 down to ×2 (or kept ×4 for tiny scans). No levels, no line darkening, no tint —
 *   page colours and paper tone are exactly preserved.
 * - One page at a time; the page on screen goes first: a background prefetch yields between tiles as soon as a
 *   visible page is waiting ([Preempted]), and leaving composition cancels a job between tiles.
 */
object MangaAiUpscaler {
    private const val SCALE = 4
    private const val TILE = 256
    private const val PAD = 8
    /** The model's fixed input side (see class doc). */
    private const val IN = TILE + 2 * PAD
    private const val OUT = IN * SCALE
    private const val MAX_OUT_SIDE = 2600
    private const val MAX_BITMAP_SIDE = 8192
    private const val PAGE_MAX_WORK_PIXELS = 1080 * 1600
    private const val STRIP_MAX_WORK_PIXELS = 1_300_000
    private const val TAG = "IDJ"

    /** Thrown to a background (prefetch) job that gave way to the page on screen; simply retry later. */
    class Preempted : Exception("yielded to the visible page")

    private val maxCacheBytes = (Runtime.getRuntime().maxMemory() / 5).coerceIn(48L shl 20, 192L shl 20).toInt()
    private val cache = object : LruCache<String, Bitmap>(maxCacheBytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val inferenceLock = Mutex()
    private val foregroundWaiting = AtomicInteger(0)
    /** True while a page is being processed (Compose-observable): preloaded neighbours wait until idle. */
    var busy by mutableStateOf(false)
        private set
    private val environment by lazy { OrtEnvironment.getEnvironment() }
    /** Only touched under [inferenceLock]. */
    private var session: OrtSession? = null
    private var model: ByteArray? = null

    // Reused tile buffers (only touched under [inferenceLock]).
    private val tileIn = FloatArray(3 * IN * IN)
    private val tileOut by lazy { FloatArray(3 * OUT * OUT) }
    private val inBuf by lazy { floatBuffer(3 * IN * IN) }
    private val outBuf by lazy { floatBuffer(3 * OUT * OUT) }

    fun getCached(key: String): Bitmap? = cache.get(key)

    /** [workWidth]×[workHeight] is fed to the network; the ×4 output is box-averaged [box]×[box]. */
    internal data class Plan(val workWidth: Int, val workHeight: Int, val box: Int) {
        val outWidth get() = workWidth * SCALE / box
        val outHeight get() = workHeight * SCALE / box
    }

    internal fun plan(srcWidth: Int, srcHeight: Int, fillWidth: Boolean): Plan? {
        if (srcWidth < 16 || srcHeight < 16) return null
        val pixels = srcWidth.toDouble() * srcHeight
        val maxWork = if (fillWidth) STRIP_MAX_WORK_PIXELS else PAGE_MAX_WORK_PIXELS
        // the side whose on-screen size matters: the long side of a page, the width of a webtoon strip
        val ref = if (fillWidth) srcWidth else max(srcWidth, srcHeight)
        for (box in intArrayOf(1, 2)) {
            val outFactor = SCALE.toDouble() / box
            val scale = minOf(
                1.0,
                MAX_OUT_SIDE / (ref * outFactor),
                MAX_BITMAP_SIDE / (max(srcWidth, srcHeight) * outFactor),
                sqrt(maxWork / pixels),
            )
            // ×4 only for tiny scans that need no shrinking at all; otherwise ×2
            if (box == 1 && scale < 1.0) continue
            if (scale < 0.5) return null
            val w = (srcWidth * scale).roundToInt().coerceAtLeast(8)
            val h = (srcHeight * scale).roundToInt().coerceAtLeast(8)
            return Plan(w, h, box)
        }
        return null
    }

    /**
     * Real-ESRGAN enhancement of a software [source] bitmap; null if skipped / failed. A background job
     * ([foreground] false) throws [Preempted] when it gives way to a visible page.
     */
    suspend fun upscale(
        context: Context, key: String, source: Bitmap, fillWidth: Boolean, foreground: () -> Boolean,
    ): Bitmap? {
        getCached(key)?.let { return it }
        val plan = plan(source.width, source.height, fillWidth) ?: return null
        return withContext(Dispatchers.Default) {
            val fg = foreground()
            if (fg) foregroundWaiting.incrementAndGet()
            var counted = fg
            try {
                inferenceLock.withLock {
                    if (counted) { foregroundWaiting.decrementAndGet(); counted = false }
                    getCached(key) ?: try {
                        busy = true
                        infer(context, source, plan) { !foreground() && foregroundWaiting.get() > 0 }
                            .also { cache.put(key, it) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Preempted) {
                        throw e
                    } catch (e: Throwable) {
                        Log.w(TAG, "manga AI upscale failed", e)
                        null
                    } finally {
                        busy = false
                    }
                }
            } finally {
                if (counted) foregroundWaiting.decrementAndGet()
            }
        }
    }

    private fun modelBytes(context: Context): ByteArray =
        model ?: context.assets.open("models/realesr_anime_v3_x4.onnx").use { it.readBytes() }.also { model = it }

    private fun createSession(context: Context, nnapi: Boolean): OrtSession = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        if (nnapi) options.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16, NNAPIFlags.CPU_DISABLED))
        environment.createSession(modelBytes(context), options)
    }

    /** Runs the network on the packed [tileIn] into [tileOut]. */
    private fun runTile(s: OrtSession) {
        inBuf.clear(); inBuf.put(tileIn); inBuf.flip()
        outBuf.clear()
        OnnxTensor.createTensor(environment, inBuf, longArrayOf(1, 3, IN.toLong(), IN.toLong())).use { input ->
            OnnxTensor.createTensor(environment, outBuf, longArrayOf(1, 3, OUT.toLong(), OUT.toLong())).use { output ->
                s.run(mapOf(s.inputNames.first() to input), mapOf(s.outputNames.first() to output)).close()
            }
        }
        outBuf.rewind(); outBuf.get(tileOut)
    }

    /**
     * The session to use, picking NNAPI vs CPU on first use with the already packed first tile (whose CPU result is
     * left in [tileOut]): NNAPI must reproduce the CPU output (mean |Δ| < 1 %) and beat it by ≥ 15 %.
     */
    private fun obtainSession(context: Context): OrtSession {
        session?.let { runTile(it); return it }
        val cpu = createSession(context, nnapi = false)
        var t0 = SystemClock.elapsedRealtime()
        runTile(cpu)
        val cpuMs = SystemClock.elapsedRealtime() - t0
        if (Build.VERSION.SDK_INT < 29) return cpu.also { session = it }
        val reference = tileOut.copyOf()
        val nn = runCatching { createSession(context, nnapi = true) }.getOrNull() ?: return cpu.also { session = it }
        val chosen = runCatching {
            runTile(nn) // warm-up: the first NNAPI run compiles the model for the accelerator
            t0 = SystemClock.elapsedRealtime()
            runTile(nn)
            val nnMs = SystemClock.elapsedRealtime() - t0
            var err = 0.0
            var finite = true
            for (i in reference.indices step 7) {
                val v = tileOut[i]
                if (v.isNaN() || v.isInfinite()) { finite = false; break }
                err += abs(v - reference[i])
            }
            err /= (reference.size / 7).coerceAtLeast(1)
            Log.i(TAG, "manga AI upscale: cpu ${cpuMs}ms, nnapi ${nnMs}ms, |Δ| ${"%.4f".format(err)}")
            finite && err < 0.01 && nnMs < cpuMs * 0.85
        }.getOrDefault(false)
        if (chosen) { cpu.close(); session = nn; return nn }
        nn.close()
        System.arraycopy(reference, 0, tileOut, 0, reference.size)
        session = cpu
        return cpu
    }

    private fun resizeSoftware(source: Bitmap, width: Int, height: Int): Bitmap {
        var current = if (source.config == Bitmap.Config.ARGB_8888) source else source.copy(Bitmap.Config.ARGB_8888, false)
        if (current.width == width && current.height == height) return current
        while (current.width / 2 >= width && current.height / 2 >= height) {
            val next = Bitmap.createScaledBitmap(current, current.width / 2, current.height / 2, true)
            if (current !== source) current.recycle()
            current = next
        }
        val last = Bitmap.createScaledBitmap(current, width, height, true)
        if (current !== source && current !== last) current.recycle()
        return last
    }

    private suspend fun infer(context: Context, source: Bitmap, plan: Plan, shouldYield: () -> Boolean): Bitmap {
        if (shouldYield()) throw Preempted()
        val work = resizeSoftware(source, plan.workWidth, plan.workHeight)
        val ww = work.width; val wh = work.height
        val px = IntArray(ww * wh)
        work.getPixels(px, 0, ww, 0, 0, ww, wh)
        if (work !== source) work.recycle()

        val k = plan.box
        val out = Bitmap.createBitmap(plan.outWidth, plan.outHeight, Bitmap.Config.ARGB_8888)
        val rowOut = IntArray((TILE * SCALE / k) * (TILE * SCALE / k))
        val plane = IN * IN
        val outPlane = OUT * OUT
        val inv = 1f / 255f
        val boxInv = 1f / (k * k)
        var first = true
        try {
            for (top in 0 until wh step TILE) {
                for (left in 0 until ww step TILE) {
                    currentCoroutineContext().ensureActive()
                    if (shouldYield()) throw Preempted()
                    // pack the 272×272 context window as RGB planar NCHW in [0, 1], edge-replicated at the borders
                    val x0 = left - PAD; val y0 = top - PAD
                    for (yy in 0 until IN) {
                        val sy = (y0 + yy).coerceIn(0, wh - 1) * ww
                        val row = yy * IN
                        for (xx in 0 until IN) {
                            val p = px[sy + (x0 + xx).coerceIn(0, ww - 1)]
                            val i = row + xx
                            tileIn[i] = (p ushr 16 and 255) * inv
                            tileIn[plane + i] = (p ushr 8 and 255) * inv
                            tileIn[2 * plane + i] = (p and 255) * inv
                        }
                    }
                    if (first) { obtainSession(context); first = false } else runTile(session!!)

                    // unpack the tile's core, box-averaging k×k (k = 1: pure ×4, k = 2: clean ×2)
                    val coreW = (min(left + TILE, ww) - left) * SCALE
                    val coreH = (min(top + TILE, wh) - top) * SCALE
                    val fw = coreW / k; val fh = coreH / k
                    val base = PAD * SCALE
                    for (fy in 0 until fh) {
                        for (fx in 0 until fw) {
                            var r = 0f; var g = 0f; var b = 0f
                            for (dy in 0 until k) {
                                val o = (base + fy * k + dy) * OUT + base + fx * k
                                for (dx in 0 until k) {
                                    r += tileOut[o + dx]; g += tileOut[outPlane + o + dx]; b += tileOut[2 * outPlane + o + dx]
                                }
                            }
                            rowOut[fy * fw + fx] = (0xff shl 24) or (channel(r * boxInv) shl 16) or (channel(g * boxInv) shl 8) or channel(b * boxInv)
                        }
                    }
                    out.setPixels(rowOut, 0, fw, left * SCALE / k, top * SCALE / k, fw, fh)
                }
            }
        } catch (e: Throwable) {
            out.recycle()
            throw e
        }
        out.setHasMipMap(true)
        return out
    }

    private fun floatBuffer(floats: Int): FloatBuffer =
        ByteBuffer.allocateDirect(floats * Float.SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private fun channel(value: Float): Int = (value * 255f + 0.5f).toInt().coerceIn(0, 255)
}
