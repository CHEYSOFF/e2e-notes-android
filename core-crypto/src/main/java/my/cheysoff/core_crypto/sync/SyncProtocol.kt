package my.cheysoff.core_crypto.sync

/**
 * Every constant that two devices must agree on, byte for byte, to sync with each other.
 *
 * **Changing any value in this file is a breaking protocol change.** These bytes feed HKDF `info`
 * strings, HMAC messages and AEAD associated data, so a device running an edited copy derives
 * different keys, computes different record IDs and produces envelopes that the other device
 * cannot open. There is no negotiation step and no fallback: the failure mode is "every record
 * on the account becomes undecryptable", not "the two devices agree on an older version".
 *
 * If a change is genuinely needed, it needs a new version tag in the string (`.../v2/...`), code
 * that can read both, and a migration — not an edit in place. The `v1` embedded in each string is
 * there precisely so that such a change is expressible.
 *
 * The strings are deliberately ASCII, so [toByteArray] is unambiguous regardless of the platform
 * default charset. See `docs/design/e2e-sync-architecture.md` §"Key hierarchy" and §"Record
 * envelope" for where each one sits in the design.
 */
object SyncProtocol {

    // -----------------------------------------------------------------------------------------
    // Key hierarchy — HKDF info strings, all applied to the 32-byte Account Root Key (ARK).
    // -----------------------------------------------------------------------------------------

    /** `info` for the record AEAD key `K_content`. */
    const val INFO_CONTENT = "manana/sync/v1/content"

    /** `info` for `K_id`, the HMAC key that blinds record IDs. */
    const val INFO_RECORD_ID = "manana/sync/v1/recordid"

    /** `info` for `accountId`, the only ARK-derived value the server ever sees. */
    const val INFO_ACCOUNT = "manana/sync/v1/account"

    /**
     * Prefix of the per-record `info`; the blinded record ID is appended to it.
     *
     * Per-record keys are what make random GCM nonces safe here: each key encrypts on the order of
     * one message per record version, so the birthday bound on a 96-bit random nonce is never
     * anywhere near approached.
     */
    const val INFO_RECORD_KEY_PREFIX = "manana/rec/v1"

    // -----------------------------------------------------------------------------------------
    // Sizes
    // -----------------------------------------------------------------------------------------

    /** Length of the Account Root Key, and of both keys derived from it, in bytes. */
    const val ARK_BYTES = 32

    /** Length of `K_content` and `K_id` in bytes (AES-256 / HMAC-SHA256 key size). */
    const val DERIVED_KEY_BYTES = 32

    /** `accountId` is 128 bits — unguessable, and short enough to be a comfortable URL path segment. */
    const val ACCOUNT_ID_BYTES = 16

    /** Blinded record IDs are the first 128 bits of an HMAC-SHA256 tag. */
    const val BLINDED_ID_BYTES = 16

    /**
     * Separator between `recType` and the raw UUID inside the blinded-ID HMAC message.
     *
     * A UUID never contains `:`, so `recType ‖ ":" ‖ uuid` is injective for the record types this
     * protocol uses and two different (recType, uuid) pairs cannot collide by re-splitting.
     */
    const val BLINDED_ID_SEPARATOR = ":"

    /**
     * Plaintext is padded up to a multiple of this many bytes before sealing, so that ciphertext
     * length reveals only a bucket index rather than a byte count. A one-line shopping list and a
     * one-line diary entry are then indistinguishable to the server, and so are all the notes in
     * between two bucket boundaries.
     */
    const val PADDING_BUCKET_BYTES = 256

    // -----------------------------------------------------------------------------------------
    // Record envelope
    // -----------------------------------------------------------------------------------------

    /**
     * Envelope format version, the first byte on the wire and the first byte of the AEAD
     * associated data. Bumping it is the supported way to change the envelope layout.
     */
    const val ENVELOPE_VERSION: Byte = 1

    /** GCM nonce length in bytes. 12 is the only length GCM is specified for without extra hashing. */
    const val NONCE_BYTES = 12

    /** GCM authentication tag length in bytes (128 bits — the full tag, never truncated). */
    const val TAG_BYTES = 16
}
