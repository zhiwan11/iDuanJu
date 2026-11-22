package app.iduanju.manga

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 漫画 AI 超分「增强」: offline Real-ESRGAN AnimeVideo v3 (×4, bundled ONNX) run tile by tile on the CPU.
 * Pages are first fitted to a [MAX_INPUT_PIXELS] budget so inference time and the ×4 output stay bounded; results are
 * kept in a small byte-bounded LRU. One inference at a time; cancelling the caller aborts between tiles.
 */
object MangaAiUpscaler {
    private const val SCALE = 4
    private const val MAX_INPUT_PIXELS = 448 * 640
    private const val TILE = 128
    private const val PAD = 8
    private const val PADDED_TILE = TILE + 2 * PAD
    private const val MAX_CACHE_BYTES = 64 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val inferenceLock = Mutex()
    private val environment by lazy { OrtEnvironment.getEnvironment() }
    /** Only touched under [inferenceLock]. */
    private var session: OrtSession? = null

    fun getCached(key: String): Bitmap? = cache.get(key)

    /** ×4 neural upscale of a software [source] bitmap; null if inference fails. Cancellation-safe. */
    suspend fun upscale(context: Context, key: String, source: Bitmap): Bitmap? {
        getCached(key)?.let { return it }
        return withContext(Dispatchers.Default) {
            inferenceLock.withLock {
                getCached(key) ?: try {
                    infer(getSession(context), source).also { cache.put(key, it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) { // incl. OutOfMemoryError on huge pages / ORT errors
                    android.util.Log.w("IDJ", "manga AI upscale failed", e)
                    null
                }
            }
        }
    }

    private fun getSession(context: Context): OrtSession = session ?: OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        val model = context.assets.open("models/realesr_anime_v3_x4.onnx").use { it.readBytes() }
        environment.createSession(model, options).also { session = it }
    }

    private suspend fun infer(session: OrtSession, source: Bitmap): Bitmap {
        val ratio = sqrt(MAX_INPUT_PIXELS.toDouble() / (source.width.toLong() * source.height)).coerceAtMost(1.0)
        val work = if (ratio >= 1.0) source else Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).roundToInt().coerceAtLeast(1),
            (source.height * ratio).roundToInt().coerceAtLeast(1),
            true,
        )
        try {
            val outWidth = work.width * SCALE
            val outHeight = work.height * SCALE
            val outPixels = IntArray(outWidth * outHeight)
            val tilePixels = IntArray(PADDED_TILE * PADDED_TILE)
            val input = rgbBuffer(PADDED_TILE)
            val output = rgbBuffer(PADDED_TILE * SCALE)
            val inputName = session.inputNames.first()
            val outputName = session.outputNames.first()
            for (top in 0 until work.height step TILE) {
                for (left in 0 until work.width step TILE) {
                    currentCoroutineContext().ensureActive()
                    // Each tile is inferred with PAD px of context on every side, then only its core is kept (no seams).
                    val right = minOf(left + TILE, work.width)
                    val bottom = minOf(top + TILE, work.height)
                    val x0 = maxOf(0, left - PAD)
                    val y0 = maxOf(0, top - PAD)
                    val width = minOf(right + PAD, work.width) - x0
                    val height = minOf(bottom + PAD, work.height) - y0
                    val plane = width * height

                    work.getPixels(tilePixels, 0, width, x0, y0, width, height)
                    input.limit(3 * plane)
                    for (index in 0 until plane) {
                        val pixel = tilePixels[index]
                        input.put(index, (pixel ushr 16 and 255) / 255f)
                        input.put(plane + index, (pixel ushr 8 and 255) / 255f)
                        input.put(2 * plane + index, (pixel and 255) / 255f)
                    }

                    val tileOutWidth = width * SCALE
                    val outPlane = tileOutWidth * height * SCALE
                    output.limit(3 * outPlane)
                    tensor(input, width, height).use { inputTensor ->
                        tensor(output, tileOutWidth, height * SCALE).use { outputTensor ->
                            session.run(mapOf(inputName to inputTensor), mapOf(outputName to outputTensor)).close()
                        }
                    }
                    val cropX = (left - x0) * SCALE
                    val cropY = (top - y0) * SCALE
                    for (row in 0 until (bottom - top) * SCALE) {
                        val src = (cropY + row) * tileOutWidth + cropX
                        val dst = (top * SCALE + row) * outWidth + left * SCALE
                        for (col in 0 until (right - left) * SCALE) {
                            val index = src + col
                            outPixels[dst + col] = 0xff000000.toInt() or
                                (channel(output[index]) shl 16) or
                                (channel(output[outPlane + index]) shl 8) or
                                channel(output[2 * outPlane + index])
                        }
                    }
                }
            }
            return Bitmap.createBitmap(outPixels, outWidth, outHeight, Bitmap.Config.ARGB_8888)
        } finally {
            if (work !== source) work.recycle()
        }
    }

    private fun rgbBuffer(side: Int): FloatBuffer =
        ByteBuffer.allocateDirect(side * side * 3 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private fun tensor(buffer: FloatBuffer, width: Int, height: Int): OnnxTensor =
        OnnxTensor.createTensor(environment, buffer, longArrayOf(1, 3, height.toLong(), width.toLong()))

    private fun channel(value: Float): Int = (value.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
}
