package app.iduanju.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.IdjApp
import app.iduanju.core.hongguo.HGRankGenre
import app.iduanju.core.hongguo.HGRankSub
import app.iduanju.core.hongguo.HGSeries
import app.iduanju.core.hongguo.HGSeriesDetail
import app.iduanju.core.hongguo.SeasonParser
import app.iduanju.ui.components.DS
import app.iduanju.ui.components.glassSurface
import app.iduanju.ui.components.pressClick
import app.iduanju.ui.theme.IdjDarkColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** One 播完连刷推荐 candidate. [tier] 1 = next season, 2 = same category / tag match, 3 = hot feed. */
data class NextDramaPick(val series: HGSeries, val tier: Int) {
    val reason: String get() = when (tier) { 1 -> "下一季"; 2 -> "同类推荐"; else -> "热播推荐" }
    /** "共 80 集 · 1.2 亿热度" */
    val meta: String get() = listOfNotNull(
        series.episodeCount.takeIf { it > 0 }?.let { "共 $it 集" },
        series.playCount.takeIf { it > 0 }?.let { DS.count(it) + "热度" },
    ).joinToString(" · ")
}

/**
 * 播完连刷推荐: picks the drama to continue with once the current one's final episode ends.
 *
 * 3-tier cascade: (1) the next season in `relatedSeries`, (2) the same category's hot / recommended boards ranked by
 * shared tags and popularity, (3) the general hot / recommended feed. Watched (library history), the current drama
 * and anything skipped with 换一部 in this app session are never offered. Boards are cached for a few minutes so
 * 换一部 re-picks instantly, and the picked drama's detail + first stream can be prepared ahead so switching is
 * immediate.
 */
object PlayerNextDrama {
    private const val POOL_TTL_MS = 10 * 60_000L
    private const val DETAIL_TTL_MS = 10 * 60_000L
    /** Resolved stream URLs are signed and short-lived; only hand over a recent one. */
    private const val STREAM_TTL_MS = 3 * 60_000L
    private const val TOP_CANDIDATES = 8

    private val skipped: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private class Pool(val items: List<HGSeries>, val at: Long)
    private val pools = ConcurrentHashMap<HGRankGenre, Pool>()
    private val details = ConcurrentHashMap<String, Pair<HGSeriesDetail, Long>>()
    private val streams = ConcurrentHashMap<String, Pair<Deferred<Playable>, Long>>()

    /** 换一部: never offer [seriesId] again in this session. */
    fun skip(seriesId: String) { skipped.add(seriesId) }

    suspend fun pickNextDrama(cur: HGSeriesDetail, watched: Set<String>): NextDramaPick? = withContext(Dispatchers.IO) {
        fun allowed(s: HGSeries) = s.seriesId.isNotEmpty() && s.seriesId != cur.seriesId && s.seriesId !in watched && s.seriesId !in skipped

        // Tier 1 — same series, next season.
        val selfEntry = cur.relatedSeries.firstOrNull { it.seriesId == cur.seriesId }
        val curSeason = SeasonParser.seasonNo(selfEntry?.title ?: cur.title)
        cur.relatedSeries.asSequence().filter(::allowed)
            .map { it to SeasonParser.seasonNo(it.title) }
            .filter { it.second > curSeason }
            .minByOrNull { it.second }
            ?.let { return@withContext NextDramaPick(it.first, 1) }

        // Tier 2 — same category, tag-matched popular pool.
        val genre = genreOf(cur.tags.ifEmpty { selfEntry?.tags.orEmpty() }, cur.title)
        val categoryPool = if (genre != HGRankGenre.all) pool(genre) else emptyList()
        val curTags = cur.tags.ifEmpty { selfEntry?.tags.orEmpty() }
            .ifEmpty { categoryPool.firstOrNull { it.seriesId == cur.seriesId }?.tags.orEmpty() }
            .toSet()
        val tier2 = categoryPool.filter(::allowed)
        if (tier2.isNotEmpty()) {
            val tagged = if (curTags.isEmpty()) emptyList() else tier2.filter { s -> s.tags.any { it in curTags } }
                .sortedByDescending { s -> s.tags.count { it in curTags } }
            val base = tagged.ifEmpty { tier2 }
            return@withContext NextDramaPick(randomPopular(base), 2)
        }

        // Tier 3 — general hot / recommended feed.
        val tier3 = pool(HGRankGenre.all).filter(::allowed)
        if (tier3.isNotEmpty()) NextDramaPick(randomPopular(tier3), 3) else null
    }

    /** 漫剧 / AI剧 / 真人剧 from the drama's own tags; [HGRankGenre.all] when nothing is known. */
    fun genreOf(tags: List<String>, title: String): HGRankGenre {
        val all = tags + title
        return when {
            all.any { it.contains("漫剧") || it.contains("动漫") || it.contains("漫画") || it.contains("国漫") } -> HGRankGenre.comic
            tags.any { it.equals("AI", true) || it.startsWith("AI") } -> HGRankGenre.ai
            tags.isNotEmpty() -> HGRankGenre.human
            else -> HGRankGenre.all
        }
    }

    /** Top of the list (already ordered by match strength) re-ranked by popularity, then one random pick. */
    private fun randomPopular(items: List<HGSeries>): HGSeries {
        val top = items.take(TOP_CANDIDATES * 3)
            .sortedWith(compareByDescending<HGSeries> { it.playCount }.thenBy { if (it.rank > 0) it.rank else Int.MAX_VALUE })
            .take(TOP_CANDIDATES)
        return top.random()
    }

    /** 热播 + 推荐 boards of [genre], merged in board order, de-duplicated; failures just shrink the pool. */
    private suspend fun pool(genre: HGRankGenre): List<HGSeries> {
        pools[genre]?.takeIf { System.currentTimeMillis() - it.at < POOL_TTL_MS }?.let { return it.items }
        val hg = IdjApp.instance.hongguo
        val boards = coroutineScope {
            listOf(HGRankSub.hot, HGRankSub.recommend).filter { it in genre.subs }.map { sub ->
                async(Dispatchers.IO) {
                    try { hg.rank(sub, genre).items } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
                }
            }.map { it.await() }
        }
        val merged = boards.flatten().distinctBy { it.seriesId }.filter { it.seriesId.isNotEmpty() }
        if (merged.isNotEmpty()) pools[genre] = Pool(merged, System.currentTimeMillis())
        return merged
    }

    /** Warm the picked drama's detail so the next PlayerScreen opens without a detail round-trip. */
    suspend fun prefetchDetail(seriesId: String): HGSeriesDetail? {
        details[seriesId]?.takeIf { System.currentTimeMillis() - it.second < DETAIL_TTL_MS }?.let { return it.first }
        return withContext(Dispatchers.IO) {
            try { app.iduanju.data.Catalog.detail(seriesId, web = false) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        }?.also { details[seriesId] = it to System.currentTimeMillis() }
    }

    /** Consumed once by PlayerScreen when it opens [seriesId]. */
    fun takeDetail(seriesId: String): HGSeriesDetail? =
        details.remove(seriesId)?.takeIf { System.currentTimeMillis() - it.second < DETAIL_TTL_MS }?.first

    /** Resolve (and for CENC download + decrypt) episode 1 ahead of the switch. */
    fun prepareFirstEpisode(detail: HGSeriesDetail) {
        val ep = detail.episodes.firstOrNull() ?: return
        streams.entries.removeAll { System.currentTimeMillis() - it.value.second > STREAM_TTL_MS }
        if (streams[ep.vid]?.first?.isCancelled == false) return
        streams[ep.vid] = EpisodeCache.get(ep) to System.currentTimeMillis()
    }

    /** A recent prepared stream for [vid], consumed once. */
    fun takePrepared(vid: String): Deferred<Playable>? =
        streams.remove(vid)?.takeIf { System.currentTimeMillis() - it.second < STREAM_TTL_MS }?.first
}

/** 播完连刷推荐 countdown card: poster, title, meta, tags; 立即播放 fills up as the countdown runs. */
@Composable
internal fun NextDramaCountdownCard(
    pick: NextDramaPick?,
    loading: Boolean,
    progressProvider: () -> Float,
    secondsLeftProvider: () -> Int,
    paused: Boolean,
    onPlay: () -> Unit,
    onSwap: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pc = IdjDarkColors
    // Only the caption follows the per-frame countdown, and only when the whole second changes.
    val secondsLeft by remember(secondsLeftProvider) { derivedStateOf(secondsLeftProvider) }
    Column(
        modifier.widthIn(max = 380.dp).fillMaxWidth()
            .glassSurface(RoundedCornerShape(24.dp), dark = true, base = Color(0xFF15171C), shadowRadius = 22.dp, fallbackAlpha = 0.62f)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(74.dp).aspectRatio(0.72f).shadow(10.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(0.08f))) {
                pick?.series?.cover?.takeIf { it.isNotEmpty() }?.let {
                    coil.compose.AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        pick == null -> "正在挑选下一部…"
                        paused -> "推荐新剧 · ${pick.reason} · 已暂停"
                        else -> "推荐新剧 · ${pick.reason} · ${secondsLeft} 秒后播放"
                    },
                    color = pc.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(pick?.series?.title?.let { "《$it》" } ?: "　", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    lineHeight = 21.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                pick?.meta?.takeIf { it.isNotEmpty() }?.let {
                    Spacer(Modifier.height(3.dp))
                    Text(it, color = Color.White.copy(0.62f), fontSize = 12.sp, maxLines = 1)
                }
                val tags = pick?.series?.tags.orEmpty().take(3)
                if (tags.isNotEmpty()) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    tags.forEach { t ->
                        Text(t, color = Color.White.copy(0.82f), fontSize = 10.sp, maxLines = 1,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.White.copy(0.12f))
                                .padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // 立即播放: accent capsule whose fill sweeps left → right with the countdown (draw-phase read only).
            Row(Modifier.weight(1f).height(38.dp).clip(CircleShape).background(pc.accent.copy(0.28f))
                .drawBehind {
                    val f = progressProvider().coerceIn(0f, 1f)
                    if (f > 0f) drawRect(pc.accent, size = androidx.compose.ui.geometry.Size(size.width * f, size.height))
                }
                .border(1.dp, pc.accent.copy(0.7f), CircleShape)
                .pressClick(0.96f, enabled = pick != null, label = "立即播放", onClick = onPlay),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("立即播放", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Row(Modifier.height(38.dp).glassSurface(CircleShape, dark = true, shadowRadius = 0.dp, fallbackAlpha = 0.5f)
                .pressClick(0.94f, enabled = !loading, label = "换一部", onClick = onSwap).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Refresh, null, tint = Color.White.copy(if (loading) 0.4f else 1f), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("换一部", color = Color.White.copy(if (loading) 0.4f else 0.92f), fontSize = 13.sp)
            }
            Box(Modifier.height(38.dp).clip(CircleShape).pressClick(0.94f, label = "取消", onClick = onCancel).padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center) {
                Text("取消", color = Color.White.copy(0.7f), fontSize = 13.sp)
            }
        }
    }
}

/** Final-episode centre swipe-up: the recommended drama slides in below the video, like the adjacent-episode card. */
@Composable
internal fun NextDramaSwitchPreview(pick: NextDramaPick, armed: Boolean, modifier: Modifier) {
    val cover = pick.series.cover
    Box(modifier.background(Color.Black)) {
        if (cover.isNotEmpty()) coil.compose.AsyncImage(cover, null, Modifier.fillMaxSize().blur(36.dp),
            contentScale = ContentScale.Crop, alpha = 0.55f)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(0.35f), Color.Black.copy(0.74f)))))
        Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (cover.isNotEmpty()) coil.compose.AsyncImage(cover, null,
                Modifier.width(132.dp).aspectRatio(0.72f).shadow(18.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)),
                contentScale = ContentScale.Crop)
            Spacer(Modifier.height(18.dp))
            Text("推荐新剧 · 《${pick.series.title}》", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            val sub = listOf(pick.reason, pick.meta).filter { it.isNotEmpty() }.joinToString(" · ")
            Text(sub, color = Color.White.copy(0.7f), fontSize = 13.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(16.dp))
            Row(Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(if (armed) 0.24f else 0.12f)).padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.KeyboardArrowUp, null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (armed) "松手立即播放" else "上滑连刷下一部",
                    color = Color.White, fontSize = 13.sp, fontWeight = if (armed) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}
