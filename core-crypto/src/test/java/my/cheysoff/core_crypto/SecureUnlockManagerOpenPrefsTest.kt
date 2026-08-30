package my.cheysoff.core_crypto

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import java.security.GeneralSecurityException
import java.security.KeyStoreException
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `SecureUnlockManager.openPrefs` — the cross-launch key-loss counter.
 *
 * This is the code path that decides whether the user's entire notes database gets deleted.
 * [KeyLossPolicy] already has its own tests for the "is this error provably terminal?" half; the
 * half tested here is the other one, which had none: the in-process retry budget, the counter that
 * spans process launches, and the reset that fires once that budget is spent.
 *
 * Both directions of the decision are expensive, and both are asserted below:
 *   - resetting too eagerly destroys every note (`wasStateReset`, `discardCount`);
 *   - never resetting leaves the app crash-looping with no way out but Clear App Data.
 *
 * A separate class from [SecureUnlockManagerTest] because these tests deliberately drive the
 * failure path of [FakeEncryptedPrefsStore.open], and because they share a fixture the other file
 * does not touch: the PLAIN `secure_unlock_health` prefs, which is where the launch counter lives
 * and the only piece of state that has to survive a manager being thrown away.
 *
 * Each of these tests spends real wall time in `openPrefs`'s backoff — 50ms + 100ms per failing
 * launch, so at most ~750ms for the five-launch case. That is the production behaviour, not a test
 * artifact, and it is left alone rather than made configurable.
 */
@RunWith(RobolectricTestRunner::class)
class SecureUnlockManagerOpenPrefsTest {

    /** Mirrors SecureUnlockManager's private companion; see the note in SecureUnlockManagerTest. */
    private val healthPrefsName = "secure_unlock_health"
    private val keyOpenFailures = "prefs_open_failures"

    /** Mirrors `OPEN_ATTEMPTS`: tries per launch before the launch counts as failed. */
    private val openAttempts = 3

    /** Mirrors `OPEN_FAILURE_LAUNCHES`: failed launches needed before the file is discarded. */
    private val openFailureLaunches = 5

    private lateinit var context: Context
    private lateinit var store: FakeEncryptedPrefsStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = FakeEncryptedPrefsStore(context)
        store.clearFile()
        health().edit().clear().commit()
    }

    private fun health(): SharedPreferences =
        context.getSharedPreferences(healthPrefsName, Context.MODE_PRIVATE)

    private fun newManager() = SecureUnlockManager(context, BiometricKeystoreCipher(), store)

    /**
     * Forces the lazy `prefs` to be opened. `isPinSet()` is the first thing the auth screen calls
     * on a real launch, so this is exactly how the failure surfaces in production.
     */
    private fun touchPrefs(manager: SecureUnlockManager) = manager.isPinSet()

    /**
     * An error whose type proves nothing either way — Tink raises this for its terminal
     * "master key exists but is unusable" case AND for trouble that clears on its own, which is
     * precisely why [KeyLossPolicy] refuses to classify it and the launch counter has to.
     */
    private fun ambiguousFailure() = KeyStoreException("the master key ... exists but is unusable")

    // ---------------------------------------------------------------------------------------

    @Test
    fun `a failure that clears within the retry budget is absorbed, with no reset`() {
        // Keystore momentarily busy at cold start is real. The in-process retries exist to absorb
        // it, and absorbing it must leave no trace: no reset, no discard, no counter.
        store.failuresQueued = openAttempts - 1

        val manager = newManager()
        touchPrefs(manager)

        assertEquals("every retry in the budget was used", openAttempts, store.openCount)
        assertEquals("nothing was thrown away", 0, store.discardCount)
        assertFalse(manager.wasStateReset)
        assertFalse(health().contains(keyOpenFailures))
    }

    @Test
    fun `an open that succeeds first time clears a counter left by earlier launches`() {
        // Only CONSECUTIVE failures are evidence. Without this, four bad launches spread over a
        // month would arm the reset for the fifth, whenever that happened to fall.
        health().edit().putInt(keyOpenFailures, openFailureLaunches - 1).commit()

        val manager = newManager()
        touchPrefs(manager)

        assertFalse("the history was cleared", health().contains(keyOpenFailures))
        assertEquals(0, store.discardCount)
        assertFalse(manager.wasStateReset)
    }

    @Test
    fun `a launch that exhausts its retries rethrows and records one failure`() {
        // Rethrowing crashes the app, which is bad — and deliberate. The ciphertext is all still on
        // disk at this point, so a crash is recoverable and a reset is not.
        store.failure = ::ambiguousFailure
        store.failuresQueued = openAttempts

        val manager = newManager()
        assertThrows(KeyStoreException::class.java) { touchPrefs(manager) }

        assertEquals(1, health().getInt(keyOpenFailures, -1))
        assertEquals("the file must NOT be discarded on the first bad launch", 0, store.discardCount)
        assertFalse(manager.wasStateReset)
    }

    @Test
    fun `it takes OPEN_FAILURE_LAUNCHES failed launches to discard the file`() {
        // The core of the whole design: five separate app launches, each getting a fresh manager
        // with nothing in memory, all sharing the one counter on disk.
        store.failure = ::ambiguousFailure

        for (launch in 1 until openFailureLaunches) {
            store.failuresQueued = openAttempts
            val manager = newManager()
            assertThrows(KeyStoreException::class.java) { touchPrefs(manager) }
            assertEquals("launch $launch is counted", launch, health().getInt(keyOpenFailures, -1))
            assertEquals("and nothing is destroyed yet", 0, store.discardCount)
            assertFalse("nor reported as reset", manager.wasStateReset)
        }

        // The last launch: retries are exhausted as before, but this time the counter reaches the
        // threshold, so the file is discarded and a fresh one opened instead of rethrowing.
        store.failuresQueued = openAttempts
        val fifth = newManager()
        touchPrefs(fifth)

        assertEquals("exactly one discard, never more", 1, store.discardCount)
        assertTrue("DataModule reads this to drop the undecryptable database", fifth.wasStateReset)
        assertFalse("and the counter starts over", health().contains(keyOpenFailures))
        // The reset must leave a usable file behind, not a hole: the setup screen runs next.
        assertFalse(fifth.isPinSet())
        fifth.setupPin(charArrayOf('1', '2', '3', '4', '5', '6'))
        assertTrue(fifth.isPinSet())
    }

    @Test
    fun `a provable key loss resets on its first sighting instead of waiting five launches`() {
        // AEADBadTagException means the stored keyset does not authenticate under the key the
        // Keystore now holds — the same bytes and the same key every retry. Making the user crash
        // four more times to learn that would be pure cost.
        store.failure = { GeneralSecurityException("wrapped by Tink", AEADBadTagException()) }
        store.failuresQueued = openAttempts

        val manager = newManager()
        touchPrefs(manager)

        assertEquals(1, store.discardCount)
        assertTrue(manager.wasStateReset)
        assertFalse(health().contains(keyOpenFailures))
    }

    @Test
    fun `an ambiguous error is NOT treated as provable key loss`() {
        // The inverse of the test above, and the bug that shipped: a blanket
        // GeneralSecurityException match swept KeyStoreException in with AEADBadTagException and
        // deleted notes over faults that would have cleared.
        store.failure = ::ambiguousFailure
        store.failuresQueued = openAttempts

        val manager = newManager()
        assertThrows(KeyStoreException::class.java) { touchPrefs(manager) }

        assertEquals(0, store.discardCount)
        assertFalse(manager.wasStateReset)
    }

    @Test
    fun `wasStateReset is false on a healthy launch`() {
        val manager = newManager()
        touchPrefs(manager)
        assertFalse(manager.wasStateReset)
    }

    @Test
    fun `an interrupt during the backoff abandons the remaining retries`() {
        // The backoff runs on whichever thread first touches `prefs`, which may be a pooled one.
        // Once that thread is interrupted, every remaining Thread.sleep would throw immediately,
        // collapsing the doubling backoff into a busy loop that hammers a Keystore that is already
        // failing. openPrefs breaks out of the loop instead.
        store.failure = ::ambiguousFailure
        store.failuresQueued = openAttempts

        val manager = newManager()
        Thread.currentThread().interrupt()
        assertThrows(KeyStoreException::class.java) { touchPrefs(manager) }

        // Attempt 0 ran; attempt 1 hit the interrupted sleep and broke out before opening again.
        // Without the break this would be `openAttempts` opens with no waiting between them.
        assertEquals("no further attempts after the interrupt", 1, store.openCount)
        // The launch still counts, exactly as an uninterrupted failed launch would: an interrupt
        // says nothing about whether the keyset is recoverable.
        assertEquals("the launch still counts as failed", 1, health().getInt(keyOpenFailures, -1))
        assertEquals(0, store.discardCount)

        // openPrefs re-raises the interrupt for whoever owns this thread, but that is NOT
        // observable from out here and is deliberately not asserted: the failure counter it writes
        // next goes through SharedPreferences.commit(), which blocks on a latch and swallows a
        // pending InterruptedException, clearing the flag again. Measured directly at API 35 —
        // interrupt(), then commit(), leaves isInterrupted() false. Clearing here regardless so
        // that nothing can leak into the next test if that ever changes.
        Thread.interrupted()
    }
}
