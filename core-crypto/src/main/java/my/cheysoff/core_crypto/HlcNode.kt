package my.cheysoff.core_crypto

import my.cheysoff.core_crypto.sync.Hkdf

/**
 * The node component of this device's hybrid logical clock: a **per-account pseudonym**, derived
 * from the Account Root Key.
 *
 * ## Read this before changing anything here
 *
 * The row clock travels to the sync server in **plaintext**, outside the encrypted envelope. It
 * has to: a client reads `hlc` to order records and to build the envelope's associated data,
 * before it is in a position to decrypt anything, and the server reads it to detect a stale
 * `baseSeq`. Every other fact about a note is opaque to the operator — the title, the body, even
 * the record's real UUID (which is blinded through `K_id`). The node is not.
 *
 * So whatever goes in this string, the operator gets a **per-record log of it, for every edit the
 * account ever makes**. If it were a device identifier — `Settings.Secure.ANDROID_ID`, the model
 * name, the server's own `deviceId`, or any value that outlives an account — the operator would
 * learn which of the user's devices made every single edit, and could follow that device across
 * accounts. That is a materially different product from "the server stores opaque blobs", and it
 * would be true without a single line of it appearing in the threat model.
 *
 * ## The derivation
 *
 * ```
 * node = hex( HKDF-SHA256( ikm = ARK, salt = deviceId, info = "manana/sync/v1/hlcnode" )[0..8] )
 * ```
 *
 * Each input earns its place:
 *
 *  - **ARK as the input keying material** is what makes the pseudonym *per account*. The ARK is
 *    the account's root secret, so a device that leaves one account and joins another produces a
 *    completely unrelated node with no work and no rotation logic — the two accounts cannot be
 *    linked to one device even by an operator hosting both. It is also why this is a derivation
 *    rather than "a random string stored in prefs", which is what the design docs originally
 *    proposed: a stored random string is stable across accounts and therefore links them.
 *  - **`deviceId` as the salt** is what makes it *per device*, which the clock needs: the node is
 *    the tie-breaker that stops two devices writing in the same millisecond from producing equal
 *    clocks for different values, which is the one situation in which two replicas can each
 *    decide the other's write lost. `deviceId` is a locally generated 128-bit random string
 *    ([SecureUnlockManager.deviceId]) — never hardware-derived, never `ANDROID_ID`, never the
 *    model name — so nothing about the device leaks into it even before the HKDF.
 *  - **HKDF** is one-way, so the operator cannot recover `deviceId` from the node even though
 *    `deviceId` is a value it will separately be told during the session handshake. Learning both
 *    does not let it link them.
 *
 * The output is 8 bytes / 16 hex characters. Long enough that two devices on one account
 * practically never collide; short enough that the whole `"$ms-$counter-$node"` string stays far
 * inside the server's 128-character limit for it.
 *
 * ## When there is no ARK, there is no node
 *
 * A device that has never paired has no account key, so there is nothing to derive from and no
 * account for the pseudonym to belong to. The clock's node is then `""` — see [Hlc.node] — rather
 * than some local fallback. That is deliberate: any fallback would be a device-specific string
 * that eventually reaches the operator on the first push, which is exactly what this file exists
 * to prevent. Rows written before an account existed cannot collide with another device's rows
 * anyway; their record UUIDs were minted locally and no other device has ever seen them.
 *
 * ## Open question D4, restated honestly
 *
 * Because `deviceId` is the salt, rotating `deviceId` rotates the node. Whether a revoked device
 * *should* have its node rotated is unresolved (`docs/design/e2e-sync-phase3-plan.md` §10, D4):
 * rotating discards the tie-breaking history, not rotating leaves a revoked device's past edits
 * attributable within the account. This file takes no position; it only makes both reachable.
 *
 * Pure JVM and stateless, so it is unit-tested in `src/test` without a Keystore. Never logs key
 * material.
 */
object HlcNode {

    /**
     * HKDF `info` for the node pseudonym.
     *
     * Not in `SyncProtocol` on purpose, even though it looks like its siblings. Every constant in
     * that file is one two devices must agree on byte for byte; this one is local — no other
     * device ever derives this device's node, and changing it breaks nothing on the wire. What it
     * *would* do is change this device's node, which is harmless for correctness (the node only
     * breaks ties) but would split one device's history into two pseudonyms in the operator's
     * view. Change it only with that in mind.
     */
    const val INFO_HLC_NODE = "manana/sync/v1/hlcnode"

    /** Length of the pseudonym in bytes; it is rendered as twice this many hex characters. */
    const val NODE_BYTES = 8

    /**
     * Derives the node pseudonym for a device.
     *
     * Deterministic: the same `(ark, deviceId)` pair always gives the same string, so the node is
     * stable across unlocks, process restarts and app upgrades without being stored anywhere.
     *
     * [ark] is not modified and remains the caller's; the intermediate key material is zeroed
     * before returning. The result is not a secret — it is published to the operator with every
     * record — but it must not be invertible, which is the HKDF's job.
     */
    fun derive(ark: ByteArray, deviceId: String): String {
        require(deviceId.isNotEmpty()) { "deviceId must not be empty" }
        val bytes = Hkdf.derive(
            ikm = ark,
            salt = deviceId.toByteArray(Charsets.US_ASCII),
            info = INFO_HLC_NODE.toByteArray(Charsets.US_ASCII),
            length = NODE_BYTES,
        )
        try {
            return toHex(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * Lowercase hex, hand-rolled.
     *
     * `Long.toHexString` would drop leading zeroes and `java.util.HexFormat` is Java 17, while
     * base64url — the encoding the rest of the sync code uses — has `-` in its alphabet, and `-`
     * is the separator inside the clock's own wire form. Hex has none of those problems and keeps
     * `Hlc.parse` a two-`indexOf` function.
     */
    private fun toHex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4])
            out.append(HEX[v and 0x0F])
        }
        return out.toString()
    }

    private const val HEX = "0123456789abcdef"
}
