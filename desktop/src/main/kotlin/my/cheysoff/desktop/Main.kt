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
import my.cheysoff.desktop.app.DesktopSyncState
import my.cheysoff.desktop.keychain.CredentialStores
import my.cheysoff.desktop.platform.HostOs
import my.cheysoff.desktop.ui.CreatePassphraseScreen
import my.cheysoff.desktop.ui.DamagedScreen
import my.cheysoff.desktop.ui.FirstRunScreen
import my.cheysoff.desktop.ui.MananaColors
import my.cheysoff.desktop.ui.PairingScreen
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
            // One pass when the vault opens, which is the moment a person is most likely to want
            // what the other device wrote while this one was closed. Keyed on the screen so it runs
            // once per unlock rather than on every recomposition.
            LaunchedEffect(screen) { controller.syncNow() }

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
                syncLabel = syncLabelOf(controller.syncState),
                onSync = if (controller.syncState is DesktopSyncState.Unavailable) null
                else controller::syncNow,
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
        is AppController.Screen.FirstRun -> FirstRunScreen(
            onPair = controller::choosePairing,
            onUseStandalone = controller::chooseStandalone,
        )

        is AppController.Screen.Pairing -> PairingScreen(
            step = screen.controller.step,
            onAddressChange = screen.controller::editAddress,
            onStart = screen.controller::start,
            // Confirming the SAS is the only thing on this screen the *controller* cannot finish on
            // its own: it hands the bundle over, and only AppController knows where it goes next.
            onConfirmSas = {
                screen.controller.confirmSas()
                controller.pairingConfirmed()
            },
            onRejectSas = screen.controller::rejectSas,
            onStartOver = screen.controller::startOver,
            onBack = controller::backToFirstRun,
        )

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

/**
 * What the title bar says about syncing, or null when this device cannot sync at all.
 *
 * Deliberately never says "up to date". A completed pass means the server held what this device
 * held **at that moment**, which is a fact about the past; the phrasing has to survive being read
 * ten minutes later, when it is no longer evidence of anything.
 */
internal fun syncLabelOf(state: DesktopSyncState): String? = when (state) {
    DesktopSyncState.Unavailable -> null
    DesktopSyncState.Idle -> "Sync"
    DesktopSyncState.Syncing -> "Syncing..."
    is DesktopSyncState.Done ->
        if (state.applied == 0) "Synced, nothing new" else "Synced, ${state.applied} new"
    DesktopSyncState.Deferred -> "Server asked to wait"
    is DesktopSyncState.Halted -> "Stopped: ${state.reason}"
    is DesktopSyncState.Failed -> "Couldn't reach the server"
}
