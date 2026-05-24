package my.cheysoff.core_ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class Spacing(
    // --- Primitive Scale ---
    val none: Dp = 0.dp,
    val xs: Dp = 4.dp,
    val s: Dp = 8.dp,
    val m: Dp = 16.dp,
    val l: Dp = 24.dp,
    val xl: Dp = 32.dp,
    val xxl: Dp = 48.dp,
    val xxxl: Dp = 64.dp,

    // --- Semantic Roles ---

    /** Horizontal padding applied to the edges of a screen's content. */
    val screenHorizontal: Dp = m,

    /** Vertical padding applied to the top and bottom edges of a screen's content. */
    val screenVertical: Dp = none,

    /** Spacing used between small elements inside a larger component, like a card. */
    val insideCardItemSpacing: Dp = xs,

    /** Vertical spacing between large, distinct sections of a screen. */
    val sectionSpacing: Dp = l,

    /** Horizontal or vertical spacing between items in a list, like a LazyRow or LazyColumn. */
    val listItemSpacing: Dp = m,

    /** Vertical spacing between related text elements, such as a title and a subtitle. */
    val interItemSpacingVertical: Dp = s,

    /** Horizontal spacing between related elements on the same row, such as an icon and its text. */
    val interItemSpacingHorizontal: Dp = s,

    /** Margin specifically for the bottom of a primary logo or hero image. */
    val logoBottomMargin: Dp = l,

    /** Horizontal padding inside interactive components like buttons to space content from the edges. */
    val buttonContentPadding: Dp = m,

    /** Spacing between a series of related buttons or interactive elements. */
    val buttonGroupSpacing: Dp = xs,

    /** Height of the bottom navigation bar. */
    val bottomBarHeight: Dp = 80.dp,

    /** Size of the primary Floating Action Button. */
    val fabSize: Dp = 56.dp,

    /** Size of the icon inside a FAB. */
    val fabIconSize: Dp = xl,

    /** Vertical offset for the FAB to overlap the bottom bar. */
    val fabOverlapOffset: Dp = 44.dp,

    /** Gap in the bottom bar to accommodate the centered FAB. */
    val bottomBarFabGap: Dp = xxxl,
)
