package my.cheysoff.feature_pairing.protocol

import java.security.GeneralSecurityException
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM around the account bundle, with `"manana/pair/v1" ‖ sid` as additional authenticated
 * data.
 *
 * ## Why the AAD is not optional
 *
 * The ciphertext alone says "someone who knew `Ks` sealed this". The AAD is what pins it to *this*
 * pairing attempt. Drop `sid` from it and a QR2 lifted from an earlier session between the same two
 * phones becomes replayable the moment `Ks` collides — and more practically, it removes the second
 * of the two independent bindings the design deliberately has (the other being `sid` as the HKDF
 * salt). Redundant bindings are the point: a mistake in either one alone is not exploitable.
 * `PairingSealTest.aadMustCarrySid` is the test that fails if this stops being true.
 *
 * ## Nonce
 *
 * 12 fresh bytes from `SecureRandom` per seal, never a counter. `Ks` is derived from an ephemeral
 * ECDH and is used for exactly one message, so there is no birthday bound to worry about, and a
 * counter would be a way for a restored backup to silently repeat a nonce under a repeated key.
 */
internal object PairingSeal {

    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /**
     * Seal [bundle] under [sessionKey].
     *
     * @return the raw GCM output: ciphertext followed by the 16-byte tag, as JCA lays it out.
     */
    fun seal(sessionKey: ByteArray, nonce: ByteArray, sid: ByteArray, bundle: AccountBundle): ByteArray {
        require(sessionKey.size == PairingProtocol.SESSION_KEY_SIZE_BYTES)
        require(nonce.size == PairingProtocol.GCM_NONCE_SIZE_BYTES)
        require(sid.size == PairingProtocol.SID_SIZE_BYTES)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(sessionKey, "AES"),
            GCMParameterSpec(PairingProtocol.GCM_TAG_SIZE_BITS, nonce),
        )
        cipher.updateAAD(PairingProtocol.sealAad(sid))
        return cipher.doFinal(PairingCodec.encodeBundle(bundle))
    }

    /**
     * Open a seal, or return null if GCM rejects it.
     *
     * Null rather than an exception because the caller — [NewDeviceSession] — has exactly one
     * correct response to a tag failure and it is not a retry: kill the session with
     * [PairingFailure.SEAL_REJECTED] and tell the user. Nothing about *why* the tag failed is
     * returned, and nothing is logged: a caller that could distinguish "wrong key" from "wrong AAD"
     * from "modified ciphertext" would be an oracle, and the honest answer to all three is the
     * same one.
     */
    fun open(sessionKey: ByteArray, nonce: ByteArray, sid: ByteArray, seal: ByteArray): ByteArray? {
        if (sessionKey.size != PairingProtocol.SESSION_KEY_SIZE_BYTES) return null
        if (nonce.size != PairingProtocol.GCM_NONCE_SIZE_BYTES) return null
        if (sid.size != PairingProtocol.SID_SIZE_BYTES) return null

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(sessionKey, "AES"),
                GCMParameterSpec(PairingProtocol.GCM_TAG_SIZE_BITS, nonce),
            )
            cipher.updateAAD(PairingProtocol.sealAad(sid))
            cipher.doFinal(seal)
        } catch (e: GeneralSecurityException) {
            // The expected failure, and every shape it comes in. AEADBadTagException is the one the
            // specification names; providers also report a tag mismatch as its superclass
            // BadPaddingException, and an input shorter than the tag as IllegalBlockSizeException or
            // ShortBufferException. All four are GeneralSecurityException, all four mean the same
            // thing to this caller -- "this did not open" -- and distinguishing them would build an
            // oracle out of the difference.
            null
        } catch (e: ProviderException) {
            // Not a GeneralSecurityException: it is an unchecked wrapper a provider throws when it
            // hits an internal limit. Observed on JDK 17's SunJCE, which reports a GCM input shorter
            // than its own tag as ProviderException(ShortBufferException) rather than as a checked
            // exception. Caught because the input here comes off a stranger's QR code and this
            // function's contract is that it returns null rather than throwing.
            null
        }
    }
}
