package app.iduanju.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.ui.theme.IdjThemeColors
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Material 3 Expressive motion tokens: physics-based springs shared by every tactile control of the app.
 *
 * - [spatialFast]: snappy pills, tab indicators, press feedback.
 * - [spatialDefault]: sheets, navigation transitions, expanding panels.
 * - [spatialSlow]: large modal / morphing transitions.
 * - [effects]: alpha / colour fades without overshoot.
 */
object ExpressiveMotion {
    fun <T> spatialFast(): SpringSpec<T> = spring(dampingRatio = 0.65f, stiffness = 600f)
    fun <T> spatialDefault(): SpringSpec<T> = spring(dampingRatio = 0.78f, stiffness = 320f)
    fun <T> spatialSlow(): SpringSpec<T> = spring(dampingRatio = 0.82f, stiffness = 240f)
    fun <T> effects(): SpringSpec<T> = spring(dampingRatio = 1.0f, stiffness = 900f)
}

/**
 * Warm paper / charcoal tones of the `极简质感` ([GlassStyle.EXPRESSIVE]) appearance, shared by the theme palette,
 * [glassSurface], [glassPill] and the Expressive controls below.
 */
object ExpressivePalette {
    fun bg(dark: Boolean) = if (dark) Color(0xFF151515) else Color(0xFFF9F9F7)
    fun surface(dark: Boolean) = if (dark) Color(0xFF20201F) else Color(0xFFFFFFFF)
    fun hairline(dark: Boolean) = if (dark) Color.White.copy(alpha = 0.09f) else Color(0xFF151515).copy(alpha = 0.10f)
    /** High-contrast ink: the solid active pill / label bubble colour. */
    fun ink(dark: Boolean) = if (dark) Color(0xFFF5F5F5) else Color(0xFF151515)
    /** Content drawn on top of [ink]. */
    fun onInk(dark: Boolean) = if (dark) Color(0xFF151515) else Color.White
    /** Soft tinted track behind the ink pill. */
    fun track(dark: Boolean) = if (dark) Color.White.copy(alpha = 0.10f) else Color(0xFF151515).copy(alpha = 0.07f)
    /** Soft circular button surface (sheet close / back). */
    fun softButton(dark: Boolean) = if (dark) Color.White.copy(alpha = 0.10f) else Color(0xFF151515).copy(alpha = 0.06f)
    fun shadow(dark: Boolean) = Color.Black.copy(alpha = if (dark) 0.34f else 0.09f)
}

/**
 * Lightweight tactile spring press: scales the node to [pressedScale] while a pointer is down, springing back with
 * [ExpressiveMotion.spatialFast]. Pass the [interactionSource] of a sibling `clickable` to share its press state;
 * otherwise the pointer is tracked passively (nothing is consumed, so clicks and drags still work).
 */
fun Modifier.expressivePress(
    pressedScale: Float = 0.96f,
    interactionSource: MutableInteractionSource? = null,
    enabled: Boolean = true,
): Modifier = composed {
    var tracked by remember { mutableStateOf(false) }
    val sourcePressed = interactionSource?.collectIsPressedAsState()?.value ?: false
    val pressed = enabled && (sourcePressed || tracked)
    val s by animateFloatAsState(if (pressed) pressedScale else 1f, ExpressiveMotion.spatialFast(), label = "expressivePress")
    this
        .then(
            if (interactionSource == null && enabled) {
                Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        tracked = true
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                        } while (event.changes.any { it.pressed })
                        tracked = false
                    }
                }
            } else {
                Modifier
            },
        )
        .graphicsLayer { scaleX = s; scaleY = s }
}

/** Colours of the thick pill sliders; [dark] picks the ink-on-charcoal or ink-on-paper variant. */
@Immutable
class ExpressiveSliderColors(
    val track: Color,
    val active: Color,
    val handle: Color,
    val tickInactive: Color,
    val tickActive: Color,
) {
    companion object {
        fun of(dark: Boolean, active: Color? = null): ExpressiveSliderColors {
            val fill = active ?: ExpressivePalette.ink(dark)
            val onFill = if (active == null) ExpressivePalette.onInk(dark) else Color.White
            return ExpressiveSliderColors(
                track = ExpressivePalette.track(dark),
                active = fill,
                handle = onFill,
                tickInactive = if (dark) Color.White.copy(alpha = 0.34f) else Color(0xFF181817).copy(alpha = 0.26f),
                tickActive = onFill.copy(alpha = 0.55f),
            )
        }
    }
}

/**
 * Discrete thick-pill step slider: a soft tinted 40 dp track with circular tick dots, a solid high-contrast active
 * pill that springs to the selected step ([ExpressiveMotion.spatialFast]) and a crisp inner handle on its tip.
 * Drag or tap anywhere; crossing a step fires a [HapticFeedbackType.TextHandleMove] tick.
 *
 * [labels] (optional, one per step) render under the track and are tappable too. When [enabled] is false the slider
 * dims and ignores input; taps are forwarded to [onDisabledClick] instead (e.g. to explain why it is locked).
 */
@Composable
fun ExpressiveStepSlider(
    steps: Int,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    labels: List<String> = emptyList(),
    enabled: Boolean = true,
    onDisabledClick: (() -> Unit)? = null,
    dark: Boolean = IdjThemeColors.dark,
    height: Dp = 40.dp,
    colors: ExpressiveSliderColors = remember(dark) { ExpressiveSliderColors.of(dark) },
) {
    val count = steps.coerceAtLeast(2)
    val maxIdx = count - 1
    val haptic = LocalHapticFeedback.current
    val latestOnSelect by rememberUpdatedState(onSelect)
    val latestDisabled by rememberUpdatedState(onDisabledClick)
    val latestEnabled by rememberUpdatedState(enabled)
    val sel = selected.coerceIn(0, maxIdx)
    val latestSel by rememberUpdatedState(sel)

    // The step currently under the finger while dragging (null = follow [selected]).
    var dragStep by remember { mutableStateOf<Int?>(null) }
    val shownStep = dragStep ?: sel
    val pos = remember { Animatable(sel.toFloat()) }
    LaunchedEffect(shownStep) { pos.animateTo(shownStep.toFloat(), ExpressiveMotion.spatialFast()) }
    val handleGrow by animateFloatAsState(if (dragStep != null) 1f else 0f, ExpressiveMotion.spatialFast(), label = "stepHandle")

    Column(modifier.fillMaxWidth()) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(height)
                .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
                .semantics {
                    stateDescription = labels.getOrNull(sel) ?: "${sel + 1}/$count"
                    progressBarRangeInfo = ProgressBarRangeInfo(sel.toFloat(), 0f..maxIdx.toFloat(), count - 2)
                    if (enabled) setProgress { v -> latestOnSelect(v.roundToInt().coerceIn(0, maxIdx)); true }
                },
        ) {
            val wPx = constraints.maxWidth.toFloat()
            val hPx = constraints.maxHeight.toFloat()
            val pad = hPx / 2f
            fun stepX(i: Float) = pad + (wPx - 2 * pad) * (i / maxIdx)
            fun stepAt(x: Float) = (((x - pad) / (wPx - 2 * pad).coerceAtLeast(1f)) * maxIdx).roundToInt().coerceIn(0, maxIdx)

            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(count, wPx) {
                        detectTapGestures { o ->
                            if (!latestEnabled) { latestDisabled?.invoke(); return@detectTapGestures }
                            val i = stepAt(o.x)
                            if (i != latestSel) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            latestOnSelect(i)
                        }
                    }
                    .pointerInput(count, wPx) {
                        detectHorizontalDragGestures(
                            onDragStart = { o ->
                                if (!latestEnabled) return@detectHorizontalDragGestures
                                dragStep = stepAt(o.x)
                            },
                            onHorizontalDrag = { change, _ ->
                                if (!latestEnabled) return@detectHorizontalDragGestures
                                change.consume()
                                val i = stepAt(change.position.x)
                                if (i != dragStep) {
                                    dragStep = i
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    latestOnSelect(i)
                                }
                            },
                            onDragEnd = {
                                dragStep?.let { latestOnSelect(it) }
                                dragStep = null
                            },
                            onDragCancel = { dragStep = null },
                        )
                    },
            ) {
                val r = CornerRadius(hPx / 2f, hPx / 2f)
                drawRoundRect(colors.track, cornerRadius = r)
                val tip = stepX(pos.value.coerceIn(0f, maxIdx.toFloat()))
                val activeW = (tip + pad).coerceIn(hPx, wPx)
                drawRoundRect(colors.active, size = Size(activeW, hPx), cornerRadius = r)
                val dotR = 2.6.dp.toPx()
                for (i in 0..maxIdx) {
                    val x = stepX(i.toFloat())
                    if (abs(x - tip) < 6.dp.toPx()) continue
                    drawCircle(if (x < tip) colors.tickActive else colors.tickInactive, dotR, Offset(x, hPx / 2f))
                }
                val hw = (3.5f + 2f * handleGrow).dp.toPx()
                val hh = hPx * (0.42f + 0.08f * handleGrow)
                drawRoundRect(
                    colors.handle,
                    topLeft = Offset(tip - hw / 2f, (hPx - hh) / 2f),
                    size = Size(hw, hh),
                    cornerRadius = CornerRadius(hw / 2f, hw / 2f),
                )
            }
        }
        if (labels.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            BoxWithConstraints(Modifier.fillMaxWidth().graphicsLayer { alpha = if (enabled) 1f else 0.45f }) {
                val pad = height / 2
                val span = maxWidth - pad * 2
                val labelW = 52.dp
                labels.take(count).forEachIndexed { i, t ->
                    val on = i == shownStep
                    val cx = pad + span * (i.toFloat() / maxIdx)
                    Text(
                        t,
                        color = if (dark) Color.White.copy(alpha = if (on) 0.96f else 0.55f) else Color(0xFF181817).copy(alpha = if (on) 0.92f else 0.52f),
                        fontSize = 12.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier
                            .offset(x = (cx - labelW / 2).coerceIn(0.dp, maxWidth - labelW))
                            .width(labelW)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                if (!enabled) latestDisabled?.invoke()
                                else {
                                    if (i != sel) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    latestOnSelect(i)
                                }
                            }
                            .padding(vertical = 3.dp)
                            .semantics { contentDescription = "step.$t" },
                    )
                }
            }
        }
    }
}

/**
 * Continuous thick-pill slider (34 dp): soft tinted track, solid high-contrast active fill and a crisp inner handle
 * pill on its tip. Drag or tap anywhere. [steps] > 0 snaps to that many intermediate stops (like Material's Slider).
 */
@Composable
fun ExpressiveSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    dark: Boolean = IdjThemeColors.dark,
    height: Dp = 34.dp,
    colors: ExpressiveSliderColors = remember(dark) { ExpressiveSliderColors.of(dark) },
) {
    val haptic = LocalHapticFeedback.current
    val latestOnChange by rememberUpdatedState(onValueChange)
    val latestFinished by rememberUpdatedState(onValueChangeFinished)
    val lo = valueRange.start
    val hi = valueRange.endInclusive
    val span = (hi - lo).takeIf { it > 0f } ?: 1f
    fun snap(v: Float): Float {
        val c = v.coerceIn(lo, hi)
        if (steps <= 0) return c
        val seg = span / (steps + 1)
        return lo + ((c - lo) / seg).roundToInt() * seg
    }
    val fraction = ((value - lo) / span).coerceIn(0f, 1f)
    var dragging by remember { mutableStateOf(false) }
    // Taps glide with a spring; drags track the finger 1:1.
    val shown by animateFloatAsState(
        fraction,
        if (dragging) spring(stiffness = 4000f) else ExpressiveMotion.spatialFast(),
        label = "expressiveSlider",
    )
    val handleGrow by animateFloatAsState(if (dragging) 1f else 0f, ExpressiveMotion.spatialFast(), label = "sliderHandle")

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(lo, hi), valueRange, steps)
                if (enabled) setProgress { v -> latestOnChange(snap(v)); latestFinished?.invoke(); true }
            },
    ) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val pad = hPx / 2f
        fun valueAt(x: Float) = snap(lo + ((x - pad) / (wPx - 2 * pad).coerceAtLeast(1f)).coerceIn(0f, 1f) * span)
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(enabled, wPx, lo, hi, steps) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { o ->
                        latestOnChange(valueAt(o.x))
                        latestFinished?.invoke()
                    }
                }
                .pointerInput(enabled, wPx, lo, hi, steps) {
                    if (!enabled) return@pointerInput
                    var lastStop = -1
                    detectHorizontalDragGestures(
                        onDragStart = { o ->
                            dragging = true
                            latestOnChange(valueAt(o.x))
                        },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            val v = valueAt(change.position.x)
                            if (steps > 0) {
                                val stop = ((v - lo) / (span / (steps + 1))).roundToInt()
                                if (stop != lastStop && lastStop >= 0) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                lastStop = stop
                            }
                            latestOnChange(v)
                        },
                        onDragEnd = { dragging = false; lastStop = -1; latestFinished?.invoke() },
                        onDragCancel = { dragging = false; lastStop = -1; latestFinished?.invoke() },
                    )
                },
        ) {
            val r = CornerRadius(hPx / 2f, hPx / 2f)
            drawRoundRect(colors.track, cornerRadius = r)
            val tip = pad + (wPx - 2 * pad) * shown
            val activeW = (tip + pad).coerceIn(hPx, wPx)
            drawRoundRect(colors.active, size = Size(activeW, hPx), cornerRadius = r)
            if (steps in 1..20) {
                val dotR = 2.2.dp.toPx()
                for (i in 0..steps + 1) {
                    val x = pad + (wPx - 2 * pad) * (i.toFloat() / (steps + 1))
                    if (abs(x - tip) < 6.dp.toPx()) continue
                    drawCircle(if (x < tip) colors.tickActive else colors.tickInactive, dotR, Offset(x, hPx / 2f))
                }
            }
            val hw = (3.5f + 2f * handleGrow).dp.toPx()
            val hh = hPx * (0.44f + 0.10f * handleGrow)
            drawRoundRect(
                colors.handle,
                topLeft = Offset(tip - hw / 2f, (hPx - hh) / 2f),
                size = Size(hw, hh),
                cornerRadius = CornerRadius(hw / 2f, hw / 2f),
            )
        }
    }
}

/**
 * Sheet header for 28 dp-corner sheets: a 34 dp circular soft-surface close / back button on the left, a centred
 * semibold [title] and an optional [trailing] action on the right.
 */
@Composable
fun ExpressiveSheetHeader(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    closeIcon: ImageVector = Icons.Filled.Close,
    closeLabel: String = "关闭",
    subtitle: String? = null,
    dark: Boolean = IdjThemeColors.dark,
    trailing: (@Composable () -> Unit)? = null,
) {
    val ink = if (dark) Color.White else Color(0xFF181817)
    Box(modifier.fillMaxWidth().heightIn(min = 40.dp), contentAlignment = Alignment.Center) {
        val src = remember { MutableInteractionSource() }
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .size(34.dp)
                .expressivePress(0.90f, src)
                .clip(CircleShape)
                .background(ExpressivePalette.softButton(dark))
                .clickable(src, null, onClick = onClose)
                .semantics { contentDescription = closeLabel },
            contentAlignment = Alignment.Center,
        ) {
            Icon(closeIcon, null, tint = ink.copy(alpha = 0.86f), modifier = Modifier.size(18.dp))
        }
        Column(
            Modifier.padding(horizontal = 46.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, color = ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, color = ink.copy(alpha = 0.55f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) Box(Modifier.align(Alignment.CenterEnd)) { trailing() }
    }
}

/**
 * Floating status card (`RoundedCornerShape(20.dp)`) for lightweight transient notifications above the bottom bar.
 * Springs in with scale + slide and out again when [message] becomes null; auto-dismisses after [durationMs]
 * through [onDismiss] (pass `durationMs = 0` to keep it until [message] is cleared).
 */
@Composable
fun ExpressiveStatusPill(
    message: String?,
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector? = null,
    dark: Boolean = IdjThemeColors.dark,
    durationMs: Long = 2600L,
    onDismiss: () -> Unit = {},
) {
    val latestDismiss by rememberUpdatedState(onDismiss)
    // Keep the last text alive while the exit animation plays.
    var shown by remember { mutableStateOf(message) }
    if (message != null) shown = message
    LaunchedEffect(message) {
        if (message != null && durationMs > 0) {
            delay(durationMs)
            latestDismiss()
        }
    }
    AnimatedVisibility(
        visible = message != null,
        modifier = modifier,
        enter = fadeIn(ExpressiveMotion.effects()) +
            scaleIn(ExpressiveMotion.spatialDefault(), initialScale = 0.86f, transformOrigin = TransformOrigin(0.5f, 1f)) +
            slideInVertically(ExpressiveMotion.spatialDefault()) { it / 2 },
        exit = fadeOut(ExpressiveMotion.effects()) +
            scaleOut(ExpressiveMotion.spatialFast(), targetScale = 0.92f, transformOrigin = TransformOrigin(0.5f, 1f)) +
            slideOutVertically(ExpressiveMotion.spatialFast()) { it / 3 },
    ) {
        val shape = RoundedCornerShape(20.dp)
        val ink = if (dark) Color(0xFFF4F2EC) else Color(0xFF181817)
        Column(
            Modifier
                .widthIn(max = 360.dp)
                .shadow(10.dp, shape, ambientColor = ExpressivePalette.shadow(dark), spotColor = ExpressivePalette.shadow(dark))
                .clip(shape)
                .background(ExpressivePalette.surface(dark))
                .border(0.8.dp, ExpressivePalette.hairline(dark), shape)
                .clickable { latestDismiss() }
                .padding(horizontal = 16.dp, vertical = 11.dp),
        ) {
            if (title != null || icon != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) {
                        Icon(icon, null, tint = ink.copy(alpha = 0.60f), modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    if (title != null) Text(title, color = ink.copy(alpha = 0.60f), fontSize = 12.sp, maxLines = 1)
                }
                Spacer(Modifier.height(3.dp))
            }
            Text(shown.orEmpty(), color = ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 19.sp)
        }
    }
}
