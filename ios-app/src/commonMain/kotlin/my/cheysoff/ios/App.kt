package my.cheysoff.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.collectAsState
import my.cheysoff.ios.theme.Manana
import my.cheysoff.ios.theme.MananaTheme
import my.cheysoff.ios.ui.EditorScreen
import my.cheysoff.ios.ui.NotesScreen
import my.cheysoff.ios.ui.UnlockScreen

/**
 * The whole app: one `when` over [AppState].
 *
 * ## No navigation library, and why that is a decision rather than an omission
 *
 * The Android app uses `navigation-compose` because it has eight destinations, deep links and a
 * back stack that survives process death. This has three states and no back stack worth the name:
 * a note is open or it is not, and the device is unlocked or it is not. A navigation graph would
 * add a dependency whose iOS behaviour nobody on this branch can observe in exchange for expressing
 * a two-level hierarchy that a `when` expresses in four lines.
 *
 * The moment a fourth destination appears -- Trash, folders, settings, pairing -- that trade
 * changes and this should become a real navigation graph rather than a deeper `when`.
 *
 * ## What a real device will find wrong with these screens
 *
 * They have never been rendered. The layout work this port still owes is listed in
 * `docs/BUILDING-IOS.md`, and the honest summary is: the safe-area insets, the keyboard, and the
 * absence of a system back gesture are three things a phone has and a compiler does not check.
 */
@Composable
fun App(model: AppModel) {
    val state by model.state.collectAsState()

    MananaTheme {
        // Painted explicitly rather than relying on the surface behind it: `ComposeUIViewController`
        // hands Compose a UIView whose background is whatever the storyboard said, and a
        // transparent Compose tree over a white UIView is a white flash on every launch.
        Box(Modifier.fillMaxSize().background(Manana.Black)) {
            when (val current = state) {
                is AppState.NeedsSetup -> UnlockScreen(
                    isSetup = true,
                    error = null,
                    busy = false,
                    onSubmit = model::setUp,
                )

                is AppState.Locked -> UnlockScreen(
                    isSetup = false,
                    error = current.error,
                    busy = current.busy,
                    onSubmit = model::unlock,
                )

                is AppState.Unlocked -> {
                    val editing = current.editing
                    if (editing == null) {
                        NotesScreen(
                            notes = current.notes,
                            onOpen = model::openNote,
                            onNew = model::newNote,
                        )
                    } else {
                        EditorScreen(
                            note = editing,
                            onSave = model::save,
                            onDelete = { model.delete(editing) },
                            onClose = model::closeNote,
                        )
                    }
                }
            }
        }
    }
}
