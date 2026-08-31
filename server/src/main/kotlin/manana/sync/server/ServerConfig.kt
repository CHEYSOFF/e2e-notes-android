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
)
