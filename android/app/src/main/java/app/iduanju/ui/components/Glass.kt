package app.iduanju.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
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

@Composable
private fun Modifier.liquidGlassSurface(
    shape: CornerBasedShape,
    backdrop: Backdrop?,
    surfaceTint: Color,
    fallbackColor: Color,
    borderTopColor: Color,
    borderBottomColor: Color,
    shadowRadius: Dp = 18.dp,
    shadowColor: Color = Color.Black.copy(alpha = 0.18f),
    highlightAlpha: Float = 0.75f,
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
                    blur(16f.dp.toPx())
                    if (hasLens) {
                        lens(
                            refractionHeight = 14f.dp.toPx(),
                            refractionAmount = 28f.dp.toPx(),
                            depthEffect = true,
                        )
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
                            alpha = highlightAlpha * 0.6f,
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
                    drawRect(surfaceTint)
                    if (activeOverlay.alpha > 0f) {
                        drawRect(activeOverlay)
                    }
                },
            )
            .border(
                0.6.dp,
                Brush.verticalGradient(listOf(borderTopColor, borderBottomColor)),
                shape,
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
    content: @Composable RowScope.() -> Unit,
) {
    val c = IdjThemeColors
    Row(
        modifier
            .liquidGlassSurface(
                shape = PillShape,
                backdrop = backdrop,
                surfaceTint = c.surface.copy(alpha = if (c.dark) 0.56f else 0.68f),
                fallbackColor = c.surface.copy(alpha = 0.94f),
                borderTopColor = c.text.copy(alpha = if (c.dark) 0.20f else 0.14f),
                borderBottomColor = c.text.copy(alpha = 0.05f),
                shadowRadius = 18.dp,
                shadowColor = Color.Black.copy(alpha = if (c.dark) 0.28f else 0.14f),
                highlightAlpha = if (c.dark) 0.65f else 0.85f,
            )
            .padding(6.dp),
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
) {
    val c = IdjThemeColors
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GlassCapsule(Modifier.weight(1f), backdrop = backdrop) {
                items.forEachIndexed { i, (label, icon) ->
                    val on = i == selected && !searchSelected
                    Box(
                        Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(PillShape)
                            .background(if (on) c.text.copy(alpha = if (c.dark) 0.12f else 0.09f) else Color.Transparent)
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
                            )
                        }
                    }
                }
            }
            if (onSearch != null && searchIcon != null) {
                Box(
                    Modifier
                        .size(60.dp)
                        .liquidGlassSurface(
                            shape = PillShape,
                            backdrop = backdrop,
                            surfaceTint = c.surface.copy(alpha = if (c.dark) 0.56f else 0.68f),
                            fallbackColor = c.surface.copy(alpha = 0.94f),
                            borderTopColor = c.text.copy(alpha = if (c.dark) 0.20f else 0.14f),
                            borderBottomColor = c.text.copy(alpha = 0.05f),
                            shadowRadius = 18.dp,
                            shadowColor = Color.Black.copy(alpha = if (c.dark) 0.28f else 0.14f),
                            highlightAlpha = if (c.dark) 0.65f else 0.85f,
                            activeOverlay = if (searchSelected) c.text.copy(alpha = if (c.dark) 0.14f else 0.10f) else Color.Transparent,
                        )
                        .clickable(onClick = onSearch),
                    contentAlignment = Alignment.Center,
                ) {
                    searchIcon(searchSelected)
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
                        surfaceTint = c.surface.copy(alpha = if (c.dark) 0.72f else 0.85f),
                        fallbackColor = c.surface.copy(alpha = 0.94f),
                        borderTopColor = c.text.copy(alpha = if (c.dark) 0.20f else 0.14f),
                        borderBottomColor = c.text.copy(alpha = 0.05f),
                        shadowRadius = 14.dp,
                        shadowColor = Color.Black.copy(alpha = if (c.dark) 0.24f else 0.14f),
                        highlightAlpha = if (c.dark) 0.6f else 0.8f,
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
                    surfaceTint = c.surface.copy(alpha = if (c.dark) 0.72f else 0.85f),
                    fallbackColor = c.surface.copy(alpha = 0.94f),
                    borderTopColor = c.text.copy(alpha = if (c.dark) 0.20f else 0.14f),
                    borderBottomColor = c.text.copy(alpha = 0.05f),
                    shadowRadius = 14.dp,
                    shadowColor = Color.Black.copy(alpha = if (c.dark) 0.24f else 0.14f),
                    highlightAlpha = if (c.dark) 0.6f else 0.8f,
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
