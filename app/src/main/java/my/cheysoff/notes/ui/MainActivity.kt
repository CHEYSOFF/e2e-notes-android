package my.cheysoff.notes.ui

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.AndroidEntryPoint
import my.cheysoff.core_ui.theme.NotesTheme
import my.cheysoff.notes.navigation.AppNavHost

// TODO: Change to ComponentActivity once biometric prompt issue is resolved.
// Using FragmentActivity as a workaround for biometrics.
// See issue: https://issuetracker.google.com/issues/178855209
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Transparent system bars with forced light (white) icons — the whole app is black.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            NotesTheme(darkTheme = true) {
                AppNavHost()
            }
        }
    }
}
