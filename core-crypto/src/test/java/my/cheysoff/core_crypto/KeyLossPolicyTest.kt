package my.cheysoff.core_crypto

import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.KeyStoreException
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The predicate guarded here decides whether to delete every wrap of the DB passphrase — and with
 * it, via wasStateReset, the whole notes database. Both of the bugs that shipped in this area came
 * from it being written inline with no test, so the cases below are the ones that were wrong.
 *
 * KeyPermanentlyInvalidatedException is not exercised: it is an android.* class with no JVM
 * implementation on the unit-test classpath. Negative checks against it still work (nothing built
 * here is an instance of it), which is all these tests need.
 */
class KeyLossPolicyTest {

    /**
     * The case the reset path exists for: a cloud/D2D restore brings back `secret_shared_prefs`
     * but not the non-exportable master key, so the freshly minted key cannot authenticate the
     * restored keyset. Tink surfaces this as a bad GCM tag, directly or wrapped.
     */
    @Test
    fun `a bad GCM tag is provable key loss, however deeply wrapped`() {
        assertTrue(KeyLossPolicy.isProvableKeyLoss(AEADBadTagException("tag mismatch")))
        assertTrue(
            KeyLossPolicy.isProvableKeyLoss(
                KeyStoreException("the master key X exists but is unusable", AEADBadTagException())
            )
        )
        assertTrue(
            KeyLossPolicy.isProvableKeyLoss(
                RuntimeException(GeneralSecurityException(AEADBadTagException()))
            )
        )
    }

    /**
     * REGRESSION. Tink reports a master key that exists but can no longer be loaded as
     * KeyStoreException wrapping InvalidKeyException. An earlier version treated KeyStoreException
     * as terminal, which wiped notes over faults that clear on their own; the version after that
     * dropped it entirely, which crash-looped forever on the faults that do not. It must be
     * neither — not provable here, so openPrefs falls through to the launch counter.
     */
    @Test
    fun `Tink's unusable-master-key error is not provable on first sighting`() {
        val tinkTerminal = KeyStoreException(
            "the master key android-keystore://_androidx_security_master_key_ exists but is unusable",
            InvalidKeyException("Keystore cannot load the key with ID: foo"),
        )
        assertFalse(KeyLossPolicy.isProvableKeyLoss(tinkTerminal))
    }

    /**
     * REGRESSION. KeyStoreException, InvalidKeyException and AEADBadTagException all EXTEND
     * GeneralSecurityException, so accepting the base class collapsed the predicate into "any
     * crypto error at all" — on a path that deletes the database with no retry.
     */
    @Test
    fun `a bare crypto error is not provable key loss`() {
        assertFalse(KeyLossPolicy.isProvableKeyLoss(GeneralSecurityException("boom")))
        assertFalse(KeyLossPolicy.isProvableKeyLoss(InvalidKeyException("boom")))
        assertFalse(KeyLossPolicy.isProvableKeyLoss(KeyStoreException("boom")))
        assertFalse(KeyLossPolicy.isProvableKeyLoss(java.io.IOException("disk full")))
        assertFalse(KeyLossPolicy.isProvableKeyLoss(RuntimeException(IllegalStateException())))
    }

    /** A self-referential cause must not spin forever on a path that is already handling corruption. */
    @Test
    fun `a cyclic cause chain terminates`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertFalse(KeyLossPolicy.isProvableKeyLoss(a))
    }
}
