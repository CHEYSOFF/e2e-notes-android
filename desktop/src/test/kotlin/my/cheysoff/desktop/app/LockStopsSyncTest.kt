package my.cheysoff.desktop.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import my.cheysoff.core_crypto.sync.AccountRootKey
import my.cheysoff.desktop.keychain.NoCredentialStore
import my.cheysoff.desktop.vault.AccountOrigin
import my.cheysoff.desktop.vault.DesktopVault
import my.cheysoff.desktop.vault.DeviceKeyPair
import my.cheysoff.desktop.vault.PairedEnrolment
import my.cheysoff.desktop.vault.SetupResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Locking tears down the sync triggers along with the vault.
 *
 * This became load-bearing when the periodic pass landed. Before it, a trigger that survived a lock
 * was survivable in practice: the only other trigger fires on a local write, and nothing writes
 * while the app is locked. A timer has no such excuse — it would call a pass every minute against a
 * closed store, holding a service whose keys the session has just zeroed, for as long as the app
 * stayed open.
 *
 * The jobs themselves are private, so what is asserted is what a user could see: after a lock this
 * device reports that it cannot sync, and the screen is back at the passphrase.
 *
 * A real scope and real waiting, not `runTest`: `AppController.withVault` hops to
 * `Dispatchers.Default` for the PBKDF2, which a test scheduler's virtual time does not drive — so
 * `advanceUntilIdle` returns while the unlock is still in flight and the assertions read a screen
 * that has not changed yet. The waits below are bounded and assert on timeout.
 */
class LockStopsSyncTest {

    @get:Rule val folder = TemporaryFolder()

    private val passphrase = "lock-stops-sync-passphrase"

    @Test
    fun `a paired vault syncs until it is locked, and not after`() {
        val vault = DesktopVault(
            directory = folder.newFolder("vault").toPath(),
            credentialStore = NoCredentialStore,
        )
        val created = vault.setUp(
            passphrase.toCharArray(),
            AccountOrigin.PAIRED,
            AccountRootKey.generateArk(),
            PairedEnrolment(
                serverUrl = "https://notes.example.com",
                deviceId = "device-id",
                deviceKey = DeviceKeyPair.generate(),
            ),
        )
        assertTrue("set up did not succeed: $created", created is SetupResult.Created)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = AppController(vault = vault, scope = scope)

            controller.unlock(passphrase.toCharArray())
            awaitUntil("the vault to open") { controller.screen is AppController.Screen.Open }

            assertTrue(
                "a paired vault must come up able to sync, but was ${controller.syncState}",
                controller.syncState !is DesktopSyncState.Unavailable,
            )

            controller.lock()

            assertEquals(
                "a locked vault cannot sync, and must not claim otherwise",
                DesktopSyncState.Unavailable,
                controller.syncState,
            )
            assertEquals(AppController.Screen.Unlock, controller.screen)
        } finally {
            scope.cancel()
        }
    }

    /** Polls [condition] until it holds, failing the test rather than hanging the suite. */
    private fun awaitUntil(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out after ${timeoutMs}ms waiting for $what")
    }
}
