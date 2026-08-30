package my.cheysoff.feature_pairing.protocol

/**
 * The one-way key-derivation function the pairing protocol needs, and the ONLY place this module
 * touches the sync key hierarchy.
 *
 * ## Why this is an interface rather than a call to `Hkdf`
 *
 * Not because there is a choice of implementation — there is exactly one, [HkdfKeyDerivation] over
 * `core-crypto`'s `Hkdf`, and adding a second would be the bug. It is an interface because it is
 * the seam: it names, in one place, everything this module needs from the key hierarchy, so a test
 * can substitute a *deliberately wrong* KDF to prove the protocol above it notices (see
 * `PairingEndToEndTest.oneSideDerivingWithADifferentInfoCannotPair`), and so nothing in
 * `protocol/` has to import another module.
 *
 * The history is worth keeping: this module was landed before the key hierarchy existed and its
 * tests bound their own RFC-5869 HKDF. Two implementations of one protocol primitive is how the
 * halves of a protocol drift — a mismatch in how `info` is assembled, or in whether a zero-length
 * salt means "no salt" or "a 32-byte block of zeros", produces two versions that each pass their
 * own tests and cannot talk to each other. The two were checked byte for byte against each other
 * and the second was then deleted; `HkdfSeamTest` pins what is left to literal bytes.
 *
 * ## Contract
 *
 * `derive` must be RFC 5869 HKDF-SHA256: extract with `salt` as the HMAC key over `ikm`, then
 * expand with `info` to `outLen` bytes. It must be deterministic — the entire pairing scheme rests
 * on both devices computing byte-identical output from the same three inputs — and it must not
 * mutate or retain its arguments.
 *
 * @see PairingProtocol for the exact `salt`/`info` values this module passes.
 */
fun interface KeyDerivation {

    /**
     * @param ikm input keying material. For pairing this is the raw ECDH shared secret; it is key
     *   material and must never be logged.
     * @param salt the HKDF-Extract salt. Never empty in this module — pairing always passes `sid`.
     * @param info the HKDF-Expand context string.
     * @param outLen how many bytes to produce. At most 255 * 32 for SHA-256.
     * @return exactly [outLen] fresh bytes.
     */
    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, outLen: Int): ByteArray
}
