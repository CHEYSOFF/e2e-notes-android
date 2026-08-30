package my.cheysoff.feature_pairing.protocol

/**
 * The one-way key-derivation function the pairing protocol needs, and the ONLY place this module
 * touches the sync key hierarchy.
 *
 * ## Why this is an interface here rather than an implementation
 *
 * The sync work is being landed in phases. Phase 1 (`sync-phase1-crypto`) owns the real
 * HKDF-SHA256 (RFC 5869) together with the ARK hierarchy, blinded record IDs and the record
 * envelope. Phase 2 — this module — owns pairing. A second copy of HKDF living in this module is
 * exactly how two halves of one protocol drift apart: a mismatch in how `info` is assembled, or in
 * whether a zero-length salt means "no salt" or "a 32-byte block of zeros", produces two
 * implementations that each pass their own tests and cannot talk to each other.
 *
 * So there is deliberately **no production implementation of this interface on this branch**. The
 * unit tests bind a test fake (a straightforward RFC 5869 HKDF-SHA256, checked against the RFC's
 * published vectors) so the protocol above it can be tested end to end, and the production binding
 * is a one-line `@Binds` in [my.cheysoff.feature_pairing.di.PairingSeamModule] once Phase 1 lands.
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
