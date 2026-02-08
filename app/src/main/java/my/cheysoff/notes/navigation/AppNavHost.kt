package my.cheysoff.notes.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import my.cheysoff.feature_auth.ui.AuthEvent
import my.cheysoff.feature_auth.ui.AuthScreen
import my.cheysoff.feature_auth.ui.AuthViewModel
import my.cheysoff.feature_notes.ui.list.NotesListScreen
import my.cheysoff.feature_notes.ui.list.NotesListViewModel

@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController()
) {
    NavHost(navController, startDestination = "auth") {
        // Auth feature
        composable("auth") {
            val viewModel: AuthViewModel = hiltViewModel()
            val state by viewModel.state.collectAsState()

            LaunchedEffect(viewModel) {
                viewModel.events.collect { event ->
                    when (event) {
                        AuthEvent.NavigationToNotesList -> {
                            navController.navigate("notes") {
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
            val viewModel: NotesListViewModel = hiltViewModel()
            val state by viewModel.state.collectAsState()

            NotesListScreen(state = state)
        }
    }
}
