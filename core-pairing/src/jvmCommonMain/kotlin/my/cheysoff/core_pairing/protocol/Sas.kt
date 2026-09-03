package my.cheysoff.core_pairing.protocol

import java.math.BigInteger

/**
 * The six-digit short authentication strings, one per pairing direction.
 *
 * **There are two, they are computed from different key material, and they are worth different
 * things.** [derive] is the scanned direction's and is a mis-scan check; [deriveFromAgreement] is
 * the invite direction's and is that direction's entire man-in-the-middle defence. Each documents
 * only itself, deliberately: a shared paragraph would end up claiming the stronger of the two
 * guarantees for both.
 *
 * ## Six digits
 *
 * ~20 bits, i.e. a one-in-a-million chance that two unrelated exchanges show the same code. That
 * is the right order for a code a human compares by eye. What twenty bits *buys* differs by
 * direction and is stated on each function.
 *
 * ## Bias
 *
 * Reducing 64 uniform bits mod 10^6 is biased by about 5.4e-14 in relative terms — the low
 * residues are that much more likely than the high ones. Four bytes would have been enough for the
 * design's purpose (bias ~2.3e-7) but eight costs nothing and removes the question. Rejection
 * sampling would remove it entirely and is not worth the extra state and the extra failure mode.
 */
object Sas {

    /** Number of decimal digits shown. */
    const val DIGITS = 6

    /** Bytes drawn from the KDF before reduction. See the bias note above. */
    private const val KDF_OUTPUT_BYTES = 8

    private val MODULUS: BigInteger = BigInteger.TEN.pow(DIGITS)

    /**
     * The SAS both devices display at the end of a **scanned** pairing — QR1 then QR2.
     *
     * ```
     *   sas = HKDF(ikm = ARK, salt = sid, info = "manana/pair/v1/confirm", 8) mod 1_000_000
     * ```
     *
     * ## Not the man-in-the-middle defence, in this direction
     *
     * A man in the middle is structurally impossible in the scanned exchange: the only key the
     * account device has to authenticate is `EB`, and it obtains `EB` by a human pointing a camera
     * at the new device's screen. There is no channel for an attacker to sit in the middle of —
     * they would have to be physically holding the phone the user is looking at, at which point
     * pairing is not the problem. **[deriveFromAgreement]'s direction has no such property**; do
     * not carry this paragraph across to it.
     *
     * What this SAS catches is the mundane failure the scanned design is actually exposed to: the
     * user scanned the wrong phone, or two people are pairing in the same room and the codes
     * crossed. Both devices derive it from the ARK that came out of the exchange and the `sid` that
     * identified the exchange, so it matches if and only if both devices ended up with the same
     * account key from the same session. The account device knows the ARK from the start; the new
     * device only learns it by opening the seal — so a matching SAS is the new device *proving* it
     * opened the right seal, not merely echoing something it was told.
     *
     * Twenty bits is not load-bearing against an adversary here for the reason above, and nobody
     * gets to try a million times: a failed comparison means the ARK has already crossed and the
     * fix is to start over with a new `sid`.
     *
     * @param ark the account root key. Key material — it is read, reduced, and never retained or
     *   logged here.
     * @param sid the 16-byte session id that identified this pairing attempt.
     * @return exactly [DIGITS] characters, zero-padded (`"000472"`, not `"472"`).
     */
    fun derive(keyDerivation: KeyDerivation, ark: ByteArray, sid: ByteArray): String {
        require(ark.size == AccountBundle.ARK_SIZE_BYTES) {
            "an ARK is ${AccountBundle.ARK_SIZE_BYTES} bytes"
        }
        require(sid.size == PairingProtocol.SID_SIZE_BYTES) {
            "a sid is ${PairingProtocol.SID_SIZE_BYTES} bytes"
        }
        val bytes = keyDerivation.derive(
            ikm = ark,
            salt = sid,
            info = PairingProtocol.SAS_INFO.toByteArray(Charsets.US_ASCII),
            outLen = KDF_OUTPUT_BYTES,
        )
        // signum 1 so the top bit of the first byte is magnitude rather than a sign.
        return reduce(bytes)
    }

    /**
     * Derive the SAS both devices display in the **invite** direction.
     *
     * ```
     *   sas = HKDF(ikm = Z, salt = sid, info = "manana/pair/v1/confirm/inv" ‖ EA ‖ EB, 8)
     *         mod 1_000_000
     * ```
     *
     * ## This one IS the man-in-the-middle defence
     *
     * Everything [derive]'s documentation says about the SAS not being load-bearing applies to the
     * scanned direction and **does not apply here**, and the difference has to be stated rather
     * than inherited.
     *
     * In the scanned direction the account device obtains the joining device's ephemeral key by
     * having a person point a camera at it. There is no channel to interpose on, so the SAS only
     * catches mis-scans. In the invite direction the joining device's key travels through the
     * rendezvous server, which is exactly a channel an attacker may control. If they substitute
     * their own `EB`, they agree one secret with the account device and the honest device agrees a
     * different one — and *nothing in the protocol notices*. The two six-digit strings differ, a
     * person compares them, and that comparison is the only thing between an attacker and the
     * account root key.
     *
     * So it is derived from the agreed secret rather than the ARK, because the ARK has not moved
     * yet and must not move until this succeeds; and both public points are bound into `info`, so
     * that agreeing "the same" secret by any other pair of keys produces different digits.
     *
     * ## Six digits, and what they are worth here
     *
     * ~20 bits. An attacker who substitutes a key gets **one** attempt per pairing: the rendezvous
     * slot is single-use, a mismatch ends the attempt, and the next one has a fresh `sid` and fresh
     * ephemerals. So the odds are one in a million per attempt and there is no way to grind them —
     * but that is a probabilistic guarantee resting on a human comparing digits carefully, where
     * the scanned direction's guarantee rests on physics. That is the trade this direction makes,
     * and it is why the scanned direction is still the one offered first.
     *
     * @param sharedSecret the raw ECDH output `Z`. Key material: read here, never retained.
     */
    fun deriveFromAgreement(
        keyDerivation: KeyDerivation,
        sharedSecret: ByteArray,
        sid: ByteArray,
        encodedEa: ByteArray,
        encodedEb: ByteArray,
    ): String {
        require(sid.size == PairingProtocol.SID_SIZE_BYTES) {
            "a sid is ${PairingProtocol.SID_SIZE_BYTES} bytes"
        }
        val bytes = keyDerivation.derive(
            ikm = sharedSecret,
            salt = sid,
            info = PairingProtocol.inviteSasInfo(encodedEa, encodedEb),
            outLen = KDF_OUTPUT_BYTES,
        )
        return reduce(bytes)
    }

    /** signum 1 so the top bit of the first byte is magnitude rather than a sign. */
    private fun reduce(bytes: ByteArray): String =
        BigInteger(1, bytes).mod(MODULUS).toString().padStart(DIGITS, '0')
}
