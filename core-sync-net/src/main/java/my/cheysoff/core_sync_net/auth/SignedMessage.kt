package my.cheysoff.core_sync_net.auth

import java.io.ByteArrayOutputStream

/**
 * The canonical byte encoding of everything this device signs for the sync server.
 *
 * ```
 * message := lp("manana/sync/v1/sig") ‖ lp(purpose) ‖ lp(field_1) ‖ … ‖ lp(field_n)
 * lp(s)   := uint16be(len(utf8(s))) ‖ utf8(s)
 * ```
 *
 * **Changing anything in this file is a breaking protocol change**, in the same sense and for the
 * same reason as `core-crypto/.../sync/SyncProtocol.kt`. There is no negotiation step in this
 * protocol and no fallback: one byte of disagreement with the server and *every* signature this
 * device makes is rejected, forever, with a `401 bad_signature` that says nothing about why.
 *
 * ## Why it is written twice
 *
 * The server has its own copy in `server/.../Signatures.kt`. They are two implementations of one
 * specification on purpose -- the server build is standalone and must not depend on an Android
 * library -- and that is exactly the arrangement in which two implementations quietly diverge. This
 * project has already shipped that failure once, with two HKDFs. The defence is not care; it is
 * `SyncServerContractTest`, which signs with *this* file and enrols against the *real* server, so
 * a divergence fails a test instead of a user's first sync.
 *
 * The specification the two are written against is `server/README.md`, "The canonical signed
 * message -- implement this exactly". Neither file is the authority; the README is.
 *
 * ## Why every field is length-prefixed
 *
 * Plain concatenation is ambiguous across adjacent variable-length fields. Without the prefixes,
 * `("authorize", "AB", "C…")` and `("authorize", "A", "BC…")` produce the same bytes, so a
 * signature authorising one public key would verify as a signature authorising a different one. The
 * length prefixes make the encoding injective.
 *
 * The domain string is prefixed for the same reason -- so that no `purpose` can be confused with a
 * longer domain -- and `purpose` is its own field so that a `session` signature can never be
 * replayed as an `authorize`.
 */
object SignedMessage {

    private const val DOMAIN = "manana/sync/v1/sig"

    const val PURPOSE_CLAIM = "claim"
    const val PURPOSE_AUTHORIZE = "authorize"
    const val PURPOSE_SESSION = "session"

    /**
     * `("claim", accountId, devicePublicKey, ts)` -- self-signed by the account's very first device.
     *
     * @param publicKeyB64 the **base64url text exactly as it will appear in the request body**, not
     *   the raw key bytes. The server rebuilds this message from the strings it received, so
     *   re-encoding the key differently here (padded, or standard base64) signs a message the
     *   server never reconstructs.
     * @param ts client wall clock in epoch milliseconds, formatted as a decimal string in the
     *   message and as a JSON number in the body. The server allows five minutes of skew.
     */
    fun claim(accountId: String, publicKeyB64: String, ts: Long): ByteArray =
        encode(PURPOSE_CLAIM, accountId, publicKeyB64, ts.toString())

    /**
     * `("authorize", accountId, newPubKey, ts)` -- signed by an already-enrolled device to vouch
     * for a new one.
     *
     * Note what is **not** in here: the voucher's own device ID. The server looks that up from the
     * request body and verifies this signature against the key it finds, so the signature proves
     * "the holder of some enrolled key approves this new key", and the body says which one.
     */
    fun authorize(accountId: String, newPublicKeyB64: String, ts: Long): ByteArray =
        encode(PURPOSE_AUTHORIZE, accountId, newPublicKeyB64, ts.toString())

    /**
     * `("session", accountId, deviceId, challenge)` -- signed to redeem a server challenge for a
     * bearer token.
     *
     * There is no timestamp: the challenge is a single-use server nonce with its own expiry, and it
     * is consumed on lookup, so a captured body cannot be replayed.
     */
    fun session(accountId: String, deviceId: String, challenge: String): ByteArray =
        encode(PURPOSE_SESSION, accountId, deviceId, challenge)

    internal fun encode(purpose: String, vararg fields: String): ByteArray {
        val out = ByteArrayOutputStream()
        writeLengthPrefixed(out, DOMAIN)
        writeLengthPrefixed(out, purpose)
        for (field in fields) writeLengthPrefixed(out, field)
        return out.toByteArray()
    }

    private fun writeLengthPrefixed(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 0xFFFF) { "signed-message field is too long to length-prefix" }
        out.write((bytes.size ushr 8) and 0xFF)
        out.write(bytes.size and 0xFF)
        out.write(bytes, 0, bytes.size)
    }
}
