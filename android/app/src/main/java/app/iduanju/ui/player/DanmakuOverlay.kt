package app.iduanju.ui.player

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import app.iduanju.IdjApp
import app.iduanju.core.hongguo.*
import app.iduanju.ui.components.ExpressivePalette
import app.iduanju.ui.components.ExpressiveSheetHeader
import app.iduanju.ui.components.ExpressiveSlider
import app.iduanju.ui.components.ExpressiveStepSlider
import app.iduanju.ui.components.GlassStyle
import app.iduanju.ui.components.GlassStylePref
import app.iduanju.ui.components.expressivePress
import app.iduanju.ui.components.glassPill
import app.iduanju.ui.theme.IdjColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Persisted reactive danmaku settings (stored in SharedPreferences("idj") under `stv.danmaku.*`). */
object DanmakuPrefs {
    private const val PREF_NAME = "idj"
    private const val KEY_AREA_LEVEL = "stv.danmaku.areaLevel"
    private const val KEY_OPACITY = "stv.danmaku.opacity"
    private const val KEY_FONT_SCALE = "stv.danmaku.fontScale"
    private const val KEY_SPEED_LEVEL = "stv.danmaku.speedLevel"
    private const val KEY_AVOID_SUBTITLE = "stv.danmaku.avoidSubtitle"
    private const val KEY_BLOCK_COLOR = "stv.danmaku.blockColor"
    private const val KEY_MERGE_DUPLICATES = "stv.danmaku.mergeDuplicates"
    private const val KEY_BLOCK_WORDS = "stv.danmaku.blockWords"

    const val DEFAULT_AREA_LEVEL = 1
    const val DEFAULT_OPACITY = 0.85f
    const val DEFAULT_FONT_SCALE = 1.0f
    const val DEFAULT_SPEED_LEVEL = 2
    const val DEFAULT_AVOID_SUBTITLE = true
    const val DEFAULT_BLOCK_COLOR = false
    const val DEFAULT_MERGE_DUPLICATES = true
    const val BASE_FONT_SP = 16f

    val AREA_STEPS = listOf("1/4", "1/3", "半屏", "3/4", "全屏")
    val AREA_LABELS = listOf("1/4屏", "1/3屏", "半屏", "3/4屏", "全屏")
    val AREA_FRACTIONS = listOf(0.25f, 0.34f, 0.50f, 0.75f, 1.0f)

    val SPEED_LABELS = listOf("极慢", "较慢", "适中", "较快", "极快")
    val SPEED_DURATIONS = listOf(12.5, 10.5, 8.5, 6.5, 5.0)

    private val sp by lazy { IdjApp.instance.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE) }

    private fun parseBlockWords(raw: String?): List<String> =
        raw.orEmpty().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()

    private val areaLevelState by lazy {
        mutableIntStateOf(sp.getInt(KEY_AREA_LEVEL, DEFAULT_AREA_LEVEL).coerceIn(0, 4))
    }
    private val opacityState by lazy {
        mutableFloatStateOf(sp.getFloat(KEY_OPACITY, DEFAULT_OPACITY).coerceIn(0.20f, 1.0f))
    }
    private val fontScaleState by lazy {
        mutableFloatStateOf(sp.getFloat(KEY_FONT_SCALE, DEFAULT_FONT_SCALE).coerceIn(0.65f, 1.40f))
    }
    private val speedLevelState by lazy {
        mutableIntStateOf(sp.getInt(KEY_SPEED_LEVEL, DEFAULT_SPEED_LEVEL).coerceIn(0, 4))
    }
    private val avoidSubtitleState by lazy {
        mutableStateOf(sp.getBoolean(KEY_AVOID_SUBTITLE, DEFAULT_AVOID_SUBTITLE))
    }
    private val blockColorState by lazy {
        mutableStateOf(sp.getBoolean(KEY_BLOCK_COLOR, DEFAULT_BLOCK_COLOR))
    }
    private val mergeDuplicatesState by lazy {
        mutableStateOf(sp.getBoolean(KEY_MERGE_DUPLICATES, DEFAULT_MERGE_DUPLICATES))
    }
    private val blockWordsState by lazy {
        mutableStateOf(parseBlockWords(sp.getString(KEY_BLOCK_WORDS, null)))
    }

    var areaLevel: Int
        get() = areaLevelState.intValue
        set(value) {
            val v = value.coerceIn(0, 4)
            if (areaLevelState.intValue != v) {
                areaLevelState.intValue = v
                sp.edit().putInt(KEY_AREA_LEVEL, v).apply()
            }
        }

    var opacity: Float
        get() = opacityState.floatValue
        set(value) {
            val v = value.coerceIn(0.20f, 1.0f)
            if (opacityState.floatValue != v) {
                opacityState.floatValue = v
                sp.edit().putFloat(KEY_OPACITY, v).apply()
            }
        }

    var fontScale: Float
        get() = fontScaleState.floatValue
        set(value) {
            val v = value.coerceIn(0.65f, 1.40f)
            if (fontScaleState.floatValue != v) {
                fontScaleState.floatValue = v
                sp.edit().putFloat(KEY_FONT_SCALE, v).apply()
            }
        }

    var speedLevel: Int
        get() = speedLevelState.intValue
        set(value) {
            val v = value.coerceIn(0, 4)
            if (speedLevelState.intValue != v) {
                speedLevelState.intValue = v
                sp.edit().putInt(KEY_SPEED_LEVEL, v).apply()
            }
        }

    var avoidSubtitle: Boolean
        get() = avoidSubtitleState.value
        set(value) {
            if (avoidSubtitleState.value != value) {
                avoidSubtitleState.value = value
                sp.edit().putBoolean(KEY_AVOID_SUBTITLE, value).apply()
            }
        }

    var blockColor: Boolean
        get() = blockColorState.value
        set(value) {
            if (blockColorState.value != value) {
                blockColorState.value = value
                sp.edit().putBoolean(KEY_BLOCK_COLOR, value).apply()
            }
        }

    var mergeDuplicates: Boolean
        get() = mergeDuplicatesState.value
        set(value) {
            if (mergeDuplicatesState.value != value) {
                mergeDuplicatesState.value = value
                sp.edit().putBoolean(KEY_MERGE_DUPLICATES, value).apply()
            }
        }

    var blockWords: List<String>
        get() = blockWordsState.value
        set(value) {
            val normalized = value.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (blockWordsState.value != normalized) {
                blockWordsState.value = normalized
                sp.edit().putString(KEY_BLOCK_WORDS, normalized.joinToString("\n")).apply()
            }
        }

    val areaFraction: Float get() = AREA_FRACTIONS[areaLevel.coerceIn(0, 4)]
    val areaLabel: String get() = AREA_LABELS[areaLevel.coerceIn(0, 4)]
    val durationSec: Double get() = SPEED_DURATIONS[speedLevel.coerceIn(0, 4)]
    val speedLabel: String get() = SPEED_LABELS[speedLevel.coerceIn(0, 4)]
    val fontSp: Float get() = BASE_FONT_SP * fontScale.coerceIn(0.65f, 1.40f)

    fun summary(): String = "$areaLabel · $speedLabel"

    fun addBlockWord(word: String): Boolean {
        val trimmed = word.trim()
        if (trimmed.isEmpty()) return false
        if (blockWords.any { it.equals(trimmed, ignoreCase = true) }) return false
        blockWords = blockWords + trimmed
        return true
    }

    fun removeBlockWord(word: String) {
        blockWords = blockWords.filterNot { it == word }
    }

    fun resetDefaults() {
        areaLevelState.intValue = DEFAULT_AREA_LEVEL
        opacityState.floatValue = DEFAULT_OPACITY
        fontScaleState.floatValue = DEFAULT_FONT_SCALE
        speedLevelState.intValue = DEFAULT_SPEED_LEVEL
        avoidSubtitleState.value = DEFAULT_AVOID_SUBTITLE
        blockColorState.value = DEFAULT_BLOCK_COLOR
        mergeDuplicatesState.value = DEFAULT_MERGE_DUPLICATES
        blockWordsState.value = emptyList()
        sp.edit()
            .putInt(KEY_AREA_LEVEL, DEFAULT_AREA_LEVEL)
            .putFloat(KEY_OPACITY, DEFAULT_OPACITY)
            .putFloat(KEY_FONT_SCALE, DEFAULT_FONT_SCALE)
            .putInt(KEY_SPEED_LEVEL, DEFAULT_SPEED_LEVEL)
            .putBoolean(KEY_AVOID_SUBTITLE, DEFAULT_AVOID_SUBTITLE)
            .putBoolean(KEY_BLOCK_COLOR, DEFAULT_BLOCK_COLOR)
            .putBoolean(KEY_MERGE_DUPLICATES, DEFAULT_MERGE_DUPLICATES)
            .remove(KEY_BLOCK_WORDS)
            .apply()
    }

    val client by lazy {
        HGDanmakuClient {
            val a = sp.getString("stv.danmaku.did", null); val b = sp.getString("stv.danmaku.iid", null)
            if (a?.length == 19 && b?.length == 19) a to b
            else (HGCommentSign.newDeviceId() to HGCommentSign.newDeviceId()).also {
                sp.edit().putString("stv.danmaku.did", it.first).putString("stv.danmaku.iid", it.second).apply()
            }
        }
    }
}

private data class DanmakuPreviewSample(
    val text: String,
    val color: Color,
    val phaseOffset: Double,
)

private val PREVIEW_SAMPLES = listOf(
    DanmakuPreviewSample("前方高能！名场面来了", Color.White, 0.14),
    DanmakuPreviewSample("这段演技太绝了", Color.White, 0.34),
    DanmakuPreviewSample("彩色弹幕效果预览", Color(0xFFFFC857), 0.52),
    DanmakuPreviewSample("节奏好紧凑根本停不下来", Color.White, 0.70),
    DanmakuPreviewSample("字号与透明度实时预览", Color.White, 0.86),
)

/** Scrolling bullet-comment layer synced to the player clock (iOS DanmakuOverlay). Never intercepts touches. */
@Composable
fun DanmakuOverlay(
    player: Player,
    seriesId: String,
    vid: String,
    laneTop: Dp,
    bottomLimit: Dp,
    previewActive: Boolean = false,
    onStatus: (String) -> Unit,
) {
    var raw by remember(vid) { mutableStateOf<List<HGDanmaku>>(emptyList()) }
    LaunchedEffect(seriesId, vid) {
        if (vid.isEmpty() || !seriesId.all { it.isDigit() }) {
            raw = emptyList()
            return@LaunchedEffect
        }
        onStatus("弹幕加载中…")
        val r = withContext(Dispatchers.IO) { DanmakuPrefs.client.fetch(seriesId, vid) }
        raw = r.items
        onStatus(if (r.items.isEmpty()) r.notice else r.notice.ifEmpty { "本集 ${r.items.size} 条" })
    }

    val areaFraction = DanmakuPrefs.areaFraction
    val opacity = DanmakuPrefs.opacity
    val fontSp = DanmakuPrefs.fontSp
    val duration = DanmakuPrefs.durationSec
    val avoidSubtitle = DanmakuPrefs.avoidSubtitle
    val blockColor = DanmakuPrefs.blockColor
    val mergeDuplicates = DanmakuPrefs.mergeDuplicates
    val blockWords = DanmakuPrefs.blockWords

    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val filtered = remember(raw, blockWords) {
        if (blockWords.isEmpty()) raw
        else raw.filter { item ->
            val display = DanmakuText.display(item.text)
            blockWords.none { kw ->
                item.text.contains(kw, ignoreCase = true) || display.contains(kw, ignoreCase = true)
            }
        }
    }
    val prepared = remember(filtered, mergeDuplicates) {
        if (mergeDuplicates) DanmakuMerger.merge(filtered) else filtered
    }

    var t by remember(player) { mutableDoubleStateOf(player.currentPosition / 1000.0) }
    LaunchedEffect(player) {
        while (isActive) {
            withFrameNanos { }
            t = player.currentPosition / 1000.0
        }
    }

    val currentDuration by rememberUpdatedState(duration)
    var previewPhase by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(previewActive) {
        if (!previewActive) return@LaunchedEffect
        var lastNs = System.nanoTime()
        while (isActive) {
            withFrameNanos {
                val nowNs = System.nanoTime()
                val dtSec = (nowNs - lastNs).coerceIn(0L, 100_000_000L) / 1_000_000_000.0
                lastNs = nowNs
                previewPhase = (previewPhase + dtSec / currentDuration) % 1.0
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wPx = with(density) { maxWidth.toPx() }
        val laneH = fontSp * 1.55f
        val availableH = (maxHeight - laneTop).value.coerceAtLeast(0f)
        val subtitleGuard = if (avoidSubtitle) maxOf(maxHeight.value * 0.18f, 72f) else 0f
        val baseBottom = minOf(bottomLimit.value, maxHeight.value * 0.08f)
        val bottomReserve = if (avoidSubtitle) maxOf(subtitleGuard, baseBottom) else baseBottom
        val usableH = (availableH - bottomReserve).coerceAtLeast(laneH)
        val maxRows = (usableH / laneH).toInt().coerceIn(1, 40)
        val targetH = (availableH * areaFraction).coerceAtMost(usableH).coerceAtLeast(laneH)
        val rows = (targetH / laneH).toInt().coerceIn(1, maxRows)

        // Layout in dp units; re-runs instantly when filtered items, area, font size, or speed change.
        val placed = remember(prepared, maxWidth, rows, fontSp, duration) {
            DanmakuLayout.layout(prepared, rows, maxWidth.value, fontSp, duration, true)
        }
        if (placed.isEmpty() && !previewActive) return@BoxWithConstraints

        val parsedColors = remember(placed) {
            placed.map { runCatching { Color(android.graphics.Color.parseColor(it.item.color)) }.getOrDefault(Color.White) }
        }
        val textLayouts = remember(placed, fontSp) { arrayOfNulls<TextLayoutResult>(placed.size) }
        val badgeLayouts = remember(placed, fontSp) { arrayOfNulls<TextLayoutResult>(placed.size) }
        val previewLayouts = remember(fontSp, previewActive) { arrayOfNulls<TextLayoutResult>(PREVIEW_SAMPLES.size) }

        val normalStyle = remember(fontSp) { TextStyle(fontSize = fontSp.sp, fontWeight = FontWeight.SemiBold) }
        val boldStyle = remember(fontSp) { TextStyle(fontSize = fontSp.sp, fontWeight = FontWeight.Bold) }
        val badgeStyle = remember(fontSp) { TextStyle(fontSize = (fontSp * 0.8f).sp, fontWeight = FontWeight.Black) }
        val textShadow = remember(opacity) { Shadow(Color.Black.copy(alpha = 0.85f * opacity), Offset(0f, 1f), 2.4f) }
        val badgeColor = remember(opacity) { Color(0xFFFFB347).copy(alpha = opacity) }

        val areaLabel = DanmakuPrefs.AREA_LABELS[DanmakuPrefs.areaLevel.coerceIn(0, 4)]
        val guideText = "弹幕显示区域 · $areaLabel"
        val guideStyle = remember { TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium) }
        val guideLayout = remember(guideText, previewActive) {
            if (previewActive) measurer.measure(guideText, guideStyle) else null
        }
        val dashEffect = remember(density) {
            with(density) { PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()), 0f) }
        }

        Canvas(Modifier.fillMaxSize()) {
            val laneTopPx = laneTop.toPx()
            val laneHPx = laneH.dp.toPx()
            val badgeGapPx = 4.dp.toPx()

            if (previewActive) {
                val areaHeightPx = rows * laneHPx
                drawRect(
                    color = Color.White.copy(alpha = 0.06f),
                    topLeft = Offset(0f, laneTopPx),
                    size = Size(size.width, areaHeightPx),
                )
            }

            var lo = 0
            var hi = placed.size
            while (lo < hi) {
                val m = (lo + hi) / 2
                if (placed[m].item.time < t - duration) lo = m + 1 else hi = m
            }
            var i = lo
            while (i < placed.size) {
                val idx = i
                val p = placed[idx]
                if (p.item.time > t) break
                i++
                val elapsed = t - p.item.time
                if (elapsed < 0 || elapsed >= duration || p.lane >= rows) continue
                val wDpPx = p.width.dp.toPx()
                val x = wPx - (elapsed / duration).toFloat() * (wPx + wDpPx)
                val y = laneTopPx + laneHPx / 2f + p.lane * laneHPx
                val merged = p.item.count > 1
                val layout = textLayouts[idx] ?: measurer.measure(
                    p.text,
                    if (merged || p.item.likes >= 50) boldStyle else normalStyle,
                ).also { textLayouts[idx] = it }
                val baseColor = if (blockColor) Color.White else parsedColors[idx]
                drawText(
                    layout,
                    color = baseColor.copy(alpha = opacity),
                    topLeft = Offset(x, y - layout.size.height / 2f),
                    shadow = textShadow,
                )
                if (merged) {
                    val b = badgeLayouts[idx] ?: measurer.measure(p.badge, badgeStyle).also { badgeLayouts[idx] = it }
                    drawText(
                        b,
                        color = badgeColor,
                        topLeft = Offset(x + layout.size.width + badgeGapPx, y - b.size.height / 2f),
                        shadow = textShadow,
                    )
                }
            }

            if (previewActive) {
                val previewLanes = intArrayOf(
                    0,
                    (rows / 4).coerceIn(0, rows - 1),
                    (rows / 2).coerceIn(0, rows - 1),
                    ((3 * rows) / 4).coerceIn(0, rows - 1),
                    (rows - 1).coerceAtLeast(0),
                )
                PREVIEW_SAMPLES.forEachIndexed { idx, sample ->
                    val layout = previewLayouts[idx] ?: measurer.measure(sample.text, normalStyle).also {
                        previewLayouts[idx] = it
                    }
                    val progress = ((previewPhase + sample.phaseOffset) % 1.0).toFloat()
                    val x = wPx - progress * (wPx + layout.size.width)
                    val y = laneTopPx + laneHPx / 2f + previewLanes[idx] * laneHPx
                    val baseColor = if (blockColor) Color.White else sample.color
                    drawText(
                        layout,
                        color = baseColor.copy(alpha = opacity),
                        topLeft = Offset(x, y - layout.size.height / 2f),
                        shadow = textShadow,
                    )
                }

                val areaBottomPx = laneTopPx + rows * laneHPx
                drawLine(
                    color = Color.White.copy(alpha = 0.45f),
                    start = Offset(0f, areaBottomPx),
                    end = Offset(size.width, areaBottomPx),
                    strokeWidth = 1.2.dp.toPx(),
                    pathEffect = dashEffect,
                )
                guideLayout?.let { label ->
                    val padH = 10.dp.toPx()
                    val padV = 4.dp.toPx()
                    val pillW = label.size.width + padH * 2f
                    val pillH = label.size.height + padV * 2f
                    val pillX = (size.width - pillW) / 2f
                    val maxPillY = (size.height - pillH - 4.dp.toPx()).coerceAtLeast(laneTopPx + 2.dp.toPx())
                    val pillY = (areaBottomPx - pillH / 2f).coerceIn(laneTopPx + 2.dp.toPx(), maxPillY)
                    val radius = CornerRadius(pillH / 2f, pillH / 2f)
                    drawRoundRect(
                        color = Color(0xFF14161B).copy(alpha = 0.78f),
                        topLeft = Offset(pillX, pillY),
                        size = Size(pillW, pillH),
                        cornerRadius = radius,
                    )
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.35f),
                        topLeft = Offset(pillX, pillY),
                        size = Size(pillW, pillH),
                        cornerRadius = radius,
                        style = Stroke(width = 0.8.dp.toPx()),
                    )
                    drawText(
                        label,
                        color = Color.White.copy(alpha = 0.92f),
                        topLeft = Offset(pillX + padH, pillY + padV),
                    )
                }
            }
        }
    }
}

/** Interactive danmaku settings sheet rendered inside the player's 28 dp dark glass/expressive sheet. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DanmakuSettingsSheet(
    pc: IdjColors,
    onDismiss: () -> Unit,
    onReset: (() -> Unit)? = null,
) {
    val haptic = LocalHapticFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current
    val liquid = GlassStylePref.style == GlassStyle.LIQUID

    val areaLevel = DanmakuPrefs.areaLevel
    val opacity = DanmakuPrefs.opacity
    val fontScale = DanmakuPrefs.fontScale
    val speedLevel = DanmakuPrefs.speedLevel
    val avoidSubtitle = DanmakuPrefs.avoidSubtitle
    val blockColor = DanmakuPrefs.blockColor
    val mergeDuplicates = DanmakuPrefs.mergeDuplicates
    val blockWords = DanmakuPrefs.blockWords

    var wordDraft by remember { mutableStateOf("") }

    ExpressiveSheetHeader(
        title = "弹幕设置",
        onClose = onDismiss,
        dark = true,
        trailing = {
            Box(
                Modifier
                    .expressivePress(0.92f)
                    .then(
                        if (liquid) Modifier.glassPill(RoundedCornerShape(50), selected = false, dark = true, accent = pc.accent)
                        else Modifier
                            .clip(RoundedCornerShape(50))
                            .background(ExpressivePalette.softButton(true))
                            .border(0.8.dp, ExpressivePalette.hairline(true), RoundedCornerShape(50)),
                    )
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        DanmakuPrefs.resetDefaults()
                        wordDraft = ""
                        onReset?.invoke()
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("重置", color = Color.White.copy(alpha = 0.88f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        },
    )

    Spacer(Modifier.height(14.dp))

    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DanmakuFilterChip("防挡字幕", avoidSubtitle, pc) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            DanmakuPrefs.avoidSubtitle = !avoidSubtitle
        }
        DanmakuFilterChip("屏蔽彩色", blockColor, pc) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            DanmakuPrefs.blockColor = !blockColor
        }
        DanmakuFilterChip("合并重复", mergeDuplicates, pc) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            DanmakuPrefs.mergeDuplicates = !mergeDuplicates
        }
    }

    Spacer(Modifier.height(16.dp))

    DanmakuSliderHeader("显示区域", DanmakuPrefs.areaLabel, pc.accent)
    Spacer(Modifier.height(8.dp))
    ExpressiveStepSlider(
        steps = DanmakuPrefs.AREA_STEPS.size,
        selected = areaLevel,
        labels = DanmakuPrefs.AREA_STEPS,
        dark = true,
        onSelect = { DanmakuPrefs.areaLevel = it },
    )

    Spacer(Modifier.height(14.dp))

    DanmakuSliderHeader("不透明度", "${(opacity * 100f).roundToInt()}%", pc.accent)
    Spacer(Modifier.height(8.dp))
    ExpressiveSlider(
        value = opacity,
        onValueChange = { DanmakuPrefs.opacity = it },
        valueRange = 0.20f..1.0f,
        dark = true,
    )

    Spacer(Modifier.height(14.dp))

    DanmakuSliderHeader("弹幕字号", "${(fontScale * 100f).roundToInt()}%", pc.accent)
    Spacer(Modifier.height(8.dp))
    ExpressiveSlider(
        value = fontScale,
        onValueChange = { DanmakuPrefs.fontScale = it },
        valueRange = 0.65f..1.40f,
        dark = true,
    )

    Spacer(Modifier.height(14.dp))

    DanmakuSliderHeader("弹幕速度", DanmakuPrefs.speedLabel, pc.accent)
    Spacer(Modifier.height(8.dp))
    ExpressiveStepSlider(
        steps = DanmakuPrefs.SPEED_LABELS.size,
        selected = speedLevel,
        labels = DanmakuPrefs.SPEED_LABELS,
        dark = true,
        onSelect = { DanmakuPrefs.speedLevel = it },
    )

    Spacer(Modifier.height(16.dp))

    DanmakuSliderHeader(
        "弹幕屏蔽词",
        if (blockWords.isEmpty()) "未添加" else "${blockWords.size} 个",
        if (blockWords.isEmpty()) Color.White.copy(alpha = 0.45f) else pc.accent,
    )
    Spacer(Modifier.height(8.dp))

    fun submitWord() {
        if (DanmakuPrefs.addBlockWord(wordDraft)) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            wordDraft = ""
            keyboard?.hide()
        }
    }

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .weight(1f)
                .height(38.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.08f))
                .border(0.8.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (wordDraft.isEmpty()) {
                Text(
                    "输入屏蔽关键词…",
                    color = Color.White.copy(alpha = 0.40f),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = wordDraft,
                onValueChange = { wordDraft = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                cursorBrush = SolidColor(pc.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submitWord() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val canAdd = wordDraft.trim().isNotEmpty()
        Box(
            Modifier
                .height(38.dp)
                .expressivePress(0.94f, enabled = canAdd)
                .then(
                    if (liquid) Modifier.glassPill(RoundedCornerShape(14.dp), selected = canAdd, dark = true, accent = pc.accent)
                    else Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (canAdd) pc.accentSoft else Color.White.copy(alpha = 0.06f))
                        .border(0.8.dp, if (canAdd) pc.accent.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp)),
                )
                .clickable(enabled = canAdd) { submitWord() }
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "添加",
                color = if (canAdd) pc.accent else Color.White.copy(alpha = 0.35f),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }

    if (blockWords.isEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(
            "添加后将自动过滤包含该词的弹幕（不区分大小写）",
            color = Color.White.copy(alpha = 0.42f),
            fontSize = 11.sp,
        )
    } else {
        Spacer(Modifier.height(10.dp))
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            blockWords.forEach { word ->
                Row(
                    Modifier
                        .expressivePress(0.94f)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.10f))
                        .border(0.8.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(50))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            DanmakuPrefs.removeBlockWord(word)
                        }
                        .padding(start = 11.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        word,
                        color = Color.White.copy(alpha = 0.90f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 180.dp),
                    )
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "移除 $word",
                        tint = Color.White.copy(alpha = 0.58f),
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DanmakuSliderHeader(title: String, valueText: String, valueColor: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            color = Color.White.copy(alpha = 0.72f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Text(
            valueText,
            color = valueColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun DanmakuFilterChip(label: String, selected: Boolean, pc: IdjColors, onClick: () -> Unit) {
    val liquid = GlassStylePref.style == GlassStyle.LIQUID
    Row(
        Modifier
            .expressivePress(0.94f)
            .then(
                if (liquid) Modifier.glassPill(RoundedCornerShape(50), selected = selected, dark = true, accent = pc.accent)
                else Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) pc.accentSoft else Color.White.copy(alpha = 0.08f))
                    .border(0.8.dp, if (selected) pc.accent.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.12f), RoundedCornerShape(50)),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = pc.accent,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            label,
            color = if (selected) pc.accent else Color.White.copy(alpha = 0.84f),
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
