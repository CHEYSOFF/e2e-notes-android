package my.cheysoff.notes.navigation

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
                            navController.navigate("note/${event.noteId}")
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
            route = "note/{noteId}",
            arguments = listOf(
                navArgument("noteId") { type = NavType.StringType }
            )
        ) {
            // Same lock guard as the notes list — this destination also opens the database.
            if (!unlocked) return@composable

            val viewModel: SingleNoteViewModel = hiltViewModel()
            val state by viewModel.state.collectAsState()

            LaunchedEffect(viewModel) {
                viewModel.events.collect { event ->
                    when (event) {
                        SingleNoteEvent.NavigateBack -> {
                            navController.popBackStack()
                        }
                    }
                }
            }

            SingleNoteScreen(
                state = state,
                onIntent = { intent -> viewModel.onIntent(intent) }
            )
        }
    }
}
