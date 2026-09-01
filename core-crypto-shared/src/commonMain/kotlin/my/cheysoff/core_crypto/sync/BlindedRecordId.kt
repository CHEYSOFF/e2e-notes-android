package my.cheysoff.core_crypto.sync

import my.cheysoff.core_crypto.platform.hmacSha256

/**
 * Turns a local record identity into the opaque handle the server files it under.
 *
 * ```
 * blindedId = base64url( HMAC-SHA256(K_id, recType ‖ ":" ‖ uuid)[0..16] )
 * ```
 *
 * **The raw note UUID never leaves the device.** The server needs a stable per-record key so it
 * can apply an update to the right blob, and that is all it gets: an unlinkable 128-bit label it
 * cannot invert (HMAC is a PRF and `K_id` is a 256-bit secret) and cannot correlate across
 * accounts, since a different ARK gives a different `K_id` and therefore a completely different
 * label for the same UUID.
 *
 * Truncating a 256-bit HMAC tag to 128 bits is standard and safe here: the property needed is
 * collision resistance across the number of records one user owns, where 2^64 birthday work is
 * many orders of magnitude out of reach, and 22 base64url characters make a much better path
 * segment than 43.
 *
 * Common code over the one HMAC primitive in `platform` — no Android, no JVM, no state,
 * unit-testable on every target. Never logs `K_id` or the UUID.
 */
object BlindedRecordId {

    /**
     * Computes the blinded ID for ([recType], [uuid]) under [kId].
     *
     * [recType] is part of the HMAC message rather than a prefix on the output, so `note:X` and
     * `folder:X` are unrelated labels — the server cannot tell that a note and a folder share an
     * underlying identifier, and a record cannot be moved between types by relabelling it.
     *
     * Deterministic: the same three inputs always give the same string, which is what lets two
     * devices independently name the same record without ever exchanging the mapping.
     */
    fun compute(kId: ByteArray, recType: String, uuid: String): String {
        require(kId.isNotEmpty()) { "K_id must not be empty" }
        // `encodeToByteArray()` rather than `toByteArray(Charsets.UTF_8)`: `Charsets` is a JVM
        // type. The two agree on every string this is called with -- record types are literals
        // and UUIDs are ASCII -- and differ only on a lone surrogate, which a UUID cannot contain.
        val message =
            (recType + SyncProtocol.BLINDED_ID_SEPARATOR + uuid).encodeToByteArray()

        val tag = hmacSha256(key = kId, message = message)

        val truncated = tag.copyOf(SyncProtocol.BLINDED_ID_BYTES)
        val encoded = Base64Url.encode(truncated)
        // The discarded half of the tag is still derived from K_id; there is no reason to leave it
        // in a heap array that outlives this call.
        tag.fill(0)
        truncated.fill(0)
        return encoded
    }
}
