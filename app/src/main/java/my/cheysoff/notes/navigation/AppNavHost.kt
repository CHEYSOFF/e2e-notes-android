package my.cheysoff.notes.navigation

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import my.cheysoff.feature_auth.model.AuthScreenIntent
import my.cheysoff.feature_auth.ui.AuthEvent
import my.cheysoff.feature_auth.ui.AuthScreen
import my.cheysoff.feature_auth.ui.AuthViewModel
import my.cheysoff.feature_notes.ui.list.NotesListEvent
import my.cheysoff.feature_notes.ui.list.NotesListScreen
import my.cheysoff.feature_notes.ui.list.NotesListViewModel
import my.cheysoff.feature_notes.ui.single.SingleNoteEvent
import my.cheysoff.feature_notes.ui.single.SingleNoteScreen
import my.cheysoff.feature_notes.ui.single.SingleNoteViewModel
import my.cheysoff.feature_notes.ui.trash.TrashEvent
import my.cheysoff.feature_notes.ui.trash.TrashScreen
import my.cheysoff.feature_notes.ui.trash.TrashViewModel
import my.cheysoff.feature_pairing.ui.PairingScreen
import my.cheysoff.feature_pairing.ui.PairingViewModel
import my.cheysoff.feature_settings.ui.SettingsScreen
import my.cheysoff.feature_settings.ui.SettingsViewModel

@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController()
) {
    // Re-lock gating: when the session locks (passphrase dropped on background), route back to the
    // auth screen and clear the back stack so notes aren't reachable without re-authenticating.
    val appViewModel: AppViewModel = hiltViewModel()
    val unlocked by appViewModel.unlocked.collectAsState()
    LaunchedEffect(unlocked) {
        if (!unlocked) {
            val current = navController.currentDestination?.route
            if (current != null && current != "auth") {
                navController.navigate("auth") {
                    popUpTo(0) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
    }

    NavHost(navController, startDestination = "auth") {
        // Auth feature
        composable("auth") {
            val viewModel: AuthViewModel = hiltViewModel()
            val state by viewModel.state.collectAsState()
            val context = LocalContext.current

            LaunchedEffect(viewModel) {
                viewModel.events.collect { event ->
                    when (event) {
                        AuthEvent.NavigationToNotesList -> {
                            navController.navigate("notes") {
                                popUpTo("auth") { inclusive = true }
                            }
                        }

                        AuthEvent.RequestBiometricEnroll -> {
                            (context as? FragmentActivity)?.let {
                                viewModel.processIntent(AuthScreenIntent.EnableBiometric(it))
                            } ?: navController.navigate("notes") {
                                popUpTo("auth") { inclusive = true }
                            }
                        }
                    }
                }
            }

            AuthScreen(
                state = state,
                onIntentReceived = { intent ->
                    viewModel.processIntent(intent)
                }
            )
        }

        // Notes feature
        composable("notes") {
            // Never compose the notes graph while locked. hiltViewModel() builds NoteDatabase,
            // which throws "Database requested while locked". After process death the saved back
            // stack re-composes this destination in the SAME pass that only registers the re-lock
            // LaunchedEffect above — effects run after composition, so the redirect is too late.
            if (!unlocked) return@composable

            val viewModel: NotesListViewModel = hiltViewModel()
            val state by viewModel.state.collectAsState()

            LaunchedEffect(viewModel) {
                viewModel.events.collect { event ->
                    when (event) {
                        is NotesListEvent.NavigateToNote -> {
                            navController.navigate("note/${event.noteId}?isNew=${event.isNew}")
                        }

                        NotesListEvent.NavigateToProfile -> {
                            // launchSingleTop because the events channel is BUFFERED: two quick
                            // taps on the Profile icon queue two events, and without this they
                            // would push two copies of the screen for the user to back out of.
                            navController.navigate("profile") { launchSingleTop = true }
                        }

                        NotesListEvent.NavigateToTrash -> {
                            navController.navigate("trash")
                        }
                    }
                }
            }

            NotesListScreen(
                state = state,
                onIntent = { intent -> viewModel.onIntent(intent) }
            )
        }

        composable(
            // isNew is an optional query argument, so plain "note/{noteId}" still matches and any
            // caller that doesn't set it gets the safe default. It tells the editor that the list
            // screen inserted this row for it and that the row may therefore be auto-discarded if
            // it's still empty on back — a fact only the inserting caller can know, which is why it
            // is passed rather than inferred from the row's timestamps.
            route = "note/{noteId}?isNew={isNew}",
            arguments = listOf(
                navArgument("noteId") { type = NavType.StringType },
                navArgument("isNew") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) {
            // Same lock guard as the notes list — this destination also opens the database.
            if (!unlocked) return@composable

            val viewModel: SingleNoteViewModel = hiltViewModel()
            val state by viewModel.state.collectAsState()
            val context = LocalContext.current

            LaunchedEffect(viewModel) {
                viewModel.events.collect { event ->
                    when (event) {
                        SingleNoteEvent.NavigateBack -> {
                            navController.popBackStack()
                        }

                        // The editor stays on the original note, so nothing on screen changes when
                        // a copy is written — this toast is the whole confirmation. The copy shows
                        // up in the list behind this screen.
                        is SingleNoteEvent.NoteDuplicated -> {
                            Toast
                                .makeText(
                                    context,
                                    "Duplicated as \"${event.title}\"",
                                    Toast.LENGTH_SHORT,
                                )
                                .show()
                        }

                        // Which of the two ways an import can fail matters: "too large" and "not an
                        // image" call for different words, and a single generic message would be
                        // worse than none (docs/design/image-attachments.md §7).
                        is SingleNoteEvent.AttachmentImportFailed -> {
                            Toast
                                .makeText(
                                    context,
                                    if (event.tooLarge) {
                                        "That photo is too large to attach"
                                    } else {
                                        "That file isn't a photo"
                                    },
                                    Toast.LENGTH_SHORT,
                                )
                                .show()
                        }
                    }
                }
            }

            SingleNoteScreen(
                state = state,
                onIntent = { intent -> viewModel.onIntent(intent) }
            )
        }

        // Profile / settings feature
        composable("profile") {
            // Same lock guard as the notes destinations. This screen does not open the database,
            // but it does read and write the secure-unlock store (the biometric toggle), and
            // enabling biometric unlock needs the passphrase that only exists while unlocked —
            // so there is nothing here to show or do while locked.
            if (!unlocked) return@composable

            val viewModel: SettingsViewModel = hiltViewModel()
            val state by viewModel.state.collectAsState()

            SettingsScreen(
                state = state,
                onIntent = { intent -> viewModel.onIntent(intent) },
                onBack = { navController.popBackStack() },
                // launchSingleTop for the same reason the Profile push has it: a double tap on the
                // row would otherwise push two copies of a screen that holds a live camera.
                onPairDevice = { navController.navigate("pairing") { launchSingleTop = true } },
            )
        }

        composable("pairing") {
            // Same lock guard as every other pushed destination. Pairing never opens the database,
            // but the account key it shares is wrapped under the database passphrase, which
            // exists in memory only while unlocked -- so a locked session has nothing to pair
            // with, and SecureUnlockManager.ensureArk() would refuse anyway.
            if (!unlocked) return@composable

            val viewModel: PairingViewModel = hiltViewModel()
            val state by viewModel.state.collectAsState()

            PairingScreen(
                state = state,
                onIntent = { intent -> viewModel.onIntent(intent) },
                onBack = { navController.popBackStack() },
            )
        }

        composable("trash") {
            // Same lock guard as the notes list and the editor — this destination reads the
            // database too, and hiltViewModel() would build NoteDatabase while locked.
            if (!unlocked) return@composable

            val viewModel: TrashViewModel = hiltViewModel()
            val state by viewModel.state.collectAsState()

            LaunchedEffect(viewModel) {
                viewModel.events.collect { event ->
                    when (event) {
                        TrashEvent.NavigateBack -> {
                            navController.popBackStack()
                        }
                    }
                }
            }

            TrashScreen(
                state = state,
                onIntent = { intent -> viewModel.onIntent(intent) }
            )
        }
    }
}
