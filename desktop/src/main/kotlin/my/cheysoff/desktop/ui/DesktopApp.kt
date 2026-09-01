package my.cheysoff.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.Window
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import my.cheysoff.core_domain.repository.NotesRepository
import my.cheysoff.desktop.ui.notes.MaxSidebarWidth
import my.cheysoff.desktop.ui.notes.MinSidebarWidth
import my.cheysoff.desktop.ui.notes.NotesWorkspaceScreen
import my.cheysoff.desktop.ui.preview.InMemoryNotesRepository
import my.cheysoff.desktop.ui.preview.SampleLibrary
import my.cheysoff.desktop.ui.state.NotesWorkspaceModel
import my.cheysoff.desktop.ui.theme.MananaDesktopTheme

/**
 * The desktop app's entry point.
 *
 * The repository is built here and nowhere else, so the day the encrypted desktop store lands the
 * change is one line: hand [MananaWindow] the real [NotesRepository] instead of the in-memory
 * fake, and put the unlock gate in front of it. Nothing in `ui.notes` or `ui.state` knows which
 * implementation it has.
 *
 * `-Dmanana.emptyLibrary=true` starts with no notes at all, which is how the empty-state
 * screenshot is taken without deleting seven notes by hand first.
 */
fun main() {
    val startEmpty = System.getProperty("manana.emptyLibrary")?.toBoolean() == true
    val repository = if (startEmpty) {
        InMemoryNotesRepository()
    } else {
        InMemoryNotesRepository(notes = SampleLibrary.notes, folders = SampleLibrary.folders)
    }
    application {
        MananaWindow(
            repository = repository,
            onLock = {},
            isRemembered = false,
            onToggleRemember = {},
            onExit = ::exitApplication,
        )
    }
}

@Composable
fun MananaWindow(
    repository: NotesRepository,
    onLock: () -> Unit,
    isRemembered: Boolean,
    onToggleRemember: () -> Unit,
    syncLabel: String? = null,
    onSync: (() -> Unit)? = null,
    /** Null when this computer has no server and therefore cannot authorise another device. */
    onAddDevice: (() -> Unit)? = null,
    onExit: () -> Unit,
) {
    val windowState = rememberWindowState(
        size = DpSize(WindowGeometry.width(), WindowGeometry.height()),
        position = WindowGeometry.position()
            ?.let { (x, y) -> WindowPosition(x, y) }
            ?: WindowPosition.PlatformDefault,
        placement = if (WindowGeometry.isMaximized()) WindowPlacement.Maximized else WindowPlacement.Floating,
    )
    val scope = rememberCoroutineScope()
    val model = remember { NotesWorkspaceModel(repository = repository, scope = scope) }
    val state by model.state.collectAsState()

    var sidebarWidth by remember { mutableStateOf(WindowGeometry.sidebarWidth()) }
    var sidebarVisible by remember { mutableStateOf(true) }
    val titleFocus = remember { FocusRequester() }

    // The clock the whole UI formats "3 h ago" against. Held in state and ticked rather than read
    // at each recomposition, so every relative timestamp on screen agrees with every other one and
    // they all move together instead of drifting as unrelated recompositions happen.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    /** Creates a note and drops the caret into its title, which is where typing should start. */
    fun newNote() {
        model.newNote()
        // The pane has to compose the new draft before its title field can take focus.
        scope.launch { titleFocus.requestFocusWhenReady() }
    }

    Window(
        onCloseRequest = {
            // The debounce may still be holding the last few keystrokes; closing must not eat them.
            model.flushPendingSave()
            WindowGeometry.save(
                width = windowState.size.width,
                height = windowState.size.height,
                x = windowState.position.takeIf { it.isSpecified }?.x,
                y = windowState.position.takeIf { it.isSpecified }?.y,
                maximized = windowState.placement == WindowPlacement.Maximized,
                sidebarWidth = sidebarWidth,
            )
            onExit()
        },
        state = windowState,
        title = "Mañana",
        // Preview, not `onKeyEvent`: these have to fire while a text field has focus, and a
        // focused field consumes the ordinary event before the window ever sees it.
        onPreviewKeyEvent = { event ->
            handleShortcut(
                event = event,
                searchOpen = state.search.isOpen,
                onNewNote = ::newNote,
                onOpenSearch = model::openSearch,
                onCloseSearch = model::closeSearch,
                onMoveHighlight = model::moveSearchHighlight,
                onOpenHighlighted = { model.openHighlightedSearchHit() },
                onFlushSave = model::flushPendingSave,
                onToggleSidebar = { sidebarVisible = !sidebarVisible },
            )
        },
    ) {
        MananaDesktopTheme {
            NotesWorkspaceScreen(
                model = model,
                state = state,
                now = now,
                sidebarWidth = sidebarWidth,
                onSidebarWidthChange = { sidebarWidth = it.coerceIn(MinSidebarWidth, MaxSidebarWidth) },
                sidebarVisible = sidebarVisible,
                titleFocus = titleFocus,
                onNewNote = ::newNote,
                onLock = onLock,
                isRemembered = isRemembered,
                onToggleRemember = onToggleRemember,
                syncLabel = syncLabel,
                onSync = onSync,
                onAddDevice = onAddDevice,
            )
        }
    }
}

/**
 * The window's keyboard map. Pure routing — every branch delegates — so it stays readable as the
 * one place to look up what a key does.
 *
 * Ctrl and Cmd are both accepted. This build runs on Windows, but the approved layout says ⌘K and
 * the same binary is meant to run on a Mac; accepting either costs one boolean and means the
 * shortcuts work on whichever machine it is opened on.
 */
private fun handleShortcut(
    event: KeyEvent,
    searchOpen: Boolean,
    onNewNote: () -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onMoveHighlight: (Int) -> Unit,
    onOpenHighlighted: () -> Unit,
    onFlushSave: () -> Unit,
    onToggleSidebar: () -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val command = event.isCtrlPressed || event.isMetaPressed

    // While the palette is open it owns the navigation keys; without this, Up/Down would scroll
    // whatever is behind the scrim and Enter would insert a newline in the note underneath.
    if (searchOpen) {
        when (event.key) {
            Key.Escape -> { onCloseSearch(); return true }
            Key.DirectionDown -> { onMoveHighlight(1); return true }
            Key.DirectionUp -> { onMoveHighlight(-1); return true }
            Key.Enter, Key.NumPadEnter -> { onOpenHighlighted(); return true }
        }
    }

    if (!command) return false
    return when (event.key) {
        Key.N -> { onNewNote(); true }
        // F as well as K: ⌘K is the palette convention this app follows, but Ctrl+F is the reflex
        // a lot of people have for "find", and having it do nothing would read as a missing feature.
        Key.K, Key.F -> { onOpenSearch(); true }
        // Saving is automatic. Ctrl+S flushes the pending write rather than doing nothing, so the
        // habit is harmless and, if anything, slightly useful.
        Key.S -> { onFlushSave(); true }
        Key.B -> { onToggleSidebar(); true }
        else -> false
    }
}

/**
 * Requests focus once the target composable exists.
 *
 * A [FocusRequester] throws if it is asked for focus before the node it is attached to has been
 * placed, and a note created by Ctrl+N is exactly that case: the request is issued in the same
 * frame the editor pane is first composing its title field. Retrying for a few frames is enough,
 * and giving up quietly is the right failure — a caret that did not move is a much smaller problem
 * than a crash.
 */
private suspend fun FocusRequester.requestFocusWhenReady(attempts: Int = 10) {
    repeat(attempts) {
        if (runCatching { requestFocus() }.isSuccess) return
        delay(16)
    }
}
