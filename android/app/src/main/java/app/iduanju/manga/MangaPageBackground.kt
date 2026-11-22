package app.iduanju.manga

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/** Fill colour of a page slot (the margins around a fitted page) for 图片背景 [bg]; [dark] = app in dark theme. */
fun readerPageColor(bg: MangaPageBg, dark: Boolean, paper: MangaPaperStyle): Color = when (bg) {
    MangaPageBg.SYSTEM -> if (dark) Color.Black else Color.White
    MangaPageBg.WHITE -> Color.White
    MangaPageBg.BLACK, MangaPageBg.IMMERSIVE -> Color.Black
    // white base: the multiplied paper overlay supplies the tint (a tinted base would be tinted twice)
    MangaPageBg.TEXTURE -> Color.White
}

/** True when page-slot text (loading / errors / 本话完) must be dark to stay legible. */
fun readerPageIsLight(bg: MangaPageBg, dark: Boolean): Boolean = when (bg) {
    MangaPageBg.SYSTEM -> !dark
    MangaPageBg.WHITE, MangaPageBg.TEXTURE -> true
    MangaPageBg.BLACK, MangaPageBg.IMMERSIVE -> false
}

/**
 * One page with its 图片背景:
 * - 沉浸式: the page itself, cropped full-bleed, heavily blurred (36 dp; a 64 px decode drawn large is already soft
 *   below Android 12) under a dark scrim — the margins glow with the page's own colours;
 * - 纹理: a procedural paper grain multiplied over the page *and* its margins, so the page looks printed on paper.
 */
@Composable
fun ReaderPageFrame(
    bg: MangaPageBg,
    modifier: Modifier,
    immersiveUrl: String? = null,
    headers: Map<String, String> = emptyMap(),
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier) {
        if (bg == MangaPageBg.IMMERSIVE && immersiveUrl != null) ImmersiveBackdrop(immersiveUrl, headers)
        content()
        if (bg == MangaPageBg.TEXTURE) PaperGrainOverlay(MangaReaderPrefs.paperStyle, MangaReaderPrefs.textureOpacity, Modifier.matchParentSize())
    }
}

@Composable
private fun BoxScope.ImmersiveBackdrop(url: String, headers: Map<String, String>) {
    val ctx = LocalContext.current
    val req = remember(url) {
        ImageRequest.Builder(ctx).data(url).apply { headers.forEach { (k, v) -> addHeader(k, v) } }.size(64).build()
    }
    AsyncImage(
        req, null,
        Modifier.matchParentSize().graphicsLayer { scaleX = 1.12f; scaleY = 1.12f }.blur(36.dp, BlurredEdgeTreatment.Rectangle),
        contentScale = ContentScale.Crop, filterQuality = FilterQuality.High,
    )
    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.38f)))
}

@Composable
fun PaperGrainOverlay(style: MangaPaperStyle, opacity: Float, modifier: Modifier = Modifier.fillMaxSize()) {
    if (opacity <= 0.01f) return
    val texture = remember(style) { PaperTexture.get(style) }
    val brush = remember(texture) { ShaderBrush(ImageShader(texture, TileMode.Repeated, TileMode.Repeated)) }
    Canvas(modifier) { drawRect(brush, alpha = opacity, blendMode = BlendMode.Multiply) }
}

/**
 * Seamless procedural paper (multiplied over the page): the style's warm tint, two octaves of tileable value noise
 * (cloudy pulp), fine per-pixel tooth, and scattered translucent fibres. Generated once per style (~0.3 MB).
 */
private object PaperTexture {
    private const val SIZE = 256
    private val cache = HashMap<MangaPaperStyle, ImageBitmap>()

    @Synchronized
    fun get(style: MangaPaperStyle): ImageBitmap = cache.getOrPut(style) { build(style) }

    private fun build(style: MangaPaperStyle): ImageBitmap {
        val rnd = Random(20261122 + style.ordinal)
        fun lattice(period: Int) = FloatArray(period * period) { rnd.nextFloat() }
        val coarse = lattice(8); val mid = lattice(32)
        fun noise(table: FloatArray, period: Int, x: Int, y: Int): Float {
            val fx = x * period / SIZE.toFloat(); val fy = y * period / SIZE.toFloat()
            val x0 = floor(fx).toInt(); val y0 = floor(fy).toInt()
            val tx = fx - x0; val ty = fy - y0
            val sx = tx * tx * (3 - 2 * tx); val sy = ty * ty * (3 - 2 * ty)
            fun v(i: Int, j: Int) = table[((j % period + period) % period) * period + ((i % period + period) % period)]
            val a = v(x0, y0) + (v(x0 + 1, y0) - v(x0, y0)) * sx
            val b = v(x0, y0 + 1) + (v(x0 + 1, y0 + 1) - v(x0, y0 + 1)) * sx
            return a + (b - a) * sy
        }
        val tint = Color(style.tint)
        val pixels = IntArray(SIZE * SIZE)
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val cloud = noise(coarse, 8, x, y) * 0.6f + noise(mid, 32, x, y) * 0.4f
            val tooth = rnd.nextFloat()
            val shade = (1f - 0.07f * cloud - 0.05f * tooth).coerceIn(0f, 1f)
            pixels[y * SIZE + x] = Color(tint.red * shade, tint.green * shade, tint.blue * shade).toArgb()
        }
        val bitmap = Bitmap.createBitmap(pixels, SIZE, SIZE, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
        // fibres: short wavy strands, darker and lighter, wrapped around the tile edges so the texture stays seamless
        val canvas = AndroidCanvas(bitmap)
        val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply { setStyle(AndroidPaint.Style.STROKE); strokeCap = AndroidPaint.Cap.ROUND }
        repeat(70) {
            val x0 = rnd.nextFloat() * SIZE; val y0 = rnd.nextFloat() * SIZE
            val angle = rnd.nextFloat() * 6.2832f
            val len = 6f + rnd.nextFloat() * 22f
            val bend = (rnd.nextFloat() - 0.5f) * 8f
            val path = AndroidPath().apply {
                moveTo(x0, y0)
                quadTo(x0 + cos(angle) * len / 2 - sin(angle) * bend, y0 + sin(angle) * len / 2 + cos(angle) * bend, x0 + cos(angle) * len, y0 + sin(angle) * len)
            }
            val darker = rnd.nextFloat() < 0.6f
            paint.color = if (darker) android.graphics.Color.argb(26 + rnd.nextInt(26), 90, 70, 40) else android.graphics.Color.argb(40 + rnd.nextInt(30), 255, 252, 242)
            paint.strokeWidth = 0.5f + rnd.nextFloat() * 0.9f
            for (dx in -1..1) for (dy in -1..1) {
                canvas.save(); canvas.translate(dx * SIZE.toFloat(), dy * SIZE.toFloat()); canvas.drawPath(path, paint); canvas.restore()
            }
        }
        return bitmap.asImageBitmap()
    }
}
