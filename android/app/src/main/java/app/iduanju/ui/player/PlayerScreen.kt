package app.iduanju.ui.player

import app.iduanju.ui.components.glassSurface
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlinx.coroutines.withContext

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
    var ambientMode by remember { mutableStateOf(PlayerPrefs.getAmbientMode(ctx)) }
    var enhanceLevel by remember { mutableIntStateOf(PlayerPrefs.getEnhanceLevel(ctx)) }
    var sdrToHdr by remember { mutableStateOf(PlayerPrefs.getSdrToHdr(ctx)) }
    var textureView by remember { mutableStateOf<android.view.TextureView?>(null) }
    var ambientTop by remember { mutableStateOf(Color.Black) }
    var ambientBottom by remember { mutableStateOf(Color.Black) }
    var ambientLeft by remember { mutableStateOf(Color.Black) }
    var ambientRight by remember { mutableStateOf(Color.Black) }
    var ambientBitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
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
    fun setLandscape(on: Boolean) {
        activity?.requestedOrientation = when {
            on -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            metrics.isTablet -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
    androidx.activity.compose.BackHandler(enabled = landscape) { setLandscape(false) }
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
    }
    LaunchedEffect(player, enhanceFailed) {
        installVideoEnhance(player, if (enhanceFailed) null else enhanceEffect)
    }
    LaunchedEffect(enhanceLevel, sdrToHdr, isNativeHdr, enhanceFailed) { syncEnhance() }
    // Wide colour gamut for SDR→HDR / native HDR. Not COLOR_MODE_HDR: on a TextureView that would
    // dim the Compose overlays and break the getBitmap() ambient readback.
    LaunchedEffect(sdrToHdr, isNativeHdr, enhanceFailed) {
        activity?.window?.colorMode =
            if ((sdrToHdr && !enhanceFailed) || isNativeHdr) android.content.pm.ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
            else android.content.pm.ActivityInfo.COLOR_MODE_DEFAULT
    }

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
            override fun onIsPlayingChanged(isPlaying: Boolean) { keepScreenOn(activity, isPlaying) }
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

    LaunchedEffect(seriesId, epIndex) {
        ambientTop = Color.Black
        ambientBottom = Color.Black
        ambientLeft = Color.Black
        ambientRight = Color.Black
        ambientBitmap = null
    }
    val hdrAmbientLocked = isNativeHdr || (sdrToHdr && !enhanceFailed)
    LaunchedEffect(ambientMode, fillMode, inPip, hdrAmbientLocked, playing, textureView, ambientSampleTick) {
        // Locked off whenever native HDR/DV or SDR->HDR is active.
        if (!ambientMode || fillMode || inPip || hdrAmbientLocked) return@LaunchedEffect
        val tv = textureView ?: return@LaunchedEffect
        while (isActive) {
            val bmp = if (tv.isAvailable) runCatching { tv.getBitmap(24, 24) }.getOrNull() else null
            if (bmp != null) {
                val sampled = withContext(Dispatchers.Default) {
                    try {
                        computeAmbientEdgeColors(bmp)
                    } finally {
                        bmp.recycle()
                    }
                }
                ambientTop = sampled.top
                ambientBottom = sampled.bottom
                ambientLeft = sampled.left
                ambientRight = sampled.right
                if (sampled.glowBitmap != null) {
                    ambientBitmap = sampled.glowBitmap
                }
            }
            if (!playing) break
            delay(280L)
        }
    }

    val activeQueue by rememberUpdatedState(queue)
    fun selectEpisode(index: Int) {
        val current = activeQueue
        if (current == null) epIndex = index else current.select(index)
    }
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
            // Only mutate Compose UI states when controls are visible or play state/duration changed
            if (showChrome || curPlaying != playing || curDur != dur) {
                pos = curPos; dur = curDur; buffered = player.bufferedPosition; playing = curPlaying
            }
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
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.colorMode = android.content.pm.ActivityInfo.COLOR_MODE_DEFAULT
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
    Box(Modifier.weight(1f).fillMaxHeight().background(Color.Black)) {
        AmbientGlowLayer(
            topColor = ambientTop,
            bottomColor = ambientBottom,
            leftColor = ambientLeft,
            rightColor = ambientRight,
            glowBitmap = ambientBitmap,
            videoAspectRatio = videoAspectRatio,
            visible = ambientMode && !fillMode && !inPip && !hdrAmbientLocked,
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
                view.resizeMode = if (fillMode) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
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
            modifier = Modifier.fillMaxSize(),
        )
        // official danmaku (红果 only), lanes under the top bar + tech strip, never into the bottom controls
        val epNow = detail?.episodes?.getOrNull(epIndex)
        if (!inPip && danmaku && epNow != null && seriesId.all { it.isDigit() }) {
            val topInset = with(androidx.compose.ui.platform.LocalDensity.current) { androidx.compose.foundation.layout.WindowInsets.statusBars.getTop(this).toDp() }
            DanmakuOverlay(player, seriesId, epNow.vid, laneTop = topInset + 44.dp + 34.dp, bottomLimit = 220.dp) { android.util.Log.i("IDJ", "danmaku: $it") }
        }
        // gesture layer: tap = chrome, double-tap sides = ±10s, long-press = 2x, vertical swipe = prev/next episode,
        // horizontal drag = scrub.
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
                .pointerInput(detail) {
                    var acc = 0f
                    detectVerticalDragGestures(onDragStart = { acc = 0f }, onDragEnd = {
                        val n = detail?.episodes?.size ?: 0
                        if (!locked && acc < -160 && epIndex + 1 < n) selectEpisode(epIndex + 1)
                        else if (!locked && acc > 160 && epIndex > 0) selectEpisode(epIndex - 1)
                    }) { _, d -> acc += d }
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

        // ── Chrome (redesign 14–17) ─────────────────────────────────────────────────────────────────────
        // Compact title + 2dp edge progress stay visible; full controls are independent overlays. The centre transport
        // only appears once the current episode is actually playable, so switching episodes never flashes it.
        val pc = IdjDarkColors
        val chromeA by androidx.compose.animation.core.animateFloatAsState(if (!inPip && (showChrome || panel != null)) 1f else 0f, DS.panel(), label = "chrome")
        val ready = status.isEmpty() && error == null && dur > 0
        Box(Modifier.fillMaxWidth().height(if(landscape)72.dp else 110.dp).graphicsLayer { alpha = chromeA }
            .background(Brush.verticalGradient(listOf(Color.Black.copy(0.45f), Color.Black.copy(0.16f), Color.Transparent))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(if(landscape)100.dp else 150.dp).graphicsLayer { alpha = chromeA }
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.42f to Color.Black.copy(0.16f), 1f to Color.Black.copy(0.45f))))
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = if(landscape)64.dp else 16.dp).padding(top = if(landscape)14.dp else 6.dp).graphicsLayer { alpha = chromeA }) {
            Row(Modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                CircleBtn(Icons.AutoMirrored.Filled.ArrowBackIos, "返回", showChrome && !locked, onBack)
                if(landscape) {
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(detail?.title.orEmpty(),color=Color.White,fontSize=16.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(if (queue != null) "第 ${epIndex + 1} 章 · ${detail?.episodes?.getOrNull(epIndex)?.title.orEmpty()}" else "第 ${detail?.episodes?.getOrNull(epIndex)?.ep ?: 1} 集 / 共 ${detail?.episodes?.size ?: 0} 集",maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,color=Color.White.copy(0.7f),fontSize=12.sp)
                    }
                    TechLine(tags, Modifier.padding(end = 14.dp), enhanceLevel, sdrToHdr)
                    if (queue == null) LandChip(Icons.Filled.Repeat,"连播",if(autoNext)"开" else "关",autoNext,showChrome&&!locked){autoNext=!autoNext}
                    Spacer(Modifier.width(10.dp))
                    CircleBtn(if(locked)Icons.Filled.Lock else Icons.Filled.LockOpen,if(locked)"解锁" else "锁屏",showChrome){locked=!locked}
                    Spacer(Modifier.width(10.dp))
                    CircleBtn(Icons.Filled.MoreHoriz, "更多", showChrome && !locked) { panel = if (panel == "more") null else "more" }
                    Spacer(Modifier.width(10.dp))
                    CircleBtn(Icons.Filled.FullscreenExit,"退出横屏",showChrome&&!locked){setLandscape(false)}
                } else {
                    Spacer(Modifier.weight(1f))
                    CircleBtn(Icons.Filled.PictureInPictureAlt, "画中画", showChrome) { enterPip() }
                    Spacer(Modifier.width(10.dp))
                    val fav = lib.isFavorite(seriesId)
                    if (queue == null) CircleBtn(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "收藏", showChrome) {
                        detail?.let { lib.toggleFavorite(seriesId, it.title, it.cover) }
                    }
                    Spacer(Modifier.width(10.dp))
                    CircleBtn(Icons.Filled.MoreHoriz, "更多", showChrome) { panel = if (panel == "more") null else "more" }
                }
            }
            if (!landscape) TechLine(tags, Modifier.fillMaxWidth().padding(top = 6.dp), enhanceLevel, sdrToHdr)
        }
        androidx.compose.animation.AnimatedVisibility(!inPip && showChrome && ready && !centerSuppressed && !locked && panel == null, Modifier.align(Alignment.Center),
            enter = fadeIn(DS.panel()) + androidx.compose.animation.scaleIn(DS.panel(), initialScale = 0.92f),
            exit = fadeOut(DS.panel()) + androidx.compose.animation.scaleOut(DS.panel(), targetScale = 0.92f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if(landscape)72.dp else 44.dp)) {
                Icon(Icons.Filled.Replay10, "后退10秒", tint = Color.White, modifier = Modifier.size(36.dp).pressClick(0.9f) { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)) })
                Box(Modifier.size(68.dp).glassSurface(CircleShape, dark = true, shadowRadius = 10.dp, fallbackAlpha = 0.46f)
                    .pressClick(0.92f, label = if (playing) "暂停" else "播放") { if (player.isPlaying) player.pause() else player.play() },
                    contentAlignment = Alignment.Center) {
                    androidx.compose.animation.Crossfade(playing, label = "pp") { p ->
                        Icon(if (p) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                }
                Icon(Icons.Filled.Forward10, "前进10秒", tint = Color.White, modifier = Modifier.size(36.dp).pressClick(0.9f) { player.seekTo(player.currentPosition + 10_000) })
            }
        }
        // Expanded bottom controls overlay the video; never reserve space in its viewport.
        if (showChrome || panel != null) Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().navigationBarsPadding().graphicsLayer { alpha = chromeA }
            .padding(horizontal = if (landscape) 64.dp else 20.dp).padding(bottom = if (landscape) 24.dp else 4.dp)) {
            val d = detail
            if (d != null && !landscape) {
                Row(Modifier.graphicsLayer { alpha = chromeA }.heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(d.source, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.White.copy(0.16f)).padding(horizontal = 7.dp, vertical = 2.dp))
                    Spacer(Modifier.width(8.dp))
                    val seasonTag = remember(d.relatedSeries) {
                        val ss = d.relatedSeries
                        val nums = ss.map { app.iduanju.core.hongguo.SeasonParser.seasonNo(it.title) }
                        if (ss.size > 1 && nums.toSet().size == ss.size)
                            "第 " + app.iduanju.core.hongguo.SeasonParser.seasonNo(ss.firstOrNull { it.seriesId == d.seriesId }?.title ?: d.title) + " 季" else null
                    }
                    seasonTag?.let { Text("$it · ", color = Color.White.copy(0.72f), fontSize = 13.sp) }
                    Text(if (queue != null) "第 ${epIndex + 1} 章" else "第 ${d.episodes.getOrNull(epIndex)?.ep ?: startEp} 集", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (queue != null) " · ${d.episodes.getOrNull(epIndex)?.title.orEmpty()}" else " / 共 ${maxOf(d.totalEps, d.episodes.size)} 集", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = Color.White.copy(0.72f), fontSize = 13.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text(d.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 27.sp, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(0.7f), blurRadius = 6f)))
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val tStyle = androidx.compose.ui.text.TextStyle(color = Color.White.copy(0.8f), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    fontFeatureSettings = "tnum", shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(0.6f), blurRadius = 4f))
                Text(fmt(pos), style = tStyle)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) { ProgressBar(pos, dur, buffered) { if(!locked){player.seekTo(it); showChrome = true} } }
                Spacer(Modifier.width(10.dp))
                Text(if (dur > 0) "−" + fmt(dur - pos) else "−0:00", style = tStyle)
                if (!landscape) {
                    Spacer(Modifier.width(10.dp))
                    Icon(Icons.Filled.Fullscreen, "横屏全屏", tint = Color.White,
                        modifier = Modifier.size(24.dp).pressClick(0.9f) { setLandscape(true) })
                }
            }
            if(landscape) Row(Modifier.fillMaxWidth().padding(top=6.dp).graphicsLayer{alpha=if(locked)0f else chromeA},verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                if(pos<15000&&dur>45000) LandChip(Icons.Filled.PlayArrow,"跳过片头",null,false,showChrome&&!locked){player.seekTo(15000)}
                Spacer(Modifier.weight(1f))
                LandChip(Icons.Filled.GridView,"选集",null,panel=="episodes",showChrome&&!locked){panel=if(panel=="episodes")null else "episodes"}
                LandChip(Icons.Filled.Speed,"倍速",if(speed==1f)"1.0×" else "${speed}×",panel=="speed",showChrome&&!locked){panel=if(panel=="speed")null else "speed"}
                LandChip(Icons.Filled.HighQuality,"画质",quality?:tags.res,panel=="quality",showChrome&&!locked){panel=if(panel=="quality")null else "quality"}
                LandChip(Icons.Filled.Subtitles,"弹幕",if(seriesId.all{it.isDigit()}) (if(danmaku)"开" else "关") else null,danmaku&&seriesId.all{it.isDigit()},showChrome&&!locked&&seriesId.all{it.isDigit()}){danmaku=!danmaku}
                LandChip(Icons.Filled.ClosedCaption,"音轨字幕",null,panel=="audio",showChrome&&!locked){panel=if(panel=="audio")null else "audio"}
            } else Row(Modifier.fillMaxWidth().padding(top = 10.dp).graphicsLayer { alpha = chromeA }.padding(horizontal = if (landscape) 120.dp else 0.dp)) {
                val dmOK = seriesId.all { it.isDigit() }
                val on = showChrome
                ActionBtn(Icons.Filled.GridView, "选集", null, panel == "episodes", false, on) { panel = if (panel == "episodes") null else "episodes" }
                ActionBtn(Icons.Filled.Speed, "倍速", if (speed == 1f) "1.0×" else "${speed}×".replace(".0×", "×"), panel == "speed", speed != 1f, on) { panel = if (panel == "speed") null else "speed" }
                ActionBtn(Icons.Filled.HighQuality, "画质", quality ?: tags.res, panel == "quality", false, on) { panel = if (panel == "quality") null else "quality" }
                ActionBtn(Icons.Filled.Subtitles, "弹幕", if (dmOK) (if (danmaku) "开" else "关") else null, false, dmOK && danmaku, on) { if (dmOK) danmaku = !danmaku }
                ActionBtn(Icons.Filled.ClosedCaption, "音轨字幕", null, panel == "audio", false, on) { panel = if (panel == "audio") null else "audio" }
            }

        }
        // Always-on title + edge progress are overlays, not a layout inset or a scrubber row.
        if (!inPip && !showChrome && panel == null) {
            Text(detail?.title.orEmpty(), color = Color.White.copy(0.85f), fontSize = 12.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(0.7f), blurRadius = 4f)),
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(horizontal = if (landscape) 28.dp else 16.dp, vertical = 10.dp))
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp).background(Color.White.copy(0.18f))) {
                Box(Modifier.fillMaxWidth(if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f).fillMaxHeight().background(Color.White.copy(0.6f)))
            }
        }
        // panels: one style for 选集 / 倍速 / 画质 / 音轨字幕 / 更多, sitting just above the action row
        if (!inPip && panel != null) Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { panel = null } })
        androidx.compose.animation.AnimatedVisibility(!inPip && panel != null, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
            .padding(horizontal = 12.dp).padding(bottom = if (landscape) 16.dp else 78.dp),
            enter = fadeIn(DS.panel()) + androidx.compose.animation.slideInVertically(DS.panel()) { it / 6 },
            exit = fadeOut(DS.panel()) + androidx.compose.animation.slideOutVertically(DS.panel()) { it / 6 }) {
            val last = remember { arrayOfNulls<String>(1) }
            if (panel != null) last[0] = panel
            val shown = last[0]
            Column(Modifier.fillMaxWidth().widthIn(max = 520.dp)
                .glassSurface(RoundedCornerShape(24.dp), dark = true, base = Color(0xFF1C1E24), shadowRadius = 20.dp, fallbackAlpha = 0.92f)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 14.dp)) {
                when (shown) {
                    "episodes" -> detail?.let { d -> EpisodesPanel(d, epIndex, pc, onPick = { selectEpisode(it); panel = null },
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
                        PanelRow(detail?.source ?: "当前片源", "当前", true, pc) { panel = null }
                    }
                    "more" -> {
                        MoreSettingsPanel(
                            fillMode = fillMode,
                            ambientMode = ambientMode,
                            enhanceLevel = enhanceLevel,
                            sdrToHdr = sdrToHdr,
                            nativeHdrRange = if (isNativeHdr) tags.range else null,
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
                            onAmbientMode = { on ->
                                when {
                                    isNativeHdr -> seekHint = "原生 ${tags.range} 模式下不可开启氛围光晕"
                                    sdrToHdr -> seekHint = "HDR 模式下不可开启氛围光晕，请先关闭 SDR 转 HDR"
                                    else -> {
                                        ambientMode = on
                                        PlayerPrefs.setAmbientMode(ctx, on)
                                        seekHint = if (on) "氛围光晕：已开启" else "氛围光晕：已关闭"
                                    }
                                }
                            },
                            onEnhance = { lvl ->
                                enhanceLevel = lvl
                                PlayerPrefs.setEnhanceLevel(ctx, lvl)
                                seekHint = when (lvl) {
                                    1 -> "AI 超分辨率：标准"
                                    2 -> "AI 超分辨率：强效"
                                    else -> "AI 超分辨率：已关闭"
                                }
                            },
                            onSdrToHdr = { on ->
                                if (isNativeHdr) {
                                    seekHint = "当前片源已是原生 ${tags.range}"
                                } else {
                                    sdrToHdr = on
                                    PlayerPrefs.setSdrToHdr(ctx, on)
                                    seekHint = if (on) "SDR 转 HDR：已开启" else "SDR 转 HDR：已关闭"
                                }
                            },
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
        // Tablet portrait-video split toggle chip (top-end when drawer is collapsed)
        if (metrics.isTablet && landscape && metrics.widthDp >= 840 && isPortraitMedia && detail != null && !padDrawerOpen && showChrome) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 14.dp, end = 16.dp)
                    .glassSurface(RoundedCornerShape(50), dark = true, shadowRadius = 8.dp, fallbackAlpha = 0.60f)
                    .clickable { padDrawerOpen = true }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text("展开选集", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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

/** spec `.tech`: one small centred line instead of a row of badges. */
@Composable
private fun TechLine(t: TechTags, modifier: Modifier = Modifier, enhanceLevel: Int = 0, sdrToHdr: Boolean = false) {
    val sdr = t.range == "SDR"
    // On SDR content the active GPU stages are shown in accent: "SDR→HDR" replaces "SDR", "AI超分" is appended.
    val hdrOn = sdrToHdr && sdr
    val parts = listOfNotNull(t.res, if (hdrOn) null else t.range, t.codec, t.audio, t.fps?.let { "$it fps" })
    val active = listOfNotNull(if (hdrOn) "SDR→HDR" else null, if (enhanceLevel > 0 && (t.range == null || sdr)) "AI超分" else null)
    if (parts.isEmpty() && active.isEmpty()) return
    val accent = IdjDarkColors.accent
    Text(androidx.compose.ui.text.buildAnnotatedString {
        parts.forEachIndexed { i, p -> if (i > 0) append(" · "); append(p) }
        active.forEach { a ->
            if (length > 0) append(" · ")
            pushStyle(androidx.compose.ui.text.SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)); append(a); pop()
        }
    }, modifier = modifier, color = Color.White.copy(0.55f), fontSize = 11.sp, fontWeight = FontWeight.Medium,
        letterSpacing = 0.3.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}

@Composable
private fun CircleBtn(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    // spec `.pbtn`: 40 dp dark glass. Drawn over the video (inside the root layer), so no backdrop sampling:
    // LIQUID = sheer fill + specular rim, FROSTED = denser frosted fill + hairline.
    app.iduanju.ui.components.GlassIconButton(icon, label, dark = true, fallbackAlpha = 0.42f, enabled = enabled, onClick = onClick)
}

@Composable
private fun LandChip(icon:ImageVector,title:String,value:String?,on:Boolean,enabled:Boolean,onClick:()->Unit){
    val pc=IdjDarkColors
    Row(Modifier.height(34.dp).graphicsLayer { alpha = if(enabled)1f else 0.45f }
        .then(if (on) Modifier.clip(CircleShape).background(pc.accentSoft).border(1.dp, pc.accentLine, CircleShape)
              else Modifier.glassSurface(CircleShape, dark = true, shadowRadius = 0.dp, fallbackAlpha = 0.46f))
        .pressClick(enabled=enabled,onClick=onClick).padding(horizontal=13.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
        Icon(icon,null,tint=if(on)pc.accent else Color.White,modifier=Modifier.size(17.dp))
        Text(title+(value?.let{" $it"}?:""),color=if(on)pc.accent else Color.White.copy(0.8f),fontSize=13.sp,maxLines=1,softWrap=false)
    }
}

/** spec `.pact`: 23 dp icon over an 11 sp caption that carries the current value. */
@Composable
private fun RowScope.ActionBtn(icon: ImageVector, title: String, value: String?, active: Boolean, accent: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val pc = IdjDarkColors
    val bg by androidx.compose.animation.animateColorAsState(if (active) Color.White.copy(0.15f) else Color.Transparent, DS.select(), label = "pact")
    Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(bg).pressClick(enabled = enabled, label = title, onClick = onClick)
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
    val chip: @Composable (String, Boolean, Boolean, () -> Unit) -> Unit = { t, on, sm, f ->
        Box(Modifier.height(if (sm) 28.dp else 32.dp).clip(RoundedCornerShape(50)).background(if (on) pc.thumb else pc.glass)
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
        LazyVerticalGrid(GridCells.Fixed(app.iduanju.ui.components.LocalLayoutMetrics.current.episodeCols), Modifier.heightIn(max = 300.dp).padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sl.size) { k ->
                val (i, ep) = sl[k]
                val on = i == epIndex
                Box(Modifier.height(44.dp).clip(RoundedCornerShape(12.dp)).background(if (on) pc.accentSoft else pc.glass)
                    .then(if (on) Modifier.border(1.dp, pc.accentLine, RoundedCornerShape(12.dp)) else Modifier)
                    .pressClick { onPick(i) }, contentAlignment = Alignment.Center) {
                    Text("${ep.ep}", color = if (on) pc.accent else Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    if (on) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(5.dp).clip(CircleShape).background(pc.accent))
                }
            }
        }
    }
}

/** spec `.ptrack`: 4 dp track, buffered white 30 %, 14 dp round knob; grows while dragging. Tap/drag to seek. */
@Composable
private fun ProgressBar(pos: Long, dur: Long, buffered: Long, onSeek: (Long) -> Unit) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val frac = drag ?: if (dur > 0) pos.toFloat() / dur else 0f
    val buf = if (dur > 0) buffered.toFloat() / dur else 0f
    val h by androidx.compose.animation.core.animateDpAsState(if (drag != null) 8.dp else 4.dp, DS.press(), label = "trk")
    val knob by androidx.compose.animation.core.animateDpAsState(if (drag != null) 20.dp else 14.dp, DS.press(), label = "knob")
    BoxWithConstraints(Modifier.fillMaxWidth().height(28.dp)
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

private const val ACTION_PIP_PLAY_PAUSE = "app.iduanju.action.PIP_PLAY_PAUSE"
private const val ACTION_PIP_NEXT = "app.iduanju.action.PIP_NEXT"

@Composable
private fun MoreSettingsPanel(
    fillMode: Boolean,
    ambientMode: Boolean,
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
    onAmbientMode: (Boolean) -> Unit,
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
            nativeHdrRange != null -> "黑边氛围光晕 (原生 $nativeHdrRange 已锁定关闭)"
            sdrToHdr -> "黑边氛围光晕 (SDR 转 HDR 开启时已锁定关闭)"
            else -> "黑边氛围光晕"
        },
        options = listOf("开启" to true, "关闭" to false),
        selected = if (hdrLocked) false else ambientMode,
        enabled = !hdrLocked,
        pc = pc,
        onSelect = onAmbientMode,
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
        options.forEach { (title, value) ->
            val on = value == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) pc.accentSoft else Color.White.copy(0.08f))
                    .border(
                        1.dp,
                        if (on) pc.accent.copy(0.55f) else Color.White.copy(0.10f),
                        RoundedCornerShape(10.dp),
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

private data class AmbientEdgeColors(
    val top: Color = Color.Black,
    val bottom: Color = Color.Black,
    val left: Color = Color.Black,
    val right: Color = Color.Black,
    val glowBitmap: androidx.compose.ui.graphics.ImageBitmap? = null,
)

private fun computeAmbientEdgeColors(bmp: android.graphics.Bitmap): AmbientEdgeColors {
    val w = bmp.width
    val h = bmp.height
    if (w <= 0 || h <= 0) return AmbientEdgeColors()
    val pixels = IntArray(w * h)
    bmp.getPixels(pixels, 0, w, 0, 0, w, h)

    fun rowMaxLuma(y: Int): Int {
        var maxL = 0
        val rowOff = y * w
        for (x in 0 until w) {
            val px = pixels[rowOff + x]
            val m = maxOf((px shr 16) and 0xFF, (px shr 8) and 0xFF, px and 0xFF)
            if (m > maxL) maxL = m
        }
        return maxL
    }

    fun colMaxLuma(x: Int): Int {
        var maxL = 0
        for (y in 0 until h) {
            val px = pixels[y * w + x]
            val m = maxOf((px shr 16) and 0xFF, (px shr 8) and 0xFF, px and 0xFF)
            if (m > maxL) maxL = m
        }
        return maxL
    }

    // Trim near-black rows/columns baked into re-encoded video frames
    var activeTop = 0
    while (activeTop < h / 3 && rowMaxLuma(activeTop) < 18) activeTop++
    var activeBottom = h
    while (activeBottom > h - h / 3 && rowMaxLuma(activeBottom - 1) < 18) activeBottom--
    var activeLeft = 0
    while (activeLeft < w / 3 && colMaxLuma(activeLeft) < 18) activeLeft++
    var activeRight = w
    while (activeRight > w - w / 3 && colMaxLuma(activeRight - 1) < 18) activeRight--

    if (activeBottom - activeTop < 4) {
        activeTop = 0
        activeBottom = h
    }
    if (activeRight - activeLeft < 4) {
        activeLeft = 0
        activeRight = w
    }
    val activeW = (activeRight - activeLeft).coerceAtLeast(1)
    val activeH = (activeBottom - activeTop).coerceAtLeast(1)
    val stripY = (activeH / 3).coerceAtLeast(2).coerceAtMost(activeH)
    val stripX = (activeW / 3).coerceAtLeast(2).coerceAtMost(activeW)

    fun sampleRawRgb(xStart: Int, xEnd: Int, yStart: Int, yEnd: Int): IntArray {
        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var weightSum = 0L
        var fallbackR = 0L
        var fallbackG = 0L
        var fallbackB = 0L
        var fallbackCount = 0
        for (y in yStart until yEnd) {
            val rowOffset = y * w
            for (x in xStart until xEnd) {
                val px = pixels[rowOffset + x]
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                fallbackR += r
                fallbackG += g
                fallbackB += b
                fallbackCount++
                val maxCh = maxOf(r, g, b)
                val minCh = minOf(r, g, b)
                if (maxCh < 14) continue
                val satWeight = (maxCh - minCh) * 2
                val wPx = (1 + maxCh + satWeight).toLong()
                rSum += r * wPx
                gSum += g * wPx
                bSum += b * wPx
                weightSum += wPx
            }
        }
        return if (weightSum > 0L) {
            intArrayOf((rSum / weightSum).toInt(), (gSum / weightSum).toInt(), (bSum / weightSum).toInt())
        } else if (fallbackCount > 0) {
            intArrayOf((fallbackR / fallbackCount).toInt(), (fallbackG / fallbackCount).toInt(), (fallbackB / fallbackCount).toInt())
        } else {
            intArrayOf(0, 0, 0)
        }
    }

    /**
     * Gentle vibrancy that preserves darkness: near-black stays black (a black suit must not glow), everything else
     * gets a mild brightness curve and saturation lift. Never lifts dark pixels to a fixed minimum brightness.
     */
    fun boostColorInt(r: Int, g: Int, b: Int): Int {
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(r, g, b, hsv)
        val s = hsv[1]
        val v = hsv[2]
        if (v < 0.06f) return 0xFF000000.toInt()
        if (s > 0.05f) hsv[1] = (s * 1.25f).coerceIn(0f, 0.88f)
        hsv[2] = (v * 1.15f).coerceIn(0f, 0.85f)
        return android.graphics.Color.HSVToColor(hsv)
    }

    // Edge colours come only from their own region: no blending with the whole-frame average, so a dark edge stays
    // dark even when the rest of the frame is bright.
    fun regionToAmbientColor(xStart: Int, xEnd: Int, yStart: Int, yEnd: Int): Color {
        val raw = sampleRawRgb(xStart, xEnd, yStart, yEnd)
        return Color(boostColorInt(raw[0].coerceIn(0, 255), raw[1].coerceIn(0, 255), raw[2].coerceIn(0, 255)))
    }

    // Build a 16x16 spatially accurate glow bitmap from the trimmed active frame for API 31+ RenderEffect blur
    val glowSize = 16
    val glowPixels = IntArray(glowSize * glowSize)
    for (gy in 0 until glowSize) {
        val sy = (activeTop + (gy * activeH) / glowSize).coerceIn(activeTop, activeBottom - 1)
        val rowOff = sy * w
        for (gx in 0 until glowSize) {
            val sx = (activeLeft + (gx * activeW) / glowSize).coerceIn(activeLeft, activeRight - 1)
            val px = pixels[rowOff + sx]
            val pr = (px shr 16) and 0xFF
            val pg = (px shr 8) and 0xFF
            val pb = px and 0xFF
            // spatially faithful: each glow cell is its own pixel, dark stays dark and bright glows locally
            glowPixels[gy * glowSize + gx] = boostColorInt(pr, pg, pb)
        }
    }
    val glowImg = runCatching {
        android.graphics.Bitmap.createBitmap(
            glowPixels,
            glowSize,
            glowSize,
            android.graphics.Bitmap.Config.ARGB_8888,
        ).asImageBitmap()
    }.getOrNull()

    return AmbientEdgeColors(
        top = regionToAmbientColor(activeLeft, activeRight, activeTop, activeTop + stripY),
        bottom = regionToAmbientColor(activeLeft, activeRight, activeBottom - stripY, activeBottom),
        left = regionToAmbientColor(activeLeft, activeLeft + stripX, activeTop, activeBottom),
        right = regionToAmbientColor(activeRight - stripX, activeRight, activeTop, activeBottom),
        glowBitmap = glowImg,
    )
}

@Composable
private fun AmbientGlowLayer(
    topColor: Color,
    bottomColor: Color,
    leftColor: Color,
    rightColor: Color,
    glowBitmap: androidx.compose.ui.graphics.ImageBitmap? = null,
    videoAspectRatio: Float = 0f,
    visible: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val layerAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(450),
        label = "ambientVisible",
    )
    if (layerAlpha <= 0.005f) return
    val animTop by androidx.compose.animation.animateColorAsState(topColor, androidx.compose.animation.core.tween(600), label = "ambTop")
    val animBottom by androidx.compose.animation.animateColorAsState(bottomColor, androidx.compose.animation.core.tween(600), label = "ambBottom")
    val animLeft by androidx.compose.animation.animateColorAsState(leftColor, androidx.compose.animation.core.tween(600), label = "ambLeft")
    val animRight by androidx.compose.animation.animateColorAsState(rightColor, androidx.compose.animation.core.tween(600), label = "ambRight")

    val useBitmapGlow = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && glowBitmap != null
    Box(modifier.fillMaxSize()) {
        if (useBitmapGlow) {
            // API 31+: the 16x16 frame map, scaled onto the actual video rect (+44 dp) and hardware-blurred. No uniform
            // full-width blooms, so dark regions stay dark and bright regions glow right next to their true colours.
            androidx.compose.animation.Crossfade(
                targetState = glowBitmap,
                animationSpec = androidx.compose.animation.core.tween(260),
                label = "ambientFrame",
            ) { bmp ->
                androidx.compose.foundation.Canvas(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = 0.75f * layerAlpha }
                        .blur(radius = 48.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded),
                ) {
                    val w = size.width
                    val h = size.height
                    if (w <= 0f || h <= 0f) return@Canvas
                    val expandPx = 44.dp.toPx()
                    val viewAspect = w / h
                    val ar = if (videoAspectRatio > 0.05f) videoAspectRatio else (16f / 9f)
                    val vidW: Float
                    val vidH: Float
                    if (ar > viewAspect) {
                        vidW = w
                        vidH = w / ar
                    } else {
                        vidH = h
                        vidW = h * ar
                    }
                    val dstW = vidW + expandPx * 2f
                    val dstH = vidH + expandPx * 2f
                    drawImage(
                        image = bmp,
                        dstOffset = androidx.compose.ui.unit.IntOffset(((w - dstW) * 0.5f).toInt(), ((h - dstH) * 0.5f).toInt()),
                        dstSize = androidx.compose.ui.unit.IntSize(dstW.toInt().coerceAtLeast(1), dstH.toInt().coerceAtLeast(1)),
                        filterQuality = androidx.compose.ui.graphics.FilterQuality.Low,
                    )
                }
            }
        } else {
            // API < 31 fallback: per-edge gradients, each tinted only by its own (darkness-preserving) edge colour.
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                if (w <= 0f || h <= 0f) return@Canvas
                val baseAlpha = 0.78f * layerAlpha
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to animTop.copy(alpha = baseAlpha * 0.52f),
                        0.34f to animTop.copy(alpha = baseAlpha),
                        0.55f to Color.Transparent,
                        startY = 0f,
                        endY = h,
                    ),
                )
                drawRect(
                    brush = Brush.verticalGradient(
                        0.45f to Color.Transparent,
                        0.66f to animBottom.copy(alpha = baseAlpha),
                        1f to animBottom.copy(alpha = baseAlpha * 0.52f),
                        startY = 0f,
                        endY = h,
                    ),
                )
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to animLeft.copy(alpha = baseAlpha * 0.45f),
                        0.34f to animLeft.copy(alpha = baseAlpha * 0.85f),
                        0.55f to Color.Transparent,
                        startX = 0f,
                        endX = w,
                    ),
                )
                drawRect(
                    brush = Brush.horizontalGradient(
                        0.45f to Color.Transparent,
                        0.66f to animRight.copy(alpha = baseAlpha * 0.85f),
                        1f to animRight.copy(alpha = baseAlpha * 0.45f),
                        startX = 0f,
                        endX = w,
                    ),
                )
            }
        }
    }
}
