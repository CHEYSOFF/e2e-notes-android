package my.cheysoff.notes.ui

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
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
        // Keep note plaintext out of the Recents carousel. The OS captures its task snapshot at
        // onStop, i.e. *before* the lock-on-background re-lock has repainted, so without this the
        // last open note stays readable in the app switcher (and on disk in
        // /data/system_ce/<user>/snapshots/) even though the database itself is locked.
        // setRecentsScreenshotEnabled is API 33+; it hides only the thumbnail and leaves the user
        // free to screenshot their own notes. Below 33 there is no such targeted API, so fall back
        // to FLAG_SECURE — coarser (it also blocks screenshots and screen recording) but the
        // alternative is leaking plaintext on Android 12/12L.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(false)
        } else {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        }
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
