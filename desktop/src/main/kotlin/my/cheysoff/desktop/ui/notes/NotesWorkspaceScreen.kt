package my.cheysoff.desktop.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import my.cheysoff.desktop.ui.state.NotesWorkspaceModel
import my.cheysoff.desktop.ui.state.WorkspaceUiState
import my.cheysoff.desktop.ui.theme.AccentIndigo
import my.cheysoff.desktop.ui.theme.AppBlack
import my.cheysoff.desktop.ui.theme.BodyGrey
import my.cheysoff.desktop.ui.theme.IndigoTint
import my.cheysoff.desktop.ui.theme.OutlineDark
import my.cheysoff.desktop.ui.theme.TextScale
import my.cheysoff.desktop.ui.theme.SurfaceDark
import my.cheysoff.desktop.ui.theme.TitleGrey
import java.awt.Cursor

/** How narrow and how wide the user is allowed to drag the sidebar. */
val MinSidebarWidth = 210.dp
val MaxSidebarWidth = 520.dp

/**
 * The whole window below the title bar: list on the left, editor on the right, one resizable seam
 * between them, and the search palette floating over the top when it is open.
 *
 * There is no navigation anywhere in here — no back stack, no destinations. Clicking a note in the
 * list changes what the right pane draws and nothing else, which is the difference the brief is
 * asking for between a desktop app and a phone app in a window.
 */
@Composable
fun NotesWorkspaceScreen(
    model: NotesWorkspaceModel,
    state: WorkspaceUiState,
    now: Long,
    sidebarWidth: Dp,
    onSidebarWidthChange: (Dp) -> Unit,
    sidebarVisible: Boolean,
    titleFocus: FocusRequester,
    onNewNote: () -> Unit,
    onLock: () -> Unit,
    isRemembered: Boolean,
    onToggleRemember: () -> Unit,
    syncLabel: String?,
    onSync: (() -> Unit)?,
    /** Null when this computer has no server and therefore cannot authorise another device. */
    onAddDevice: (() -> Unit)? = null,
    textScale: TextScale,
    onCycleTextScale: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(AppBlack)) {
        Column(modifier = Modifier.fillMaxSize()) {
            TitleBar(
                onNewNote = onNewNote,
                onOpenSearch = model::openSearch,
                onLock = onLock,
                isRemembered = isRemembered,
                onToggleRemember = onToggleRemember,
                syncLabel = syncLabel,
                onSync = onSync,
                onAddDevice = onAddDevice,
                textScale = textScale,
                onCycleTextScale = onCycleTextScale,
            )

            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                // The editor needs a floor of its own, or dragging the seam to the right edge
                // leaves a pane too narrow to read a sentence in.
                val maxSidebar = minOf(MaxSidebarWidth, maxWidth - 360.dp).coerceAtLeast(MinSidebarWidth)

                Row(modifier = Modifier.fillMaxSize()) {
                    if (sidebarVisible) {
                        NoteListPane(
                            content = state.content,
                            chips = state.chips,
                            selectedFolderId = state.selectedFolderId,
                            selectedNoteId = state.selectedNoteId,
                            loaded = state.loaded,
                            now = now,
                            onSelectFolder = model::selectFolder,
                            onSelectNote = model::selectNote,
                            onNewNote = onNewNote,
                            modifier = Modifier.width(sidebarWidth.coerceIn(MinSidebarWidth, maxSidebar)),
                        )
                        SplitHandle { deltaPx ->
                            val delta = with(density) { deltaPx.toDp() }
                            onSidebarWidthChange((sidebarWidth + delta).coerceIn(MinSidebarWidth, maxSidebar))
                        }
                    }
                    NoteEditorPane(
                        draft = state.editor,
                        folders = state.folders,
                        saveStatus = state.saveStatus,
                        now = now,
                        titleFocus = titleFocus,
                        onTitleChange = model::setTitle,
                        onContentChange = model::setContent,
                        onSetFolder = model::setNoteFolder,
                        onTogglePin = model::togglePinned,
                        onToggleFavorite = model::toggleFavorite,
                        onDelete = model::deleteSelectedNote,
                        onAddChecklistItem = model::addChecklistItem,
                        onChecklistTextChange = model::setChecklistItemText,
                        onToggleChecklistItem = model::toggleChecklistItem,
                        onRemoveChecklistItem = model::removeChecklistItem,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (state.search.isOpen) {
            SearchPalette(
                search = state.search,
                now = now,
                onQueryChange = model::setSearchQuery,
                onOpenHit = model::openSearchHit,
                onDismiss = model::closeSearch,
            )
        }
    }
}

/**
 * The seam. Six device-independent pixels wide with a one-pixel rule down the middle: a hairline
 * is the right visual weight but the wrong hit target, and on a mouse the two do not have to be
 * the same thing.
 */
@Composable
private fun SplitHandle(onDrag: (Float) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // pointerInput(Unit) captures its lambda once and never restarts, so the gesture would keep
    // calling the FIRST onDrag it was given -- one that closes over the sidebar width as it was
    // when the drag started. The seam then jumped back to "start width + this frame's delta" on
    // every event and the whole drag moved it a few pixels. rememberUpdatedState keeps the block
    // long-lived while the callback it reaches stays current.
    val currentOnDrag by rememberUpdatedState(onDrag)
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(6.dp)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
            // hoverable rather than clickable: the seam is dragged, never clicked, and the
            // interaction source is here purely to colour the rule under the pointer.
            .hoverable(interaction)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    currentOnDrag(dragAmount.x)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(if (hovered) IndigoTint else OutlineDark)
        )
    }
}

@Composable
private fun TitleBar(
    onNewNote: () -> Unit,
    onOpenSearch: () -> Unit,
    onLock: () -> Unit,
    isRemembered: Boolean,
    onToggleRemember: () -> Unit,
    syncLabel: String?,
    onSync: (() -> Unit)?,
    onAddDevice: (() -> Unit)?,
    textScale: TextScale,
    onCycleTextScale: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(AppBlack)
            .padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 10.dp),
    ) {
        // The wordmark keeps the header's two-tone treatment from the phone's home screen: the
        // name in Light grey with the accent syllable in indigo.
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = TitleGrey, fontWeight = FontWeight.Light)) { append("Ma") }
                withStyle(SpanStyle(color = IndigoTint, fontWeight = FontWeight.Medium)) { append("ñana") }
            },
            style = MaterialTheme.typography.titleMedium,
        )

        Spacer(Modifier.weight(1f))

        SearchAffordance(onOpenSearch)
        Spacer(Modifier.width(8.dp))
        // Shown only when this device is actually enrolled on a server. A sync control on a
        // device that cannot sync is an invitation to press something that will never work, and
        // the reason it cannot -- not paired, or a stored address that stopped validating -- is not
        // something a button can explain.
        if (syncLabel != null && onSync != null) {
            WordedPill(label = syncLabel, onClick = onSync, clickLabel = "Sync now")
            Spacer(Modifier.width(8.dp))
        }

        // Shown only where it can work, on the same argument as the sync control: authorising
        // another device is a signature the server checks against this computer's own device row,
        // so a computer with no row cannot do it and a button offering to would lead nowhere.
        if (onAddDevice != null) {
            WordedPill(
                label = "Add a phone",
                onClick = onAddDevice,
                clickLabel = "Add a phone to this account",
            )
            Spacer(Modifier.width(8.dp))
        }

        // Text size. A control rather than a buried setting for the same reason "Remember" is one:
        // it is read at a glance and changed in a click, and the person who wants larger text wants
        // it now, not after finding a settings screen. Cycling rather than opening a menu keeps it
        // to one widget -- four steps is short enough that clicking past the one you wanted costs
        // three more clicks, not a search.
        WordedPill(
            label = "Aa ${textScale.label}",
            onClick = onCycleTextScale,
            clickLabel = "Change text size",
        )
        Spacer(Modifier.width(8.dp))

        // Whether the OS credential store holds the vault key for this machine.
        //
        // Worded rather than iconographic, and shown in both states rather than only when on.
        // "This computer can open my notes without asking" is exactly the kind of setting a person
        // should be able to read the state of at a glance and switch off in one click -- an icon
        // whose meaning has to be learned, buried behind a settings screen they would have to
        // remember exists, is how that decision gets made once and never revisited.
        RememberAffordance(isRemembered = isRemembered, onClick = onToggleRemember)
        Spacer(Modifier.width(8.dp))
        // Locking has to be reachable from the workspace, because it is the only way to put the
        // notes back behind the passphrase without quitting. The app locks itself on nothing --
        // there is no equivalent of the phone's lock-on-background here, since a desktop window
        // losing focus is not the user walking away from it.
        PillIconButton(
            icon = Icons.Default.Lock,
            description = "Lock",
            tint = BodyGrey,
            background = Color.Transparent,
            onClick = onLock,
        )
        Spacer(Modifier.width(8.dp))
        PillIconButton(
            icon = Icons.Default.Add,
            description = "New note",
            tint = Color.White,
            background = AccentIndigo,
            onClick = onNewNote,
        )
    }
}

/**
 * A worded control in the title bar — the shape the header uses for anything whose *state* has to
 * be readable, not just its action: sync, "Remember", text size.
 *
 * @param clickLabel what activating it does, for a screen reader. Required rather than defaulted,
 *   because the default that used to exist here announced "Remember this computer" on the sync
 *   control.
 */
@Composable
private fun WordedPill(
    label: String,
    onClick: () -> Unit,
    clickLabel: String,
    accent: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (hovered) Color(0xFF212127) else SurfaceDark)
            .pointerHoverIcon(handCursor)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = clickLabel,
                onClick = onClick,
            )
            .padding(start = 9.dp, end = 9.dp, top = 5.dp, bottom = 5.dp),
    ) {
        Text(
            text = label,
            color = if (accent) AccentIndigo else BodyGrey,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** Whether this computer may open the vault without the passphrase — stated, and switchable. */
@Composable
private fun RememberAffordance(isRemembered: Boolean, onClick: () -> Unit) {
    WordedPill(
        label = if (isRemembered) "Remembered" else "Remember",
        onClick = onClick,
        clickLabel = if (isRemembered) "Stop remembering this computer" else "Remember this computer",
        accent = isRemembered,
    )
}

@Composable
private fun SearchAffordance(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (hovered) Color(0xFF212127) else SurfaceDark)
            .pointerHoverIcon(handCursor)
            .clickable(interactionSource = interaction, indication = null, onClickLabel = "Search notes", onClick = onClick)
            .padding(start = 9.dp, end = 7.dp, top = 5.dp, bottom = 5.dp),
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = BodyGrey, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(7.dp))
        Text("Search", color = BodyGrey, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.width(9.dp))
        KeyHint("Ctrl K")
    }
}
