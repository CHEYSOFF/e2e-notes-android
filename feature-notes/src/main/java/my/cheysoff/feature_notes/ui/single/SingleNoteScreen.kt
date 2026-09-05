package my.cheysoff.feature_notes.ui.single

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.outlined.ArrowBackIos
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStyle
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mohamedrejeb.richeditor.model.HeadingStyle
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.onEach
import my.cheysoff.core_ui.theme.AccentIndigo
import my.cheysoff.core_ui.theme.AppBlack
import my.cheysoff.core_ui.theme.BodyGrey
import my.cheysoff.core_ui.theme.LocalSpacing
import my.cheysoff.core_ui.theme.SurfaceDark
import my.cheysoff.core_ui.theme.TitleGrey
import my.cheysoff.core_ui.theme.ToolbarDark
import my.cheysoff.core_domain.model.TrashPolicy
import my.cheysoff.core_domain.model.Folder
import my.cheysoff.core_ui.theme.folderAccentColor
import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.core_domain.model.SketchData
import my.cheysoff.core_domain.sketch.Sketch
import my.cheysoff.core_domain.sketch.StrokeCodec
import my.cheysoff.core_ui.sketch.RenderedStroke
import my.cheysoff.core_ui.sketch.SketchRenderer
import my.cheysoff.feature_notes.model.single.ChecklistItem
import my.cheysoff.feature_notes.model.single.SingleNoteIntent
import my.cheysoff.feature_notes.model.single.SingleNoteScreenState
import my.cheysoff.feature_notes.model.single.buildNoteShareText
import my.cheysoff.feature_notes.model.single.noteShareTitle
import androidx.activity.compose.BackHandler
import my.cheysoff.feature_notes.ui.folder.FolderChooser
import my.cheysoff.feature_notes.ui.folder.FolderRef
import my.cheysoff.feature_notes.ui.sketch.SketchCanvasScreen

/**
 * Quiet period after the last keystroke before the editor is serialized to HTML and forwarded to
 * the ViewModel. Only the serialization is delayed — the ViewModel keeps its own autosave debounce
 * on top, and every exit path flushes synchronously, so no edit can be left behind.
 */
private const val CONTENT_SERIALIZE_DEBOUNCE_MS = 300L

@OptIn(ExperimentalLayoutApi::class, FlowPreview::class)
@Composable
fun SingleNoteScreen(
    state: SingleNoteScreenState,
    onIntent: (SingleNoteIntent) -> Unit
) {
    val focusManager = LocalFocusManager.current
    val isImeVisible = WindowInsets.isImeVisible
    // Only used by the overflow actions, which start an activity / write the clipboard.
    val context = LocalContext.current

    val accent = remember(state.folderId, state.folders) { editorAccent(state.folderId, state.folders) }
    val richTextState = rememberRichTextState()

    // Set on every editor change and cleared whenever the HTML reaches the ViewModel. A flush is a
    // no-op while it is false, so leaving an untouched note never re-serializes it into a save (and
    // never bumps updatedAt, which orders the list). Deliberately NOT a Compose state — writing one
    // per keystroke would recompose this whole screen, which is the cost this debounce removes.
    val contentDirty = remember { AtomicBoolean(false) }
    // The lambdas below are remembered so they keep one identity for the life of the composition.
    // The compiler will not memoize them on its own — they capture RichTextState and AtomicBoolean,
    // both unstable — so unmemoized every recomposition handed EditorTopBar brand-new
    // onBack/onUndo/onRedo. (It is still not skippable: the onIntent it receives is rebuilt each
    // pass.)
    //
    // onIntent is a plain parameter that the caller rebuilds inline on each of ITS recompositions,
    // so capturing it directly inside a remember would pin the very first instance forever.
    // rememberUpdatedState keeps a holder that always points at the current one, read at call time,
    // so no intent can be stranded on a stale lambda.
    val currentOnIntent by rememberUpdatedState(onIntent)
    // Serializes the editor to HTML and hands it to the ViewModel *right now*, on the caller's
    // thread. onIntent → _state.update is synchronous, so anything invoked after this call already
    // sees the latest content.
    val flushContent = remember {
        {
            if (contentDirty.getAndSet(false)) {
                currentOnIntent(SingleNoteIntent.ContentChanged(richTextState.toHtml()))
            }
        }
    }
    // Back must flush BEFORE BackClicked: the ViewModel decides there whether to delete, save, or
    // skip, and that decision compares _state.value against the persisted row. A debounced
    // forwarding alone would let it read stale content and silently skip a real save.
    val onBack = remember {
        {
            flushContent()
            currentOnIntent(SingleNoteIntent.BackClicked)
        }
    }
    // Undo/redo flush first for the same reason back does: the body reaches the ViewModel only
    // after the serialize debounce, so without a flush the newest keystrokes would not be on the
    // history stack at all. Undo would then take back some older edit instead — and if that edit
    // was a body one, the re-seed it triggers would discard the un-forwarded characters with it.
    val onUndo = remember {
        {
            flushContent()
            currentOnIntent(SingleNoteIntent.Undo)
        }
    }
    val onRedo = remember {
        {
            flushContent()
            currentOnIntent(SingleNoteIntent.Redo)
        }
    }

    // Which sketch, if any, the full-screen canvas below is currently showing in place of this
    // editor. Null means the editor is showing; New/Existing are hoisted here (rather than into the
    // canvas itself) because opening it has to read live editor state (flushContent, the current
    // body for anchoring) that only this composable holds.
    var sketchTarget by remember { mutableStateOf<SketchEditTarget?>(null) }

    // System back must run the same flush/discard logic as the top-bar arrow — a plain nav pop
    // would skip the final save and leave an abandoned empty note behind. Disabled while the sketch
    // canvas covers the screen, so back closes the CANVAS (see the handler below it, which — being
    // composed later — takes priority whenever both are enabled) rather than popping the whole note
    // out from under it.
    BackHandler(enabled = sketchTarget == null) { onBack() }

    // Nav-away is already covered by onBack; this catches the editor vanishing because the activity
    // is *recreated* (rotation, night-mode/locale change, "don't keep activities" off) with a
    // debounce still pending. There the ViewModel is retained across the recreation, so its own
    // autosave still runs on a live viewModelScope and the HTML handed over here is persisted.
    //
    // It deliberately does NOT rescue the backgrounding case, and cannot: on ON_STOP
    // MainApplication locks the session, AppNavHost's unlocked-observer navigates to "auth" with
    // popUpTo(0) { inclusive = true }, and that clears this destination's ViewModelStore —
    // cancelling viewModelScope (and therefore the debounced write) before it can fire. The
    // passphrase is dropped by then, so the database could not be written anyway. Those edits are
    // lost, exactly as they are without this debounce; closing that hole needs a save driven from
    // ON_STOP itself, not a flush here.
    DisposableEffect(Unit) {
        onDispose { flushContent() }
    }

    // Id of a checklist item that should grab focus once it appears (set when an item is added,
    // or when one above is removed). Hoisted here so the toolbar FAB and the section can both set it.
    var focusItemId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(isImeVisible) {
        if (!isImeVisible) focusManager.clearFocus()
    }

    // Seed the editor from the stored content once the note loads, then push every subsequent
    // (user) change back as HTML. drop(1) skips the emission caused by seeding, so merely opening a
    // note doesn't trigger a save; a later edit that reverts to the original content is a fresh
    // emission and is still forwarded. The note always exists before this screen opens (it is
    // created/saved before navigation), so isLoaded reliably flips and edits are never dropped.
    //
    // Stored content is HTML for rich-editor notes, but legacy notes are raw plain text; feeding
    // such text to setHtml would parse stray "<"/">" as tags and drop characters, so plain text
    // goes through setText instead. The row's recorded contentFormat decides — never a guess at
    // the string, which used to mistake "<john@example.com>" for markup and eat it.
    //
    // The same effect also re-seeds the editor after an undo/redo of the body: the ViewModel bumps
    // contentRevision when (and only when) it replaces `content` itself, so restarting on it feeds
    // the restored body back in through the very same drop(1) path that suppresses the seeding
    // echo. contentRevision does not move when the editor reports its own content, so ordinary
    // typing never restarts this effect and never moves the cursor.
    LaunchedEffect(state.isLoaded, state.contentRevision) {
        if (state.isLoaded) {
            richTextState.config.listIndent = 18
            // The editor is about to be replaced wholesale from state, so a pending "not yet
            // forwarded" mark belongs to content that no longer exists. Clearing it stops a later
            // flush from handing this programmatic seed back as if it were the user's own edit.
            contentDirty.set(false)
            if (state.contentFormat == NoteContentFormat.HTML) {
                richTextState.setHtml(state.content)
            } else {
                richTextState.setText(state.content)
            }
            // toHtml() walks the whole document, so doing it per keystroke is main-thread work
            // that grows with the note. Debounce the *serialization* (the DB write is debounced
            // separately in the ViewModel), so a burst of typing costs one pass instead of one per
            // character — debounce already discards every emission inside the quiet window, so
            // there is nothing left for a conflate() to drop. Nothing is lost by waiting: every
            // exit path flushes synchronously above, and toHtml() is read at collection time, so
            // the value forwarded is always the editor's current content rather than the emission
            // that triggered it.
            snapshotFlow { richTextState.annotatedString }
                .drop(1)
                .onEach { contentDirty.set(true) }
                .debounce(CONTENT_SERIALIZE_DEBOUNCE_MS)
                .collect { flushContent() }
        }
    }

    // Closes the canvas outright rather than replaying its own "discard?" confirmation -- hardware
    // back is rare here (the canvas' own Cancel button is the expected route), and this only ever
    // discards a session that has not been handed to onDone yet, exactly like tapping Cancel and
    // confirming would. Composed unconditionally (only ENABLED toggles) so this registers after,
    // and therefore takes priority over, the editor's own BackHandler above whenever both exist.
    BackHandler(enabled = sketchTarget != null) { sketchTarget = null }

    if (sketchTarget != null) {
        val target = sketchTarget
        SketchCanvasScreen(
            initialSketch = (target as? SketchEditTarget.Existing)?.sketch,
            onDone = { sketch ->
                onIntent(SingleNoteIntent.SketchSaved((target as? SketchEditTarget.Existing)?.id, sketch))
                sketchTarget = null
            },
            onCancel = { sketchTarget = null },
        )
        return
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        topBar = {
            EditorTopBar(
                isPinned = state.isPinned,
                isFavorite = state.isFavorite,
                accent = accent,
                // The ViewModel owns the history for the whole editor — title, body and checklist
                // in one stack — so these no longer read RichTextState's own (body-only) history.
                canUndo = state.canUndo,
                canRedo = state.canRedo,
                onUndo = onUndo,
                onRedo = onRedo,
                onBack = onBack,
                onIntent = onIntent,
                // Flush first: the ViewModel builds the copy from _state, and ContentChanged
                // updates _state synchronously, so by the time DuplicateNote is handled the body
                // it reads is the one on screen rather than one debounce behind.
                onDuplicate = {
                    flushContent()
                    currentOnIntent(SingleNoteIntent.DuplicateNote)
                },
                // These two read the note through noteAsPlainText() at click time instead. They
                // deliberately do NOT go via a flush + `state`: a flush only reaches the
                // ViewModel, and this composition still holds the pre-flush `state`, so reading
                // it here would export the note as it was up to a debounce ago.
                onCopyText = { copyNoteToClipboard(context, noteAsPlainText(state, richTextState)) },
                onShare = { shareNote(context, state.title, noteAsPlainText(state, richTextState)) },
            )
        },
        floatingActionButton = {
            FormattingToolbar(
                richTextState = richTextState,
                accent = accent,
                onAddChecklistItem = {
                    val id = UUID.randomUUID().toString()
                    focusItemId = id
                    onIntent(SingleNoteIntent.ChecklistItemAdded(id, null))
                },
                // Flushed first, exactly like onDuplicate: a brand-new sketch is anchored at the
                // note's CURRENT block count (SingleNoteViewModel.saveSketch), which must count the
                // text as it stands right now rather than as of up to a debounce ago.
                onAddSketch = {
                    flushContent()
                    sketchTarget = SketchEditTarget.New
                },
            )
        },
        floatingActionButtonPosition = androidx.compose.material3.FabPosition.Center,
        containerColor = AppBlack,
    ) { paddingValues ->
        NoteEditor(
            state = state,
            richTextState = richTextState,
            accent = accent,
            focusItemId = focusItemId,
            onSetFocusItem = { focusItemId = it },
            onIntent = onIntent,
            // Decoded here rather than in SketchSection: this is where sketchTarget (hoisted to
            // this composable) actually lives, and StrokeCodec.decode is the same one the canvas
            // itself uses to read it back, so a sketch that fails to decode simply cannot be
            // reopened rather than crashing the tap.
            onSketchTapped = { sketchData ->
                StrokeCodec.decode(sketchData.strokes)?.let { sketch ->
                    sketchTarget = SketchEditTarget.Existing(sketchData.id, sketch)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        )
    }
}

@Composable
private fun NoteEditor(
    state: SingleNoteScreenState,
    richTextState: RichTextState,
    accent: Color,
    focusItemId: String?,
    onSetFocusItem: (String?) -> Unit,
    onIntent: (SingleNoteIntent) -> Unit,
    onSketchTapped: (SketchData) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val sw = LocalConfiguration.current.screenWidthDp
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()
    var showFolderChooser by remember { mutableStateOf(false) }
    val currentFolder = state.folders.firstOrNull { it.id == state.folderId }

    val titleStyle = MaterialTheme.typography.titleLarge.copy(
        fontWeight = FontWeight.Normal,
        fontSize = (sw * 0.088f).sp,
        lineHeight = (sw * 0.098f).sp,
    )
    val bodyStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = (sw * 0.042f).sp)
    // Word count over the plain text (not the HTML), recomputed only when the text changes.
    val wordCount = remember(richTextState.annotatedString) {
        countWords(richTextState.annotatedString.text)
    }

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(horizontal = spacing.screenHorizontal),
    ) {
        BasicTextField(
            value = state.title,
            onValueChange = { onIntent(SingleNoteIntent.TitleChanged(it)) },
            textStyle = titleStyle.copy(color = TitleGrey),
            cursorBrush = SolidColor(accent),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (state.title.isEmpty()) {
                    Text("Title", style = titleStyle, color = Color(0xFF4A4A50))
                }
                inner()
            },
        )

        Text(
            text = metaLine(state.updatedAt, wordCount),
            color = Color(0xFF5E5E62),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = (sw * 0.03f).sp, fontWeight = FontWeight.Medium),
            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(bottom = 14.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(Color(0xFF1C1C22))
                .clickable { showFolderChooser = true }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Folder,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = currentFolder?.name ?: "Add to folder",
                color = if (currentFolder != null) TitleGrey else BodyGrey,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = (sw * 0.032f).sp, fontWeight = FontWeight.Medium),
            )
        }

        BasicRichTextEditor(
            state = richTextState,
            textStyle = bodyStyle.copy(color = Color(0xFFB9B9BD)),
            cursorBrush = SolidColor(accent),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (richTextState.annotatedString.isEmpty()) {
                    Text("Start writing…", style = bodyStyle, color = Color(0xFF4A4A50))
                }
                inner()
            },
        )

        ChecklistSection(
            items = state.checklist,
            accent = accent,
            textStyle = bodyStyle,
            focusItemId = focusItemId,
            onSetFocusItem = onSetFocusItem,
            onIntent = onIntent,
        )

        SketchSection(
            sketches = state.sketches,
            onTapped = onSketchTapped,
            onIntent = onIntent,
        )

        Spacer(modifier = Modifier.height(140.dp))

        if (showFolderChooser) {
            FolderChooser(
                folders = state.folders.map { FolderRef(it.id, it.name, it.colorArgb) },
                selectedId = state.folderId,
                onDismiss = { showFolderChooser = false },
                onSelect = { folderId -> onIntent(SingleNoteIntent.SetFolder(folderId)); showFolderChooser = false },
            )
        }
    }
}

@Composable
private fun ChecklistSection(
    items: List<ChecklistItem>,
    accent: Color,
    textStyle: TextStyle,
    focusItemId: String?,
    onSetFocusItem: (String?) -> Unit,
    onIntent: (SingleNoteIntent) -> Unit,
) {
    if (items.isEmpty()) return

    Column(modifier = Modifier.padding(top = 22.dp)) {
        items.forEachIndexed { index, item ->
            val requester = remember(item.id) { FocusRequester() }
            // When this item is the pending focus target, grab focus once and clear the request.
            LaunchedEffect(focusItemId) {
                if (focusItemId == item.id) {
                    requester.requestFocus()
                    onSetFocusItem(null)
                }
            }
            val rowStyle = textStyle.copy(
                color = if (item.isDone) Color(0xFF5E5E62) else Color(0xFFB9B9BD),
                textDecoration = if (item.isDone) TextDecoration.LineThrough else null,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .then(
                            if (item.isDone) Modifier.background(accent)
                            else Modifier.border(2.dp, Color(0xFF44444C), CircleShape)
                        )
                        .clickable { onIntent(SingleNoteIntent.ChecklistItemToggled(item.id)) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (item.isDone) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Done",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                BasicTextField(
                    value = item.text,
                    onValueChange = { onIntent(SingleNoteIntent.ChecklistItemTextChanged(item.id, it)) },
                    textStyle = rowStyle,
                    cursorBrush = SolidColor(accent),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = {
                        val id = UUID.randomUUID().toString()
                        onSetFocusItem(id)
                        onIntent(SingleNoteIntent.ChecklistItemAdded(id, item.id))
                    }),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(requester)
                        // Backspace on an empty item removes it and moves focus to the item above.
                        .onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.key == Key.Backspace && item.text.isEmpty()) {
                                items.getOrNull(index - 1)?.let { onSetFocusItem(it.id) }
                                onIntent(SingleNoteIntent.ChecklistItemRemoved(item.id))
                                true
                            } else {
                                false
                            }
                        },
                    decorationBox = { inner ->
                        if (item.text.isEmpty()) {
                            Text("List item", style = rowStyle, color = Color(0xFF4A4A50))
                        }
                        inner()
                    },
                )
            }
        }
    }
}

/**
 * Which sketch the full-screen canvas ([SketchCanvasScreen]) is currently editing, if any. Held by
 * [SingleNoteScreen] rather than by this section, because opening it has to flush the pending body
 * edit first (see the toolbar's `onAddSketch`) -- something only the top-level composable can do.
 */
private sealed interface SketchEditTarget {
    data object New : SketchEditTarget
    data class Existing(val id: String, val sketch: Sketch) : SketchEditTarget
}

/**
 * One card per sketch, below the note's text -- never interleaved with it. See
 * docs/design/sketch-blocks.md's 2026-09-05 amendment for why: the body is one
 * `BasicRichTextEditor`, and there is no way to host a composable *between* two of its paragraphs
 * without either a marker inside the serialized HTML (silently orphaned by a sketch-unaware build)
 * or splitting the body into several editors (breaks cursor movement, undo history and saving).
 *
 * [sketches] arrives already ordered by anchor then id ([sortSketches]) -- this just renders them
 * in that order, mirroring [ChecklistSection]'s shape: a private section below the body, an early
 * return when there is nothing to show, one row per item.
 *
 * Each card is exactly the drawing's own aspect ratio (`Modifier.aspectRatio`, fit to the note's
 * width), so [SketchRenderer] never has to letterbox it -- it only would if this box's shape
 * disagreed with the sketch's own, which it cannot. Tapping a card reopens [SketchCanvasScreen] on
 * that drawing (via [onTapped]); the small corner button deletes it.
 *
 * A [SketchData] whose `strokes` fails to decode is skipped rather than shown broken or crashing
 * the row -- the same "never throws, just loses position/rendering, never existence" posture
 * `NoteBlocks` documents for a corrupt anchor.
 */
@Composable
private fun SketchSection(
    sketches: List<SketchData>,
    onTapped: (SketchData) -> Unit,
    onIntent: (SingleNoteIntent) -> Unit,
) {
    if (sketches.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        sketches.forEach { sketchData ->
            val sketch = remember(sketchData.strokes) { StrokeCodec.decode(sketchData.strokes) }
            if (sketch != null && sketch.width > 0 && sketch.height > 0) {
                SketchCard(
                    sketch = sketch,
                    onTapped = { onTapped(sketchData) },
                    onDeleted = { onIntent(SingleNoteIntent.SketchDeleted(sketchData.id)) },
                )
            }
        }
    }
}

@Composable
private fun SketchCard(sketch: Sketch, onTapped: () -> Unit, onDeleted: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(sketch.width.toFloat() / sketch.height.toFloat())
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1C1C22))
            .clickable(onClick = onTapped),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            SketchRenderer.render(sketch, size).forEach { rendered -> drawSketchStroke(rendered) }
        }
        IconButton(onClick = onDeleted, modifier = Modifier.align(Alignment.TopEnd)) {
            Icon(
                imageVector = Icons.Outlined.DeleteOutline,
                contentDescription = "Delete drawing",
                tint = BodyGrey,
            )
        }
    }
}

/** The same DrawScope calls [SketchCanvasScreen]'s own drawing surface uses for a [RenderedStroke]
 * -- [SketchRenderer] already did the one piece of geometry both places have to agree on (the
 * mapping and smoothing); this is just handing its output to `drawPath`/`drawCircle`. */
private fun DrawScope.drawSketchStroke(rendered: RenderedStroke) {
    if (rendered.isDot) {
        drawCircle(color = rendered.color, radius = rendered.strokeWidthPx / 2f, center = rendered.dotCenter)
    } else {
        drawPath(
            path = rendered.path,
            color = rendered.color,
            style = DrawStyle(width = rendered.strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

@Composable
private fun EditorTopBar(
    isPinned: Boolean,
    isFavorite: Boolean,
    accent: Color,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onBack: () -> Unit,
    onIntent: (SingleNoteIntent) -> Unit,
    onDuplicate: () -> Unit,
    onCopyText: () -> Unit,
    onShare: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .fillMaxWidth()
            .padding(horizontal = spacing.screenHorizontal - 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopIcon(Icons.AutoMirrored.Outlined.ArrowBackIos, "Back", TitleGrey) { onBack() }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TopIcon(Icons.AutoMirrored.Filled.Undo, "Undo", if (canUndo) accent else BodyGrey, enabled = canUndo) { onUndo() }
            TopIcon(Icons.AutoMirrored.Filled.Redo, "Redo", if (canRedo) accent else BodyGrey, enabled = canRedo) { onRedo() }
            TopIcon(
                Icons.Outlined.PushPin,
                "Pin",
                if (isPinned) accent else BodyGrey,
            ) { onIntent(SingleNoteIntent.TogglePin) }
            TopIcon(
                if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                "Favorite",
                if (isFavorite) accent else BodyGrey,
            ) { onIntent(SingleNoteIntent.ToggleFavorite) }
            OverflowMenu(
                accent = accent,
                onDuplicate = onDuplicate,
                onCopyText = onCopyText,
                onShare = onShare,
                onIntent = onIntent,
            )
        }
    }
}

/**
 * The top bar's three-dot menu.
 *
 * Styled after the notes list's sort menu — same [SurfaceDark] ground, same 14.dp radius, same
 * [TitleGrey] labels — so the app has one menu look rather than two. It departs from that menu in
 * one place: the row icons take the note's [accent] (the folder color, indigo when unfiled),
 * because every other affordance in this editor already does.
 *
 * "Move to Trash" is the only route a user has to deleting a note; Trash then offers Restore and
 * Delete forever. It is separated from the rest by a divider and confirms before acting, because
 * it is the one row here that changes what is in the library rather than just copying it out.
 * The dialog says "Trash", not "delete", because that is what the intent does — the note keeps its
 * content and is restorable for [TrashPolicy.RETENTION_DAYS] days.
 *
 * Menu state is local because it is a property of this bar being on screen: the ViewModel is
 * retained across configuration changes and would otherwise re-open the menu after a rotation.
 */
@Composable
private fun OverflowMenu(
    accent: Color,
    onDuplicate: () -> Unit,
    onCopyText: () -> Unit,
    onShare: () -> Unit,
    onIntent: (SingleNoteIntent) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    // A DropdownMenu positions itself against the layout node it sits in, so it is wrapped in a
    // Box together with the button it belongs to. That is the anchoring pattern Material's own
    // docs use, and it is what lines the menu up under the three-dot icon.
    Box {
        TopIcon(Icons.Outlined.MoreVert, "More options", if (open) accent else BodyGrey) {
            open = true
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = SurfaceDark,
            shape = RoundedCornerShape(14.dp),
        ) {
            OverflowItem(Icons.Outlined.FileCopy, "Duplicate", accent) { open = false; onDuplicate() }
            OverflowItem(Icons.Outlined.ContentCopy, "Copy text", accent) { open = false; onCopyText() }
            OverflowItem(Icons.Outlined.Share, "Share", accent) { open = false; onShare() }
            HorizontalDivider(color = ToolbarDark)
            // Tinted BodyGrey rather than the note's accent: this row is not one of the copy-out
            // actions above it and should not read as another of them.
            OverflowItem(Icons.Outlined.DeleteOutline, "Move to Trash", BodyGrey) {
                open = false
                confirmDelete = true
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            containerColor = SurfaceDark,
            onDismissRequest = { confirmDelete = false },
            title = { Text("Move to Trash?", color = TitleGrey) },
            text = {
                Text(
                    "You can restore it from Trash for the next ${TrashPolicy.RETENTION_DAYS} days.",
                    color = BodyGrey,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onIntent(SingleNoteIntent.DeleteNote)
                }) { Text("Move to Trash", color = AccentIndigo) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = BodyGrey) }
            },
        )
    }
}

@Composable
private fun OverflowItem(icon: ImageVector, label: String, accent: Color, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                color = TitleGrey,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        // Null description on purpose: the label beside it already names the row, and a described
        // icon would have the action announced twice.
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
        },
        onClick = onClick,
    )
}

/**
 * The note as text for the clipboard and for share targets.
 *
 * The body comes from the rich-text editor's own `annotatedString.text` — the characters currently
 * on screen — rather than from `state.content`, which holds HTML for every note the editor has
 * written to. Nothing here parses that HTML: taking the editor's text avoids the round trip
 * entirely, and it is the same source the word count in the meta line already uses.
 */
private fun noteAsPlainText(state: SingleNoteScreenState, richTextState: RichTextState): String =
    buildNoteShareText(
        title = state.title,
        plainBody = richTextState.annotatedString.text,
        checklist = state.checklist,
    )

/**
 * Puts [text] on the system clipboard.
 *
 * Android 13 (TIRAMISU) and up show their own confirmation UI for every clipboard write, so an app
 * toast there would be a second, redundant one — hence the version check. Below 13 nothing else
 * tells the user anything happened.
 */
private fun copyNoteToClipboard(context: Context, text: String) {
    if (text.isEmpty()) {
        Toast.makeText(context, "Nothing to copy — this note is empty", Toast.LENGTH_SHORT).show()
        return
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Note", text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }
}

/** Hands the note to any app that accepts plain text, through the system share sheet. */
private fun shareNote(context: Context, title: String, text: String) {
    if (text.isEmpty()) {
        Toast.makeText(context, "Nothing to share — this note is empty", Toast.LENGTH_SHORT).show()
        return
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        // text/plain, and the body is plain text: a share that emitted the stored "<p>…</p>" would
        // paste raw markup into whatever the user picked.
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, noteShareTitle(title))
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Share note"))
}

@Composable
private fun TopIcon(icon: ImageVector, desc: String, tint: Color, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(imageVector = icon, contentDescription = desc, tint = tint)
    }
}

@Composable
private fun FormattingToolbar(
    richTextState: RichTextState,
    accent: Color,
    onAddChecklistItem: () -> Unit,
    onAddSketch: () -> Unit,
) {
    var showStyles by remember { mutableStateOf(false) }
    val inactive = Color(0xFF9A9A9E)
    val border = Color(0xFF24242C)

    val current = richTextState.currentSpanStyle
    val isBold = (current.fontWeight?.weight ?: 0) >= FontWeight.Bold.weight
    val isItalic = current.fontStyle == FontStyle.Italic
    val isList = richTextState.isUnorderedList
    val activeHeading = richTextState.currentHeadingStyle

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (showStyles) {
            StylePopover(accent = accent, active = activeHeading) { level ->
                richTextState.setHeadingStyle(level)
                showStyles = false
            }
            Spacer(Modifier.height(10.dp))
        }
        Row(
            modifier = Modifier
                .shadow(12.dp, RoundedCornerShape(percent = 50))
                .clip(RoundedCornerShape(percent = 50))
                .background(ToolbarDark)
                .border(1.dp, border, RoundedCornerShape(percent = 50))
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolIcon(Icons.Outlined.TextFields, "Text style", if (showStyles) accent else inactive) {
                showStyles = !showStyles
            }
            ToolIcon(Icons.Outlined.FormatBold, "Bold", if (isBold) accent else inactive) {
                richTextState.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
            }
            ToolIcon(Icons.Outlined.FormatItalic, "Italic", if (isItalic) accent else inactive) {
                richTextState.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic))
            }
            ToolIcon(Icons.AutoMirrored.Outlined.FormatListBulleted, "List", if (isList) accent else inactive) {
                richTextState.toggleUnorderedList()
            }
            ToolIcon(Icons.Outlined.Checklist, "Checklist", inactive) { onAddChecklistItem() }
            ToolIcon(Icons.Outlined.Brush, "Add drawing", inactive) { onAddSketch() }
        }
    }
}

/**
 * Heading levels offered by the "Aa" menu, applied as native paragraph headings
 * (rc14 `setHeadingStyle`): exclusive per paragraph and persisted as semantic <h1>..<h3> HTML.
 * The third value is a compressed preview size for the menu row. H4–H6 are intentionally
 * omitted — H4 renders ~like H3 and H5/H6 fall below body size.
 */
private val headingOptions = listOf(
    Triple("H1", HeadingStyle.H1, 19.sp),
    Triple("H2", HeadingStyle.H2, 16.sp),
    Triple("H3", HeadingStyle.H3, 14.sp),
    Triple("Body", HeadingStyle.Normal, 13.sp),
)

@Composable
private fun ToolIcon(icon: ImageVector, desc: String, tint: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) {
        Icon(imageVector = icon, contentDescription = desc, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun StylePopover(accent: Color, active: HeadingStyle, onSelect: (HeadingStyle) -> Unit) {
    Column(
        modifier = Modifier
            .shadow(12.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(ToolbarDark)
            .border(1.dp, Color(0xFF24242C), RoundedCornerShape(16.dp))
            .width(170.dp)
            .padding(6.dp),
    ) {
        headingOptions.forEach { (label, level, previewSize) ->
            val isActive = level == active
            Text(
                text = label,
                color = if (isActive) accent else TitleGrey,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = previewSize,
                    fontWeight = if (level != HeadingStyle.Normal) FontWeight.Bold else FontWeight.Normal,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .then(if (isActive) Modifier.background(accent.copy(alpha = 0.15f)) else Modifier)
                    .clickable { onSelect(level) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

/** Editor accent = the folder's chosen color (or the hash fallback), or default indigo when it has no folder. */
private fun editorAccent(folderId: String?, folders: List<Folder>): Color {
    if (folderId.isNullOrBlank()) return AccentIndigo
    val colorArgb = folders.find { it.id == folderId }?.colorArgb
    return folderAccentColor(folderId, colorArgb) ?: AccentIndigo
}

private fun metaLine(updatedAt: Long, words: Int): String {
    val wordLabel = if (words == 1) "1 word" else "$words words"
    val rel = relativeTime(updatedAt)
    return if (rel.isEmpty()) wordLabel else "Edited $rel · $wordLabel"
}

/** Counts words without allocating a list/regex (single pass over the chars). */
private fun countWords(text: String): Int {
    var count = 0
    var inWord = false
    for (c in text) {
        if (c.isWhitespace()) {
            inWord = false
        } else if (!inWord) {
            inWord = true
            count++
        }
    }
    return count
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
