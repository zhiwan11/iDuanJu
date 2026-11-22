package app.iduanju.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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

@Composable
private fun Modifier.liquidGlassSurface(
    shape: CornerBasedShape,
    backdrop: Backdrop?,
    surfaceTint: Color,
    noLensSurfaceTint: Color,
    fallbackColor: Color,
    borderTopColor: Color,
    borderBottomColor: Color,
    shadowRadius: Dp = 18.dp,
    shadowColor: Color = Color.Black.copy(alpha = 0.14f),
    highlightAlpha: Float = 0.50f,
    activeOverlay: Color = Color.Transparent,
): Modifier {
    val canBlur = backdrop != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    val hasLens = canBlur && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
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
                        blur(12f.dp.toPx())
                    }
                },
                highlight = {
                    if (hasLens) {
                        Highlight(
                            width = 0.75.dp,
                            blurRadius = 0.5.dp,
                            alpha = highlightAlpha,
                            style = HighlightStyle.Default(intensity = 0.5f),
                        )
                    } else {
                        Highlight(
                            width = 0.75.dp,
                            blurRadius = 0.dp,
                            alpha = highlightAlpha * 0.8f,
                            style = HighlightStyle.Plain,
                        )
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
                    drawRect(if (hasLens) surfaceTint else noLensSurfaceTint)
                    if (activeOverlay.alpha > 0f) {
                        drawRect(activeOverlay)
                    }
                },
            )
            .then(
                if (!hasLens) {
                    Modifier.border(
                        0.7.dp,
                        Brush.verticalGradient(listOf(borderTopColor, borderBottomColor)),
                        shape,
                    )
                } else {
                    Modifier
                },
            )
    } else {
        this
            .shadow(shadowRadius, shape, ambientColor = shadowColor, spotColor = shadowColor)
            .clip(shape)
            .background(fallbackColor)
            .then(if (activeOverlay.alpha > 0f) Modifier.background(activeOverlay) else Modifier)
            .border(
                0.8.dp,
                Brush.verticalGradient(listOf(borderTopColor, borderBottomColor)),
                shape,
            )
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
            .liquidGlassSurface(
                shape = PillShape,
                backdrop = backdrop,
                surfaceTint = if (c.dark) Color.Black.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.08f),
                noLensSurfaceTint = c.surface.copy(alpha = if (c.dark) 0.50f else 0.54f),
                fallbackColor = c.surface.copy(alpha = 0.90f),
                borderTopColor = Color.White.copy(alpha = if (c.dark) 0.24f else 0.55f),
                borderBottomColor = Color.White.copy(alpha = if (c.dark) 0.06f else 0.20f),
                shadowRadius = 18.dp,
                shadowColor = Color.Black.copy(alpha = if (c.dark) 0.24f else 0.12f),
                highlightAlpha = 0.50f,
            )
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
                        .liquidGlassSurface(
                            shape = PillShape,
                            backdrop = backdrop,
                            surfaceTint = if (c.dark) Color.Black.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.08f),
                            noLensSurfaceTint = c.surface.copy(alpha = if (c.dark) 0.50f else 0.54f),
                            fallbackColor = c.surface.copy(alpha = 0.90f),
                            borderTopColor = Color.White.copy(alpha = if (c.dark) 0.24f else 0.55f),
                            borderBottomColor = Color.White.copy(alpha = if (c.dark) 0.06f else 0.20f),
                            shadowRadius = 18.dp,
                            shadowColor = Color.Black.copy(alpha = if (c.dark) 0.24f else 0.12f),
                            highlightAlpha = 0.50f,
                        )
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
                            .liquidGlassSurface(
                                shape = PillShape,
                                backdrop = backdrop,
                                surfaceTint = if (c.dark) Color.Black.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.08f),
                                noLensSurfaceTint = c.surface.copy(alpha = if (c.dark) 0.50f else 0.54f),
                                fallbackColor = c.surface.copy(alpha = 0.90f),
                                borderTopColor = Color.White.copy(alpha = if (c.dark) 0.24f else 0.55f),
                                borderBottomColor = Color.White.copy(alpha = if (c.dark) 0.06f else 0.20f),
                                shadowRadius = 18.dp,
                                shadowColor = Color.Black.copy(alpha = if (c.dark) 0.24f else 0.12f),
                                highlightAlpha = 0.50f,
                                activeOverlay = if (searchSelected) c.accent.copy(alpha = if (c.dark) 0.06f else 0.04f) else Color.Transparent,
                            )
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
                    .liquidGlassSurface(
                        shape = PillShape,
                        backdrop = backdrop,
                        surfaceTint = if (c.dark) Color.Black.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.08f),
                        noLensSurfaceTint = c.surface.copy(alpha = if (c.dark) 0.50f else 0.54f),
                        fallbackColor = c.surface.copy(alpha = 0.90f),
                        borderTopColor = Color.White.copy(alpha = if (c.dark) 0.24f else 0.55f),
                        borderBottomColor = Color.White.copy(alpha = if (c.dark) 0.06f else 0.20f),
                        shadowRadius = 14.dp,
                        shadowColor = Color.Black.copy(alpha = if (c.dark) 0.24f else 0.12f),
                        highlightAlpha = 0.50f,
                        activeOverlay = if (searchSelected) c.text.copy(alpha = if (c.dark) 0.14f else 0.10f) else Color.Transparent,
                    )
                    .clickable(onClick = onSearch),
                contentAlignment = Alignment.Center,
            ) {
                searchIcon(searchSelected)
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .liquidGlassSurface(
                    shape = RailShape,
                    backdrop = backdrop,
                    surfaceTint = if (c.dark) Color.Black.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.08f),
                    noLensSurfaceTint = c.surface.copy(alpha = if (c.dark) 0.50f else 0.54f),
                    fallbackColor = c.surface.copy(alpha = 0.90f),
                    borderTopColor = Color.White.copy(alpha = if (c.dark) 0.24f else 0.55f),
                    borderBottomColor = Color.White.copy(alpha = if (c.dark) 0.06f else 0.20f),
                    shadowRadius = 14.dp,
                    shadowColor = Color.Black.copy(alpha = if (c.dark) 0.24f else 0.12f),
                    highlightAlpha = 0.50f,
                )
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
