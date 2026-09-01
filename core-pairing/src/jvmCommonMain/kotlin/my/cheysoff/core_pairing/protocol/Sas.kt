package my.cheysoff.core_pairing.protocol

import java.math.BigInteger

/**
 * The six-digit short authentication string both devices show at the end of a pairing.
 *
 * ```
 *   sas = HKDF(ikm = ARK, salt = sid, info = "manana/pair/v1/confirm", 8 bytes) mod 1_000_000
 * ```
 *
 * ## What it is actually for
 *
 * Not MITM defence. A man in the middle is structurally impossible in this exchange: the only key
 * device A has to authenticate is `EB`, and A obtains `EB` by a human pointing a camera at device
 * B's screen. There is no channel for an attacker to sit in the middle of — they would have to be
 * physically holding the phone the user is looking at, at which point pairing is not the problem.
 *
 * What the SAS catches is the mundane failure this design is actually exposed to: the user scanned
 * the wrong phone, or two people are pairing in the same room and the codes crossed. Both devices
 * derive it from the ARK that came out of the exchange and the `sid` that identified the exchange,
 * so it matches if and only if both devices ended up with the same account key from the same
 * session. Device A knows the ARK from the start; device B only learns it by opening the seal — so
 * a matching SAS is B *proving* it opened the right seal, not merely echoing something it was told.
 *
 * ## Six digits
 *
 * ~20 bits, i.e. a one-in-a-million chance that a wrong pairing shows a matching code. That is the
 * right order for a code a human compares by eye, and it is not load-bearing against an adversary
 * for the reason above: nobody gets to *try* a million times, because a failed comparison means the
 * user has already got the other device's ARK on this one and the fix is to start over with a new
 * `sid`.
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
     * Derive the SAS both devices display.
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
        val value = BigInteger(1, bytes).mod(MODULUS)
        return value.toString().padStart(DIGITS, '0')
    }
}
