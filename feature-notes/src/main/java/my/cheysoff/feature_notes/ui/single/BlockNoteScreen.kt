package my.cheysoff.feature_notes.ui.single

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatStrikethrough
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import my.cheysoff.core_blocks.BlockText
import my.cheysoff.core_blocks.BlockType
import my.cheysoff.core_blocks.BlocksSave
import my.cheysoff.core_blocks.InlineStyle
import my.cheysoff.core_domain.model.AttachmentData
import my.cheysoff.core_domain.model.AttachmentPreview
import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.core_domain.model.SketchData
import my.cheysoff.core_domain.sketch.DisplaySketch
import my.cheysoff.core_domain.sketch.Sketch
import my.cheysoff.core_domain.sketch.sketchesForDisplay
import my.cheysoff.core_editor.ui.BlockEditor
import my.cheysoff.core_editor.ui.BlockEditorController
import my.cheysoff.core_ui.attachment.AttachmentThumbnail
import my.cheysoff.core_ui.sketch.SketchRenderer
import my.cheysoff.core_ui.theme.AppBlack
import my.cheysoff.core_ui.theme.BodyGrey
import my.cheysoff.core_ui.theme.LocalSpacing
import my.cheysoff.core_ui.theme.TitleGrey
import my.cheysoff.core_ui.theme.ToolbarDark
import my.cheysoff.feature_notes.model.single.OpenedNoteDocument
import my.cheysoff.feature_notes.model.single.SingleNoteIntent
import my.cheysoff.feature_notes.model.single.SingleNoteScreenState
import my.cheysoff.feature_notes.model.single.buildNoteShareText
import my.cheysoff.feature_notes.ui.attachment.AttachmentViewerScreen
import my.cheysoff.feature_notes.ui.folder.FolderChooser
import my.cheysoff.feature_notes.ui.folder.FolderRef
import my.cheysoff.feature_notes.ui.sketch.SketchCanvasScreen

/**
 * The note screen: the title over the shared [BlockEditor], which holds the note's text,
 * checklists, pictures and drawings in one field, with the top bar ([EditorTopBar]), folder chip,
 * sketch canvas and photo viewer around it.
 *
 * - **One note id, one document.** The editor gets the id and the document from the same
 *   [OpenedNoteDocument], and the controller is keyed on that id, so no document can reach the
 *   editor of another note.
 * - **Saving.** Every local edit goes to the ViewModel with the controller's base read at that
 *   moment ([SingleNoteIntent.BlocksChanged]); the ViewModel debounces and saves as BLOCKS. A
 *   pending save is flushed on back, when this screen leaves composition and on `ON_STOP`, after
 *   which Android may kill the process without another callback.
 * - **Pictures and drawings** are written as records by the ViewModel first (photo picker,
 *   keyboard image commit, sketch canvas) and then inserted at the cursor here, from
 *   [SingleNoteScreenState.picturesToPlace]. Tapping one opens the photo viewer or the canvas;
 *   their Delete removes the block through the editor ([BlockEditorController.removePicture]), an
 *   undoable local edit whose save then soft-deletes the record.
 * - **An unreadable body** (BLOCKS this build cannot decode) is shown read-only under a notice and
 *   never given to the editor, so nothing can be typed into it or saved over it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BlockNoteScreen(
    state: SingleNoteScreenState,
    onIntent: (SingleNoteIntent) -> Unit,
    loadAttachment: suspend (String) -> AttachmentData?,
    onColorMixed: (Long) -> Unit,
    editorController: (noteId: String) -> BlockEditorController,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val focusManager = LocalFocusManager.current
    val isImeVisible = WindowInsets.isImeVisible
    val accent = remember(state.folderId, state.folders) { editorAccent(state.folderId, state.folders) }
    val currentOnIntent by rememberUpdatedState(onIntent)

    val opened = state.blockDocument
    // One controller per note, keyed on its id, from the ViewModel so it survives rotation (undo
    // history and unsaved typing kept). Taken here, above the canvas and viewer branches below
    // (each returns and so unmounts the editor), because both delete through it.
    val controller = opened?.let { remember(it.noteId) { editorController(it.noteId) } }
    val loaded = controller?.isLoaded() == true
    // Pictures and drawings may be added only to a readable body whose editor has opened it; the
    // ViewModel refuses an import otherwise too, before writing anything.
    val canPlace = opened != null && opened.unreadableBody == null && loaded

    var sketchTarget by remember { mutableStateOf<SketchEditTarget?>(null) }
    var viewingAttachmentId by remember { mutableStateOf<String?>(null) }
    val editorScrollState = rememberScrollState()

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) currentOnIntent(SingleNoteIntent.ImportAttachment(uri.toString())) }

    // Locked while the canvas is open: a rotation would throw away a drawing in progress.
    val isCanvasOpen = sketchTarget != null
    DisposableEffect(isCanvasOpen) {
        if (isCanvasOpen) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        onDispose {
            if (isCanvasOpen) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    BackHandler(enabled = sketchTarget == null && viewingAttachmentId == null) {
        currentOnIntent(SingleNoteIntent.BackClicked)
    }

    // Flush a debounced save when the app goes to the background (ON_STOP: the process may be
    // killed from here on with no further callback, and MainApplication's lock clears this
    // destination right after) and when this screen leaves composition. The ViewModel runs the
    // write NonCancellable, so the back stack being cleared under it does not cut it short.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) currentOnIntent(SingleNoteIntent.FlushPendingSave)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            currentOnIntent(SingleNoteIntent.FlushPendingSave)
        }
    }

    LaunchedEffect(isImeVisible) {
        if (!isImeVisible) focusManager.clearFocus()
    }

    // Places each written record's block at the cursor, once the editor has opened the note. The
    // ViewModel keeps the record out of the assembled document until PicturePlaced, so it is not
    // appended at the end first.
    val toPlace = state.picturesToPlace
    LaunchedEffect(controller, loaded, toPlace) {
        if (controller == null || !loaded) return@LaunchedEffect
        for (placement in toPlace) {
            controller.insertPicture(placement.recordId, placement.kind)
            currentOnIntent(SingleNoteIntent.PicturePlaced(placement.recordId))
        }
    }

    sketchTarget?.let { target ->
        val existing = target as? SketchEditTarget.Existing
        SketchCanvasScreen(
            initialSketch = existing?.sketch,
            recentColors = state.recentSketchColors,
            onColorMixed = onColorMixed,
            onDone = { sketch ->
                onIntent(SingleNoteIntent.SketchSaved(existing?.id, sketch))
                sketchTarget = null
            },
            onCancel = { sketchTarget = null },
            // The drawing's block goes, through the editor, as an undoable edit; its save then
            // soft-deletes the record. Deleting the record here instead would be undone by the
            // next save, which still shows the block.
            onDelete = existing?.let { drawing ->
                {
                    controller?.removePicture(drawing.id)
                    sketchTarget = null
                }
            },
        )
        return
    }

    viewingAttachmentId?.let { id ->
        AttachmentViewerScreen(
            attachments = state.attachments,
            initialAttachmentId = id,
            loadAttachment = loadAttachment,
            onClose = { viewingAttachmentId = null },
            // As for a drawing above: the block, through the editor. If no block shows the record,
            // nothing is deleted.
            onDeleted = { deletedId ->
                controller?.removePicture(deletedId)
                viewingAttachmentId = null
            },
        )
        return
    }

    // The note as on screen: the editor's blocks once it has loaded a readable body, else the
    // stored row. Read at click time, not captured.
    val noteText = {
        if (controller != null && canPlace) {
            buildNoteShareText(
                title = state.title,
                content = BlocksSave.encodeForSave(controller.blocks),
                format = NoteContentFormat.BLOCKS,
                checklist = emptyList(),
            )
        } else {
            buildNoteShareText(state.title, state.content, state.contentFormat, state.checklist)
        }
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
                // The block editor's own history; the ViewModel keeps none on this path.
                canUndo = controller?.canUndo == true,
                canRedo = controller?.canRedo == true,
                onUndo = { controller?.undo() },
                onRedo = { controller?.redo() },
                onBack = { currentOnIntent(SingleNoteIntent.BackClicked) },
                onIntent = onIntent,
                onDuplicate = { currentOnIntent(SingleNoteIntent.DuplicateNote) },
                onCopyText = { copyNoteToClipboard(context, noteText()) },
                onShare = { shareNote(context, state.title, noteText()) },
            )
        },
        floatingActionButton = {
            BlockToolbar(
                controller = controller,
                accent = accent,
                enabled = canPlace,
                isImportingAttachment = state.isImportingAttachment,
                onAddDrawing = { if (canPlace) sketchTarget = SketchEditTarget.New },
                onAddPhoto = {
                    if (canPlace) {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                },
            )
        },
        floatingActionButtonPosition = androidx.compose.material3.FabPosition.Center,
        containerColor = AppBlack,
    ) { paddingValues ->
        BlockNoteBody(
            state = state,
            opened = opened,
            controller = controller,
            editable = canPlace,
            accent = accent,
            onIntent = onIntent,
            onOpenPicture = { viewingAttachmentId = it },
            onOpenDrawing = { id, sketch -> sketchTarget = SketchEditTarget.Existing(id, sketch) },
            onImageCommitted = { uri -> currentOnIntent(SingleNoteIntent.ImportAttachment(uri)) },
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(editorScrollState),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BlockNoteBody(
    state: SingleNoteScreenState,
    opened: OpenedNoteDocument?,
    controller: BlockEditorController?,
    editable: Boolean,
    accent: Color,
    onIntent: (SingleNoteIntent) -> Unit,
    onOpenPicture: (String) -> Unit,
    onOpenDrawing: (String, Sketch) -> Unit,
    onImageCommitted: (uri: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val sw = LocalConfiguration.current.screenWidthDp
    val focusManager = LocalFocusManager.current
    var showFolderChooser by remember { mutableStateOf(false) }
    val currentFolder = state.folders.firstOrNull { it.id == state.folderId }

    val titleStyle = MaterialTheme.typography.titleLarge.copy(
        fontWeight = FontWeight.Normal,
        fontSize = (sw * 0.088f).sp,
        lineHeight = (sw * 0.098f).sp,
    )
    val bodyStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = (sw * 0.042f).sp)
    val unreadable = opened?.unreadableBody
    // Over the editor's blocks as shown (snapshot state), so it follows typing and synced changes.
    val wordCount by remember(controller, unreadable) {
        derivedStateOf {
            if (unreadable != null) countWords(unreadable)
            else countWords(BlockText.plain(controller?.blocks.orEmpty()))
        }
    }
    val currentOnImageCommitted by rememberUpdatedState(onImageCommitted)
    // A keyboard's image (Gboard stickers, GIF keyboard, a screenshot from the clipboard strip):
    // each image item is imported like a picked photo; anything else goes on to the field.
    val imageReceiver = remember {
        ReceiveContentListener { content -> receiveImages(content) { currentOnImageCommitted(it) } }
    }

    Column(modifier = modifier.padding(horizontal = spacing.screenHorizontal)) {
        BasicTextField(
            value = state.title,
            onValueChange = { title ->
                onIntent(SingleNoteIntent.TitleChanged(title))
                // A title save writes the body too, so it is given the body the editor shows, with
                // the editor's base: the stored body can trail an unechoed block save, and a
                // leftover legacy checklist exists only in the editor until a block save.
                if (controller != null && opened != null && editable) {
                    onIntent(SingleNoteIntent.BlocksChanged(opened.noteId, controller.blocks, controller.base))
                }
            },
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

        when {
            // The first assembly is a store read away; the editor appears with it.
            opened == null || controller == null -> Unit
            unreadable != null -> UnreadableBody(unreadable, bodyStyle)
            else -> BlockEditor(
                noteId = opened.noteId,
                document = opened.document,
                onChange = { blocks -> onIntent(SingleNoteIntent.BlocksChanged(opened.noteId, blocks, controller.base)) },
                pictureContent = { recordId, kind ->
                    NotePicture(recordId, kind, state.attachments, state.sketches, onOpenPicture, onOpenDrawing)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    // On an ancestor of the editor's field: the field advertises the image types
                    // to the keyboard and hands a committed image here.
                    .contentReceiver(imageReceiver),
                controller = controller,
                selectionAccent = accent,
                textStyle = bodyStyle.copy(color = Color(0xFFB9B9BD)),
            )
        }

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

/**
 * The image items of [content], each handed to [onImage] as its content uri; returns the rest
 * (text, say) for the field, or null when nothing is left. An image the keyboard sends is a
 * `content://` uri it has granted read access to; the import reads it like a picked photo.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun receiveImages(content: TransferableContent, onImage: (String) -> Unit): TransferableContent? {
    if (!content.hasMediaType(MediaType.Image)) return content
    val uris = ArrayList<String>()
    val rest = content.consume { item ->
        val uri = item.uri
        if (uri != null) uris += uri.toString()
        uri != null
    }
    uris.forEach(onImage)
    return rest
}

/**
 * Bold, Italic, Strikethrough, H1-H3 and Clear formatting (under "Aa"), Bullet, Checklist,
 * Drawing, Picture -- the desktop's set, with Undo and Redo in the top bar, where this screen has
 * always had them. Lit state comes from the controller's snapshot state
 * ([BlockEditorController.activeStyles], [BlockEditorController.activeType]), so it follows the
 * caret. Type buttons toggle, as on the desktop: H1 on an H1 line returns it to a paragraph.
 */
@Composable
private fun BlockToolbar(
    controller: BlockEditorController?,
    accent: Color,
    enabled: Boolean,
    isImportingAttachment: Boolean,
    onAddDrawing: () -> Unit,
    onAddPhoto: () -> Unit,
) {
    var showStyles by remember { mutableStateOf(false) }
    val inactive = if (enabled) Color(0xFF9A9A9E) else Color(0xFF4A4A50)
    val styles = if (enabled) controller?.activeStyles.orEmpty() else emptySet()
    val type = if (enabled) controller?.activeType else null
    fun tint(lit: Boolean) = if (lit) accent else inactive
    fun act(action: BlockEditorController.() -> Unit): () -> Unit = { if (enabled) controller?.action() }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (showStyles && enabled) {
            HeadingPopover(
                accent = accent,
                active = type,
                onSelect = { level ->
                    controller?.setType(level)
                    showStyles = false
                },
                onClearFormatting = {
                    controller?.clearFormatting()
                    showStyles = false
                },
            )
            Spacer(Modifier.height(10.dp))
        }
        Row(
            modifier = Modifier
                .shadow(12.dp, RoundedCornerShape(percent = 50))
                .clip(RoundedCornerShape(percent = 50))
                .background(ToolbarDark)
                .border(1.dp, Color(0xFF24242C), RoundedCornerShape(percent = 50))
                // Nine buttons are wider than a narrow phone; the pill scrolls rather than clips.
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolIcon(Icons.Outlined.TextFields, "Text style", tint(showStyles && enabled)) {
                if (enabled) showStyles = !showStyles
            }
            ToolIcon(Icons.Outlined.FormatBold, "Bold", tint(InlineStyle.BOLD in styles), act { toggle(InlineStyle.BOLD) })
            ToolIcon(Icons.Outlined.FormatItalic, "Italic", tint(InlineStyle.ITALIC in styles), act { toggle(InlineStyle.ITALIC) })
            ToolIcon(
                Icons.Outlined.FormatStrikethrough,
                "Strikethrough",
                tint(InlineStyle.STRIKE in styles),
                act { toggle(InlineStyle.STRIKE) },
            )
            ToolIcon(
                Icons.AutoMirrored.Outlined.FormatListBulleted,
                "Bulleted list",
                tint(type == BlockType.BULLET),
                act { setType(BlockType.BULLET) },
            )
            ToolIcon(Icons.Outlined.Checklist, "Checklist", tint(type == BlockType.CHECK), act { setType(BlockType.CHECK) })
            ToolIcon(Icons.Outlined.Brush, "Add drawing", inactive) { onAddDrawing() }
            if (isImportingAttachment) {
                Box(modifier = Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = accent)
                }
            } else {
                ToolIcon(Icons.Outlined.AddPhotoAlternate, "Add photo", inactive) { onAddPhoto() }
            }
        }
    }
}

private val headingOptions = listOf(
    Triple("H1", BlockType.H1, 19.sp),
    Triple("H2", BlockType.H2, 16.sp),
    Triple("H3", BlockType.H3, 14.sp),
)

@Composable
private fun HeadingPopover(
    accent: Color,
    active: BlockType?,
    onSelect: (BlockType) -> Unit,
    onClearFormatting: () -> Unit,
) {
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
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = previewSize, fontWeight = FontWeight.Bold),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .then(if (isActive) Modifier.background(accent.copy(alpha = 0.15f)) else Modifier)
                    .clickable { onSelect(level) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
        HorizontalDivider(color = Color(0xFF24242C), modifier = Modifier.padding(vertical = 4.dp))
        Text(
            text = "Clear formatting",
            color = TitleGrey,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onClearFormatting)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

/**
 * What a picture or drawing block shows, looked up by record id in the note's live attachments
 * and sketches. A record not here yet (still syncing, or soft-deleted until an undo's save
 * undeletes it) or one that fails to decode is a placeholder; the block itself is the editor's
 * and is saved as it is. The picture is the record's thumbnail: the full-size bytes are loaded
 * only by the viewer a tap opens.
 */
@Composable
private fun NotePicture(
    recordId: String,
    kind: BlockType,
    attachments: List<AttachmentPreview>,
    sketches: List<SketchData>,
    onOpenPicture: (String) -> Unit,
    onOpenDrawing: (String, Sketch) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1C1C22)),
        contentAlignment = Alignment.Center,
    ) {
        if (kind == BlockType.DRAWING) {
            val record = sketches.firstOrNull { it.id == recordId }
            val row = remember(record) { record?.let { sketchesForDisplay(listOf(it)).firstOrNull() } }
            when (row) {
                is DisplaySketch.Drawing -> Canvas(
                    Modifier
                        .fillMaxSize()
                        .openOnTap(recordId) { onOpenDrawing(recordId, row.sketch) },
                ) {
                    SketchRenderer.render(row.sketch, size).forEach { drawSketchStroke(it) }
                }
                is DisplaySketch.Undecodable -> PicturePlaceholder("Can't display this drawing")
                null -> PicturePlaceholder("Loading drawing…")
            }
        } else {
            val preview = attachments.firstOrNull { it.id == recordId }
            if (preview == null) {
                PicturePlaceholder("Loading picture…")
            } else {
                AttachmentThumbnail(
                    preview = preview,
                    modifier = Modifier
                        .fillMaxSize()
                        .openOnTap(recordId) { onOpenPicture(recordId) },
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}

/**
 * Calls [onOpen] on a tap that stays a tap. Deliberately not `clickable`/`detectTapGestures`:
 * those consume the press, and the editor's own overlay around this content needs it -- its tap
 * selects the picture and its long-press drag moves it. This only watches: [waitForUpOrCancellation]
 * returns null once anything consumed the gesture (a drag, or the note scrolling), so moving a
 * picture or scrolling past it never opens it.
 */
private fun Modifier.openOnTap(key: Any, onOpen: () -> Unit): Modifier =
    pointerInput(key) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            if (waitForUpOrCancellation() != null) onOpen()
        }
    }

@Composable
private fun PicturePlaceholder(text: String) {
    Text(text, color = BodyGrey, style = MaterialTheme.typography.labelSmall)
}

/**
 * A BLOCKS body this build cannot decode: the raw text, selectable but not editable, under a
 * notice. Never offered to the editor and never saved; see `OpenedNoteDocument.unreadableBody`.
 */
@Composable
private fun UnreadableBody(raw: String, bodyStyle: androidx.compose.ui.text.TextStyle) {
    Text(
        text = "This note was saved by a newer version of Manana and is shown read-only. Update the app to edit it.",
        color = Color(0xFF5E5E62),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(vertical = 6.dp),
    )
    SelectionContainer {
        Text(text = raw, style = bodyStyle.copy(color = Color(0xFFB9B9BD)))
    }
}

/**
 * Whether this controller has opened its note. [BlockEditorController.base] is documented to throw
 * until then, and that is the only public signal of it. Reads snapshot state, so a composition
 * calling it recomposes when the note opens.
 */
private fun BlockEditorController.isLoaded(): Boolean = runCatching { base }.isSuccess
