package app.iduanju.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow
import app.iduanju.ui.theme.IdjThemeColors

private val PillShape = RoundedCornerShape(percent = 50)
private val RailShape = RoundedCornerShape(28.dp)

/**
 * Shared search input state bridging the Yamby-style morphing bottom Liquid Glass search dock
 * and [app.iduanju.ui.search.SearchScreen].
 */
@Stable
class SearchDockState(initialQuery: String = "") {
    var query by mutableStateOf(initialQuery)
    var submitted by mutableStateOf(initialQuery.trim())
    var submitVersion by mutableIntStateOf(if (initialQuery.isNotBlank()) 1 else 0)
    var clearVersion by mutableIntStateOf(0)

    fun submit(q: String = query) {
        val trimmed = q.trim()
        if (trimmed.isEmpty()) return
        query = trimmed
        submitted = trimmed
        submitVersion++
    }

    fun clear() {
        query = ""
        submitted = ""
        clearVersion++
    }
}

/** Global glass material, switchable live from 我的 → 外观. */
enum class GlassStyle(val label: String) { LIQUID("液态玻璃"), FROSTED("毛玻璃") }

/** Reactive, SharedPreferences-backed holder for the app-wide [GlassStyle] (default [GlassStyle.LIQUID]). */
object GlassStylePref {
    private const val PREFS = "idj"
    private const val KEY = "ui.glass.style"
    private val prefs by lazy {
        runCatching { app.iduanju.IdjApp.instance.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE) }.getOrNull()
    }

    var style: GlassStyle by mutableStateOf(
        prefs?.getString(KEY, null)?.let { n -> GlassStyle.entries.firstOrNull { it.name == n } } ?: GlassStyle.LIQUID,
    )
        private set

    fun update(newStyle: GlassStyle) {
        if (style == newStyle) return
        style = newStyle
        prefs?.edit()?.putString(KEY, newStyle.name)?.apply()
    }
}

/**
 * Backdrop recorded from the root NavHost content. Only valid for overlays drawn OUTSIDE the
 * `layerBackdrop(rootBackdrop)` subtree (tab bar / rail); anything inside the NavHost must use its own local
 * `rememberLayerBackdrop()` (or [glassSurface] without a backdrop), otherwise the layer would sample itself.
 */
val LocalRootBackdrop = compositionLocalOf<Backdrop?> { null }

/** Colours shared by every glass surface so LIQUID / FROSTED look identical everywhere in the app. */
private class GlassPalette(dark: Boolean) {
    val liquidTint = if (dark) Color.Black.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.08f)
    val frostTint = if (dark) Color(0xFF1C1E24).copy(alpha = 0.72f) else Color.White.copy(alpha = 0.68f)
    val frostFallback = if (dark) Color(0xFF1C1E24).copy(alpha = 0.84f) else Color.White.copy(alpha = 0.90f)
    val rimTop = Color.White.copy(alpha = if (dark) 0.26f else 0.60f)
    val rimBottom = Color.White.copy(alpha = if (dark) 0.06f else 0.20f)
    val frostBorder = if (dark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)
    val sheen = Color.White.copy(alpha = if (dark) 0.10f else 0.22f)
    val shadow = Color.Black.copy(alpha = if (dark) 0.24f else 0.12f)
}

/**
 * The single glass material of the app, driven by [GlassStylePref].
 *
 * - [backdrop] != null on API 31+: real backdrop sampling. LIQUID (API 33+) = refractive lens + sheer tint + specular
 *   highlight; FROSTED = 18 dp blur, high-readability tint and a 0.7 dp hairline.
 * - otherwise: a static surface that visually matches the selected style (sheer fill + specular rim for LIQUID,
 *   denser frosted fill + soft hairline for FROSTED). [fallbackAlpha] is the LIQUID fallback fill opacity.
 *
 * [dark] forces the dark palette (player chrome); [base] is the surface colour of LIQUID fallbacks.
 */
@Composable
fun Modifier.glassSurface(
    shape: CornerBasedShape,
    backdrop: Backdrop? = null,
    dark: Boolean = IdjThemeColors.dark,
    base: Color = if (dark == IdjThemeColors.dark) IdjThemeColors.surface else Color(0xFF15171C),
    shadowRadius: Dp = 18.dp,
    activeOverlay: Color = Color.Transparent,
    fallbackAlpha: Float = 0.90f,
    highlightAlpha: Float = 0.50f,
): Modifier {
    val frosted = GlassStylePref.style == GlassStyle.FROSTED
    val p = remember(dark) { GlassPalette(dark) }
    val canBlur = backdrop != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    val hasLens = canBlur && !frosted && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
    val shadowColor = p.shadow
    val noLensTint = base.copy(alpha = if (dark) 0.50f else 0.54f)
    val edge = if (frosted) {
        Modifier.border(0.7.dp, p.frostBorder, shape)
    } else {
        Modifier.border(0.7.dp, Brush.verticalGradient(listOf(p.rimTop, p.rimBottom)), shape)
    }
    return if (canBlur) {
        this
            .drawBackdrop(
                backdrop = backdrop!!,
                shape = { shape },
                effects = {
                    vibrancy()
                    if (hasLens) {
                        blur(1.5f.dp.toPx())
                        lens(
                            refractionHeight = 12f.dp.toPx(),
                            refractionAmount = 26f.dp.toPx(),
                            depthEffect = true,
                            chromaticAberration = true,
                        )
                    } else {
                        blur((if (frosted) 18f else 12f).dp.toPx())
                    }
                },
                highlight = {
                    when {
                        hasLens -> Highlight(
                            width = 0.75.dp,
                            blurRadius = 0.5.dp,
                            alpha = highlightAlpha,
                            style = HighlightStyle.Default(intensity = 0.5f),
                        )
                        frosted -> Highlight(width = 0.5.dp, blurRadius = 0.dp, alpha = 0.22f, style = HighlightStyle.Plain)
                        else -> Highlight(width = 0.75.dp, blurRadius = 0.dp, alpha = highlightAlpha * 0.8f, style = HighlightStyle.Plain)
                    }
                },
                shadow = {
                    Shadow(
                        radius = shadowRadius,
                        offset = DpOffset(0.dp, 6.dp),
                        color = shadowColor,
                    )
                },
                onDrawSurface = {
                    drawRect(
                        when {
                            frosted -> p.frostTint
                            hasLens -> p.liquidTint
                            else -> noLensTint
                        },
                    )
                    if (activeOverlay.alpha > 0f) drawRect(activeOverlay)
                },
            )
            .then(if (!hasLens) edge else Modifier)
    } else {
        this
            .shadow(shadowRadius, shape, ambientColor = shadowColor, spotColor = shadowColor)
            .clip(shape)
            .then(
                if (frosted) {
                    Modifier.background(p.frostFallback)
                } else {
                    // sheer fill + soft top specular sheen, like a lens catching light
                    Modifier
                        .background(base.copy(alpha = fallbackAlpha))
                        .background(Brush.verticalGradient(0f to p.sheen, 0.55f to Color.Transparent))
                },
            )
            .then(if (activeOverlay.alpha > 0f) Modifier.background(activeOverlay) else Modifier)
            .then(edge)
    }
}

/** Round glass icon button (back / favourite / more) built on [glassSurface]. */
@Composable
fun GlassIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
    size: Dp = 40.dp,
    iconSize: Dp = 20.dp,
    dark: Boolean = IdjThemeColors.dark,
    tint: Color = if (dark) Color.White else IdjThemeColors.text,
    fallbackAlpha: Float = 0.55f,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(size)
            .glassSurface(
                shape = CircleShape,
                backdrop = backdrop,
                dark = dark,
                shadowRadius = 8.dp,
                fallbackAlpha = fallbackAlpha,
            )
            .pressClick(0.92f, enabled = enabled, label = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun GlassCapsule(
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
    contentPadding: PaddingValues = PaddingValues(6.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val c = IdjThemeColors
    Row(
        modifier
            .glassSurface(shape = PillShape, backdrop = backdrop, shadowRadius = 18.dp)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun GlassTabBar(
    selected: Int,
    items: List<Pair<String, @Composable (Boolean) -> Unit>>,
    onSelect: (Int) -> Unit,
    backdrop: Backdrop? = null,
    modifier: Modifier = Modifier,
    searchSelected: Boolean = false,
    onSearch: (() -> Unit)? = null,
    searchIcon: (@Composable (Boolean) -> Unit)? = null,
    searchDockState: SearchDockState? = null,
    onCloseSearch: () -> Unit = {},
) {
    val c = IdjThemeColors
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val hasSearch = onSearch != null && searchIcon != null
    val morph by animateFloatAsState(
        targetValue = if (hasSearch && searchSelected) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 360f),
        label = "dockMorph",
    )

    LaunchedEffect(searchSelected) {
        if (searchSelected && hasSearch) {
            kotlinx.coroutines.delay(140)
            runCatching { focusRequester.requestFocus() }
        } else {
            focusManager.clearFocus()
        }
    }

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        BoxWithConstraints(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            val gap = 10.dp
            val circleSize = 58.dp
            val totalW = maxWidth
            val expandedW = if (hasSearch) (totalW - gap - circleSize).coerceAtLeast(circleSize) else totalW
            val t = morph.coerceIn(0f, 1f)
            val leftW = if (hasSearch) lerp(expandedW, circleSize, t) else totalW
            val rightW = lerp(circleSize, expandedW, t)

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                // Left Liquid Glass Capsule: 4 navigation tabs (t -> 0) morphing into circular Back button (t -> 1)
                Box(
                    Modifier
                        .width(leftW)
                        .height(circleSize)
                        .glassSurface(shape = PillShape, backdrop = backdrop, shadowRadius = 18.dp)
                        .then(
                            if (searchSelected) {
                                Modifier.clickable {
                                    focusManager.clearFocus()
                                    onCloseSearch()
                                }
                            } else {
                                Modifier
                            },
                        )
                        .padding(if (t < 0.5f) 5.dp else 0.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (t < 0.5f) {
                        val tabsAlpha = (1f - t * 2f).coerceIn(0f, 1f)
                        Row(
                            Modifier.fillMaxSize().graphicsLayer { alpha = tabsAlpha },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            items.forEachIndexed { i, (label, icon) ->
                                val on = i == selected && !searchSelected
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .clip(PillShape)
                                        .background(
                                            if (on) {
                                                if (c.dark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f)
                                            } else {
                                                Color.Transparent
                                            },
                                        )
                                        .clickable { onSelect(i) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        icon(on)
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            label,
                                            color = if (on) c.text else c.secondary,
                                            fontSize = 11.sp,
                                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        val backAlpha = ((t - 0.5f) * 2f).coerceIn(0f, 1f)
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = c.text,
                            modifier = Modifier.size(22.dp).graphicsLayer { alpha = backAlpha },
                        )
                    }
                }

                // Right Liquid Glass Capsule: circular Search button (t -> 0) morphing into full Liquid Glass Search Input Bar (t -> 1)
                if (hasSearch) {
                    val searchQuery = searchDockState?.query.orEmpty()
                    Box(
                        Modifier
                            .width(rightW)
                            .height(circleSize)
                            .glassSurface(shape = PillShape, backdrop = backdrop, shadowRadius = 18.dp, activeOverlay = if (searchSelected) c.accent.copy(alpha = if (c.dark) 0.06f else 0.04f) else Color.Transparent)
                            .then(
                                if (!searchSelected) {
                                    Modifier.clickable { onSearch?.invoke() }
                                } else {
                                    Modifier
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (t < 0.38f && !searchSelected) {
                            val iconAlpha = (1f - t / 0.38f).coerceIn(0f, 1f)
                            Box(Modifier.graphicsLayer { alpha = iconAlpha }, contentAlignment = Alignment.Center) {
                                searchIcon?.invoke(false)
                            }
                        } else {
                            val fieldAlpha = ((t - 0.25f) / 0.75f).coerceIn(0f, 1f)
                            Row(
                                Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp)
                                    .graphicsLayer { alpha = fieldAlpha },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Filled.Search,
                                    contentDescription = "搜索",
                                    tint = c.accent,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            "搜索短剧 / 演员 / 频道…",
                                            color = c.secondary,
                                            fontSize = 15.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    BasicTextField(
                                        value = searchQuery,
                                        onValueChange = { newText ->
                                            if (searchDockState != null) {
                                                searchDockState.query = newText
                                                if (newText.isEmpty() && searchDockState.submitted.isNotEmpty()) {
                                                    searchDockState.clear()
                                                }
                                            }
                                        },
                                        singleLine = true,
                                        textStyle = TextStyle(
                                            color = c.text,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Medium,
                                        ),
                                        cursorBrush = SolidColor(c.accent),
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                        keyboardActions = KeyboardActions(
                                            onSearch = {
                                                focusManager.clearFocus()
                                                searchDockState?.submit()
                                            },
                                        ),
                                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                                    )
                                }
                                if (searchQuery.isNotEmpty()) {
                                    Spacer(Modifier.width(6.dp))
                                    Icon(
                                        Icons.Filled.Cancel,
                                        contentDescription = "清除",
                                        tint = c.secondary,
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clickable { searchDockState?.clear() },
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Box(
                                        Modifier
                                            .clip(PillShape)
                                            .background(c.accent)
                                            .clickable {
                                                focusManager.clearFocus()
                                                searchDockState?.submit()
                                            }
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            "搜索",
                                            color = c.onAccent,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Vertical floating glass navigation rail for Expanded tablet / desktop widths (>= 840dp). */
@Composable
fun GlassNavigationRail(
    selected: Int,
    items: List<Pair<String, @Composable (Boolean) -> Unit>>,
    onSelect: (Int) -> Unit,
    backdrop: Backdrop? = null,
    modifier: Modifier = Modifier,
    searchSelected: Boolean = false,
    onSearch: (() -> Unit)? = null,
    searchIcon: (@Composable (Boolean) -> Unit)? = null,
) {
    val c = IdjThemeColors
    Column(
        modifier
            .width(84.dp)
            .fillMaxHeight()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(vertical = 16.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onSearch != null && searchIcon != null) {
            Box(
                Modifier
                    .size(56.dp)
                    .glassSurface(shape = PillShape, backdrop = backdrop, shadowRadius = 14.dp, activeOverlay = if (searchSelected) c.text.copy(alpha = if (c.dark) 0.14f else 0.10f) else Color.Transparent)
                    .clickable(onClick = onSearch),
                contentAlignment = Alignment.Center,
            ) {
                searchIcon(searchSelected)
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .glassSurface(shape = RailShape, backdrop = backdrop, shadowRadius = 14.dp)
                .padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items.forEachIndexed { i, (label, icon) ->
                val on = i == selected && !searchSelected
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(if (on) c.text.copy(alpha = if (c.dark) 0.12f else 0.09f) else Color.Transparent)
                        .clickable { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        icon(on)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            label,
                            color = if (on) c.text else c.secondary,
                            fontSize = 11.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GlassChip(title: String, selected: Boolean, onClick: () -> Unit) {
    val c = IdjThemeColors
    Box(
        Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(if (selected) c.text else c.elevated)
            .then(if (!selected) Modifier.border(0.6.dp, c.text.copy(0.12f), RoundedCornerShape(percent = 50)) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            title,
            color = if (selected) c.bg else c.secondary,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

fun posterBadge(total: Int, updated: Int = 0): String = when {
    total > 0 && updated > 0 && updated < total -> "更新至${updated}集"
    total > 0 -> "全${total}集"
    updated > 0 -> "更新至${updated}集"
    else -> ""
}

val PosterShape = RoundedCornerShape(10.dp)
val CardShape = RoundedCornerShape(16.dp)
