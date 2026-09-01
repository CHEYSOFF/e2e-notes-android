package my.cheysoff.ios

import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import my.cheysoff.core_crypto.sync.AccountKeys
import my.cheysoff.ios.platform.ArkVault
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIViewController

/**
 * The Swift entry point: `MananaApp.MainViewControllerKt.mainViewController()`.
 *
 * Kotlin/Native exports a top-level function as a static method on a class named after its file,
 * so `iosApp/iOSApp.swift` calls `MainViewControllerKt.mainViewController()` and wraps the result
 * in a SwiftUI `UIViewControllerRepresentable`. That name is a **contract with the Swift side**:
 * renaming this file or this function silently breaks the app with a Swift compile error that does
 * not mention Kotlin.
 *
 * The [AppModel] is created here rather than inside a composable, so its scope's lifetime is the
 * view controller's rather than a composition's. A model recreated by a recomposition would drop
 * the account keys and send the user back to the lock screen.
 *
 * NOT RUN. Compiles for every iOS target; has never launched. See `docs/BUILDING-IOS.md`.
 */
fun mainViewController(): UIViewController {
    val model = AppModel(
        vault = KeychainVault(),
        // `SupervisorJob` so that one failed coroutine -- a save that throws because the database
        // file is unreadable -- does not cancel the flow that feeds the notes list and leave the
        // app showing a stale, silent screen.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
        now = { (NSDate().timeIntervalSince1970 * 1000.0).toLong() },
    )
    return ComposeUIViewController { App(model) }
}

/** [Vault] over the Keychain. The adapter exists so `AppModel` needs nothing from `platform.*`. */
private class KeychainVault(private val vault: ArkVault = ArkVault()) : Vault {
    override fun exists(): Boolean = vault.exists()
    override fun create(pin: CharArray): AccountKeys? = vault.create(pin)
    override fun unlock(pin: CharArray): AccountKeys? = vault.unlock(pin)
}
