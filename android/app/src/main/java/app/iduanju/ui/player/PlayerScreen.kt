package app.iduanju.ui.player

import app.iduanju.ui.components.glassSurface
import app.iduanju.ui.components.glassPill
import app.iduanju.ui.components.GlassStyle
import app.iduanju.ui.components.GlassStylePref
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.iduanju.IdjApp
import app.iduanju.core.hongguo.HGSeriesDetail
import app.iduanju.data.WatchProgress
import app.iduanju.ui.theme.IdjThemeColors
import app.iduanju.ui.theme.IdjDarkColors
import app.iduanju.ui.theme.IdjColors
import app.iduanju.ui.components.DS
import app.iduanju.ui.components.pressClick
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** A resolved video queue whose owner manages chapter loading and the player lifetime. */
class VideoQueueSession(val player: ExoPlayer, val detail: HGSeriesDetail, val index: Int,
    val loading: Boolean, val failure: String?, val select: (Int) -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(seriesId: String, startEp: Int, onBack: () -> Unit, queue: VideoQueueSession? = null, onSwitchSeries: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val lib = IdjApp.instance.library
    var detail by remember { mutableStateOf<HGSeriesDetail?>(null) }
    var epIndex by remember { mutableIntStateOf(-1) }
    var status by remember { mutableStateOf("加载中…") }
    var error by remember { mutableStateOf<String?>(null) }
    val fixture = app.iduanju.BuildConfig.DEBUG && seriesId == "ui-color-bars"
    var centerSuppressed by remember { mutableStateOf(false) }
    var previousEpisode by remember { mutableIntStateOf(-1) }
    var showChrome by remember { mutableStateOf(true) }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    val watchedMs = remember(seriesId, epIndex) { longArrayOf(0L) }
    var speed by remember { mutableFloatStateOf(PlayerPrefs.getSpeed(ctx)) }
    var holdSpeed by remember { mutableStateOf(false) }
    var seekHint by remember { mutableStateOf<String?>(null) }
    /** "episodes" · "speed" · "quality" · "audio" · "more"; one panel at a time. */
    var panel by remember { mutableStateOf(if(app.iduanju.BuildConfig.DEBUG)app.iduanju.ui.UiEvidence.panel else null) }
    var danmaku by remember { mutableStateOf(true) }
    var quality by remember { mutableStateOf<String?>(null) }
    var fillMode by remember { mutableStateOf(PlayerPrefs.getFillMode(ctx)) }
    /** 氛围模式 level: 0 关闭 · 1 柔和 · 2 平衡 · 3 鲜艳 · 4 极致. */
    var ambientLevel by remember { mutableIntStateOf(PlayerPrefs.getAmbientLevel(ctx).coerceIn(0, 4)) }
    var enhanceLevel by remember { mutableIntStateOf(PlayerPrefs.getEnhanceLevel(ctx)) }
    var sdrToHdr by remember { mutableStateOf(PlayerPrefs.getSdrToHdr(ctx)) }
    var textureView by remember { mutableStateOf<android.view.TextureView?>(null) }
    /** 氛围模式 temporal EMA engine: one persistent 16x16 glow + smoothed edge colours / letterbox insets. */
    val ambientGlow = remember { AmbientGlowEngine() }
    var ambientSampleTick by remember { mutableIntStateOf(0) }
    var videoAspectRatio by remember { mutableFloatStateOf(0f) }
    /** 0 = off, -1 = after current episode, >0 = minutes countdown. */
    var sleepMode by remember { mutableIntStateOf(0) }
    var sleepEndMs by remember { mutableLongStateOf(0L) }
    var locked by remember { mutableStateOf(false) }
    var autoNext by remember { mutableStateOf(true) }
    var padDrawerOpen by remember { mutableStateOf(true) }
    val inPip by app.iduanju.PipHost.isInPip
    val activity = ctx as? android.app.Activity
    val metrics = app.iduanju.ui.components.LocalLayoutMetrics.current
    val landscape = metrics.isLandscape
    var manualOrientation by remember { mutableStateOf<Boolean?>(null) }
    val forcedLandscape = manualOrientation == true
    val currentLocked by rememberUpdatedState(locked)
    val currentManualOrientation by rememberUpdatedState(manualOrientation)
    fun applyRequestedOrientation(isLocked: Boolean = locked, manual: Boolean? = manualOrientation) {
        val act = activity ?: return
        act.requestedOrientation = when {
            isLocked -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
            manual == true -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            manual == false -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER
        }
    }
    fun setLandscape(on: Boolean) {
        manualOrientation = on
        applyRequestedOrientation(locked, on)
    }
    LaunchedEffect(locked, manualOrientation, activity) {
        applyRequestedOrientation(locked, manualOrientation)
    }
    DisposableEffect(activity) {
        val listener = object : android.view.OrientationEventListener(ctx) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN || currentLocked) return
                val targetLandscape = currentManualOrientation ?: return
                val isPhysicalPortrait = orientation <= 30 || orientation >= 330
                val isPhysicalLandscape = orientation in 60..120 || orientation in 240..300
                if ((targetLandscape && isPhysicalLandscape) || (!targetLandscape && isPhysicalPortrait)) {
                    if (isAutoRotateEnabled(ctx)) {
                        manualOrientation = null
                        activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER
                    }
                }
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose {
            listener.disable()
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    androidx.activity.compose.BackHandler(enabled = !locked && landscape && forcedLandscape) { setLandscape(false) }
    LaunchedEffect(landscape) {
        val w = activity?.window ?: return@LaunchedEffect
        val ic = androidx.core.view.WindowCompat.getInsetsController(w, w.decorView)
        ic.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (landscape) ic.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars()) else ic.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }
    var tags by remember { mutableStateOf(TechTags()) }
    /** Native HDR10 / HLG / Dolby Vision: no SDR→HDR, no 8-bit getBitmap ambient sampling. */
    val isNativeHdr = remember(tags.range) { tags.range != null && tags.range != "SDR" }
    var retry by remember { mutableIntStateOf(0) }
    var detailRetry by remember { mutableIntStateOf(0) }

    // OkHttp (HTTP/2) like iOS URLSession: huangguo.video's Cloudflare 403s HTTP/1.1 (HttpURLConnection),
    // which made every 黄果 视频站 segment request fail with ERROR_CODE_IO_BAD_HTTP_STATUS.
    val httpFactory = remember { androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(EpisodeCache.http).setUserAgent(app.iduanju.core.cms.MacCMS.UA) }
    val player = remember(queue?.player) {
        queue?.player ?: ExoPlayer.Builder(ctx, DecoderCaps.renderers(ctx)).setMediaSourceFactory(
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(ctx, httpFactory))
        ).build().also { it.playWhenReady = true }
    }
    // One persistent GlEffect; level / sdrToHdr are @Volatile and picked up on the next frame.
    val enhanceEffect = remember(player) { VideoEnhanceEffect(enhanceLevel, sdrToHdr && !isNativeHdr) }
    var enhanceFailed by remember(player) { mutableStateOf(false) }
    fun syncEnhance() {
        enhanceEffect.level = if (enhanceFailed) 0 else enhanceLevel
        enhanceEffect.sdrToHdr = !enhanceFailed && sdrToHdr && !isNativeHdr
        syncWindowAndFallback(activity, textureView, enhanceEffect, isNativeHdr)
    }
    LaunchedEffect(player, enhanceFailed) {
        installVideoEnhance(player, if (enhanceFailed) null else enhanceEffect)
    }
    LaunchedEffect(enhanceLevel, sdrToHdr, isNativeHdr, enhanceFailed, textureView) { syncEnhance() }
    DisposableEffect(textureView) {
        val tv = textureView ?: return@DisposableEffect onDispose {}
        val listener = android.view.View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if ((r - l) != (or - ol) || (b - t) != (ob - ot)) syncEnhance()
        }
        tv.addOnLayoutChangeListener(listener)
        if (tv.width > 0 && tv.height > 0) syncEnhance()
        onDispose { tv.removeOnLayoutChangeListener(listener) }
    }
    // Wide colour gamut for SDR→HDR / native HDR. Not COLOR_MODE_HDR: on a TextureView that would
    // dim the Compose overlays and break the getBitmap() ambient readback.
    // SDR→HDR additionally lifts the window backlight (+0.22 over the user's system brightness, clamped to
    // 0.45..1.0) so the shader's inverse tone mapping reads as a real luminance gain; reset when turned off / left.
    LaunchedEffect(sdrToHdr, isNativeHdr, enhanceFailed) {
        val w = activity?.window ?: return@LaunchedEffect
        w.colorMode =
            if ((sdrToHdr && !enhanceFailed) || isNativeHdr) android.content.pm.ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
            else android.content.pm.ActivityInfo.COLOR_MODE_DEFAULT
        val boost = sdrToHdr && !enhanceFailed && !isNativeHdr
        val lp = w.attributes
        lp.screenBrightness = if (boost) (systemBrightness(ctx) + 0.22f).coerceIn(0.45f, 1.0f)
            else android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        w.attributes = lp
    }

    var mediaSeekTick by remember { mutableIntStateOf(0) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && sleepMode == -1) {
                    sleepMode = 0
                    sleepEndMs = 0L
                    player.pause()
                    seekHint = "本集已播完，定时暂停"
                    return
                }
                if (queue == null && state == Player.STATE_ENDED && autoNext) {
                    val n = detail?.episodes?.size ?: 0
                    if (epIndex + 1 < n) epIndex += 1
                }
            }
            override fun onPlayerError(e: PlaybackException) {
                if (e.errorCode in 7000..7009 && !enhanceFailed) {
                    android.util.Log.w("IDJ", "VideoFrameProcessor error ${e.errorCode}, falling back to direct surface", e)
                    enhanceFailed = true
                    enhanceEffect.level = 0
                    enhanceEffect.sdrToHdr = false
                    enhanceLevel = 0
                    sdrToHdr = false
                    PlayerPrefs.setEnhanceLevel(ctx, 0)
                    PlayerPrefs.setSdrToHdr(ctx, false)
                    val resumePos = player.currentPosition.coerceAtLeast(0L)
                    val shouldPlay = player.playWhenReady
                    installVideoEnhance(player, null)
                    if (resumePos > 0L) player.seekTo(resumePos)
                    player.prepare()
                    player.playWhenReady = shouldPlay
                    seekHint = "当前设备不支持 GPU 画质增强，已自动回退"
                    return
                }
                if (queue == null) error = "播放失败：${e.errorCodeName}"
                keepScreenOn(activity, false)
            }
            // Keep the display awake only while frames are actually playing (portrait, landscape, same activity window).
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                keepScreenOn(activity, isPlaying)
                playing = isPlaying
            }
            override fun onTracksChanged(t: androidx.media3.common.Tracks) {
                tags = TechTags.from(player, quality)
            }
            // videoFormat/audioFormat are populated once the renderers start, after onTracksChanged
            override fun onRenderedFirstFrame() {
                tags = TechTags.from(player, quality)
                ambientSampleTick++
            }
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                ambientSampleTick++
                if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                    reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
                ) {
                    mediaSeekTick++
                }
            }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                tags = TechTags.from(player, quality)
                videoAspectRatio = if (videoSize.width > 0 && videoSize.height > 0) {
                    (videoSize.width * videoSize.pixelWidthHeightRatio) / videoSize.height.toFloat()
                } else {
                    0f
                }
                ambientSampleTick++
            }
        }
        player.addListener(l)
        onDispose { keepScreenOn(activity, false); player.removeListener(l); if (queue == null) player.release() }
    }

    LaunchedEffect(seriesId, epIndex) { ambientGlow.reset() }
    val hdrAmbientLocked = isNativeHdr || (sdrToHdr && !enhanceFailed)
    LaunchedEffect(ambientLevel, fillMode, inPip, hdrAmbientLocked, playing, textureView, ambientSampleTick) {
        // Locked off whenever native HDR/DV or SDR->HDR is active.
        if (ambientLevel <= 0 || fillMode || inPip || hdrAmbientLocked) return@LaunchedEffect
        val tv = textureView ?: return@LaunchedEffect
        val level = ambientLevel
        val interval = ambientSpec(level).sampleIntervalMs
        while (isActive) {
            val bmp = if (tv.isAvailable) runCatching { tv.getBitmap(24, 24) }.getOrNull() else null
            if (bmp != null) {
                // Pure analysis off the main thread; only the target is updated here, the EMA loop below does the rest.
                val gen = ambientGlow.generation
                val edges = ambientGlow.edgesSnapshot()
                val frame = withContext(Dispatchers.Default) {
                    try {
                        analyzeAmbientFrame(bmp, level, edges)
                    } finally {
                        bmp.recycle()
                    }
                }
                if (frame != null) ambientGlow.commitTarget(frame, gen)
            }
            if (!playing) break
            delay(interval)
        }
    }
    // 氛围模式 temporal low-pass: blends the persistent glow toward the latest target every ~33 ms (dt-normalised to
    // the per-level 60 ms step), so the picture glow flows instead of snapping. Idles once converged (e.g. paused).
    LaunchedEffect(ambientLevel, fillMode, inPip, hdrAmbientLocked) {
        if (ambientLevel <= 0 || fillMode || inPip || hdrAmbientLocked) return@LaunchedEffect
        val stepAlpha = ambientSpec(ambientLevel).stepAlpha
        var last = System.nanoTime()
        while (isActive) {
            val idle = ambientGlow.settled
            // Converged: poll cheaply without requesting frames. Moving: step on vsync at ~30 Hz.
            if (idle) delay(120) else withFrameNanos { }
            val now = System.nanoTime()
            val dtMs = (now - last) / 1_000_000f
            if (!idle && dtMs < 28f) continue
            last = now
            // Waking from idle never takes one big catch-up step.
            ambientGlow.step(if (idle) 33f else dtMs.coerceAtMost(100f), stepAlpha)
        }
    }

    val activeQueue by rememberUpdatedState(queue)
    fun selectEpisode(index: Int) {
        val current = activeQueue
        if (current == null) epIndex = index else current.select(index)
    }

    val currentDetail by rememberUpdatedState(detail)
    val currentEpIndex by rememberUpdatedState(epIndex)
    val mediaSession = remember(player) {
        PlayerMediaSession(
            context = ctx,
            onPlayPause = { if (player.isPlaying) player.pause() else player.play() },
            onNext = {
                val n = currentDetail?.episodes?.size ?: 0
                if (currentEpIndex >= 0 && currentEpIndex + 1 < n) selectEpisode(currentEpIndex + 1)
            },
            onPrev = {
                if (currentEpIndex > 0) selectEpisode(currentEpIndex - 1)
            },
            onSeekTo = { targetMs ->
                val maxDur = player.duration.coerceAtLeast(0L)
                val clamped = if (maxDur > 0L) targetMs.coerceIn(0L, maxDur) else targetMs.coerceAtLeast(0L)
                player.seekTo(clamped)
            },
        )
    }
    DisposableEffect(mediaSession) {
        onDispose { mediaSession.release() }
    }
    val mediaTitle = detail?.title.orEmpty()
    val mediaEpisode = detail?.episodes?.getOrNull(epIndex)
    val mediaSubtitle = when {
        mediaEpisode == null -> ""
        queue != null -> "第 ${epIndex + 1} 章" + if (mediaEpisode.title.isNotBlank()) " · ${mediaEpisode.title}" else ""
        else -> "第 ${mediaEpisode.ep} 集" + if (mediaEpisode.title.isNotBlank()) " · ${mediaEpisode.title}" else ""
    }
    val mediaCoverUrl = detail?.cover
    LaunchedEffect(mediaSession, mediaTitle, mediaSubtitle, mediaCoverUrl, dur) {
        if (mediaTitle.isNotEmpty() || mediaSubtitle.isNotEmpty()) {
            mediaSession.updateMetadata(mediaTitle, mediaSubtitle, mediaCoverUrl, dur)
        }
    }
    val mediaHasPrev = epIndex > 0
    val mediaHasNext = epIndex >= 0 && epIndex + 1 < (detail?.episodes?.size ?: 0)
    val mediaSpeed = if (holdSpeed) 2f else speed
    LaunchedEffect(mediaSession, playing, epIndex, mediaHasPrev, mediaHasNext, mediaSpeed, mediaSeekTick) {
        if (detail != null) {
            val curPos = runCatching { player.currentPosition.coerceAtLeast(0L) }.getOrDefault(pos)
            mediaSession.updatePlaybackState(playing, curPos, mediaSpeed, mediaHasPrev, mediaHasNext)
        }
    }

    // ── 3-zone vertical gestures: left = 亮度, right = 音量, centre = follow-finger 上/下一集 ──
    val gestureScope = rememberCoroutineScope()
    val audioManager = remember { ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager }
    /** Vertical offset of the video while the centre zone is dragged (px); the adjacent-episode preview rides along. */
    var episodeDragY by remember { mutableFloatStateOf(0f) }
    var episodeSettle by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    /** Last brightness / volume HUD shown (kept while it fades out). */
    var hudKind by remember { mutableStateOf(GestureHudKind.BRIGHTNESS) }
    var hudValue by remember { mutableFloatStateOf(0f) }
    var hudVisible by remember { mutableStateOf(false) }
    var hudDragging by remember { mutableStateOf(false) }
    LaunchedEffect(hudDragging) { if (!hudDragging) { delay(550); hudVisible = false } }
    LaunchedEffect(queue?.index, queue?.loading, queue?.failure, queue?.detail) {
        if (queue != null) {
            detail = queue.detail
            epIndex = queue.index
            error = queue.failure
            status = if (queue.loading) "正在打开章节…" else ""
        }
    }
    LaunchedEffect(seriesId, detailRetry) {
        if (queue != null) return@LaunchedEffect
        error = null; status = "加载中…"
        if(fixture) {
            detail=HGSeriesDetail(seriesId=seriesId,title="本地彩条测试 · 非真实片源",totalEps=48,source="本地测试",
                episodes=(1..48).map{app.iduanju.core.hongguo.HGEpisode(it,"fixture-$it",60,"彩条第 $it 段")})
            epIndex=startEp.coerceIn(0,47)
            if(app.iduanju.ui.UiEvidence.landscape)setLandscape(true)
            return@LaunchedEffect
        }
        try {
            val d = withContext(Dispatchers.IO) { app.iduanju.data.Catalog.detail(seriesId, web = false) }
            detail = d
            epIndex = d.episodes.indexOfFirst { it.ep == startEp }.coerceAtLeast(0)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { android.util.Log.w("IDJ", "Player detail load failed", e); error = "无法加载节目详情"; status = "" }
    }

    LaunchedEffect(detail, epIndex, retry) {
        if (queue != null) return@LaunchedEffect
        val d = detail ?: return@LaunchedEffect
        val ep = d.episodes.getOrNull(epIndex) ?: return@LaunchedEffect
        if(previousEpisode >= 0 && previousEpisode != epIndex) centerSuppressed=true
        previousEpisode=epIndex
        error = null; status = "取流中…"
        player.stop(); player.clearMediaItems()
        if(fixture) {
            val local=java.io.File(ctx.cacheDir,"ui-color-bars.mp4")
            if(!local.exists())withContext(Dispatchers.IO){ctx.assets.open("player_landscape.mp4").use{input->local.outputStream().use{input.copyTo(it)}}}
            player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(local)))
            syncEnhance()
            installVideoEnhance(player, if (enhanceFailed) null else enhanceEffect)
            status="";player.prepare();player.play()
            return@LaunchedEffect
        }
        try {
            val p = EpisodeCache.get(ep) { status = it }.await()
            quality = p.quality
            status = ""
            // plugin / 采集站 streams may need Referer/Origin/UA on every segment request
            httpFactory.setUserAgent(p.headers.entries.firstOrNull { it.key.equals("user-agent", true) }?.value ?: app.iduanju.core.cms.MacCMS.UA)
            httpFactory.setDefaultRequestProperties(p.headers.filterKeys { !it.equals("user-agent", true) })
            player.setMediaItem(
                MediaItem.Builder().setUri(if (p.uri.startsWith("/")) android.net.Uri.fromFile(java.io.File(p.uri)) else android.net.Uri.parse(p.uri))
                    .apply { if (p.hls) setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8) }.build()
            )
            p.note?.let { android.util.Log.i("IDJ", "hls: $it") }
            player.setPlaybackSpeed(speed)
            syncEnhance()
            installVideoEnhance(player, if (enhanceFailed) null else enhanceEffect)
            val resume = lib.progress(seriesId)
            if (resume != null && resume.episodeIndex == epIndex && resume.seconds > 3 && resume.fraction < 0.95f) player.seekTo((resume.seconds * 1000).toLong())
            player.prepare(); player.play()
            // precache next episode (iOS preloads the next stream while this one plays)
            // spaced ≥6 s from the current resolve: fresh one-shot registrations back-to-back get soft-rejected (probe 2026-11-11)
            if (IdjApp.instance.settings.prefetchEnabled) d.episodes.getOrNull(epIndex + 1)?.let { delay(6_000); EpisodeCache.preload(it) }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            android.util.Log.w("IDJ", "Player media load failed", e)
            status = ""; error = "无法打开这条播放线路"
        }
    }

    LaunchedEffect(fixture) {
        if(fixture && app.iduanju.ui.UiEvidence.switchEpisode) { delay(6000); epIndex=(epIndex+1)%48 }
    }
    LaunchedEffect(player) {
        var tick = 0
        while (isActive) {
            val curPos = player.currentPosition
            val curDur = player.duration.coerceAtLeast(0)
            val curPlaying = player.isPlaying
            if (curPlaying) watchedMs[0] += 250L
            if (sleepMode > 0 && sleepEndMs > 0L && System.currentTimeMillis() >= sleepEndMs) {
                player.pause()
                sleepMode = 0
                sleepEndMs = 0L
                seekHint = "定时关闭已暂停"
            }
            // pos / buffered are read only by PlayerTimeBar / EdgeProgressBar (provider lambdas), so these 250 ms writes
            // never recompose PlayerScreen itself. dur / playing are equality-checked state writes: they only invalidate
            // readers when they actually change.
            pos = curPos
            dur = curDur
            playing = curPlaying
            if (showChrome) buffered = player.bufferedPosition
            app.iduanju.ui.UiEvidence.playerPositionMs = curPos; app.iduanju.ui.UiEvidence.playerPlaying = curPlaying
            if (queue == null && ++tick % 20 == 0 && curPlaying) detail?.let { d ->
                d.episodes.getOrNull(epIndex)?.let { ep ->
                    lib.record(WatchProgress(seriesId, d.title, d.cover, epIndex, ep.ep, curPos / 1000.0, curDur / 1000.0, System.currentTimeMillis()))
                    IdjApp.instance.hgWebAccount.reportProgressAsync(seriesId, ep.vid, ep.ep, curPos, watchedMs[0].coerceAtLeast(1500L), force = false)
                }
            }
            delay(250)
        }
    }
    LaunchedEffect(showChrome, playing, panel) { if (showChrome && playing && panel == null && !(app.iduanju.BuildConfig.DEBUG && app.iduanju.ui.UiEvidence.keepChrome)) { delay(4000); showChrome = false } }
    LaunchedEffect(seekHint) { if (seekHint != null) { delay(700); seekHint = null } }
    DisposableEffect(Unit) {
        onDispose {
            clearWindowAndFallback(enhanceEffect)
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.colorMode = android.content.pm.ActivityInfo.COLOR_MODE_DEFAULT
            activity?.window?.let { w ->
                val lp = w.attributes
                lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                w.attributes = lp
            }
            if (landscape) {
                activity?.window?.let { w -> androidx.core.view.WindowCompat.getInsetsController(w, w.decorView).show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
            }
            if (queue == null) detail?.let { d -> d.episodes.getOrNull(epIndex)?.let { ep ->
                val finalPos = runCatching { player.currentPosition }.getOrDefault(pos)
                val finalDur = runCatching { player.duration.coerceAtLeast(0) }.getOrDefault(dur)
                lib.record(WatchProgress(seriesId, d.title, d.cover, epIndex, ep.ep, finalPos / 1000.0, finalDur / 1000.0, System.currentTimeMillis()))
                if (watchedMs[0] >= 1000L) IdjApp.instance.hgWebAccount.reportProgressAsync(seriesId, ep.vid, ep.ep, finalPos, watchedMs[0], force = true)
            } }
        }
    }

    val isPortraitMedia = remember(player.videoSize, seriesId) {
        val vs = player.videoSize
        if (vs.width > 0 && vs.height > 0) vs.height >= vs.width else !seriesId.startsWith("cms:")
    }

    fun buildPipParams(): android.app.PictureInPictureParams {
        val vs = player.videoSize
        val rawW = if (vs.width > 0 && vs.height > 0) vs.width else if (isPortraitMedia) 9 else 16
        val rawH = if (vs.width > 0 && vs.height > 0) vs.height else if (isPortraitMedia) 16 else 9
        // Android PiP requires aspect ratio within [1/2.39, 2.39]
        val ratio = rawW.toFloat() / rawH.toFloat()
        val safeRational = when {
            ratio < 0.45f -> android.util.Rational(9, 19)
            ratio > 2.30f -> android.util.Rational(23, 10)
            else -> android.util.Rational(rawW, rawH)
        }
        val piFlags = android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        val playPausePi = android.app.PendingIntent.getBroadcast(
            ctx, 1001, android.content.Intent(ACTION_PIP_PLAY_PAUSE).setPackage(ctx.packageName), piFlags,
        )
        val actions = mutableListOf(
            android.app.RemoteAction(
                android.graphics.drawable.Icon.createWithResource(
                    ctx,
                    if (player.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                ),
                if (player.isPlaying) "暂停" else "播放",
                if (player.isPlaying) "暂停" else "播放",
                playPausePi,
            ),
        )
        val hasNext = epIndex + 1 < (detail?.episodes?.size ?: 0)
        if (hasNext) {
            val nextPi = android.app.PendingIntent.getBroadcast(
                ctx, 1002, android.content.Intent(ACTION_PIP_NEXT).setPackage(ctx.packageName), piFlags,
            )
            actions.add(
                android.app.RemoteAction(
                    android.graphics.drawable.Icon.createWithResource(ctx, android.R.drawable.ic_media_next),
                    "下一集",
                    "下一集",
                    nextPi,
                ),
            )
        }
        return android.app.PictureInPictureParams.Builder()
            .setAspectRatio(safeRational)
            .setActions(actions)
            .build()
    }

    fun applyEnhance(lvl: Int) {
        if (lvl > 0 && enhanceFailed) { seekHint = "当前设备不支持 GPU 画质增强"; return }
        enhanceLevel = lvl
        PlayerPrefs.setEnhanceLevel(ctx, lvl)
        syncEnhance()
        seekHint = when (lvl) {
            1 -> "AI 超分辨率：标准"
            2 -> "AI 超分辨率：强效"
            else -> "AI 超分辨率：已关闭"
        }
    }
    fun applySdrToHdr(on: Boolean) {
        when {
            isNativeHdr -> seekHint = "当前片源已是原生 ${tags.range}"
            on && enhanceFailed -> seekHint = "当前设备不支持 GPU 画质增强"
            else -> {
                sdrToHdr = on
                PlayerPrefs.setSdrToHdr(ctx, on)
                syncEnhance()
                seekHint = if (on) "SDR 转 HDR：已开启" else "SDR 转 HDR：已关闭"
            }
        }
    }

    fun enterPip() {
        val act = activity ?: return
        if (!ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            seekHint = "当前设备不支持画中画"
            return
        }
        panel = null
        showChrome = false
        runCatching { act.enterPictureInPictureMode(buildPipParams()) }
            .onFailure { seekHint = "无法开启画中画" }
    }

    DisposableEffect(player, activity) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                when (intent?.action) {
                    ACTION_PIP_PLAY_PAUSE -> if (player.isPlaying) player.pause() else player.play()
                    ACTION_PIP_NEXT -> {
                        val n = detail?.episodes?.size ?: 0
                        if (epIndex + 1 < n) selectEpisode(epIndex + 1)
                    }
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(ACTION_PIP_PLAY_PAUSE)
            addAction(ACTION_PIP_NEXT)
        }
        androidx.core.content.ContextCompat.registerReceiver(
            ctx, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        app.iduanju.PipHost.onUserLeaveHint = {
            if (player.isPlaying) enterPip()
        }
        onDispose {
            app.iduanju.PipHost.onUserLeaveHint = null
            runCatching { ctx.unregisterReceiver(receiver) }
        }
    }

    LaunchedEffect(inPip, playing, epIndex, detail) {
        if (inPip) {
            runCatching { activity?.setPictureInPictureParams(buildPipParams()) }
        }
    }

    val showPadSplit = !inPip && metrics.isTablet && landscape && metrics.widthDp >= 840 && isPortraitMedia && detail != null && padDrawerOpen
    Row(Modifier.fillMaxSize().background(Color.Black)) {
    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().background(Color.Black)) {
        // 画面比例 铺满: a Compose-side zoom of the FIT-sized video. PlayerView.resizeMode = ZOOM only re-lays out the
        // inner AspectRatioFrameLayout, which the AndroidView holder often skips (outer size unchanged), and does
        // nothing visible for 9:16 sources that already match the screen or carry baked-in letterbox bars.
        val containerAspect = if (constraints.maxHeight > 0) constraints.maxWidth.toFloat() / constraints.maxHeight.toFloat() else 0.5625f
        val targetScale = remember(fillMode, videoAspectRatio, containerAspect) {
            if (!fillMode) 1f
            else {
                val raw = if (videoAspectRatio > 0f && containerAspect > 0f) {
                    maxOf(containerAspect / videoAspectRatio, videoAspectRatio / containerAspect)
                } else 1.22f
                // At least 1.18× so 9:16 videos and videos with baked-in bars still visibly fill the screen.
                raw.coerceIn(1.18f, 2.4f)
            }
        }
        val videoScale by androidx.compose.animation.core.animateFloatAsState(
            targetValue = targetScale,
            animationSpec = androidx.compose.animation.core.tween(220),
            label = "videoZoom",
        )
        // Last non-zero level, so switching 氛围模式 off fades out with the strength it had instead of jumping.
        val ambientRender = remember { intArrayOf(2) }
        if (ambientLevel > 0) ambientRender[0] = ambientLevel
        val ambientVisible = ambientLevel > 0 && !fillMode && !inPip && !hdrAmbientLocked
        val containerH = constraints.maxHeight.toFloat()
        // Follow-finger episode swipe: the whole picture (glow + video + edge bleed) rides the centre-zone drag; the offset is
        // read in the layer block only, so dragging redraws without recomposing the player.
        Box(Modifier.fillMaxSize().graphicsLayer { translationY = episodeDragY }) {
        AmbientGlowLayer(
            glow = ambientGlow,
            level = ambientRender[0],
            visible = ambientVisible,
        )
        AndroidView(
            factory = {
                (android.view.LayoutInflater.from(it).inflate(app.iduanju.R.layout.player_texture_view, null) as PlayerView).apply {
                    useController = false
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    this.player = player
                }.also { pv ->
                    (pv.videoSurfaceView as? android.view.TextureView)?.let { tv ->
                        tv.isOpaque = false
                        textureView = tv
                    }
                }
            },
            update = { view ->
                // Always FIT inside the view; 铺满 is the graphicsLayer zoom below.
                if (view.resizeMode != AspectRatioFrameLayout.RESIZE_MODE_FIT) {
                    view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
                view.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_content_frame)?.requestLayout()
                val tv = view.videoSurfaceView as? android.view.TextureView
                tv?.isOpaque = false
                if (tv !== textureView) textureView = tv
                if (app.iduanju.BuildConfig.DEBUG) {
                    // Inspect Media3's actual SurfaceView, not the Compose/PlayerView wrapper.
                    val controls = showChrome
                    view.postDelayed({
                        val surface = view.videoSurfaceView
                        val rect = android.graphics.Rect()
                        surface?.getGlobalVisibleRect(rect)
                        val value = "controls=$controls surface=$rect bounds=${surface?.width}x${surface?.height} viewport=${view.width}x${view.height}"
                        android.util.Log.i("IDJ_RENDER", value)
                    }, 250)
                }
            },
            // clipToBounds first so the zoomed video never bleeds into the iPad episode drawer; the scale is read in
            // the layer block only, so the zoom animation redraws without recomposing.
            modifier = Modifier.fillMaxSize().clipToBounds().graphicsLayer { scaleX = videoScale; scaleY = videoScale },
        )
        // 氛围模式 edge bleed: draws over the TextureView's own opaque pixels (baked-in bars / full-height 9:16), below
        // danmaku, gestures and the HUD. Draw-only, so every touch passes straight through to the gesture layer.
        AmbientEdgeBleedOverlay(
            glow = ambientGlow,
            videoAspectRatio = videoAspectRatio,
            portrait = !landscape,
            level = ambientRender[0],
            visible = ambientVisible,
        )
        }
        // Adjacent-episode preview panel: below the video while dragging up (下一集), above it while dragging down.
        val density = androidx.compose.ui.platform.LocalDensity.current
        val epSwitchThresholdPx = remember(containerH, density) {
            minOf(containerH * EPISODE_SWITCH_THRESHOLD, with(density) { EPISODE_SWITCH_MAX_DP.toPx() })
        }
        val epDragDir by remember { derivedStateOf { if (episodeDragY < -0.5f) 1 else if (episodeDragY > 0.5f) -1 else 0 } }
        val epArmed by remember(epSwitchThresholdPx) { derivedStateOf { kotlin.math.abs(episodeDragY) > epSwitchThresholdPx } }
        val previewEp = if (epDragDir != 0) detail?.episodes?.getOrNull(epIndex + epDragDir) else null
        if (previewEp != null) EpisodeSwitchPreview(
            cover = detail?.cover.orEmpty(), ep = previewEp, next = epDragDir > 0, armed = epArmed,
            modifier = Modifier.fillMaxSize().graphicsLayer { translationY = episodeDragY + if (epDragDir > 0) containerH else -containerH },
        )
        // official danmaku (红果 only), lanes under the top bar + tech strip, never into the bottom controls
        val epNow = detail?.episodes?.getOrNull(epIndex)
        if (!inPip && danmaku && epNow != null && seriesId.all { it.isDigit() }) {
            val topInset = with(androidx.compose.ui.platform.LocalDensity.current) { androidx.compose.foundation.layout.WindowInsets.statusBars.getTop(this).toDp() }
            DanmakuOverlay(player, seriesId, epNow.vid, laneTop = topInset + (if (metrics.isTablet) 64.dp else 44.dp) + 34.dp, bottomLimit = 220.dp) { android.util.Log.i("IDJ", "danmaku: $it") }
        }
        // gesture layer: tap = chrome, double-tap sides = ±10s, long-press = 2x, vertical swipe = 亮度 (left) /
        // 音量 (right) / follow-finger prev/next episode (centre), horizontal drag = scrub.
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { if(locked) showChrome=true else if (panel != null) panel = null else if(centerSuppressed) {centerSuppressed=false;showChrome=true} else showChrome = !showChrome },
                        onDoubleTap = doubleTap@ { o ->
                            if(locked)return@doubleTap
                            val w = size.width
                            when {
                                o.x < w / 3 -> { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)); seekHint = "-10s" }
                                o.x > w * 2 / 3 -> { player.seekTo(player.currentPosition + 10_000); seekHint = "+10s" }
                                else -> if (player.isPlaying) player.pause() else player.play()
                            }
                        },
                        onPress = {
                            val ok = tryAwaitRelease()
                            if (holdSpeed) { holdSpeed = false; player.setPlaybackSpeed(speed) }
                            ok
                        },
                        onLongPress = { if(!locked){holdSpeed = true; player.setPlaybackSpeed(2f)} },
                    )
                }
                .pointerInput(Unit) {
                    // Zone fixed at touch-down: 0 = disabled, 1 = 亮度 (left 28 %), 2 = 音量 (right 28 %), 3 = 切集 (centre).
                    var zone = 0
                    var level = 0f
                    var maxVol = 1
                    var rawY = 0f
                    var committing = false
                    val velocityTracker = VelocityTracker()
                    val music = android.media.AudioManager.STREAM_MUSIC
                    fun finish(commit: Boolean) {
                        if (zone == 1 || zone == 2) hudDragging = false
                        if (zone == 3) {
                            val h = size.height.toFloat()
                            val off = episodeDragY
                            val vy = if (commit) velocityTracker.calculateVelocity().y else 0f
                            val thresholdPx = minOf(h * EPISODE_SWITCH_THRESHOLD, EPISODE_SWITCH_MAX_DP.toPx())
                            val flickVelPx = EPISODE_FLICK_VELOCITY_DP.toPx()
                            val flickDistPx = EPISODE_FLICK_MIN_DISTANCE_DP.toPx()
                            val n = detail?.episodes?.size ?: 0
                            val wantNext = (off < -thresholdPx) || (vy < -flickVelPx && off < -flickDistPx)
                            val wantPrev = (off > thresholdPx) || (vy > flickVelPx && off > flickDistPx)
                            val target = when {
                                !commit -> -1
                                wantNext && epIndex + 1 < n -> epIndex + 1
                                wantPrev && epIndex > 0 -> epIndex - 1
                                else -> -1
                            }
                            episodeSettle = gestureScope.launch {
                                if (target >= 0) {
                                    committing = true
                                    try {
                                        androidx.compose.animation.core.animate(off, if (off < 0f) -h else h,
                                            animationSpec = androidx.compose.animation.core.tween(200)) { v, _ -> episodeDragY = v }
                                        selectEpisode(target)
                                    } finally { episodeDragY = 0f; committing = false }
                                } else androidx.compose.animation.core.animate(off, 0f,
                                    animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.82f, stiffness = 420f)) { v, _ -> episodeDragY = v }
                            }
                        }
                        zone = 0
                    }
                    detectVerticalDragGestures(
                        onDragStart = { o ->
                            zone = when {
                                locked || inPip || panel != null -> 0
                                o.x < size.width * 0.28f -> 1
                                o.x > size.width * 0.72f -> 2
                                committing -> 0
                                else -> 3
                            }
                            when (zone) {
                                1 -> level = activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: systemBrightness(ctx)
                                2 -> {
                                    maxVol = audioManager.getStreamMaxVolume(music).coerceAtLeast(1)
                                    level = audioManager.getStreamVolume(music).toFloat() / maxVol
                                }
                                3 -> { episodeSettle?.cancel(); rawY = episodeDragY; velocityTracker.resetTracking() }
                            }
                            if (zone == 1 || zone == 2) {
                                hudKind = if (zone == 1) GestureHudKind.BRIGHTNESS else GestureHudKind.VOLUME
                                hudValue = level; hudDragging = true; hudVisible = true
                            }
                        },
                        onDragEnd = { finish(commit = true) },
                        onDragCancel = { finish(commit = false) },
                    ) { change, dy ->
                        if (zone == 0) return@detectVerticalDragGestures
                        change.consume()
                        val h = size.height.toFloat()
                        when (zone) {
                            1 -> {
                                level = (level - dy / (h * 0.65f)).coerceIn(0.02f, 1.0f)
                                activity?.window?.let { w -> val lp = w.attributes; lp.screenBrightness = level; w.attributes = lp }
                                hudValue = level
                            }
                            2 -> {
                                level = (level - dy / (h * 0.65f)).coerceIn(0f, 1f)
                                val targetInt = Math.round(level * maxVol)
                                // flags = 0: no system volume panel over our own HUD; DND can make this throw.
                                if (targetInt != audioManager.getStreamVolume(music)) runCatching { audioManager.setStreamVolume(music, targetInt, 0) }
                                hudValue = level
                            }
                            else -> {
                                velocityTracker.addPointerInputChange(change)
                                rawY += dy
                                val n = detail?.episodes?.size ?: 0
                                val atEdge = (rawY < 0f && epIndex + 1 >= n) || (rawY > 0f && epIndex <= 0)
                                episodeDragY = if (atEdge) rawY * 0.22f else rawY
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    var start = 0L; var acc = 0f
                    detectHorizontalDragGestures(onDragStart = { start = player.currentPosition; acc = 0f }, onDragEnd = {
                        if(!locked)player.seekTo((start + (acc / size.width * 90_000).toLong()).coerceIn(0, player.duration.coerceAtLeast(0)))
                        seekHint = null
                    }) { _, d ->
                        acc += d
                        val t = (start + (acc / size.width * 90_000).toLong()).coerceIn(0, player.duration.coerceAtLeast(0))
                        seekHint = fmt(t) + " / " + fmt(player.duration.coerceAtLeast(0))
                    }
                },
        )

        if (status.isNotEmpty() && error == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(12.dp))
                Text(status, color = Color.White.copy(0.8f), fontSize = 13.sp)
            }
        }
        error?.let {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(it, color = Color.White.copy(0.85f), fontSize = 14.sp)
                Spacer(Modifier.height(14.dp))
                Text("重试", color = Color.Black, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White).clickable { if (queue != null) selectEpisode(epIndex) else if (detail == null) detailRetry++ else retry++ }.padding(horizontal = 18.dp, vertical = 8.dp))
            }
        }
        if (holdSpeed) {
            Row(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 96.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(0.55f))
                .padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.FastForward, null, tint = Color.White); Spacer(Modifier.width(8.dp))
                Text("2.0×", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
        seekHint?.let {
            Text(it, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center).offset(y = (-90).dp).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(0.55f)).padding(horizontal = 16.dp, vertical = 8.dp))
        }
        androidx.compose.animation.AnimatedVisibility(hudVisible && !inPip, Modifier.align(Alignment.Center),
            enter = fadeIn(androidx.compose.animation.core.tween(120)), exit = fadeOut(androidx.compose.animation.core.tween(260))) {
            GestureHudPill(hudKind, hudValue)
        }

        // ── Chrome: floating glass HUD ─────────────────────────────────────────────────────────────────
        // Top bar (back · title / episode · tech badges · action cluster), centre transport, a lock on the left edge
        // and a floating glass console (scrubber + feature pills). Proportions scale for phone, phone landscape and
        // tablet. The compact title + 2 dp edge progress stay visible when the HUD is hidden. The centre transport
        // only appears once the current episode is actually playable, so switching episodes never flashes it.
        val pc = IdjDarkColors
        val chromeA by androidx.compose.animation.core.animateFloatAsState(if (!inPip && (showChrome || panel != null)) 1f else 0f, DS.panel(), label = "chrome")
        val ready = status.isEmpty() && error == null && dur > 0
        // derivedStateOf: PlayerScreen only recomposes when the 跳过片头 chip appears / disappears, not every tick.
        val introSkippable by remember { derivedStateOf { pos < 15000 && dur > 45000 } }
        val tablet = metrics.isTablet
        /** Landscape phones and every tablet get the floating console layout; portrait phones keep the short-drama layout. */
        val wideHud = landscape || tablet
        val btnSize = if (tablet) 48.dp else 42.dp
        val btnIcon = if (tablet) 22.dp else 20.dp
        val hudPad = when {
            tablet && landscape -> 32.dp
            landscape -> 52.dp
            tablet -> 24.dp
            else -> 14.dp
        }
        val dmOK = seriesId.all { it.isDigit() }
        val nativeRange = if (isNativeHdr) tags.range else null
        val enhanceOn = enhanceLevel > 0 && !enhanceFailed
        val hdrOn = sdrToHdr && !enhanceFailed && !isNativeHdr
        val padSplitCapable = !inPip && tablet && landscape && metrics.widthDp >= 840 && isPortraitMedia && detail != null
        val chromeOn = showChrome && !locked
        fun togglePanel(p: String) { panel = if (panel == p) null else p }
        val curDetail = detail
        val episodeLabel = when {
            curDetail == null -> ""
            queue != null -> "第 ${epIndex + 1} 章 · ${curDetail.episodes.getOrNull(epIndex)?.title.orEmpty()}"
            else -> "第 ${curDetail.episodes.getOrNull(epIndex)?.ep ?: startEp} 集 / 共 ${maxOf(curDetail.totalEps, curDetail.episodes.size)} 集"
        }
        Box(Modifier.fillMaxWidth().height(if (wideHud) 120.dp else 110.dp).graphicsLayer { alpha = if (locked) 0f else chromeA }
            .background(Brush.verticalGradient(listOf(Color.Black.copy(0.55f), Color.Black.copy(0.18f), Color.Transparent))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(if (wideHud) 170.dp else 200.dp).graphicsLayer { alpha = if (locked) 0f else chromeA }
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color.Black.copy(0.18f), 1f to Color.Black.copy(0.58f))))

        // Top bar
        if (!inPip && !locked) Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = hudPad)
            .padding(top = if (landscape) 12.dp else 6.dp).graphicsLayer { alpha = chromeA }) {
            Row(Modifier.fillMaxWidth().heightIn(min = btnSize), verticalAlignment = Alignment.CenterVertically) {
                CircleBtn(Icons.AutoMirrored.Filled.ArrowBackIos, "返回", chromeOn, btnSize, btnIcon, onClick = onBack)
                if (wideHud) {
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(curDetail?.title.orEmpty(), color = Color.White, fontSize = if (tablet) 17.sp else 16.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(0.6f), blurRadius = 6f)))
                        Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (episodeLabel.isNotEmpty()) {
                                Text(episodeLabel, color = Color.White.copy(0.72f), fontSize = 12.sp, maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                Spacer(Modifier.width(8.dp))
                            }
                            StatusBadges(tags, enhanceOn, hdrOn, nativeRange, chromeOn) { togglePanel("more") }
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                } else Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(if (tablet) 10.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircleBtn(Icons.Filled.PictureInPictureAlt, "画中画", chromeOn, btnSize, btnIcon) { enterPip() }
                    if (queue == null) {
                        val fav = lib.isFavorite(seriesId)
                        CircleBtn(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "收藏", chromeOn, btnSize, btnIcon,
                            tint = if (fav) pc.accent else Color.White) {
                            curDetail?.let { lib.toggleFavorite(seriesId, it.title, it.cover) }
                        }
                    }
                    if (padSplitCapable) CircleBtn(Icons.Filled.GridView, if (padDrawerOpen) "收起选集" else "展开选集", chromeOn, btnSize, btnIcon,
                        tint = if (padDrawerOpen) pc.accent else Color.White) { padDrawerOpen = !padDrawerOpen }
                    CircleBtn(Icons.Filled.MoreHoriz, "更多", chromeOn, btnSize, btnIcon) { togglePanel("more") }
                }
            }
            if (!wideHud) Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                StatusBadges(tags, enhanceOn, hdrOn, nativeRange, chromeOn) { togglePanel("more") }
            }
        }

        // Lock on the left edge (landscape / tablet); while locked it is the only control (kept even if the device
        // rotated to phone portrait while locked, so it can always be undone).
        if (!inPip && (wideHud || locked) && panel == null) Box(Modifier.align(Alignment.CenterStart).padding(start = hudPad).graphicsLayer { alpha = chromeA }) {
            CircleBtn(if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen, if (locked) "解锁" else "锁屏", showChrome, btnSize, btnIcon,
                tint = if (locked) pc.accent else Color.White) {
                locked = !locked
                showChrome = true
                seekHint = if (locked) "已锁定屏幕" else "已解锁"
            }
        }

        // Centre transport: −10 s · play / pause · +10 s
        val mainSize = when { tablet -> 76.dp; landscape -> 64.dp; else -> 68.dp }
        val sideSize = if (tablet) 48.dp else 44.dp
        androidx.compose.animation.AnimatedVisibility(!inPip && showChrome && ready && !centerSuppressed && !locked && panel == null, Modifier.align(Alignment.Center),
            enter = fadeIn(DS.panel()) + androidx.compose.animation.scaleIn(DS.panel(), initialScale = 0.92f),
            exit = fadeOut(DS.panel()) + androidx.compose.animation.scaleOut(DS.panel(), targetScale = 0.92f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (tablet) 84.dp else 52.dp)) {
                CircleBtn(Icons.Filled.Replay10, "后退10秒", true, sideSize, sideSize * 0.52f) { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)); seekHint = "-10s" }
                Box(Modifier.size(mainSize).glassSurface(CircleShape, dark = true, shadowRadius = 12.dp, fallbackAlpha = 0.46f)
                    .pressClick(0.92f, label = if (playing) "暂停" else "播放") { if (player.isPlaying) player.pause() else player.play() },
                    contentAlignment = Alignment.Center) {
                    androidx.compose.animation.Crossfade(playing, label = "pp") { p ->
                        Icon(if (p) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(mainSize * 0.46f))
                    }
                }
                CircleBtn(Icons.Filled.Forward10, "前进10秒", true, sideSize, sideSize * 0.52f) { player.seekTo(player.currentPosition + 10_000); seekHint = "+10s" }
            }
        }

        // Bottom console: overlays the video, never reserves space in its viewport.
        if (!inPip && !locked && (showChrome || panel != null)) Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
            .padding(horizontal = hudPad).padding(bottom = if (wideHud) 16.dp else 6.dp)
            .then(if (wideHud) Modifier.widthIn(max = 860.dp) else Modifier).fillMaxWidth()
            .graphicsLayer { alpha = chromeA }) {
            if (curDetail != null && !wideHud) {
                Row(Modifier.heightIn(min = 28.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(curDetail.source, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.White.copy(0.16f)).padding(horizontal = 7.dp, vertical = 2.dp))
                    Spacer(Modifier.width(8.dp))
                    val seasonTag = remember(curDetail.relatedSeries) {
                        val ss = curDetail.relatedSeries
                        val nums = ss.map { app.iduanju.core.hongguo.SeasonParser.seasonNo(it.title) }
                        if (ss.size > 1 && nums.toSet().size == ss.size)
                            "第 " + app.iduanju.core.hongguo.SeasonParser.seasonNo(ss.firstOrNull { it.seriesId == curDetail.seriesId }?.title ?: curDetail.title) + " 季" else null
                    }
                    seasonTag?.let { Text("$it · ", color = Color.White.copy(0.72f), fontSize = 13.sp) }
                    Text(episodeLabel, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(6.dp))
                Text(curDetail.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 27.sp, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp),
                    style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(0.7f), blurRadius = 6f)))
                Spacer(Modifier.height(8.dp))
            }
            Column(Modifier.fillMaxWidth()
                .then(if (wideHud) Modifier.glassSurface(RoundedCornerShape(26.dp), dark = true, base = Color(0xFF15171C), shadowRadius = 16.dp, fallbackAlpha = 0.44f)
                    .padding(horizontal = 16.dp, vertical = 10.dp) else Modifier.padding(horizontal = 6.dp))
                // swallow taps on the console background so they never toggle the HUD underneath
                .pointerInput(Unit) { detectTapGestures { } }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Reads pos / buffered itself, so 250 ms ticks only recompose the scrubber.
                    PlayerTimeBar(
                        posProvider = { pos },
                        durProvider = { dur },
                        bufferedProvider = { buffered },
                        thick = tablet,
                        modifier = Modifier.weight(1f),
                    ) { if (!locked) { player.seekTo(it); showChrome = true } }
                    // A tablet held in landscape has nothing to exit; only offer the toggle where it can act.
                    if (!(tablet && landscape && !forcedLandscape)) {
                        Spacer(Modifier.width(if (tablet) 12.dp else 8.dp))
                        HudIcon(if (landscape) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen, null, false) { setLandscape(!landscape) }
                    }
                }
                // Playback actions only. 画面比例 / 氛围光晕 / AI 超分 / SDR→HDR / 定时 / 连播 live solely in 更多设置 (top-right ⋯).
                val speedLabel = if (speed == 1f) "1.0×" else "${speed}×".replace(".0×", "×")
                if (landscape) Row(Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (introSkippable) LandChip(Icons.Filled.SkipNext, "跳过片头", null, false, chromeOn) { player.seekTo(15000) }
                    Spacer(Modifier.weight(1f))
                    LandChip(Icons.Filled.GridView, "选集", null, panel == "episodes", chromeOn) { togglePanel("episodes") }
                    LandChip(Icons.Filled.Speed, "倍速", speedLabel, panel == "speed" || speed != 1f, chromeOn) { togglePanel("speed") }
                    LandChip(Icons.Filled.HighQuality, "画质", quality ?: tags.res, panel == "quality", chromeOn) { togglePanel("quality") }
                    LandChip(Icons.Filled.Subtitles, "弹幕", if (dmOK) (if (danmaku) "开" else "关") else null, dmOK && danmaku, chromeOn && dmOK) { danmaku = !danmaku }
                    LandChip(Icons.Filled.ClosedCaption, "音轨字幕", null, panel == "audio", chromeOn) { togglePanel("audio") }
                } else Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    ActionBtn(Icons.Filled.GridView, "选集", null, panel == "episodes", false, chromeOn) { togglePanel("episodes") }
                    ActionBtn(Icons.Filled.Speed, "倍速", speedLabel, panel == "speed", speed != 1f, chromeOn) { togglePanel("speed") }
                    ActionBtn(Icons.Filled.HighQuality, "画质", quality ?: tags.res, panel == "quality", false, chromeOn) { togglePanel("quality") }
                    ActionBtn(Icons.Filled.Subtitles, "弹幕", if (dmOK) (if (danmaku) "开" else "关") else null, false, dmOK && danmaku, chromeOn) { if (dmOK) danmaku = !danmaku }
                    ActionBtn(Icons.Filled.ClosedCaption, "音轨字幕", null, panel == "audio", false, chromeOn) { togglePanel("audio") }
                }
            }
        }
        // Always-on title + edge progress are overlays, not a layout inset or a scrubber row.
        if (!inPip && !showChrome && panel == null) {
            Text(curDetail?.title.orEmpty(), color = Color.White.copy(0.85f), fontSize = 12.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(0.7f), blurRadius = 4f)),
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(horizontal = if (wideHud) hudPad else 16.dp, vertical = 10.dp))
            EdgeProgressBar(posProvider = { pos }, durProvider = { dur }, modifier = Modifier.align(Alignment.BottomCenter))
        }
        // Settings sheet: one style for 选集 / 倍速 / 画质 / 音轨字幕 / 更多. Tablets get a centred floating glass modal
        // over a light scrim; phones keep the sheet just above the console.
        val centeredSheet = tablet
        if (!inPip && panel != null) Box(Modifier.fillMaxSize().then(if (centeredSheet) Modifier.background(Color.Black.copy(0.30f)) else Modifier)
            .pointerInput(Unit) { detectTapGestures { panel = null } })
        androidx.compose.animation.AnimatedVisibility(!inPip && panel != null,
            if (centeredSheet) Modifier.align(Alignment.Center).navigationBarsPadding().padding(24.dp)
            else Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 12.dp).padding(bottom = if (landscape) 16.dp else 78.dp),
            enter = if (centeredSheet) fadeIn(DS.panel()) + androidx.compose.animation.scaleIn(DS.panel(), initialScale = 0.94f)
                else fadeIn(DS.panel()) + androidx.compose.animation.slideInVertically(DS.panel()) { it / 6 },
            exit = if (centeredSheet) fadeOut(DS.panel()) + androidx.compose.animation.scaleOut(DS.panel(), targetScale = 0.94f)
                else fadeOut(DS.panel()) + androidx.compose.animation.slideOutVertically(DS.panel()) { it / 6 }) {
            val last = remember { arrayOfNulls<String>(1) }
            if (panel != null) last[0] = panel
            val shown = last[0]
            val sheetShape = RoundedCornerShape(if (centeredSheet) 28.dp else 24.dp)
            val liquidGlass = GlassStylePref.style == GlassStyle.LIQUID
            Column(Modifier.widthIn(max = if (centeredSheet) 540.dp else 520.dp).fillMaxWidth()
                .heightIn(max = (metrics.heightDp * if (centeredSheet) 0.80f else 0.74f).dp)
                // LIQUID: sheer dark glass (0.52 → ~19 % fill + specular rim) plus a 30 % smoke layer so the settings
                // text stays legible over bright video; FROSTED keeps its dense high-readability fill.
                .glassSurface(sheetShape, dark = true, base = Color(0xFF1C1E24), shadowRadius = 20.dp,
                    fallbackAlpha = if (liquidGlass) 0.52f else 0.92f,
                    activeOverlay = if (liquidGlass) Color.Black.copy(alpha = 0.30f) else Color.Transparent)
                .pointerInput(Unit) { detectTapGestures { } }
                .verticalScroll(rememberScrollState())
                .padding(start = if (centeredSheet) 20.dp else 16.dp, end = if (centeredSheet) 20.dp else 16.dp,
                    top = if (centeredSheet) 20.dp else 16.dp, bottom = if (centeredSheet) 18.dp else 14.dp)) {
                when (shown) {
                    "episodes" -> curDetail?.let { d -> EpisodesPanel(d, epIndex, pc, onPick = { selectEpisode(it); panel = null },
                        onSeason = { id -> panel = null; onSwitchSeries(id) }) }
                    "speed" -> {
                        PanelTitle("倍速", "长按画面临时 2× · 自动记住偏好")
                        Spacer(Modifier.height(8.dp))
                        listOf(0.75f, 1f, 1.25f, 1.5f, 2f, 3f).forEach { s ->
                            PanelRow(if (s == 1f) "1.0× 正常速度" else "${s}×".replace(".0×", "×"), "", s == speed, pc) {
                                speed = s
                                PlayerPrefs.setSpeed(ctx, s)
                                player.setPlaybackSpeed(s)
                                panel = null
                            }
                        }
                    }
                    "quality" -> {
                        PanelTitle("画质", null)
                        Spacer(Modifier.height(8.dp))
                        val q = quality ?: tags.res ?: "自动"
                        PanelRow(q, player.videoFormat?.let { "${it.width}×${it.height}" } ?: "", true, pc) { panel = null }
                        Text("播放源", color = Color.White.copy(0.55f), fontSize = 12.sp, modifier = Modifier.padding(start = 2.dp, top = 14.dp, bottom = 8.dp))
                        PanelRow(curDetail?.source ?: "当前片源", "当前", true, pc) { panel = null }
                    }
                    "more" -> {
                        MoreSettingsPanel(
                            fillMode = fillMode,
                            ambientLevel = ambientLevel,
                            enhanceLevel = enhanceLevel,
                            sdrToHdr = sdrToHdr,
                            nativeHdrRange = nativeRange,
                            sleepMode = sleepMode,
                            sleepEndMs = sleepEndMs,
                            autoNext = autoNext,
                            showAutoNext = queue == null,
                            pc = pc,
                            onFillMode = { fill ->
                                fillMode = fill
                                PlayerPrefs.setFillMode(ctx, fill)
                                seekHint = if (fill) "画面：铺满裁剪" else "画面：完整适应"
                            },
                            onAmbientLevel = { lvl ->
                                when {
                                    isNativeHdr -> seekHint = "原生 ${tags.range} 模式下不可开启氛围模式"
                                    sdrToHdr -> seekHint = "HDR 模式下不可开启氛围模式，请先关闭 SDR 转 HDR"
                                    else -> {
                                        val l = lvl.coerceIn(0, 4)
                                        ambientLevel = l
                                        PlayerPrefs.setAmbientLevel(ctx, l)
                                        seekHint = "氛围模式：" + ambientLevelLabel(l)
                                    }
                                }
                            },
                            onEnhance = { lvl -> applyEnhance(lvl) },
                            onSdrToHdr = { on -> applySdrToHdr(on) },
                            onSleep = { mode ->
                                sleepMode = mode
                                sleepEndMs = if (mode > 0) System.currentTimeMillis() + mode * 60_000L else 0L
                                seekHint = when (mode) {
                                    0 -> "定时关闭：已取消"
                                    -1 -> "定时关闭：播完本集暂停"
                                    else -> "定时关闭：${mode} 分钟后暂停"
                                }
                            },
                            onAutoNext = { autoNext = it },
                        )
                    }
                    else -> {
                        PanelTitle("音轨字幕", null)
                        Text("音轨", color = Color.White.copy(0.55f), fontSize = 12.sp, modifier = Modifier.padding(start = 2.dp, top = 14.dp, bottom = 8.dp))
                        PanelRow(tags.audio ?: "默认音轨", "", true, pc) { panel = null }
                        Text("字幕", color = Color.White.copy(0.55f), fontSize = 12.sp, modifier = Modifier.padding(start = 2.dp, top = 14.dp, bottom = 8.dp))
                        PanelRow("关闭", "当前视频没有可选字幕", true, pc) { panel = null }
                    }
                }
            }
        }
    }
    if (showPadSplit) {
        val d = detail!!
        val pc = IdjDarkColors
        Column(
            Modifier
                .width(340.dp)
                .fillMaxHeight()
                .background(Color(0xFF15181F))
                .border(1.dp, Color.White.copy(0.08f))
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(d.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(
                    "收起",
                    color = pc.accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(pc.accentSoft)
                        .clickable { padDrawerOpen = false }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.weight(1f)) {
                Column(Modifier.fillMaxSize()) {
                    EpisodesPanel(d, epIndex, pc, onPick = { selectEpisode(it) }, onSeason = { id -> onSwitchSeries(id) })
                }
            }
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(0.06f))
                    .border(1.dp, Color.White.copy(0.09f), RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("当前解码与音轨", color = Color.White.copy(0.6f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                val info = listOfNotNull(tags.res, tags.range, tags.codec, tags.audio).joinToString(" · ").ifEmpty { "Media3 + FFmpeg 软解就绪" }
                Text(info, color = pc.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
    }
}

data class TechTags(val res: String? = null, val range: String? = null, val codec: String? = null, val audio: String? = null, val fps: Int? = null) {
    companion object {
        fun from(p: ExoPlayer, quality: String?): TechTags {
            val v = p.videoFormat; val a = p.audioFormat
            val res = quality?.uppercase() ?: v?.let { f -> val h = minOf(f.width, f.height); if (h > 0) "${h}P" else null }
            val codec = v?.sampleMimeType?.let { when { it.contains("hevc") -> "HEVC"; it.contains("avc") -> "H.264"; it.contains("av01") -> "AV1"; else -> null } }
            val dolbyVision = v?.sampleMimeType == androidx.media3.common.MimeTypes.VIDEO_DOLBY_VISION ||
                v?.codecs?.let { c -> c.startsWith("dvh") || c.startsWith("dvav") || c.startsWith("dva1") || c.startsWith("dav1") } == true
            val range = when {
                dolbyVision -> "Dolby Vision"
                v?.colorInfo?.colorTransfer == androidx.media3.common.C.COLOR_TRANSFER_HLG -> "HLG"
                v?.colorInfo?.colorTransfer == androidx.media3.common.C.COLOR_TRANSFER_ST2084 -> "HDR10"
                else -> "SDR"
            }
            val audio = a?.let { f -> val n = when { f.sampleMimeType?.contains("mp4a") == true -> "AAC"; f.sampleMimeType?.contains("eac3") == true -> "E-AC-3"; else -> "音频" }
                if (f.channelCount > 0) "$n ${f.channelCount}.0" else n }
            val fps = v?.frameRate?.takeIf { it > 0 }?.let { Math.round(it) }
            return TechTags(res, if (v != null) range else null, codec, audio, fps)
        }
    }
}

/**
 * Technical status badges: neutral glass pills for resolution / codec / high frame rate and accent
 * pills for the active GPU stages (AI 超分, SDR→HDR) or a native HDR range. Tapping opens the enhancement panel.
 */
@Composable
private fun StatusBadges(t: TechTags, enhanceOn: Boolean, hdrOn: Boolean, nativeRange: String?, enabled: Boolean, onClick: () -> Unit) {
    val items = buildList {
        t.res?.let { add(it to false) }
        t.codec?.let { add(it to false) }
        t.fps?.takeIf { it >= 48 }?.let { add("${it}FPS" to false) }
        when {
            nativeRange != null -> add(nativeRange to true)
            hdrOn -> add("SDR→HDR" to true)
        }
        if (enhanceOn && nativeRange == null) add("AI 超分" to true)
    }
    if (items.isEmpty()) return
    val pc = IdjDarkColors
    Row(Modifier.clip(RoundedCornerShape(7.dp)).pressClick(0.96f, enabled = enabled, label = "画质增强", onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        items.forEach { (label, accent) ->
            Box(Modifier.height(19.dp).clip(RoundedCornerShape(6.dp))
                .background(if (accent) pc.accent.copy(0.22f) else Color.White.copy(0.14f))
                .border(0.6.dp, if (accent) pc.accent.copy(0.62f) else Color.White.copy(0.14f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                Text(label, color = if (accent) pc.accent else Color.White.copy(0.9f), fontSize = 10.sp, lineHeight = 12.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun CircleBtn(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    size: androidx.compose.ui.unit.Dp = 42.dp,
    iconSize: androidx.compose.ui.unit.Dp = 20.dp,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    // Dark glass circle drawn over the video (inside the root layer), so no backdrop sampling:
    // LIQUID = sheer fill + specular rim, FROSTED = denser frosted fill + hairline.
    app.iduanju.ui.components.GlassIconButton(icon, label, size = size, iconSize = iconSize, dark = true, tint = tint,
        fallbackAlpha = 0.42f, enabled = enabled, onClick = onClick)
}

/** Landscape console chip: icon + title + value; accent capsule when on. */
@Composable
private fun LandChip(icon: ImageVector, title: String, value: String?, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val pc = IdjDarkColors
    Row(Modifier.height(34.dp).graphicsLayer { alpha = if (enabled) 1f else 0.45f }
        .then(if (on) Modifier.clip(CircleShape).background(pc.accentSoft).border(1.dp, pc.accentLine, CircleShape)
            else Modifier.glassSurface(CircleShape, dark = true, shadowRadius = 0.dp, fallbackAlpha = 0.46f))
        .pressClick(enabled = enabled, label = title, onClick = onClick).padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, tint = if (on) pc.accent else Color.White, modifier = Modifier.size(17.dp))
        Text(title + (value?.let { " $it" } ?: ""), color = if (on) pc.accent else Color.White.copy(0.8f), fontSize = 13.sp, maxLines = 1, softWrap = false)
    }
}

/** Portrait action row cell (spec `.pact`): 23 dp icon over an 11 sp caption that carries the current value. */
@Composable
private fun RowScope.ActionBtn(icon: ImageVector, title: String, value: String?, active: Boolean, accent: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val pc = IdjDarkColors
    val bg by androidx.compose.animation.animateColorAsState(if (active) Color.White.copy(0.15f) else Color.Transparent, DS.select(), label = "pact")
    Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(bg)
        .graphicsLayer { alpha = if (enabled) 1f else 0.42f }
        .pressClick(enabled = enabled, label = title, onClick = onClick)
        .padding(top = 7.dp, bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, title, tint = if (accent) pc.accent else Color.White, modifier = Modifier.size(23.dp))
        Spacer(Modifier.height(4.dp))
        Text(androidx.compose.ui.text.buildAnnotatedString {
            append(title)
            if (value != null) {
                append(" ")
                pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold, color = if (accent) pc.accent else Color.White)); append(value); pop()
            }
        }, color = Color.White.copy(0.72f), fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, softWrap = false)
    }
}

/** Small scrubber-row toggle (画面比例 / 横竖屏): icon with an optional caption. */
@Composable
private fun HudIcon(icon: ImageVector, label: String?, on: Boolean, onClick: () -> Unit) {
    val pc = IdjDarkColors
    Row(Modifier.height(32.dp).clip(CircleShape).pressClick(0.9f, label = label ?: "横竖屏", onClick = onClick).padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, label, tint = if (on) pc.accent else Color.White, modifier = Modifier.size(21.dp))
        if (label != null) Text(label, color = if (on) pc.accent else Color.White.copy(0.86f), fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun PanelTitle(title: String, trailing: String?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, color = Color.White.copy(0.6f), fontSize = 12.sp) }
    }
}

/** spec `.prow`: 46 dp, value right-aligned; current row accent-soft with accent text and ✓. */
@Composable
private fun PanelRow(title: String, detail: String, on: Boolean, pc: IdjColors, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(12.dp)).background(if (on) pc.accentSoft else Color.Transparent)
        .pressClick(0.98f, onClick = onClick).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = if (on) pc.accent else Color.White, fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
        if (detail.isNotEmpty()) Text(detail, color = if (on) pc.accent.copy(0.8f) else Color.White.copy(0.55f), fontSize = 12.sp)
        if (on) { Spacer(Modifier.width(10.dp)); Icon(Icons.Filled.Check, null, tint = pc.accent, modifier = Modifier.size(18.dp)) }
    }
}

@Composable
private fun EpisodesPanel(d: HGSeriesDetail, epIndex: Int, pc: IdjColors, onPick: (Int) -> Unit, onSeason: (String) -> Unit) {
    val total = d.episodes.size
    PanelTitle("选集", "共 ${maxOf(total, d.totalEps)} 集 · 更新至 ${d.episodes.lastOrNull()?.ep ?: total} 集")
    val seasons = remember(d.relatedSeries) {
        d.relatedSeries.map { it to app.iduanju.core.hongguo.SeasonParser.seasonNo(it.title) }.distinctBy { it.second }.sortedByDescending { it.second }
    }
    val liquid = GlassStylePref.style == GlassStyle.LIQUID
    val chip: @Composable (String, Boolean, Boolean, () -> Unit) -> Unit = { t, on, sm, f ->
        Box(Modifier.height(if (sm) 28.dp else 32.dp)
            .then(if (liquid) Modifier.glassPill(RoundedCornerShape(50), selected = on, dark = true, accent = pc.accent, neutralSelected = true)
                else Modifier.clip(RoundedCornerShape(50)).background(if (on) pc.thumb else pc.glass))
            .pressClick(onClick = f).padding(horizontal = if (sm) 12.dp else 14.dp), contentAlignment = Alignment.Center) {
            Text(t, color = if (on) pc.text else pc.secondary, fontSize = if (sm) 13.sp else 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
    if (seasons.size > 1) Row(Modifier.fillMaxWidth().padding(top = 14.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        seasons.forEach { (s, n) -> chip("第 $n 季", s.seriesId == d.seriesId, false) { if (s.seriesId != d.seriesId) onSeason(s.seriesId) } }
    }
    var range by remember { mutableIntStateOf((epIndex / 30).coerceAtLeast(0)) }
    if (total > 30) Row(Modifier.fillMaxWidth().padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (0..(total - 1) / 30).forEach { r -> chip("${d.episodes[r * 30].ep}-${d.episodes[minOf(total - 1, r * 30 + 29)].ep}", r == range, true) { range = r } }
    }
    val slice = d.episodes.withIndex().toList().drop(range * 30).take(30)
    androidx.compose.animation.AnimatedContent(slice, label = "eps", transitionSpec = { fadeIn(DS.select()) togetherWith fadeOut(DS.select()) }) { sl ->
        LazyVerticalGrid(GridCells.Adaptive(52.dp), Modifier.heightIn(max = 300.dp).padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sl.size) { k ->
                val (i, ep) = sl[k]
                val on = i == epIndex
                Box(Modifier.height(44.dp)
                    .then(
                        if (liquid) Modifier.glassPill(RoundedCornerShape(12.dp), selected = on, dark = true, accent = pc.accent)
                        else Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) pc.accentSoft else pc.glass)
                            .then(if (on) Modifier.border(1.dp, pc.accentLine, RoundedCornerShape(12.dp)) else Modifier),
                    )
                    .pressClick { onPick(i) }, contentAlignment = Alignment.Center) {
                    Text("${ep.ep}", color = if (on) pc.accent else Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    if (on) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(5.dp).clip(CircleShape).background(pc.accent))
                }
            }
        }
    }
}

/**
 * Elapsed · scrubber · remaining. Position / duration / buffer come in as providers so the 250 ms playback ticks
 * recompose only this row, never the whole PlayerScreen.
 */
@Composable
private fun PlayerTimeBar(
    posProvider: () -> Long,
    durProvider: () -> Long,
    bufferedProvider: () -> Long,
    thick: Boolean,
    modifier: Modifier = Modifier,
    onSeek: (Long) -> Unit,
) {
    val pos = posProvider()
    val dur = durProvider()
    val tStyle = androidx.compose.ui.text.TextStyle(color = Color.White.copy(0.86f), fontSize = if (thick) 13.sp else 12.sp, fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum", shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(0.6f), blurRadius = 4f))
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(fmt(pos), style = tStyle)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) { ProgressBar(pos, dur, bufferedProvider(), thick = thick, onSeek = onSeek) }
        Spacer(Modifier.width(10.dp))
        Text(if (dur > 0) "−" + fmt(dur - pos) else "−0:00", style = tStyle)
    }
}

/** 2 dp always-on edge progress while the HUD is hidden; reads position in the draw phase only (no recomposition). */
@Composable
private fun EdgeProgressBar(posProvider: () -> Long, durProvider: () -> Long, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(2.dp).drawBehind {
        drawRect(Color.White.copy(0.18f))
        val d = durProvider()
        val f = if (d > 0) (posProvider().toFloat() / d).coerceIn(0f, 1f) else 0f
        if (f > 0f) drawRect(Color.White.copy(0.6f), size = androidx.compose.ui.geometry.Size(size.width * f, size.height))
    })
}

/** spec `.ptrack`: 4 dp track, buffered white 30 %, 14 dp round knob; grows while dragging. Tap/drag to seek. */
@Composable
private fun ProgressBar(pos: Long, dur: Long, buffered: Long, thick: Boolean = false, onSeek: (Long) -> Unit) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val frac = drag ?: if (dur > 0) pos.toFloat() / dur else 0f
    val buf = if (dur > 0) buffered.toFloat() / dur else 0f
    val h by androidx.compose.animation.core.animateDpAsState(
        when { drag != null && thick -> 10.dp; drag != null -> 8.dp; thick -> 6.dp; else -> 4.dp }, DS.press(), label = "trk")
    val knob by androidx.compose.animation.core.animateDpAsState(
        when { drag != null -> if (thick) 22.dp else 20.dp; else -> if (thick) 16.dp else 14.dp }, DS.press(), label = "knob")
    BoxWithConstraints(Modifier.fillMaxWidth().height(if (thick) 32.dp else 28.dp)
        .pointerInput(dur) { detectTapGestures { o -> if (dur > 0) onSeek((o.x / size.width * dur).toLong()) } }
        .pointerInput(dur) {
            detectHorizontalDragGestures(onDragEnd = { drag?.let { if (dur > 0) onSeek((it * dur).toLong()) }; drag = null }) { ch, _ ->
                drag = (ch.position.x / size.width).coerceIn(0f, 1f)
            }
        }, contentAlignment = Alignment.CenterStart) {
        val w = maxWidth
        Box(Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(50)).background(Color.White.copy(0.24f)))
        Box(Modifier.width(w * buf.coerceIn(0f, 1f)).height(h).clip(RoundedCornerShape(50)).background(Color.White.copy(0.30f)))
        Box(Modifier.width(w * frac.coerceIn(0f, 1f)).height(h).clip(RoundedCornerShape(50)).background(Color.White))
        // glowing thumb while dragging
        if (drag != null) Box(Modifier.offset(x = (w * frac.coerceIn(0f, 1f)) - knob).size(knob * 2).clip(CircleShape).background(Color.White.copy(0.18f)))
        Box(Modifier.offset(x = (w * frac.coerceIn(0f, 1f)) - knob / 2).size(knob)
            .shadow(3.dp, CircleShape).clip(CircleShape).background(Color.White))
    }
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).toInt(); val h = s / 3600; val m = s / 60 % 60; val r = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
}


/** FLAG_KEEP_SCREEN_ON on the hosting window while video plays; cleared on pause, end, error and leaving the player. */
internal fun keepScreenOn(activity: android.app.Activity?, on: Boolean) {
    val w = activity?.window ?: return
    if (on) w.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    else w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    android.util.Log.i("IDJ", "keepScreenOn=$on")
}

/** The user's system brightness as 0..1 (handles OEM ranges above 255 via the framework's configured maximum). */
private fun systemBrightness(ctx: android.content.Context): Float {
    val raw = runCatching { android.provider.Settings.System.getInt(ctx.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128)
    val res = android.content.res.Resources.getSystem()
    val id = res.getIdentifier("config_screenBrightnessSettingMaximum", "integer", "android")
    val max = (if (id != 0) runCatching { res.getInteger(id) }.getOrDefault(255) else 255).coerceAtLeast(1)
    return (raw.toFloat() / maxOf(max, raw).toFloat()).coerceIn(0f, 1f)
}

/** Returns true when system auto-rotation (`ACCELEROMETER_ROTATION == 1`) is enabled. */
private fun isAutoRotateEnabled(ctx: android.content.Context): Boolean =
    runCatching {
        android.provider.Settings.System.getInt(
            ctx.contentResolver,
            android.provider.Settings.System.ACCELEROMETER_ROTATION,
            0,
        ) == 1
    }.getOrDefault(false)

/** Fraction of the player height (capped at 105 dp) the centre-zone drag must pass before releasing switches the episode. */
private const val EPISODE_SWITCH_THRESHOLD = 0.14f
private val EPISODE_SWITCH_MAX_DP = 105.dp
private val EPISODE_FLICK_VELOCITY_DP = 750.dp
private val EPISODE_FLICK_MIN_DISTANCE_DP = 36.dp

private enum class GestureHudKind { BRIGHTNESS, VOLUME }

/** Centred glass pill for the 亮度 / 音量 side-zone drags: icon · white level bar · percentage. */
@Composable
private fun GestureHudPill(kind: GestureHudKind, value: Float) {
    val v = value.coerceIn(0f, 1f)
    val icon = when (kind) {
        GestureHudKind.BRIGHTNESS -> when {
            v < 0.34f -> Icons.Filled.BrightnessLow
            v < 0.67f -> Icons.Filled.BrightnessMedium
            else -> Icons.Filled.BrightnessHigh
        }
        GestureHudKind.VOLUME -> when {
            v <= 0.001f -> Icons.AutoMirrored.Filled.VolumeOff
            v < 0.5f -> Icons.AutoMirrored.Filled.VolumeDown
            else -> Icons.AutoMirrored.Filled.VolumeUp
        }
    }
    Row(
        Modifier.glassSurface(RoundedCornerShape(50), dark = true, base = Color(0xFF15171C), shadowRadius = 14.dp, fallbackAlpha = 0.55f)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.width(132.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(0.22f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(v).background(Color.White))
        }
        Spacer(Modifier.width(12.dp))
        Text("${(v * 100).toInt()}%", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.widthIn(min = 40.dp))
    }
}

/** Adjacent-episode card that slides in with the centre-zone drag: blurred cover backdrop, poster, prompt. */
@Composable
private fun EpisodeSwitchPreview(cover: String, ep: app.iduanju.core.hongguo.HGEpisode, next: Boolean, armed: Boolean, modifier: Modifier) {
    Box(modifier.background(Color.Black)) {
        if (cover.isNotEmpty()) coil.compose.AsyncImage(cover, null, Modifier.fillMaxSize().blur(36.dp),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop, alpha = 0.55f)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(0.35f), Color.Black.copy(0.72f)))))
        Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (cover.isNotEmpty()) coil.compose.AsyncImage(cover, null,
                Modifier.width(132.dp).aspectRatio(0.72f).shadow(18.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            Spacer(Modifier.height(18.dp))
            Text(if (next) "下一集 · 第 ${ep.ep} 集" else "上一集 · 第 ${ep.ep} 集", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            if (ep.title.isNotBlank()) Text(ep.title, color = Color.White.copy(0.7f), fontSize = 13.sp, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(16.dp))
            Row(Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(if (armed) 0.24f else 0.12f)).padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(if (next) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (armed) "松手立即播放" else if (next) "上滑切换下一集" else "下滑切换上一集",
                    color = Color.White, fontSize = 13.sp, fontWeight = if (armed) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

private const val ACTION_PIP_PLAY_PAUSE = "app.iduanju.action.PIP_PLAY_PAUSE"
private const val ACTION_PIP_NEXT = "app.iduanju.action.PIP_NEXT"

@Composable
private fun MoreSettingsPanel(
    fillMode: Boolean,
    /** 0 关闭 · 1 柔和 · 2 平衡 · 3 鲜艳 · 4 极致. */
    ambientLevel: Int,
    enhanceLevel: Int,
    sdrToHdr: Boolean,
    /** Non-null ("HDR10" / "HLG" / "Dolby Vision") when the stream is already native HDR. */
    nativeHdrRange: String?,
    sleepMode: Int,
    sleepEndMs: Long,
    autoNext: Boolean,
    showAutoNext: Boolean,
    pc: IdjColors,
    onFillMode: (Boolean) -> Unit,
    onAmbientLevel: (Int) -> Unit,
    onEnhance: (Int) -> Unit,
    onSdrToHdr: (Boolean) -> Unit,
    onSleep: (Int) -> Unit,
    onAutoNext: (Boolean) -> Unit,
) {
    val sleepSubtitle = when {
        sleepMode == -1 -> "定时：播完本集"
        sleepMode > 0 && sleepEndMs > 0L -> {
            val remMin = ((sleepEndMs - System.currentTimeMillis()).coerceAtLeast(0L) / 60_000L) + 1
            "定时：约 ${remMin} 分钟后暂停"
        }
        else -> null
    }
    PanelTitle("更多设置", sleepSubtitle)
    Spacer(Modifier.height(10.dp))

    MoreOptionRow(
        label = "画面比例",
        options = listOf("适应" to false, "铺满" to true),
        selected = fillMode,
        pc = pc,
        onSelect = onFillMode,
    )
    Spacer(Modifier.height(10.dp))

    val hdrLocked = nativeHdrRange != null || sdrToHdr
    MoreOptionRow(
        label = when {
            nativeHdrRange != null -> "氛围模式 (原生 $nativeHdrRange 已锁定关闭)"
            sdrToHdr -> "氛围模式 (SDR 转 HDR 开启时已锁定关闭)"
            else -> "氛围模式"
        },
        options = listOf("关闭" to 0, "柔和" to 1, "平衡" to 2, "鲜艳" to 3, "极致" to 4),
        selected = if (hdrLocked) 0 else ambientLevel,
        enabled = !hdrLocked,
        pc = pc,
        onSelect = onAmbientLevel,
    )
    Spacer(Modifier.height(10.dp))

    MoreOptionRow(
        label = "AI 超分辨率 (EWA-Lanczos4)",
        options = listOf("关闭" to 0, "标准" to 1, "强效" to 2),
        selected = enhanceLevel,
        pc = pc,
        onSelect = onEnhance,
    )
    Spacer(Modifier.height(10.dp))

    if (nativeHdrRange != null) {
        MoreOptionRow(
            label = "SDR 转 HDR (逆色调映射) · 当前片源已是原生 $nativeHdrRange",
            options = listOf("原生 $nativeHdrRange" to true),
            selected = true,
            enabled = false,
            pc = pc,
            onSelect = onSdrToHdr,
        )
    } else {
        MoreOptionRow(
            label = "SDR 转 HDR (逆色调映射)",
            options = listOf("关闭" to false, "开启" to true),
            selected = sdrToHdr,
            pc = pc,
            onSelect = onSdrToHdr,
        )
    }
    Spacer(Modifier.height(10.dp))

    MoreOptionRow(
        label = "定时关闭",
        options = listOf("关" to 0, "本集完" to -1, "15分" to 15, "30分" to 30, "60分" to 60),
        selected = sleepMode,
        pc = pc,
        onSelect = onSleep,
    )

    if (showAutoNext) {
        Spacer(Modifier.height(10.dp))
        MoreOptionRow(
            label = "自动连播",
            options = listOf("开启" to true, "关闭" to false),
            selected = autoNext,
            pc = pc,
            onSelect = onAutoNext,
        )
    }
}

@Composable
private fun <T> MoreOptionRow(
    label: String,
    options: List<Pair<String, T>>,
    selected: T,
    pc: IdjColors,
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    Text(label, color = Color.White.copy(if (enabled) 0.60f else 0.38f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(6.dp))
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).graphicsLayer { alpha = if (enabled) 1f else 0.45f },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val liquid = GlassStylePref.style == GlassStyle.LIQUID
        options.forEach { (title, value) ->
            val on = value == selected
            Box(
                Modifier
                    .then(
                        // LIQUID: sheer dark glass chip + specular rim, glowing accent rim when selected
                        if (liquid) Modifier.glassPill(RoundedCornerShape(10.dp), selected = on, dark = true, accent = pc.accent)
                        else Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (on) pc.accentSoft else Color.White.copy(0.08f))
                            .border(1.dp, if (on) pc.accent.copy(0.55f) else Color.White.copy(0.10f), RoundedCornerShape(10.dp)),
                    )
                    .clickable { onSelect(value) }
                    .padding(horizontal = 13.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    title,
                    color = if (on) pc.accent else Color.White.copy(0.86f),
                    fontSize = 12.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/** Per-level 氛围模式 tuning (柔和 / 平衡 / 鲜艳 / 极致). */
private class AmbientSpec(
    val alpha: Float,
    val blur: androidx.compose.ui.unit.Dp,
    val sampleIntervalMs: Long,
    /** EMA blend per 60 ms reference step toward the latest sampled target (lower = calmer). */
    val stepAlpha: Float,
    val edgeOverlayAlpha: Float,
    val satMult: Float,
    val valMult: Float,
    val maxVal: Float,
)

private val AmbientSpecs = arrayOf(
    AmbientSpec(0.50f, 68.dp, 320L, 0.08f, 0.32f, 1.15f, 1.08f, 0.78f), // 1 柔和
    AmbientSpec(0.75f, 54.dp, 260L, 0.13f, 0.48f, 1.30f, 1.18f, 0.86f), // 2 平衡
    AmbientSpec(0.90f, 44.dp, 220L, 0.20f, 0.62f, 1.48f, 1.28f, 0.94f), // 3 鲜艳
    AmbientSpec(1.00f, 36.dp, 180L, 0.30f, 0.76f, 1.65f, 1.38f, 1.00f), // 4 极致
)

private fun ambientSpec(level: Int): AmbientSpec = AmbientSpecs[(level.coerceIn(1, 4)) - 1]

private fun ambientLevelLabel(level: Int): String = when (level) {
    1 -> "柔和"; 2 -> "平衡"; 3 -> "鲜艳"; 4 -> "极致"; else -> "关闭"
}

private const val AMBIENT_CELLS = 16

/** One analysed 24x24 sample: boosted 16x16 target colours (0..1 RGB), letterbox edges and raw inset fractions. */
private class AmbientFrame(
    val rgb: FloatArray,
    /** Dark rows / columns trimmed from top, bottom, left, right (in sample pixels), after hysteresis. */
    val edges: IntArray,
    val topInsetFrac: Float,
    val bottomInsetFrac: Float,
)

/**
 * Analyses one tiny TextureView readback. Pure (safe off the main thread): the previous letterbox edges come in as
 * [prevEdges] and the new ones go out in the result.
 *
 * Letterbox detection uses hysteresis plus a 1-row-per-sample clamp: a bar only grows when the stricter (< 16) scan
 * says so and only shrinks when the looser (< 22) scan says so, and each edge moves at most one sample row per call.
 * Pixels hovering around luma 17–19 therefore never make the trimmed region (and every colour) jump.
 */
private fun analyzeAmbientFrame(bmp: android.graphics.Bitmap, level: Int, prevEdges: IntArray?): AmbientFrame? {
    val spec = ambientSpec(level)
    val w = bmp.width
    val h = bmp.height
    if (w <= 0 || h <= 0) return null
    val pixels = IntArray(w * h)
    bmp.getPixels(pixels, 0, w, 0, 0, w, h)

    fun maxCh(px: Int) = maxOf((px shr 16) and 0xFF, (px shr 8) and 0xFF, px and 0xFF)
    fun rowMax(y: Int): Int { var m = 0; val o = y * w; for (x in 0 until w) m = maxOf(m, maxCh(pixels[o + x])); return m }
    fun colMax(x: Int): Int { var m = 0; for (y in 0 until h) m = maxOf(m, maxCh(pixels[y * w + x])); return m }

    // dark run length from each side for a threshold
    fun runTop(th: Int): Int { var t = 0; while (t < h / 3 && rowMax(t) < th) t++; return t }
    fun runBottom(th: Int): Int { var t = 0; while (t < h / 3 && rowMax(h - 1 - t) < th) t++; return t }
    fun runLeft(th: Int): Int { var t = 0; while (t < w / 3 && colMax(t) < th) t++; return t }
    fun runRight(th: Int): Int { var t = 0; while (t < w / 3 && colMax(w - 1 - t) < th) t++; return t }

    fun settle(prev: Int, strict: Int, loose: Int): Int = when {
        prev < 0 -> strict // first sample of a video: take the conservative value directly
        strict > prev -> prev + 1
        loose < prev -> prev - 1
        else -> prev
    }
    val p = prevEdges
    var eTop = settle(p?.get(0) ?: -1, runTop(16), runTop(22))
    var eBottom = settle(p?.get(1) ?: -1, runBottom(16), runBottom(22))
    var eLeft = settle(p?.get(2) ?: -1, runLeft(16), runLeft(22))
    var eRight = settle(p?.get(3) ?: -1, runRight(16), runRight(22))
    if (h - eTop - eBottom < 4) { eTop = 0; eBottom = 0 }
    if (w - eLeft - eRight < 4) { eLeft = 0; eRight = 0 }

    val x0 = eLeft
    val x1 = w - eRight // exclusive
    val y0 = eTop
    val y1 = h - eBottom
    val aw = (x1 - x0).toFloat()
    val ah = (y1 - y0).toFloat()

    /**
     * Gentle vibrancy that preserves darkness: near-black stays black (a black suit must not glow), everything else
     * gets a mild brightness curve and saturation lift. Never lifts dark pixels to a fixed minimum brightness.
     */
    val hsv = FloatArray(3)
    fun boost(r: Int, g: Int, b: Int): Int {
        android.graphics.Color.RGBToHSV(r, g, b, hsv)
        val s = hsv[1]
        val v = hsv[2]
        if (v < 0.05f) return 0xFF000000.toInt()
        if (s > 0.05f) hsv[1] = (s * spec.satMult).coerceIn(0f, 0.95f)
        hsv[2] = (v * spec.valMult).coerceIn(0f, spec.maxVal)
        return android.graphics.Color.HSVToColor(hsv)
    }

    // Bilinear sample at each cell centre of the trimmed active picture: far less temporal aliasing than nearest.
    val n = AMBIENT_CELLS
    val out = FloatArray(n * n * 3)
    for (gy in 0 until n) {
        val fy = (y0 + (gy + 0.5f) * ah / n - 0.5f).coerceIn(y0.toFloat(), (y1 - 1).toFloat())
        val iy = fy.toInt()
        val iy2 = (iy + 1).coerceAtMost(y1 - 1)
        val ty = fy - iy
        for (gx in 0 until n) {
            val fx = (x0 + (gx + 0.5f) * aw / n - 0.5f).coerceIn(x0.toFloat(), (x1 - 1).toFloat())
            val ix = fx.toInt()
            val ix2 = (ix + 1).coerceAtMost(x1 - 1)
            val tx = fx - ix
            val c00 = pixels[iy * w + ix]; val c10 = pixels[iy * w + ix2]
            val c01 = pixels[iy2 * w + ix]; val c11 = pixels[iy2 * w + ix2]
            fun ch(shift: Int): Int {
                val a = ((c00 shr shift) and 0xFF) * (1 - tx) + ((c10 shr shift) and 0xFF) * tx
                val b = ((c01 shr shift) and 0xFF) * (1 - tx) + ((c11 shr shift) and 0xFF) * tx
                return (a * (1 - ty) + b * ty).roundToInt().coerceIn(0, 255)
            }
            val c = boost(ch(16), ch(8), ch(0))
            val k = (gy * n + gx) * 3
            out[k] = ((c shr 16) and 0xFF) / 255f
            out[k + 1] = ((c shr 8) and 0xFF) / 255f
            out[k + 2] = (c and 0xFF) / 255f
        }
    }
    return AmbientFrame(
        rgb = out,
        edges = intArrayOf(eTop, eBottom, eLeft, eRight),
        topInsetFrac = eTop.toFloat() / h,
        bottomInsetFrac = eBottom.toFloat() / h,
    )
}

/**
 * 氛围模式 temporal engine. The sampler only moves [targetRgb]; [step] low-passes [emaRgb] toward it (EMA) and
 * renders the result into ONE persistent 16x16 glow (a 2-slot ring, so the slot being rewritten is never the one the
 * RenderThread is still uploading). Edge colours and letterbox insets are derived from the same smoothed state, so
 * the background glow and the over-video edge bleed flow together. No Crossfade, no alpha dips.
 *
 * [frame] is the only Compose state: it is read in draw scopes, so a glow update redraws two canvases and never
 * recomposes the player.
 */
@Stable
private class AmbientGlowEngine {
    private val size = AMBIENT_CELLS * AMBIENT_CELLS * 3
    private val targetRgb = FloatArray(size)
    private val emaRgb = FloatArray(size)
    private var targetTopInset = 0f
    private var targetBottomInset = 0f
    private var edges: IntArray? = null
    private val argb = IntArray(AMBIENT_CELLS * AMBIENT_CELLS)
    private val bitmaps = Array(2) {
        android.graphics.Bitmap.createBitmap(AMBIENT_CELLS, AMBIENT_CELLS, android.graphics.Bitmap.Config.ARGB_8888)
            .apply { eraseColor(android.graphics.Color.BLACK) }
    }
    private val images = bitmaps.map { it.asImageBitmap() }
    private var slot = 0

    /** Bumped by [reset]; a sample analysed before a reset is dropped. */
    var generation = 0
        private set
    var settled = true
        private set
    var frame by mutableIntStateOf(0)
        private set

    val image: androidx.compose.ui.graphics.ImageBitmap get() = images[slot]
    var top = Color.Black; private set
    var bottom = Color.Black; private set
    var left = Color.Black; private set
    var right = Color.Black; private set
    var topInsetFrac = 0f; private set
    var bottomInsetFrac = 0f; private set

    fun edgesSnapshot(): IntArray? = edges?.copyOf()

    /** New episode: glow eases in from black, letterbox detection starts over. */
    fun reset() {
        generation++
        targetRgb.fill(0f)
        edges = null
        targetTopInset = 0f
        targetBottomInset = 0f
        settled = false
    }

    fun commitTarget(f: AmbientFrame, gen: Int) {
        if (gen != generation) return
        f.rgb.copyInto(targetRgb)
        edges = f.edges
        targetTopInset = f.topInsetFrac
        targetBottomInset = f.bottomInsetFrac
        settled = false
    }

    /** One EMA step; [stepAlpha] is defined per 60 ms and rescaled to the real [dtMs] (frame-rate independent). */
    fun step(dtMs: Float, stepAlpha: Float) {
        val a = 1f - Math.pow((1f - stepAlpha).toDouble(), (dtMs / 60f).toDouble()).toFloat()
        val ai = 1f - Math.pow(0.88, (dtMs / 60f).toDouble()).toFloat()
        var maxDiff = 0f
        for (k in 0 until size) {
            val d = targetRgb[k] - emaRgb[k]
            val ad = kotlin.math.abs(d)
            if (ad > maxDiff) maxDiff = ad
            emaRgb[k] = if (ad < 0.0015f) targetRgb[k] else emaRgb[k] + d * a
        }
        val dTop = targetTopInset - topInsetFrac
        val dBottom = targetBottomInset - bottomInsetFrac
        topInsetFrac = if (kotlin.math.abs(dTop) < 0.0005f) targetTopInset else topInsetFrac + dTop * ai
        bottomInsetFrac = if (kotlin.math.abs(dBottom) < 0.0005f) targetBottomInset else bottomInsetFrac + dBottom * ai
        val moving = maxDiff >= 0.0015f || kotlin.math.abs(dTop) >= 0.0005f || kotlin.math.abs(dBottom) >= 0.0005f
        if (!moving && settled) return
        render()
        settled = !moving
    }

    private fun render() {
        val n = AMBIENT_CELLS
        for (i in 0 until n * n) {
            val k = i * 3
            val r = (emaRgb[k] * 255f).roundToInt().coerceIn(0, 255)
            val g = (emaRgb[k + 1] * 255f).roundToInt().coerceIn(0, 255)
            val b = (emaRgb[k + 2] * 255f).roundToInt().coerceIn(0, 255)
            argb[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val next = 1 - slot
        bitmaps[next].setPixels(argb, 0, n, 0, 0, n, n)
        slot = next
        val strip = n / 3 // ~5 of 16 cells, same third-strip as before
        top = regionColor(0, n, 0, strip)
        bottom = regionColor(0, n, n - strip, n)
        left = regionColor(0, strip, 0, n)
        right = regionColor(n - strip, n, 0, n)
        frame++
    }

    /**
     * Edge colour from its own smoothed cells: brightness + saturation weighted, near-black cells skipped (so a dark
     * edge with one bright cell is not dragged to grey), plain mean when the whole edge is dark — dark stays dark.
     */
    private fun regionColor(x0: Int, x1: Int, y0: Int, y1: Int): Color {
        var rs = 0f; var gs = 0f; var bs = 0f; var ws = 0f
        var fr = 0f; var fg = 0f; var fb = 0f; var fc = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            val k = (y * AMBIENT_CELLS + x) * 3
            val r = emaRgb[k]; val g = emaRgb[k + 1]; val b = emaRgb[k + 2]
            fr += r; fg += g; fb += b; fc++
            val mx = maxOf(r, g, b)
            if (mx < 14f / 255f) continue
            val wt = 1f / 255f + mx + (mx - minOf(r, g, b)) * 2f
            rs += r * wt; gs += g * wt; bs += b * wt; ws += wt
        }
        return when {
            ws > 0f -> Color((rs / ws).coerceIn(0f, 1f), (gs / ws).coerceIn(0f, 1f), (bs / ws).coerceIn(0f, 1f))
            fc > 0 -> Color((fr / fc).coerceIn(0f, 1f), (fg / fc).coerceIn(0f, 1f), (fb / fc).coerceIn(0f, 1f))
            else -> Color.Black
        }
    }
}

/**
 * 氛围模式 background glow, drawn BEHIND the PlayerView (whose letterbox is transparent).
 *
 * API 31+: the smoothed 16x16 map of the active (black-bar-trimmed) picture is stretched over the WHOLE container
 * and hardware-blurred with Rectangle edges (clamped, so the screen edges never bleed transparent black inward), in
 * ONE canvas that is simply redrawn as the EMA moves — no Crossfade, so there are no overlapping fading copies and
 * no brightness pulsing. Edge-bleed gradients from the screen edges inward add the strong rim glow.
 * API < 31: the edge-bleed gradients alone, each tinted only by its own darkness-preserving edge colour.
 */
@Composable
private fun AmbientGlowLayer(
    glow: AmbientGlowEngine,
    level: Int = 2,
    visible: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val spec = ambientSpec(level)
    val layerAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(450),
        label = "ambientVisible",
    )
    if (layerAlpha <= 0.005f) return
    val useBitmapGlow = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    Box(modifier.fillMaxSize()) {
        if (useBitmapGlow) {
            androidx.compose.foundation.Canvas(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = spec.alpha * layerAlpha }
                    .blur(radius = spec.blur, edgeTreatment = BlurredEdgeTreatment.Rectangle),
            ) {
                // reading the frame counter subscribes this draw (only) to EMA updates
                if (glow.frame < 0 || size.width <= 0f || size.height <= 0f) return@Canvas
                // full-screen container projection: (0, 0) → (w, h)
                drawImage(
                    image = glow.image,
                    dstOffset = androidx.compose.ui.unit.IntOffset(0, 0),
                    dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)),
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.Low,
                )
            }
        }
        // Edge bleed from the screen edges inward toward the video.
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            if (glow.frame < 0 || w <= 0f || h <= 0f) return@Canvas
            val top = glow.top
            val bottom = glow.bottom
            val left = glow.left
            val right = glow.right
            val baseAlpha = (if (useBitmapGlow) 0.62f else 0.82f) * spec.alpha * layerAlpha
            drawRect(
                brush = Brush.verticalGradient(
                    0f to top.copy(alpha = baseAlpha),
                    0.16f to top.copy(alpha = baseAlpha * 0.62f),
                    0.34f to Color.Transparent,
                    startY = 0f,
                    endY = h,
                ),
            )
            drawRect(
                brush = Brush.verticalGradient(
                    0.66f to Color.Transparent,
                    0.84f to bottom.copy(alpha = baseAlpha * 0.62f),
                    1f to bottom.copy(alpha = baseAlpha),
                    startY = 0f,
                    endY = h,
                ),
            )
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to left.copy(alpha = baseAlpha * 0.85f),
                    0.12f to left.copy(alpha = baseAlpha * 0.50f),
                    0.28f to Color.Transparent,
                    startX = 0f,
                    endX = w,
                ),
            )
            drawRect(
                brush = Brush.horizontalGradient(
                    0.72f to Color.Transparent,
                    0.88f to right.copy(alpha = baseAlpha * 0.50f),
                    1f to right.copy(alpha = baseAlpha * 0.85f),
                    startX = 0f,
                    endX = w,
                ),
            )
        }
    }
}

/**
 * 氛围模式 edge-bleed overlay, drawn OVER the PlayerView but below danmaku / gestures / HUD (draw-only, so touches
 * pass straight through). The TextureView paints opaque pixels over its own area, which hides the background glow
 * whenever a portrait video fills the height or carries black bars baked into the frame. This feathers the glow
 * into the top and bottom bands: container letterbox gap + baked-in bar, at least 88 dp (top) / 96 dp (bottom) in
 * portrait, so every portrait case (16:9, 4:3, 9:16 with baked-in bars, full 9:16 on a tall phone) shows a
 * spatially matched glow. Near-black edge colours (and the pre-sample black) draw nothing, so dark edges stay dark.
 * Colours and insets come straight from the engine's smoothed state, in lockstep with [AmbientGlowLayer].
 */
@Composable
private fun AmbientEdgeBleedOverlay(
    glow: AmbientGlowEngine,
    videoAspectRatio: Float,
    portrait: Boolean,
    level: Int,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val spec = ambientSpec(level)
    val overlayAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(450),
        label = "ambientEdgeVisible",
    )
    if (overlayAlpha <= 0.005f) return
    androidx.compose.foundation.Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        if (glow.frame < 0 || w <= 0f || h <= 0f) return@Canvas
        val topC = glow.top
        val bottomC = glow.bottom
        val topInset = glow.topInsetFrac.coerceIn(0f, 0.34f)
        val bottomInset = glow.bottomInsetFrac.coerceIn(0f, 0.34f)
        // Where the picture sits inside the FIT-sized TextureView (unknown aspect = fills the height).
        val ar = videoAspectRatio
        val vidH = if (ar > 0.05f) (if (ar > w / h) w / ar else h) else h
        val gap = ((h - vidH) * 0.5f).coerceAtLeast(0f)
        val topZone = maxOf(gap + vidH * topInset, if (portrait) 88.dp.toPx() else 0f).coerceAtMost(h * 0.45f)
        val bottomZone = maxOf(gap + vidH * bottomInset, if (portrait) 96.dp.toPx() else 0f).coerceAtMost(h * 0.45f)
        fun weight(c: Color) = (maxOf(c.red, c.green, c.blue) / 0.12f).coerceIn(0f, 1f)
        val topA = spec.edgeOverlayAlpha * overlayAlpha * weight(topC)
        val bottomA = spec.edgeOverlayAlpha * overlayAlpha * weight(bottomC)
        if (topZone > 1f && topA > 0.005f) {
            drawRect(
                brush = Brush.verticalGradient(
                    0f to topC.copy(alpha = topA),
                    0.5f to topC.copy(alpha = topA * 0.42f),
                    1f to Color.Transparent,
                    startY = 0f,
                    endY = topZone,
                ),
                size = androidx.compose.ui.geometry.Size(w, topZone),
            )
        }
        if (bottomZone > 1f && bottomA > 0.005f) {
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.5f to bottomC.copy(alpha = bottomA * 0.42f),
                    1f to bottomC.copy(alpha = bottomA),
                    startY = h - bottomZone,
                    endY = h,
                ),
                topLeft = androidx.compose.ui.geometry.Offset(0f, h - bottomZone),
                size = androidx.compose.ui.geometry.Size(w, bottomZone),
            )
        }
    }
}
