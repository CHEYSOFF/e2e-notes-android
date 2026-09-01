package manana.sync.server

/**
 * Every limit and deadline the server enforces, in one place, so that a test can shrink any of them
 * to something it can drive and an operator can read the whole policy without grepping.
 *
 * The defaults are sized for what this server actually is: one person's notes, two or three
 * devices, a small VPS.
 */
class ServerConfig(
    val port: Int = 8080,
    val host: String = "127.0.0.1",
    /** SQLite file, or `:memory:`. */
    val databasePath: String = "sync.db",

    /** Hard cap on any request body. A batch larger than this is the client's to split. */
    val maxRequestBytes: Int = 4 * 1024 * 1024,
    /** Hard cap on one sealed envelope. */
    val maxEnvelopeBytes: Int = 256 * 1024,
    /** Hard cap on items in one `POST /v1/records` batch. */
    val maxBatchItems: Int = 64,

    val defaultChangesLimit: Int = 200,
    val maxChangesLimit: Int = 500,
    val defaultHistoryLimit: Int = 10,
    val maxHistoryLimit: Int = 50,
    /**
     * How many versions of a record are retained. The protocol has no delete, so without a bound a
     * record edited daily accumulates ciphertext forever. Only the head version is ever returned by
     * a pull, so this affects `GET /v1/records/{id}/history` and nothing else.
     */
    val historyDepth: Int = 10,

    /** How far a signed request's `ts` may be from the server's clock, in either direction. */
    val signatureWindowMillis: Long = 5 * 60 * 1000,
    val challengeTtlMillis: Long = 2 * 60 * 1000,
    val sessionTtlMillis: Long = 24 * 60 * 60 * 1000,

    val rateLimitPerMinute: Int = 120,
    val rateLimitBurst: Int = 120,

    /**
     * How long a deposited pairing blob stays collectable.
     *
     * 120 seconds, matching the client's `PairingProtocol.CODE_TTL_MILLIS`. The two numbers live in
     * two builds and cannot be shared, so they are stated in both places: the client refuses a
     * bundle older than its own session whatever this says, and this bounds how long the server
     * holds one whatever the client says. Either one alone would be enough to close the window;
     * having both means a mistake in either is not exploitable.
     *
     * Measured from the **deposit**, not from when the new device minted `sid`. The server has no
     * way to know the latter -- no timestamp travels in QR1, deliberately (see the client's
     * `CODE_TTL_MILLIS`) -- and a blob that arrives late is one the collecting device will refuse
     * on its own clock anyway.
     */
    val pairingTtlMillis: Long = 120_000,

    /**
     * Hard cap on one deposited blob, in decoded bytes.
     *
     * Comfortably above the ~4.5 KB a maximal QR2 frame encodes to, and deliberately not equal to
     * it. **This server does not parse the frame** and must not start: an exact size would be it
     * asserting a client format it has chosen not to know, and the first client change that grew a
     * field by a byte would fail against every deployed server. What this number is for is the
     * resource question -- it bounds one row -- and for that a round, generous limit is the honest
     * one. The tight, protocol-derived check is the client's
     * (`RendezvousProtocol.MAX_SEALED_BYTES`), where it belongs.
     */
    val maxPairingBlobBytes: Int = 8 * 1024,

    /**
     * How many unexpired pairings may be parked at once, across every caller.
     *
     * The answer to storage exhaustion, and the reason it is a *global* number rather than another
     * per-IP one: per-IP limits are per-IP, and an attacker with a botnet has as many of those as
     * they like. With this cap the table cannot exceed roughly
     * `maxLivePairings * maxPairingBlobBytes` -- about 8 MB at the defaults -- whoever is writing
     * to it.
     *
     * The cost of hitting it is that a legitimate pairing is refused with a `503` and the user
     * starts over, which is a far better failure than a full disk on a machine that also holds the
     * only copy of someone's notes. 1000 is enormous for a server whose premise is one person's
     * two or three devices; it is sized so that an operator who points a family at it never sees it.
     */
    val maxLivePairings: Long = 1000,

    /**
     * Token bucket for **deposits only**, per IP address, per minute.
     *
     * Separate from [rateLimitPerMinute] and much tighter, because the two verbs are not alike.
     * An honest pairing makes exactly **one** deposit; the collect side polls dozens of times and
     * lives on the general limiter. A deposit is also the only unauthenticated request in this
     * server that causes it to *store* something, so this is the tap that a storage-exhaustion
     * attempt has to come through, and 6 per minute per address bounds it to roughly 100 KB of
     * live rows per address at the TTL above.
     */
    val pairingDepositPerMinute: Int = 6,
    val pairingDepositBurst: Int = 6,

    /**
     * Cap on a sealed device label, in decoded bytes.
     *
     * `DeviceLabelCipher` emits a constant 157 bytes today. The cap is deliberately looser than
     * that: the blob is opaque to this server, so pinning an exact size would make a future label
     * format a server change for no benefit. It is not so loose that the `devices` table can be
     * used as storage.
     */
    val maxSealedLabelBytes: Int = 512,
    val maxBlindedIdChars: Int = 64,
    val maxTokenChars: Int = 128,
) {
    companion object {
        /** `accountId` is `HKDF(ARK, "manana/sync/v1/account")` truncated to 128 bits. */
        const val ACCOUNT_ID_BYTES = 16

        /** Advertised by `GET /healthz`. Not a protocol version. */
        const val BUILD_VERSION = "1.0.0"

        /**
         * Reads a config from the environment. Every entry is optional; an unparseable value is a
         * startup failure rather than a silent fallback, because a mistyped `MANANA_PORT` that
         * quietly binds 8080 is how a server ends up listening somewhere nobody expects.
         */
        fun fromEnvironment(env: Map<String, String>): ServerConfig {
            fun int(name: String, default: Int): Int = env[name]?.let {
                it.toIntOrNull() ?: error("$name is not an integer: cannot start")
            } ?: default

            fun long(name: String, default: Long): Long = env[name]?.let {
                it.toLongOrNull() ?: error("$name is not an integer: cannot start")
            } ?: default

            return ServerConfig(
                port = int("MANANA_PORT", 8080),
                host = env["MANANA_HOST"] ?: "127.0.0.1",
                databasePath = env["MANANA_DB"] ?: "sync.db",
                maxRequestBytes = int("MANANA_MAX_REQUEST_BYTES", 4 * 1024 * 1024),
                maxEnvelopeBytes = int("MANANA_MAX_ENVELOPE_BYTES", 256 * 1024),
                maxBatchItems = int("MANANA_MAX_BATCH_ITEMS", 64),
                historyDepth = int("MANANA_HISTORY_DEPTH", 10),
                signatureWindowMillis = long("MANANA_SIGNATURE_WINDOW_MS", 5 * 60 * 1000),
                sessionTtlMillis = long("MANANA_SESSION_TTL_MS", 24 * 60 * 60 * 1000),
                rateLimitPerMinute = int("MANANA_RATE_LIMIT_PER_MINUTE", 120),
                rateLimitBurst = int("MANANA_RATE_LIMIT_BURST", 120),
                pairingTtlMillis = long("MANANA_PAIRING_TTL_MS", 120_000),
                maxPairingBlobBytes = int("MANANA_MAX_PAIRING_BLOB_BYTES", 8 * 1024),
                maxLivePairings = long("MANANA_MAX_LIVE_PAIRINGS", 1000),
                pairingDepositPerMinute = int("MANANA_PAIRING_DEPOSIT_PER_MINUTE", 6),
                pairingDepositBurst = int("MANANA_PAIRING_DEPOSIT_BURST", 6),
            )
        }
    }
}

/** Everything a running server is made of. Assembled by `main`, or by a test. */
class ServerDeps(
    val store: SyncStore,
    val config: ServerConfig,
    val clock: Clock,
    val log: RequestLog,
    val rateLimiter: RateLimiter,
    /**
     * The second, much tighter bucket that only `POST /v1/pair/{sid}` draws on.
     *
     * A separate [RateLimiter] instance rather than a second key prefix on [rateLimiter], because a
     * limiter has one rate: sharing the object would either loosen this to 120/min or throttle
     * every sync request to 6.
     */
    val pairingDepositLimiter: RateLimiter,
)
