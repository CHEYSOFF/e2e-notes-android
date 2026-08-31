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
 * The strings are deliberately ASCII, so encoding them to bytes is unambiguous regardless of the
 * platform default charset. See `docs/design/e2e-sync-architecture.md` §"Key hierarchy" and
 * §"Record envelope" for where each one sits in the design.
 *
 * In `commonMain` rather than `jvmCommonMain` with the ciphers that use them, for the same reason
 * as `Base64Url`: :core-sync-net's transport is common code and checks `ACCOUNT_ID_BYTES` against
 * every account handle it is given. These are compile-time constants with no platform code behind
 * them, so the move is a relocation and nothing else.
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
     * `info` for `K_label`, the key a device's human-readable name is sealed under.
     *
     * The name is the user's own text ("Vova's Pixel 7") and the server has no use for it: it
     * never matches on it, orders by it, or shows it to anybody. Sealing it keeps the device list
     * readable on the user's own devices and opaque everywhere else. See `DeviceLabelCipher`.
     */
    const val INFO_DEVICE_LABEL = "manana/sync/v1/devicelabel"

    /**
     * `info` for `K_arkwrap`, the key the ARK is stored under on this device.
     *
     * The odd one out: every other string here is applied to the ARK, this one is applied to the
     * **database passphrase** instead. It is still a protocol constant rather than a local detail
     * because changing it makes an already-stored `ark_ct` unopenable -- which is the account key
     * gone, on a device that may be the only one holding it. See `ArkCipher`.
     */
    const val INFO_ARK_WRAP = "manana/sync/v1/arkwrap"

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
     * length reveals only a bucket index rather than a byte count. Every note that falls between
     * two bucket boundaries is exactly the same size on the wire.
     *
     * ### Why 4 KiB, and not the 256 bytes this started at
     *
     * A bucket only hides notes that *share* it, and the serialised record payload is not small
     * before the user has typed anything. It carries a per-field clock map: with the eleven note
     * fields of the Phase 3 plan and an HLC that serialises to roughly thirty characters, the
     * field names and clocks alone come to several hundred bytes of JSON. At a 256-byte bucket
     * that fixed floor already occupies three or four buckets, and the note then grows a visible
     * bucket for every quarter-kilobyte typed -- so the scheme resolved note length to within 256
     * bytes, which for the short notes that make up most of a notes app is most of the note.
     *
     * At 4 KiB, an empty note and a note of roughly three thousand characters are the same size on
     * the wire, and that range covers the great majority of what anyone writes. What it costs is
     * that a short note occupies 4 KiB on the server rather than about 1 KiB, and a full
     * re-baseline of a thousand notes moves about 4 MB rather than 1 MB. For one person's notes on
     * a small VPS that is a cheap trade for the difference between an operator reading a note's
     * length and reading only whether it is long.
     *
     * It does **not** hide everything, and `server/README.md` says so: a note past the first
     * bucket still reveals its length to within 4 KiB, and a note that crosses a boundary between
     * two versions still shows the operator that it grew. Neither residue is fixable by a larger
     * constant -- only by a bucket that grows with the note, which swaps a bounded absolute leak
     * for a bounded relative one and can double the stored size of a large note.
     */
    const val PADDING_BUCKET_BYTES = 4096

    /**
     * Size of the padded device-label plaintext, in bytes. A **constant**, not a bucket multiple.
     *
     * Every sealed label is therefore exactly the same length, so the blob leaks nothing about the
     * name at all -- not even how long it is. There is one label per device rather than one per
     * note, so paying a fixed 128 bytes costs nothing worth measuring. The two-byte length prefix
     * inside leaves 126 bytes for UTF-8 text: 126 ASCII characters, or 42 of any script whose
     * characters cost three bytes.
     */
    const val DEVICE_LABEL_PLAINTEXT_BYTES = 128

    /**
     * Sealed-label format version, the first byte of the blob and of its associated data. Separate
     * from [ENVELOPE_VERSION] because the two formats are free to change independently.
     */
    const val DEVICE_LABEL_VERSION: Byte = 1

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
