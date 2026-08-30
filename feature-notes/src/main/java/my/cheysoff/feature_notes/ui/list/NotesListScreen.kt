package my.cheysoff.feature_notes.ui.list

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.cheysoff.core_domain.model.NotesSortOrder
import my.cheysoff.core_ui.model.menuLabel
import my.cheysoff.core_ui.model.pillLabel
import my.cheysoff.core_ui.theme.AccentIndigo
import my.cheysoff.core_ui.theme.AppBlack
import my.cheysoff.core_ui.theme.BodyGrey
import my.cheysoff.core_ui.theme.IndigoTint
import my.cheysoff.core_ui.theme.LocalSpacing
import my.cheysoff.core_ui.theme.SurfaceDark
import my.cheysoff.core_ui.theme.TitleGrey
import my.cheysoff.core_ui.theme.UncategorizedEdge
import my.cheysoff.core_ui.theme.folderAccentColor
import my.cheysoff.feature_notes.model.list.BottomBarItem
import my.cheysoff.feature_notes.model.list.FolderPreviewUi
import my.cheysoff.feature_notes.model.list.HeaderLineUi
import my.cheysoff.feature_notes.model.list.NotePreviewUi
import my.cheysoff.feature_notes.model.list.NoteSearchMatchUi
import my.cheysoff.feature_notes.model.list.NotesListIntent
import my.cheysoff.feature_notes.model.list.NotesListScreenState
import my.cheysoff.feature_notes.model.list.normalizeSearchText
import my.cheysoff.feature_notes.ui.folder.FolderChooser
import my.cheysoff.feature_notes.ui.folder.FolderEditDialog
import my.cheysoff.feature_notes.ui.folder.FolderRef
import kotlin.math.roundToInt

// ── Progressive blur behind the floating nav bar (tunables) ──────────────────
// The blurred band starts exactly at the TOP OF THE NAV BAR and runs to the bottom
// of the screen — content above the bar stays completely sharp. Inside the band the
// blur grows from nothing to `BlurMaxRadius` over `BlurRampHeight`, then holds at the
// max for the rest of the bar. Ramping inside the band rather than starting at full
// strength keeps the band's top edge from reading as a hard seam.

/**
 * Vertical distance over which the blur fades in, measured DOWN from the top of the
 * nav bar. Must stay comfortably shorter than the bar's own height or the blur never
 * reaches full strength before the screen ends.
 */
private val BlurRampHeight = 40.dp

/** Blur radius once the ramp is finished — i.e. everything behind and below the bar. */
private val BlurMaxRadius = 16.dp

/**
 * Number of cross-fade steps in the ramp. Each step is one extra GPU blur pass over
 * a band-sized buffer, so this is the main perf/smoothness dial: 2 is cheap and still
 * seam-free, 4 is smooth, above ~5 the extra steps are not visible.
 */
private const val BlurStepCount = 6

/**
 * Extra content sampled above the band. A blur near the top edge of its own buffer
 * would otherwise clamp (smear the top row downwards); the bleed gives it real
 * content to sample and is then clipped away.
 */
private val BlurEdgeBleed = 32.dp

/**
 * Size of the repeating dither tile, and the strength of one noise step.
 *
 * Blurring near-black content produces gradients so shallow that a single 8-bit step
 * spans 5-30 rows, which the eye reads as flat contour bands — a "height map" over the
 * band. Perturbing each pixel by well under one step breaks the contours up without
 * being visible as grain. This is ordinary gradient dithering, just applied to a blur.
 */
private const val DitherTile = 64
private const val DitherAlpha = 5 // out of 255, i.e. ~1 LSB of white on a near-black ground

/** One tiled noise bitmap, built once and reused as a repeating shader. */
private fun buildDitherShader(): ShaderBrush {
    val pixels = IntArray(DitherTile * DitherTile)
    val random = java.util.Random(20260830L) // fixed seed: identical every launch
    for (i in pixels.indices) {
        val a = if (random.nextBoolean()) DitherAlpha else 0
        pixels[i] = (a shl 24) or 0x00FFFFFF
    }
    val bitmap = android.graphics.Bitmap.createBitmap(
        DitherTile, DitherTile, android.graphics.Bitmap.Config.ARGB_8888,
    )
    bitmap.setPixels(pixels, 0, DitherTile, 0, 0, DitherTile, DitherTile)
    return ShaderBrush(
        ImageShader(bitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated)
    )
}

@Composable
fun NotesListScreen(
    state: NotesListScreenState,
    onIntent: (NotesListIntent) -> Unit
) {
    val spacing = LocalSpacing.current

    var showCreateFolder by remember { mutableStateOf(false) }
    var editFolderTarget by remember { mutableStateOf<FolderPreviewUi?>(null) }
    var deleteFolderTarget by remember { mutableStateOf<FolderPreviewUi?>(null) }
    var moveNoteTarget by remember { mutableStateOf<NotePreviewUi?>(null) }

    val folderRefs = remember(state.folderPreviews) {
        state.folderPreviews.map { FolderRef(it.id, it.name, it.colorArgb) }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { onIntent(NotesListIntent.AddNoteClicked) },
                shape = CircleShape,
                containerColor = AccentIndigo,
                contentColor = Color(0xFFE8E6F5),
                modifier = Modifier
                    .size(spacing.fabSize)
                    .offset(y = spacing.fabOverlapOffset)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add note",
                    modifier = Modifier.size(spacing.fabIconSize)
                )
            }
        },
        floatingActionButtonPosition = FabPosition.Center,
        bottomBar = { FloatingNavBar(state.selectedBottomBarItem, onIntent) }
    ) { innerPadding ->
        // The Scaffold body is laid out full-screen (the bar and FAB are drawn over it),
        // so the grid's own draw scope reaches the bottom edge and can host the blur band.
        // The band is anchored to the top of the nav bar, whose height (plus whatever
        // system inset it sits on) is exactly the Scaffold's bottom content padding.
        //
        // That inset alone is NOT enough clearance for the content, for two reasons:
        //  - it stops the last card exactly ON the bar's top edge, which reads as the list
        //    being clipped rather than ending;
        //  - the FAB is laid out fabSpacingAboveBar above the bar and then pulled back down by
        //    fabOverlapOffset, so it still sticks up past the bar by the remainder and would
        //    otherwise sit on top of whatever card is under it.
        // Clear the taller of the two, then add the gap. coerceAtLeast keeps this correct if the
        // offset is ever tuned past the point where the FAB no longer protrudes at all.
        val fabOverhang = (spacing.fabSpacingAboveBar + spacing.fabSize - spacing.fabOverlapOffset)
            .coerceAtLeast(0.dp)
        val bottomClearance =
            innerPadding.calculateBottomPadding() + fabOverhang + spacing.contentToNavBarGap
        LazyVerticalStaggeredGrid(
            modifier = Modifier
                .fillMaxSize()
                .progressiveBottomBlur(
                    bandHeight = innerPadding.calculateBottomPadding(),
                    background = MaterialTheme.colorScheme.background,
                ),
            columns = StaggeredGridCells.Fixed(2),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + spacing.screenVertical,
                start = innerPadding.calculateStartPadding(androidx.compose.ui.platform.LocalLayoutDirection.current) + spacing.screenHorizontal,
                end = innerPadding.calculateEndPadding(androidx.compose.ui.platform.LocalLayoutDirection.current) + spacing.screenHorizontal,
                bottom = bottomClearance
            ),
            verticalItemSpacing = spacing.interItemSpacingVertical,
            horizontalArrangement = Arrangement.spacedBy(spacing.interItemSpacingHorizontal)
        ) {
            // The Search tab is a MODE of this screen rather than its own destination. The four
            // bottom-bar tabs are already modeled as `selectedBottomBarItem` state and switch
            // without navigating, so a route would have introduced a second mechanism for the same
            // thing — and would have had to duplicate the nav bar, the FAB, the progressive blur
            // band and the note card, all of which are private to this file. Searching therefore
            // swaps the grid's content and leaves the chrome untouched.
            if (state.selectedBottomBarItem == BottomBarItem.SEARCH) {
                searchPane(state, onIntent, onLongClick = { moveNoteTarget = it })
                return@LazyVerticalStaggeredGrid
            }

            item(span = StaggeredGridItemSpan.FullLine, contentType = "header") {
                HeaderLine(state.headerLine, state.statsLine)
            }
            item(span = StaggeredGridItemSpan.FullLine, contentType = "chips") {
                // The trash and sort pills ride at the end of the chip row but OUTSIDE the chips'
                // own horizontal scroll, so they stay on screen however many folders exist. Trash
                // in particular has to be reachable without scrolling past every folder — it is
                // the only route to an undo.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.weight(1f)) {
                        FolderChips(
                            folders = state.folderPreviews,
                            selectedFolderId = state.selectedFolderId,
                            onAllClick = { state.selectedFolderId?.let { onIntent(NotesListIntent.FolderClicked(it)) } },
                            onFolderClick = { onIntent(NotesListIntent.FolderClicked(it)) },
                            onCreateFolder = { showCreateFolder = true },
                            onEditFolder = { editFolderTarget = it },
                            onDeleteFolder = { deleteFolderTarget = it },
                        )
                    }
                    TrashPill(onClick = { onIntent(NotesListIntent.TrashClicked) })
                    SortPill(
                        order = state.sortOrder,
                        onSelect = { onIntent(NotesListIntent.SortOrderSelected(it)) },
                    )
                }
            }

            if (state.pinnedPreviews.isNotEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine, contentType = "pinned_label") {
                    SectionLabel("Pinned")
                }
                item(span = StaggeredGridItemSpan.FullLine, contentType = "pinned_pager") {
                    PinnedPager(state.pinnedPreviews, onClick = { onIntent(NotesListIntent.NoteClicked(it)) }, onLongClick = { note -> moveNoteTarget = note })
                }
            }

            if (state.notePreviews.isNotEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine, contentType = "recent_label") {
                    SectionLabel("Recent")
                }
            }
            items(
                items = state.notePreviews,
                key = { it.id },
                contentType = { "note" }
            ) { note ->
                NoteCard(note, onClick = { onIntent(NotesListIntent.NoteClicked(note.id)) }, onLongClick = { moveNoteTarget = note })
            }
        }
    }

    if (showCreateFolder) {
        FolderEditDialog(
            initial = null,
            onDismiss = { showCreateFolder = false },
            onConfirm = { name, color -> onIntent(NotesListIntent.CreateFolder(name, color)); showCreateFolder = false },
        )
    }
    editFolderTarget?.let { f ->
        FolderEditDialog(
            initial = FolderRef(f.id, f.name, f.colorArgb),
            onDismiss = { editFolderTarget = null },
            onConfirm = { name, color -> onIntent(NotesListIntent.UpdateFolder(f.id, name, color)); editFolderTarget = null },
        )
    }
    deleteFolderTarget?.let { f ->
        AlertDialog(
            containerColor = SurfaceDark,
            onDismissRequest = { deleteFolderTarget = null },
            title = { Text("Delete folder?", color = TitleGrey) },
            // The copy names both halves of what happens, because only one of them is undoable:
            // the folder goes to Trash and can be restored, but its notes are unfiled immediately
            // and restoring the folder does NOT re-file them.
            text = {
                Text(
                    "\"${f.name}\" — its ${f.notesAmount} notes will move to All, " +
                        "and the folder goes to Trash.",
                    color = BodyGrey,
                )
            },
            confirmButton = { TextButton(onClick = { onIntent(NotesListIntent.DeleteFolder(f.id)); deleteFolderTarget = null }) { Text("Delete", color = AccentIndigo) } },
            dismissButton = { TextButton(onClick = { deleteFolderTarget = null }) { Text("Cancel", color = BodyGrey) } },
        )
    }
    moveNoteTarget?.let { note ->
        FolderChooser(
            folders = folderRefs,
            selectedId = note.folderId,
            onDismiss = { moveNoteTarget = null },
            onSelect = { folderId -> onIntent(NotesListIntent.MoveNoteToFolder(note.id, folderId)); moveNoteTarget = null },
        )
    }
}

/**
 * Softly blurs the bottom [bandHeight] of this composable's own content, with the blur
 * ramping in from nothing so there is no visible edge where it starts.
 *
 * How it renders. The content draws itself exactly once, into `contentLayer` (a display
 * list). That layer is then (a) played back as-is — the sharp, normal drawing — and
 * (b) replayed into [BlurStepCount] small band-sized layers, each carrying a stronger
 * `BlurEffect`. Every band is composited through a vertical alpha gradient
 * (`saveLayer` + [BlendMode.DstIn]) that reaches full opacity right where the next,
 * blurrier band starts fading in, so neighbouring radii cross-fade into one another
 * and the result is a genuine blur *gradient* rather than a stack of visible steps.
 *
 * The [background] is painted into the layer before the content so the recorded band is
 * opaque. That matters: cross-fading two opaque images of the same scene reads as a
 * focus pull, whereas cross-fading translucent copies would leave the sharp card edges
 * showing through the blurred ones as a ghosted double image.
 *
 * `Modifier.blur()` is deliberately not used — it blurs the whole content, not a band.
 * Blur radii need API 31 (`RenderEffect`); below that the `renderEffect` is ignored and
 * the band degrades to an unblurred copy of the same pixels, i.e. to nothing visible.
 */
@Composable
private fun Modifier.progressiveBottomBlur(bandHeight: Dp, background: Color): Modifier {
    val contentLayer = rememberGraphicsLayer()
    val blurLayers = List(BlurStepCount) { rememberGraphicsLayer() }
    // One reusable Paint for the saveLayer calls: this runs on every scroll frame.
    val maskPaint = remember { Paint() }
    val ditherBrush = remember { buildDitherShader() }

    // The radii never change once density is known, so configure the layers here rather
    // than churning the render nodes' properties on every frame. Radii grow quadratically:
    // the first steps stay nearly sharp, which is what keeps the top of the ramp from
    // announcing itself. `clip` keeps the bleed rows out of the drawn band.
    val density = LocalDensity.current
    remember(density, blurLayers) {
        blurLayers.forEachIndexed { step, layer ->
            val progress = (step + 1f) / BlurStepCount
            val radius = with(density) { BlurMaxRadius.toPx() } * progress * progress
            layer.renderEffect = BlurEffect(radius, radius)
            layer.clip = true
        }
    }

    return this.drawWithContent {
        contentLayer.record {
            drawRect(background)
            this@drawWithContent.drawContent()
        }
        drawLayer(contentLayer)

        val bandPx = bandHeight.toPx()
        val rampPx = BlurRampHeight.toPx()
        if (bandPx <= 0f || rampPx <= 0f || size.width < 1f || size.height < 1f) {
            return@drawWithContent
        }

        val bandTop = (size.height - bandPx).coerceAtLeast(0f)
        // Each blurred band is recorded from `sourceTop` so only the band — not the whole
        // scrolling grid — is pushed through the blur, with the bleed on top of it.
        val sourceTop = (bandTop - BlurEdgeBleed.toPx()).coerceAtLeast(0f)
        val sourceSize = IntSize(
            size.width.roundToInt(),
            (size.height - sourceTop).roundToInt(),
        )
        val bandRect = Rect(0f, bandTop, size.width, size.height)

        // Indexed loops, not forEachIndexed: these bodies run on every scroll frame.
        // Recording happens outside the clip below — the layers hold their own canvas.
        for (step in 0 until BlurStepCount) {
            blurLayers[step].record(sourceSize) {
                translate(top = -sourceTop) { drawLayer(contentLayer) }
            }
        }

        clipRect(top = bandTop) {
            for (step in 0 until BlurStepCount) {
                val layer = blurLayers[step]
                val progress = (step + 1f) / BlurStepCount
                // This step owns the slice of the ramp between its own start and the next
                // step's start; below that it stays fully opaque and simply gets covered by
                // the blurrier steps that follow.
                val fadeStart = bandTop + rampPx * (step.toFloat() / BlurStepCount)
                val fadeEnd = bandTop + rampPx * progress
                val mask = Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to Color.Black,
                    startY = fadeStart,
                    endY = fadeEnd,
                )

                // saveLayer so DstIn multiplies the blurred band by the mask's alpha only,
                // instead of punching through everything already drawn beneath it.
                drawContext.canvas.saveLayer(bandRect, maskPaint)
                translate(top = sourceTop) { drawLayer(layer) }
                drawRect(
                    brush = mask,
                    topLeft = Offset(0f, bandTop),
                    size = Size(size.width, size.height - bandTop),
                    blendMode = BlendMode.DstIn,
                )
                drawContext.canvas.restore()
            }

            // Finally dither the whole band. Everything above resolves to 8-bit, and the
            // blurred near-black content changes so slowly that one step can span 30 rows —
            // which reads as flat contour bands. Sub-step noise breaks the contours without
            // being visible as grain.
            drawRect(
                brush = ditherBrush,
                topLeft = Offset(0f, bandTop),
                size = Size(size.width, size.height - bandTop),
            )
        }
    }
}

@Composable
private fun HeaderLine(header: HeaderLineUi?, statsLine: String?) {
    val sw = LocalConfiguration.current.screenWidthDp
    Column(modifier = Modifier.padding(start = 4.dp, top = 28.dp)) {
        if (header == null) {
            Text(
                text = "Mañana",
                color = Color(0xFF888888),
                fontWeight = FontWeight.Bold,
                fontSize = (sw * 0.04f).sp,
                style = MaterialTheme.typography.titleSmall,
            )
        } else {
            val headerSize = (sw * 0.092f).sp
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = TitleGrey, fontWeight = FontWeight.Light)) {
                        append(header.prefix)
                    }
                    append("\n")
                    withStyle(SpanStyle(color = IndigoTint, fontWeight = FontWeight.Medium)) {
                        append(header.accent)
                    }
                },
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = headerSize,
                    lineHeight = headerSize * 1.05f,
                    letterSpacing = (-0.6).sp,
                ),
            )
        }
        // Permanent stats sub-line (when enabled), always beneath the motivational line.
        if (statsLine != null) {
            Text(
                text = statsLine,
                color = Color(0xFF6A6A70),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = (sw * 0.036f).sp,
                    fontWeight = FontWeight.Medium,
                ),
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderChips(
    folders: List<FolderPreviewUi>,
    selectedFolderId: String?,
    onAllClick: () -> Unit,
    onFolderClick: (String) -> Unit,
    onCreateFolder: () -> Unit,
    onEditFolder: (FolderPreviewUi) -> Unit,
    onDeleteFolder: (FolderPreviewUi) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 16.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Chip(text = "All", selected = selectedFolderId == null, onClick = onAllClick)
        folders.forEach { folder ->
            var menuOpen by remember(folder.id) { mutableStateOf(false) }
            Box {
                Chip(
                    text = folder.name,
                    selected = selectedFolderId == folder.id,
                    onClick = { onFolderClick(folder.id) },
                    onLongClick = { menuOpen = true },
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; onEditFolder(folder) })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; onDeleteFolder(folder) })
                }
            }
        }
        Chip(text = "+", selected = false, onClick = onCreateFolder)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Chip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val sw = LocalConfiguration.current.screenWidthDp
    Text(
        text = text,
        color = if (selected) Color(0xFFE0DDF2) else Color(0xFF8A8A8A),
        style = MaterialTheme.typography.bodySmall.copy(
            fontSize = (sw * 0.038f).sp,
            fontWeight = FontWeight.Medium,
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(if (selected) AccentIndigo else SurfaceDark)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/**
 * Opens Trash. Icon-only so it costs almost no width next to the sort pill, and styled as a sibling
 * of [Chip]/[SortPill] (same pill radius, same SurfaceDark ground) — but, like the sort pill, it
 * never takes the chips' selected/indigo fill, because it is a destination rather than a filter.
 */
@Composable
private fun TrashPill(onClick: () -> Unit) {
    val sw = LocalConfiguration.current.screenWidthDp
    Box(modifier = Modifier.padding(start = 8.dp)) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(SurfaceDark)
                // The Box is the tap target, so the accessible name and role live here; the Icon
                // stays contentDescription = null so it is not announced a second time.
                .clickable(onClickLabel = "Open trash", role = Role.Button, onClick = onClick)
                .padding(horizontal = 11.dp, vertical = 10.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.DeleteOutline,
                contentDescription = null,
                tint = Color(0xFF8A8A8A),
                modifier = Modifier.size((sw * 0.045f).dp),
            )
        }
    }
}

/**
 * The notes-list sort picker: a chip-sized pill showing the active order's short name, which
 * opens a menu of all three orders. Styled as a sibling of [Chip] (same pill radius, same
 * SurfaceDark ground, same type ramp) so it reads as part of the chip row - but it never takes
 * the chips' selected/indigo fill, because it is a mode switch, not another filter.
 */
@Composable
private fun SortPill(order: NotesSortOrder, onSelect: (NotesSortOrder) -> Unit) {
    val sw = LocalConfiguration.current.screenWidthDp
    var menuOpen by remember { mutableStateOf(false) }

    Box(modifier = Modifier.padding(start = 8.dp, end = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(SurfaceDark)
                // The whole Row is the tap target, so the accessible name and the button role
                // belong here - not on the Icon, which would otherwise be announced as a node of
                // its own, next to the label, with neither carrying a role.
                .clickable(onClickLabel = "Change sort order", role = Role.Button) {
                    menuOpen = true
                }
                .padding(start = 11.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Icon(
                imageVector = Icons.Default.SwapVert,
                contentDescription = null,
                tint = IndigoTint,
                modifier = Modifier.size((sw * 0.045f).dp),
            )
            Text(
                text = order.pillLabel,
                color = Color(0xFF8A8A8A),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = (sw * 0.038f).sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            containerColor = SurfaceDark,
            shape = RoundedCornerShape(14.dp),
        ) {
            NotesSortOrder.entries.forEach { candidate ->
                val active = candidate == order
                DropdownMenuItem(
                    text = {
                        Text(
                            text = candidate.menuLabel,
                            color = if (active) IndigoTint else TitleGrey,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                            ),
                        )
                    },
                    // Every row reserves the trailing slot (a blank spacer when it is not the
                    // active one) so all three labels stay left-aligned at the same x; otherwise
                    // only the checked row would carry the icon's width and the menu would
                    // re-lay-out each time the selection moved.
                    trailingIcon = {
                        if (active) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = IndigoTint,
                                modifier = Modifier.size(18.dp),
                            )
                        } else {
                            Spacer(Modifier.size(18.dp))
                        }
                    },
                    onClick = { menuOpen = false; onSelect(candidate) },
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    val sw = LocalConfiguration.current.screenWidthDp
    Text(
        text = text.uppercase(),
        color = Color(0xFF5E5E62),
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = (sw * 0.032f).sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        ),
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 10.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PinnedPager(pinned: List<NotePreviewUi>, onClick: (String) -> Unit, onLongClick: (NotePreviewUi) -> Unit) {
    val spacing = LocalSpacing.current
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val pagerState = rememberPagerState(pageCount = { pinned.size })
    val multiple = pinned.size > 1
    Column(modifier = Modifier.fillMaxWidth()) {
        // Full-bleed: requiredWidth forces the pager to the true screen width (the lazy grid would
        // otherwise clamp it to the padded slot), so a swiped card slides off the real screen edge.
        // The pager's own contentPadding then gives the card a symmetric inset.
        Box(modifier = Modifier.requiredWidth(screenWidth)) {
            HorizontalPager(
                state = pagerState,
                // One card per page (no peek): equal side insets match the screen padding, and the
                // page spacing equals the inset so the next card sits fully off-screen at rest.
                // The pager itself is full-bleed, so a swiped card slides off the real screen edge
                // instead of being clipped at the inner padding.
                pageSpacing = spacing.screenHorizontal,
                contentPadding = PaddingValues(horizontal = spacing.screenHorizontal),
                // Key pages by note id: edits now reorder the list (updatedAt sort), and without
                // identity Compose would reuse pages by index and swap card contents in place.
                key = { pinned[it].id },
            ) { page ->
                PinnedCard(pinned[page], onClick = { onClick(pinned[page].id) }, onLongClick = { onLongClick(pinned[page]) })
            }
            // Soft fade both edges into the black background (non-interactive: swipes pass through).
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to AppBlack,
                            0.05f to Color.Transparent,
                            0.95f to Color.Transparent,
                            1f to AppBlack,
                        )
                    )
            )
        }
        if (multiple) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 11.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                repeat(pinned.size) { i ->
                    val active = i == pagerState.currentPage
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 2.5.dp)
                            .height(6.dp)
                            .width(if (active) 16.dp else 6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (active) IndigoTint else Color(0xFF2E2E34))
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PinnedCard(note: NotePreviewUi, onClick: () -> Unit, onLongClick: () -> Unit) {
    val sw = LocalConfiguration.current.screenWidthDp
    val color = folderAccentColor(note.folderId, note.folderColorArgb) ?: AccentIndigo
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = color),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = note.title.ifBlank { "Untitled" },
                    color = Color.White.copy(alpha = 0.92f),
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = (sw * 0.05f).sp, fontWeight = FontWeight.Medium),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = note.content,
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = (sw * 0.036f).sp, lineHeight = (sw * 0.05f).sp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = relativeTime(note.updatedAt)
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = meta,
                        color = Color.White.copy(alpha = 0.45f),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = (sw * 0.028f).sp, fontWeight = FontWeight.Medium),
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.PushPin,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .size(14.dp),
            )
        }
    }
}

/**
 * The standard note card used by the Recent grid and, with [title]/[body] supplied, by the search
 * results. Those two default to the preview's own strings, so an ordinary call renders exactly what
 * it did before; search passes annotated copies with the matched term styled. They are whole
 * strings rather than "text + ranges" because the offsets a highlight needs are only valid against
 * the exact string being drawn (a search snippet is a window into the body, not the body).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(
    note: NotePreviewUi,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    title: AnnotatedString = AnnotatedString(note.title.ifBlank { "Untitled" }),
    body: AnnotatedString = AnnotatedString(note.content),
) {
    val sw = LocalConfiguration.current.screenWidthDp
    val titleSize = (sw * 0.043f).sp
    val bodySize = (sw * 0.034f).sp
    val bodyLine = (sw * 0.046f).sp
    val base = folderAccentColor(note.folderId, note.folderColorArgb)
    val filled = note.isPinned || note.isFavorite

    if (filled) {
        val color = base ?: AccentIndigo
        Card(
            modifier = Modifier
                .heightIn(max = 300.dp)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = color),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = title,
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = titleSize, fontWeight = FontWeight.Medium),
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = body,
                    color = Color.White.copy(alpha = 0.55f),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = bodySize, lineHeight = bodyLine),
                    overflow = TextOverflow.Ellipsis,
                )
                if (note.checklistTotal > 0) {
                    Spacer(Modifier.height(10.dp))
                    ChecklistProgress(note.checklistDone, note.checklistTotal, onColor = true)
                }
            }
        }
    } else {
        val edge = base ?: UncategorizedEdge
        val shape = RoundedCornerShape(18.dp)
        // Two stacked rounded cards: a colored one behind, and the gray content card shifted right
        // so the color peeks along the left edge and tapers as it curls around the top/bottom corners.
        Box(modifier = Modifier.heightIn(max = 300.dp)) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(shape)
                    .background(edge)
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 5.dp)
                    .combinedClickable(onClick = onClick, onLongClick = onLongClick),
                shape = shape,
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            ) {
                Column(modifier = Modifier.padding(start = 13.dp, top = 14.dp, end = 14.dp, bottom = 14.dp)) {
                    Text(
                        text = title,
                        color = TitleGrey,
                        style = MaterialTheme.typography.titleSmall.copy(fontSize = titleSize, fontWeight = FontWeight.Medium),
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = body,
                        color = BodyGrey,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = bodySize, lineHeight = bodyLine),
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (note.checklistTotal > 0) {
                        Spacer(Modifier.height(10.dp))
                        ChecklistProgress(note.checklistDone, note.checklistTotal, onColor = false)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChecklistProgress(done: Int, total: Int, onColor: Boolean) {
    val maxDots = 7
    val shown = total.coerceAtMost(maxDots)
    // When the list is longer than the dot budget, fill dots proportionally instead of literally.
    val filled = if (total <= maxDots) done else (done * maxDots) / total
    val remaining = if (onColor) Color.White.copy(alpha = 0.3f) else Color(0xFF333333)
    val labelColor = if (onColor) Color.White.copy(alpha = 0.6f) else Color(0xFF6A6A70)
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(shown) { i ->
            Box(
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (i < filled) Color(0xFF1F9E4A) else remaining)
            )
        }
        Spacer(Modifier.width(3.dp))
        Text(
            text = "$done/$total",
            color = labelColor,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Medium),
        )
    }
}

@Composable
private fun FloatingNavBar(
    selected: BottomBarItem,
    onIntent: (NotesListIntent) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(SurfaceDark),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            NavIcon(Icons.Default.Description, selected == BottomBarItem.ALL_NOTES) {
                onIntent(NotesListIntent.AllNotesClicked)
            }
            NavIcon(Icons.Default.Search, selected == BottomBarItem.SEARCH) {
                onIntent(NotesListIntent.SearchClicked)
            }
            Spacer(Modifier.width(56.dp)) // gap for the centered FAB
            NavIcon(Icons.Default.CalendarToday, selected == BottomBarItem.CALENDAR) {
                onIntent(NotesListIntent.CalendarClicked)
            }
            NavIcon(Icons.Default.Person, selected == BottomBarItem.PROFILE) {
                onIntent(NotesListIntent.ProfileClicked)
            }
        }
    }
}

@Composable
private fun NavIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, active: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (active) IndigoTint else Color(0xFF5E5E5E),
        )
    }
}


// ── Search tab ───────────────────────────────────────────────────────────────
//
// Rendered into the notes list's own staggered grid, so results sit in the same two-column
// layout, with the same padding and the same [NoteCard], as the Recent section.

/** Style applied to the matched term inside a result's title and snippet. */
private val SearchHighlightStyle = SpanStyle(
    // A white wash plus a brighter, bolder face. Both are ground-agnostic: a result card is
    // SurfaceDark when the note is neither pinned nor favorited, and a saturated category color
    // otherwise, and this reads on either without the card having to pick the style.
    color = Color(0xFFF2F0FF),
    background = Color.White.copy(alpha = 0.14f),
    fontWeight = FontWeight.Bold,
)

/**
 * [text] with [ranges] styled. The ranges are offsets into [text] itself — see [NoteSearchMatchUi],
 * whose title/snippet fields are exactly the strings drawn here. They are additionally clamped to
 * the string, because addStyle throws on an out-of-range span and a mis-placed highlight is not
 * worth a crash.
 */
private fun highlighted(text: String, ranges: List<IntRange>): AnnotatedString {
    if (ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        ranges.forEach { range ->
            val start = range.first.coerceIn(0, text.length)
            val end = (range.last + 1).coerceIn(start, text.length)
            if (end > start) addStyle(SearchHighlightStyle, start, end)
        }
    }
}

private fun LazyStaggeredGridScope.searchPane(
    state: NotesListScreenState,
    onIntent: (NotesListIntent) -> Unit,
    onLongClick: (NotePreviewUi) -> Unit,
) {
    item(span = StaggeredGridItemSpan.FullLine, contentType = "search_field") {
        SearchField(
            query = state.searchQuery,
            onQueryChange = { onIntent(NotesListIntent.SearchQueryChanged(it)) },
        )
    }

    // state.searchResults was computed for state.searchResultsQuery, which lags the field by the
    // debounce window. So an empty result list means "no matches" ONLY once the two agree; before
    // that it just means the current query has not been run yet.
    val normalized = normalizeSearchText(state.searchQuery)
    val settled = state.searchResultsQuery == normalized

    when {
        normalized.isEmpty() -> item(
            span = StaggeredGridItemSpan.FullLine,
            contentType = "search_message",
        ) {
            SearchMessage(
                headline = "Search your notes",
                detail = "Type to match titles and note text, in every folder.",
            )
        }

        state.searchResults.isEmpty() && settled -> item(
            span = StaggeredGridItemSpan.FullLine,
            contentType = "search_message",
        ) {
            SearchMessage(
                headline = "No matches",
                detail = "Nothing in your notes contains \u201C$normalized\u201D.",
            )
        }

        // Query typed, first results not back yet: draw the field alone rather than flashing an
        // empty state that the very next emission would replace.
        state.searchResults.isEmpty() -> Unit

        else -> {
            item(span = StaggeredGridItemSpan.FullLine, contentType = "search_count") {
                val n = state.searchResults.size
                SectionLabel(if (n == 1) "1 result" else "$n results")
            }
            items(
                items = state.searchResults,
                key = { it.preview.id },
                contentType = { "search_result" },
            ) { match ->
                NoteCard(
                    note = match.preview,
                    onClick = { onIntent(NotesListIntent.NoteClicked(match.preview.id)) },
                    onLongClick = { onLongClick(match.preview) },
                    // Blank titles keep the card's "Untitled" filler; a blank title cannot have
                    // matched a non-blank query, so no highlight is lost by replacing it.
                    title = if (match.title.isBlank()) AnnotatedString("Untitled")
                    else highlighted(match.title, match.titleHighlights),
                    body = highlighted(match.snippet, match.snippetHighlights),
                )
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    val sw = LocalConfiguration.current.screenWidthDp
    val textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = (sw * 0.042f).sp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 14.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(SurfaceDark)
            .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = IndigoTint,
            modifier = Modifier.size((sw * 0.05f).dp),
        )
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = textStyle.copy(color = TitleGrey),
            cursorBrush = SolidColor(AccentIndigo),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text("Search notes", color = Color(0xFF6A6A70), style = textStyle)
                }
                inner()
            },
        )
        // The clear button only exists while there is text, so the slot is reserved either way to
        // stop the text area resizing on the first and last keystroke.
        if (query.isEmpty()) {
            Spacer(Modifier.width(36.dp))
        } else {
            IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Clear search",
                    tint = Color(0xFF8A8A8A),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** The Search tab's two non-result states: nothing typed yet, and nothing found. */
@Composable
private fun SearchMessage(headline: String, detail: String) {
    val sw = LocalConfiguration.current.screenWidthDp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 40.dp),
    ) {
        Text(
            text = headline,
            color = TitleGrey,
            style = MaterialTheme.typography.titleSmall.copy(
                fontSize = (sw * 0.052f).sp,
                fontWeight = FontWeight.Medium,
            ),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = detail,
            color = Color(0xFF6A6A70),
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = (sw * 0.036f).sp,
                lineHeight = (sw * 0.05f).sp,
            ),
        )
    }
}


private fun relativeTime(ts: Long): String {
    if (ts <= 0L) return ""
    val min = (System.currentTimeMillis() - ts) / 60_000
    return when {
        min < 1 -> "just now"
        min < 60 -> "${min}m ago"
        min < 1440 -> "${min / 60}h ago"
        else -> "${min / 1440}d ago"
    }
}
