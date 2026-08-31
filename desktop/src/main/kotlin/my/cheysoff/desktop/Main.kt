package my.cheysoff.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import my.cheysoff.desktop.app.AppController
import my.cheysoff.desktop.keychain.CredentialStores
import my.cheysoff.desktop.platform.HostOs
import my.cheysoff.desktop.ui.CreatePassphraseScreen
import my.cheysoff.desktop.ui.DamagedScreen
import my.cheysoff.desktop.ui.FirstRunScreen
import my.cheysoff.desktop.ui.MananaColors
import my.cheysoff.desktop.ui.MananaTheme
import my.cheysoff.desktop.ui.UnlockScreen
import my.cheysoff.desktop.ui.MananaWindow
import my.cheysoff.desktop.vault.DesktopVault
import my.cheysoff.desktop.vault.VaultLocation
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The desktop entry point.
 *
 * `--vault-dir <path>` overrides where the vault lives. It exists so a second vault can be opened
 * without touching the real one — which is what makes the app itself testable by hand and what the
 * screenshots in the pull request were taken against. It is not a hidden feature: a user who passes
 * it gets a separate, equally real vault at that path.
 */
fun main(args: Array<String>) {
    val directory = vaultDirectoryFrom(args)
    val vault = DesktopVault(
        directory = directory,
        credentialStore = CredentialStores.forHost(HostOs.current(), directory),
    )

    application {
        val scope = rememberCoroutineScope()
        val controller = remember(vault) { AppController(vault, scope) }

        // Attempted once, on the way in. A machine that has never been asked to remember anything
        // simply stays on the passphrase prompt; that is not a failure and is not reported as one.
        LaunchedEffect(controller) { controller.tryStoredKey() }

        val screen = controller.screen
        if (screen is AppController.Screen.Open) {
            // The unlocked workspace opens its OWN window rather than rendering inside the gate's.
            // It restores the size, position and sidebar width the user left behind, and a window
            // sized for a passphrase prompt is the wrong shape to inherit for a two-pane editor.
            MananaWindow(
                repository = screen.repository,
                onLock = controller::lock,
                isRemembered = controller.isRemembered(),
                onToggleRemember = {
                    if (controller.isRemembered()) {
                        controller.forgetOnThisComputer()
                    } else {
                        controller.rememberOnThisComputer()
                    }
                },
                onExit = ::exitApplication,
            )
        } else {
            Window(
                onCloseRequest = ::exitApplication,
                title = "Manana",
                state = rememberWindowState(width = 1100.dp, height = 760.dp),
            ) {
                MananaTheme {
                    // A Surface rather than a plain Box: `Surface` is what publishes
                    // `contentColor`, and Material 3's `Text` takes its default colour from that.
                    // Inside a bare Box the default is black, so every Text that does not name a
                    // colour explicitly renders black on this black background -- invisible, and
                    // invisible in a way that looks like a layout bug rather than a colour one.
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MananaColors.Black,
                        contentColor = MananaColors.TitleGrey,
                    ) {
                        GateScreens(vault, controller)
                    }
                }
            }
        }
    }
}

/** Parses `--vault-dir <path>`, falling back to [VaultLocation.defaultDirectory]. */
internal fun vaultDirectoryFrom(args: Array<String>): Path {
    val index = args.indexOf("--vault-dir")
    return if (index >= 0 && index + 1 < args.size) {
        Paths.get(args[index + 1]).toAbsolutePath()
    } else {
        VaultLocation.defaultDirectory()
    }
}

/** Everything before the vault is open: first run, passphrase, unlock, and the unopenable case. */
@Composable
private fun GateScreens(vault: DesktopVault, controller: AppController) {
    when (val screen = controller.screen) {
        is AppController.Screen.FirstRun ->
            FirstRunScreen(onUseStandalone = controller::chooseStandalone)

        is AppController.Screen.CreatePassphrase -> CreatePassphraseScreen(
            busy = controller.busy,
            message = controller.message,
            onBack = controller::backToFirstRun,
            onCreate = { passphrase, confirmation ->
                controller.create(passphrase, confirmation, screen.origin)
            },
        )

        is AppController.Screen.Unlock -> UnlockScreen(
            busy = controller.busy,
            message = controller.message,
            credentialStoreName = controller.credentialStoreName,
            onUnlock = controller::unlock,
        )

        is AppController.Screen.Damaged ->
            DamagedScreen(reason = screen.reason, directory = vault.directory.toString())

        // Handled by the caller, which swaps the whole window for the workspace rather than
        // rendering it inside the gate. Kept as an explicit branch so that `when` stays exhaustive
        // and a new screen cannot be added without deciding which side of the gate it belongs on.
        is AppController.Screen.Open -> Unit
    }
}
