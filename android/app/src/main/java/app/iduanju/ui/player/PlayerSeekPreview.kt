package app.iduanju.ui.player

import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.ui.components.glassSurface
import app.iduanju.ui.theme.IdjDarkColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Scrub state shared by the seek bar (writer) and the floating preview card (reader). [fraction] is non-null only
 * while the thumb is being dragged; the track bounds are in root coordinates so the card can sit right above the thumb.
 */
@Stable
class SeekPreviewState {
    var fraction by mutableStateOf<Float?>(null)
        private set
    /** Playback position when the drag started, for the +15s / -8s delta. */
    var startMs by mutableLongStateOf(0L)
        private set
    var trackLeft by mutableFloatStateOf(0f)
        private set
    var trackTop by mutableFloatStateOf(0f)
        private set
    var trackWidth by mutableFloatStateOf(0f)
        private set

    val active: Boolean get() = fraction != null

    fun begin(f: Float, positionMs: Long) { startMs = positionMs; fraction = f.coerceIn(0f, 1f) }
    fun update(f: Float) { if (fraction != null) fraction = f.coerceIn(0f, 1f) }
    fun end() { fraction = null }

    fun onTrackPositioned(c: LayoutCoordinates) {
        val b = c.boundsInRoot()
        if (b.left != trackLeft) trackLeft = b.left
        if (b.top != trackTop) trackTop = b.top
        if (b.width != trackWidth) trackWidth = b.width
    }
}

/**
 * Seek-bar thumbnails via [MediaMetadataRetriever], opened lazily on its own serial IO thread (never the main thread,
 * never ExoPlayer's connection). Frames are nearest-keyframe (OPTION_CLOSEST_SYNC), scaled to ≤ 240 px, and cached
 * per 2-second bucket (24 entries) so scrubbing back and forth is instant. Only one extraction runs at a time; a
 * queued, not-yet-started request is dropped as soon as the thumb moves to another bucket. HLS playlists and sources
 * the retriever cannot open return null, and the card falls back to the darkened cover.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeekPreviewExtractor {
    private class Source(val url: String, val headers: Map<String, String>, val extractable: Boolean)
    private class Pending(val bucket: Int, val src: Source, val job: Deferred<ImageBitmap?>, val started: AtomicBoolean)

    private val serial = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + serial)
    private val cache = LruCache<Int, ImageBitmap>(MAX_THUMBS)

    @Volatile private var source: Source? = null
    /** Main thread only. */
    private var pending: Pending? = null

    // Confined to [serial].
    private var retriever: MediaMetadataRetriever? = null
    private var openedSrc: Source? = null
    private var openFailed = false

    /** Bumped whenever the source changes, so the card drops a previous episode's frame. */
    var generation by mutableIntStateOf(0)
        private set

    /** Point at the current playable stream. [hls] (or an `.m3u8` URL) disables extraction; null detaches. */
    fun setSource(url: String?, headers: Map<String, String> = emptyMap(), hls: Boolean = false) {
        if (url == source?.url) return
        detach()
        if (url.isNullOrEmpty()) return
        source = Source(url, headers, extractable = !hls && !url.substringBefore('?').endsWith(".m3u8", ignoreCase = true))
    }

    fun cached(positionMs: Long): ImageBitmap? = cache.get(bucketOf(positionMs))

    suspend fun frameAt(positionMs: Long): ImageBitmap? {
        val src = source?.takeIf { it.extractable } ?: return null
        val bucket = bucketOf(positionMs)
        cache.get(bucket)?.let { return it }
        val cur = pending
        val job = if (cur != null && cur.bucket == bucket && cur.src === src && !cur.job.isCancelled) cur.job else {
            // A request that already reached the retriever finishes and lands in the cache; a queued one is stale.
            if (cur != null && !cur.started.get()) cur.job.cancel()
            val started = AtomicBoolean(false)
            val d = scope.async { started.set(true); extract(src, bucket) }
            pending = Pending(bucket, src, d, started)
            d
        }
        // Superseded by a newer bucket → null; the caller itself being cancelled rethrows.
        return try { job.await() } catch (e: CancellationException) { kotlinx.coroutines.currentCoroutineContext().ensureActive(); null }
    }

    /** Release the retriever and forget the source (episode change / leaving the player). */
    fun release() = detach()

    private fun detach() {
        pending?.job?.cancel()
        pending = null
        source = null
        cache.evictAll()
        generation++
        scope.launch { closeRetriever() }
    }

    private fun extract(src: Source, bucket: Int): ImageBitmap? {
        if (source !== src) return null
        cache.get(bucket)?.let { return it }
        val r = open(src) ?: return null
        val timeUs = (bucket * BUCKET_MS + BUCKET_MS / 2) * 1000L
        val bmp = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                r.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, MAX_EDGE_PX, MAX_EDGE_PX)
            } else {
                r.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { full -> downscale(full) }
            }
        } catch (e: Exception) {
            android.util.Log.w("IDJ", "seek preview frame failed", e)
            null
        } ?: return null
        val img = bmp.asImageBitmap()
        if (source === src) cache.put(bucket, img)
        return img
    }

    private fun open(src: Source): MediaMetadataRetriever? {
        if (openedSrc === src) return if (openFailed) null else retriever
        closeRetriever()
        openedSrc = src
        val r = MediaMetadataRetriever()
        return try {
            when {
                src.url.startsWith("/") -> r.setDataSource(src.url)
                src.url.startsWith("file://") -> r.setDataSource(android.net.Uri.parse(src.url).path)
                else -> r.setDataSource(src.url, src.headers)
            }
            retriever = r
            openFailed = false
            r
        } catch (e: Exception) {
            android.util.Log.i("IDJ", "seek preview unavailable for this source: ${e.javaClass.simpleName}")
            runCatching { r.release() }
            openFailed = true
            null
        }
    }

    private fun closeRetriever() {
        retriever?.let { runCatching { it.release() } }
        retriever = null
        openedSrc = null
        openFailed = false
    }

    private fun downscale(full: android.graphics.Bitmap): android.graphics.Bitmap {
        val maxEdge = maxOf(full.width, full.height)
        if (maxEdge <= MAX_EDGE_PX) return full
        val k = MAX_EDGE_PX.toFloat() / maxEdge
        val scaled = android.graphics.Bitmap.createScaledBitmap(full, (full.width * k).toInt().coerceAtLeast(1), (full.height * k).toInt().coerceAtLeast(1), true)
        if (scaled !== full) full.recycle()
        return scaled
    }

    companion object {
        private const val BUCKET_MS = 2000L
        private const val MAX_THUMBS = 24
        private const val MAX_EDGE_PX = 240
        fun bucketOf(positionMs: Long): Int = (positionMs.coerceAtLeast(0L) / BUCKET_MS).toInt()
    }
}

/**
 * Floating glass preview above the seek-bar thumb while scrubbing: frame thumbnail (or the softly darkened cover),
 * `mm:ss / mm:ss` and the offset from where the drag started. Fills its parent for positioning but draws only the
 * card and has no pointer input, so every touch passes through.
 */
@Composable
fun SeekPreviewOverlay(
    state: SeekPreviewState,
    extractor: SeekPreviewExtractor,
    durationProvider: () -> Long,
    coverUrl: String,
    portraitVideo: Boolean,
    modifier: Modifier = Modifier,
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    val gen = extractor.generation
    LaunchedEffect(gen) { frame = null }
    val active = state.active
    LaunchedEffect(active, gen) {
        if (!active) return@LaunchedEffect
        var lastRequestAt = 0L
        snapshotFlow { state.fraction?.let { f -> SeekPreviewExtractor.bucketOf((f * durationProvider()).toLong()) } }
            .distinctUntilChanged()
            .collectLatest { bucket ->
                if (bucket == null) return@collectLatest
                val posMs = bucket * 2000L
                extractor.cached(posMs)?.let { frame = it; return@collectLatest }
                // Throttle: at most one extraction request per ~130 ms; a newer bucket cancels this wait.
                val wait = THROTTLE_MS - (System.currentTimeMillis() - lastRequestAt)
                if (wait > 0) delay(wait)
                lastRequestAt = System.currentTimeMillis()
                extractor.frameAt(posMs)?.let { frame = it }
            }
    }
    val imgW = if (portraitVideo) 84.dp else 148.dp
    val imgH = if (portraitVideo) 132.dp else 84.dp
    val cardW = maxOf(imgW, 96.dp) + 12.dp
    // Last value kept while the card fades out.
    val shownFraction = remember { floatArrayOf(0f) }
    state.fraction?.let { shownFraction[0] = it }
    Box(modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        AnimatedVisibility(
            visible = active,
            modifier = Modifier.layout { measurable, constraints ->
                val p = measurable.measure(Constraints())
                layout(constraints.maxWidth, constraints.maxHeight) {
                    val margin = 12.dp.roundToPx()
                    val f = state.fraction ?: shownFraction[0]
                    val thumbX = state.trackLeft - origin.x + state.trackWidth * f
                    val maxX = (constraints.maxWidth - p.width - margin).coerceAtLeast(margin)
                    val x = (thumbX - p.width / 2f).toInt().coerceIn(margin, maxX)
                    val y = (state.trackTop - origin.y - p.height - 4.dp.toPx()).toInt().coerceAtLeast(margin)
                    p.place(x, y)
                }
            },
            enter = fadeIn(androidx.compose.animation.core.tween(120)) + scaleIn(androidx.compose.animation.core.tween(140), initialScale = 0.9f,
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)),
            exit = fadeOut(androidx.compose.animation.core.tween(160)) + scaleOut(androidx.compose.animation.core.tween(160), targetScale = 0.94f,
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)),
        ) {
            val dur = durationProvider().coerceAtLeast(0L)
            val target = (shownFraction[0] * dur).toLong()
            SeekPreviewCard(frame, coverUrl, imgW, imgH, cardW, target, dur, target - state.startMs)
        }
    }
}

@Composable
private fun SeekPreviewCard(frame: ImageBitmap?, coverUrl: String, imgW: androidx.compose.ui.unit.Dp, imgH: androidx.compose.ui.unit.Dp,
    cardW: androidx.compose.ui.unit.Dp, targetMs: Long, durMs: Long, deltaMs: Long) {
    Column(
        Modifier.width(cardW)
            .glassSurface(RoundedCornerShape(14.dp), dark = true, base = Color(0xFF15171C), shadowRadius = 14.dp, fallbackAlpha = 0.66f)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(imgW, imgH).clip(RoundedCornerShape(10.dp)).background(Color(0xFF0E0F12))) {
            if (frame != null) {
                Image(frame, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                if (coverUrl.isNotEmpty()) coil.compose.AsyncImage(coverUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(0.38f), Color.Black.copy(0.62f)))))
                Text(fmtPreview(targetMs), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum",
                        shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(0.7f), blurRadius = 6f)),
                    modifier = Modifier.align(Alignment.Center))
            }
        }
        Spacer(Modifier.height(5.dp))
        val tnum = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")
        Text("${fmtPreview(targetMs)} / ${fmtPreview(durMs)}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, softWrap = false, style = tnum)
        Text(fmtDelta(deltaMs), color = if (deltaMs >= 0) IdjDarkColors.accent else Color.White.copy(0.72f), fontSize = 11.sp,
            fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false, style = tnum)
    }
}

private const val THROTTLE_MS = 130L

private fun fmtPreview(ms: Long): String {
    val s = (ms.coerceAtLeast(0L) / 1000).toInt(); val h = s / 3600; val m = s / 60 % 60; val r = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%02d:%02d".format(m, r)
}

/** "+15s" / "-8s", "+1:05" past a minute. */
private fun fmtDelta(ms: Long): String {
    val sign = if (ms < 0) "-" else "+"
    val s = kotlin.math.abs(ms) / 1000
    return if (s < 60) "$sign${s}s" else "$sign${s / 60}:%02d".format(s % 60)
}
