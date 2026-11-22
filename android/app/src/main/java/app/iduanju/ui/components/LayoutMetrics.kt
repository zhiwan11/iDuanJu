package app.iduanju.ui.components

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class SizeTier { Compact, Medium, Expanded }

/**
 * Unified responsive layout metrics shared across phone, foldable, split-screen, and Android Pad.
 * Breakpoints match iOS `LayoutMetrics` (Compact < 600, Medium 600..<840, Expanded >= 840).
 *
 * Tablet portrait (≈800×1280) lands in Medium, tablet landscape (≈1280×800 minus the rail) in Expanded.
 */
@Immutable
data class LayoutMetrics(
    val widthDp: Int,
    val heightDp: Int,
    val smallestWidthDp: Int,
) {
    val tier: SizeTier
        get() = when {
            widthDp >= 840 -> SizeTier.Expanded
            widthDp >= 600 -> SizeTier.Medium
            else -> SizeTier.Compact
        }

    val isTablet: Boolean get() = smallestWidthDp >= 600
    val isLandscape: Boolean get() = widthDp > heightDp
    val useRail: Boolean get() = widthDp >= 840
    /** Tablet held in portrait (600..839 wide). */
    val isTabletPortrait: Boolean get() = isTablet && tier == SizeTier.Medium
    /** Tablet landscape / desktop width. */
    val isTabletLandscape: Boolean get() = isTablet && tier == SizeTier.Expanded

    /** Horizontal page gutter: 16 phone, 24 tablet portrait, 28 tablet landscape. */
    val pagePadding: Dp
        get() = when (tier) {
            SizeTier.Expanded -> 28.dp
            SizeTier.Medium -> 24.dp
            SizeTier.Compact -> 16.dp
        }

    /** Portrait poster grid columns (home, category, manga, TVBox, library, search results). */
    val posterCols: Int
        get() = when {
            widthDp >= 1100 -> 7
            widthDp >= 840 -> 6
            widthDp >= 600 -> 5
            else -> 3
        }

    /** Landscape / wide card grid columns (search hot list, landscape feeds). */
    val wideCardCols: Int
        get() = when {
            widthDp >= 840 -> 4
            widthDp >= 600 -> 3
            else -> 2
        }

    /** Columns of compact list rows (search rank list, history) so tablet rows never stretch edge to edge. */
    val listCols: Int
        get() = when {
            widthDp >= 1000 -> 3
            widthDp >= 600 -> 2
            else -> 1
        }

    /** Episode selector grid columns inside detail and player sheets. */
    val episodeCols: Int
        get() = when {
            widthDp >= 1100 -> 12
            widthDp >= 840 -> 10
            widthDp >= 600 -> 8
            else -> 6
        }

    /** Horizontal shelf poster card width (home 热播榜 / 推荐榜 / 新剧榜 …). */
    val shelfCardWidth: Dp
        get() = when (tier) {
            SizeTier.Expanded -> 146.dp
            SizeTier.Medium -> 132.dp
            SizeTier.Compact -> 112.dp
        }

    /** How many items a horizontal shelf shows before 「更多」. */
    val shelfItemCount: Int get() = if (isTablet || tier != SizeTier.Compact) 16 else 12

    /** 继续观看 card width. */
    val continueCardWidth: Dp get() = if (tier == SizeTier.Compact) 296.dp else 280.dp

    val detailMaxWidth: Dp
        get() = when (tier) {
            SizeTier.Compact -> Dp.Unspecified
            SizeTier.Medium -> 760.dp
            SizeTier.Expanded -> 900.dp
        }
    val readerMaxWidth: Dp get() = if (tier == SizeTier.Compact) Dp.Unspecified else 720.dp
}

val LocalLayoutMetrics = staticCompositionLocalOf {
    LayoutMetrics(widthDp = 390, heightDp = 844, smallestWidthDp = 390)
}
