package manana.sync.server

import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One stored version of one record: the handle it is filed under, the sequence number this server
 * gave it, and opaque ciphertext that is never interpreted.
 *
 * Those three fields are the whole of it, and that is the privacy property rather than an
 * accident. A record's type and its hybrid logical clock used to sit here as well; the server
 * length-checked them, stored them and echoed them back without ever reading either, so they moved
 * inside the envelope where they are encrypted. **Do not add a field to this class that the server
 * does not itself read** -- anything the server merely carries belongs in the ciphertext.
 */
class RecordVersion(
    val blindedId: String,
    val seq: Long,
    val envelope: ByteArray,
)

/**
 * An enrolled device: its public key, and a sealed blob the client calls its label.
 *
 * [sealedLabel] is AES-GCM ciphertext produced by `core-crypto/.../sync/DeviceLabelCipher`, and
 * every one of them is the same length whatever the name inside, so it tells this server neither
 * what the device is called nor how long the name is. It is stored and returned; it is never
 * matched, ordered or parsed. Empty means the client sent no label.
 */
class DeviceRow(
    val deviceId: String,
    val publicKey: ByteArray,
    val sealedLabel: ByteArray,
    val createdAt: Long,
    val revokedAt: Long?,
)

/** The account and device a live bearer token belongs to. */
class SessionRow(val accountId: String, val deviceId: String)

/** One item of a `POST /v1/records` batch, after validation. */
class UpsertItem(
    val blindedId: String,
    val baseSeq: Long,
    val envelope: ByteArray,
)

/** The result of applying one [UpsertItem]: either a new seq, or the version that blocked it. */
sealed interface UpsertOutcome {
    val blindedId: String

    class Ok(override val blindedId: String, val seq: Long) : UpsertOutcome
    class Conflict(override val blindedId: String, val current: RecordVersion?) : UpsertOutcome
}

/**
 * Everything the server persists, over one SQLite file.
 *
 * ### Why a single connection behind a single lock
 *
 * Every public method here runs inside [tx], which holds a process-wide [ReentrantLock] for the
 * duration of one SQLite transaction. That makes the whole store strictly serialisable, and it is
 * not a performance compromise worth arguing about at this scale -- the workload is two or three of
 * one person's own devices.
 *
 * It buys the one property the cursor design depends on. `seq` is allocated inside the same
 * transaction that inserts the row it labels, so sequence numbers become visible to readers in
 * exactly the order they were allocated. Without that, a reader could observe seq 5 committed while
 * seq 4 was still in flight, advance its cursor past 4, and never see that record again -- a silent,
 * permanent data loss that no client-side check could detect. Serialising every transaction removes
 * the window entirely rather than narrowing it.
 *
 * ### Append-only, and what "no delete endpoint" means here
 *
 * There is no method that removes a record. A delete on the client is an ordinary upsert whose
 * *plaintext* carries a tombstone flag, sealed inside [RecordVersion.envelope]; the server cannot
 * tell it apart from an edit and does not try. The only rows this class ever deletes are its own
 * housekeeping: expired challenges, expired sessions, expired replay-cache entries, and record
 * versions older than the retained history depth.
 */
class SyncStore(
    private val connection: Connection,
    private val clock: Clock,
    private val historyDepth: Int,
) : AutoCloseable {

    private val lock = ReentrantLock()

    companion object {
        /**
         * Opens (creating if necessary) the SQLite database at [path], or an in-memory database
         * when [path] is `:memory:`.
         */
        fun open(path: String, clock: Clock, historyDepth: Int): SyncStore {
            val url = if (path == ":memory:") "jdbc:sqlite::memory:" else "jdbc:sqlite:$path"
            val connection = DriverManager.getConnection(url)
            connection.createStatement().use { statement ->
                // Foreign keys are off by default in SQLite and must be enabled per connection.
                statement.execute("PRAGMA foreign_keys = ON")
                // WAL survives a hard kill without corrupting the file and lets a reader run while
                // a writer commits. It is not load-bearing for correctness here -- the lock above
                // already serialises everything -- but it is the right default for a file that
                // holds the only copy of someone's account.
                statement.execute("PRAGMA journal_mode = WAL")
                statement.execute("PRAGMA synchronous = FULL")
                for (ddl in SCHEMA) statement.execute(ddl)
            }
            return SyncStore(connection, clock, historyDepth)
        }

        private val SCHEMA = listOf(
            """
            CREATE TABLE IF NOT EXISTS accounts (
                account_id TEXT    NOT NULL PRIMARY KEY,
                created_at INTEGER NOT NULL,
                last_seq   INTEGER NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS devices (
                account_id   TEXT    NOT NULL,
                device_id    TEXT    NOT NULL,
                public_key   BLOB    NOT NULL,
                sealed_label BLOB    NOT NULL,
                created_at   INTEGER NOT NULL,
                revoked_at   INTEGER,
                PRIMARY KEY (account_id, device_id),
                FOREIGN KEY (account_id) REFERENCES accounts(account_id)
            )
            """,
            "CREATE UNIQUE INDEX IF NOT EXISTS devices_key ON devices(account_id, public_key)",
            """
            CREATE TABLE IF NOT EXISTS records (
                account_id  TEXT    NOT NULL,
                blinded_id  TEXT    NOT NULL,
                seq         INTEGER NOT NULL,
                envelope    BLOB    NOT NULL,
                PRIMARY KEY (account_id, blinded_id, seq),
                FOREIGN KEY (account_id) REFERENCES accounts(account_id)
            )
            """,
            "CREATE INDEX IF NOT EXISTS records_by_seq ON records(account_id, seq)",
            """
            CREATE TABLE IF NOT EXISTS sessions (
                token_hash TEXT    NOT NULL PRIMARY KEY,
                account_id TEXT    NOT NULL,
                device_id  TEXT    NOT NULL,
                expires_at INTEGER NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS challenges (
                challenge  TEXT    NOT NULL PRIMARY KEY,
                account_id TEXT    NOT NULL,
                device_id  TEXT    NOT NULL,
                expires_at INTEGER NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS used_signatures (
                message_hash TEXT    NOT NULL PRIMARY KEY,
                expires_at   INTEGER NOT NULL
            )
            """,
        )
    }

    override fun close() = connection.close()

    private fun <T> tx(body: () -> T): T = lock.withLock {
        connection.autoCommit = false
        try {
            val result = body()
            connection.commit()
            result
        } catch (t: Throwable) {
            connection.rollback()
            throw t
        } finally {
            connection.autoCommit = true
        }
    }

    // ---------------------------------------------------------------------------------------
    // Accounts and devices
    // ---------------------------------------------------------------------------------------

    fun accountExists(accountId: String): Boolean = tx {
        query("SELECT 1 FROM accounts WHERE account_id = ?", accountId) { it.next() }
    }

    /**
     * Trust-on-first-use claim: creates [accountId] and enrols [publicKey] as its first device.
     * Returns the new device ID, or null if the account already exists.
     *
     * TOFU is safe precisely because `accountId` is `HKDF(ARK, "manana/sync/v1/account")`. An
     * attacker cannot claim an account whose name they cannot compute, and computing it requires
     * the ARK, which never leaves a paired device.
     */
    fun claimAccount(accountId: String, publicKey: ByteArray, sealedLabel: ByteArray): String? = tx {
        if (query("SELECT 1 FROM accounts WHERE account_id = ?", accountId) { it.next() }) return@tx null
        val now = clock.nowMillis()
        update(
            "INSERT INTO accounts(account_id, created_at, last_seq) VALUES (?, ?, 0)",
            accountId, now,
        )
        insertDevice(accountId, publicKey, sealedLabel, now)
    }

    /**
     * Enrols [publicKey] on an existing account. Returns the new device ID, or null if that exact
     * key is already enrolled (including if it is enrolled but revoked -- a revoked key must not
     * come back through the front door).
     */
    fun enrolDevice(accountId: String, publicKey: ByteArray, sealedLabel: ByteArray): String? = tx {
        val alreadyPresent = query(
            "SELECT 1 FROM devices WHERE account_id = ? AND public_key = ?", accountId, publicKey,
        ) { it.next() }
        if (alreadyPresent) return@tx null
        insertDevice(accountId, publicKey, sealedLabel, clock.nowMillis())
    }

    private fun insertDevice(
        accountId: String,
        publicKey: ByteArray,
        sealedLabel: ByteArray,
        now: Long,
    ): String {
        val deviceId = Ids.random(16)
        update(
            "INSERT INTO devices(account_id, device_id, public_key, sealed_label, created_at, revoked_at) " +
                "VALUES (?, ?, ?, ?, ?, NULL)",
            accountId, deviceId, publicKey, sealedLabel, now,
        )
        return deviceId
    }

    fun device(accountId: String, deviceId: String): DeviceRow? = tx {
        query(
            "SELECT device_id, public_key, sealed_label, created_at, revoked_at FROM devices " +
                "WHERE account_id = ? AND device_id = ?",
            accountId, deviceId,
        ) { if (it.next()) readDevice(it) else null }
    }

    fun listDevices(accountId: String): List<DeviceRow> = tx {
        query(
            "SELECT device_id, public_key, sealed_label, created_at, revoked_at FROM devices " +
                "WHERE account_id = ? ORDER BY created_at ASC, device_id ASC",
            accountId,
        ) { rs -> buildList { while (rs.next()) add(readDevice(rs)) } }
    }

    /**
     * Marks a device revoked and destroys its live sessions in the same transaction, so a token
     * issued a second earlier stops working immediately rather than at its next expiry.
     *
     * Returns false if the device does not exist. Revoking an already-revoked device is a no-op
     * that returns true: revocation is the state the caller asked for, and it holds.
     */
    fun revokeDevice(accountId: String, deviceId: String): Boolean = tx {
        val exists = query(
            "SELECT 1 FROM devices WHERE account_id = ? AND device_id = ?", accountId, deviceId,
        ) { it.next() }
        if (!exists) return@tx false
        update(
            "UPDATE devices SET revoked_at = ? WHERE account_id = ? AND device_id = ? AND revoked_at IS NULL",
            clock.nowMillis(), accountId, deviceId,
        )
        update("DELETE FROM sessions WHERE account_id = ? AND device_id = ?", accountId, deviceId)
        update("DELETE FROM challenges WHERE account_id = ? AND device_id = ?", accountId, deviceId)
        true
    }

    private fun readDevice(rs: ResultSet) = DeviceRow(
        deviceId = rs.getString(1),
        publicKey = rs.getBytes(2),
        sealedLabel = rs.getBytes(3),
        createdAt = rs.getLong(4),
        revokedAt = rs.getLong(5).takeUnless { rs.wasNull() },
    )

    // ---------------------------------------------------------------------------------------
    // Replay cache, challenges, sessions
    // ---------------------------------------------------------------------------------------

    /**
     * Records that a signed message has been used. Returns false if it was already recorded, which
     * is exactly the replay case.
     *
     * The key is a digest of the *canonical message*, not of the signature. That is deliberately
     * the stronger of the two: ECDSA is randomised, so an attacker who captured one signature could
     * not produce a second valid signature over the same message without the private key -- but
     * keying on the message rather than the signature means the replay window closes even if a
     * future algorithm change made signatures malleable.
     *
     * Entries expire at [expiresAt], which callers set to the far edge of the freshness window they
     * enforce on the timestamp. Nothing outside that window can be replayed anyway, because the
     * timestamp check rejects it first.
     */
    fun claimSignature(messageHash: String, expiresAt: Long): Boolean = tx {
        update("DELETE FROM used_signatures WHERE expires_at <= ?", clock.nowMillis())
        val fresh = update(
            "INSERT OR IGNORE INTO used_signatures(message_hash, expires_at) VALUES (?, ?)",
            messageHash, expiresAt,
        )
        fresh == 1
    }

    fun createChallenge(accountId: String, deviceId: String, challenge: String, expiresAt: Long) = tx {
        update("DELETE FROM challenges WHERE expires_at <= ?", clock.nowMillis())
        update(
            "INSERT INTO challenges(challenge, account_id, device_id, expires_at) VALUES (?, ?, ?, ?)",
            challenge, accountId, deviceId, expiresAt,
        )
        Unit
    }

    /**
     * Looks a challenge up and deletes it in the same transaction, so it is usable exactly once.
     * Returns null if it is unknown, already used, or expired. Single use is what makes a captured
     * `POST /v1/session` body worthless to a replayer.
     */
    fun consumeChallenge(challenge: String): SessionRow? = tx {
        val now = clock.nowMillis()
        val row = query(
            "SELECT account_id, device_id FROM challenges WHERE challenge = ? AND expires_at > ?",
            challenge, now,
        ) { if (it.next()) SessionRow(it.getString(1), it.getString(2)) else null }
        update("DELETE FROM challenges WHERE challenge = ?", challenge)
        row
    }

    fun createSession(accountId: String, deviceId: String, tokenHash: String, expiresAt: Long) = tx {
        update("DELETE FROM sessions WHERE expires_at <= ?", clock.nowMillis())
        update(
            "INSERT OR REPLACE INTO sessions(token_hash, account_id, device_id, expires_at) " +
                "VALUES (?, ?, ?, ?)",
            tokenHash, accountId, deviceId, expiresAt,
        )
        Unit
    }

    /**
     * Resolves a bearer token to its account and device, or null.
     *
     * The join onto `devices` is the load-bearing part: a token whose device has since been revoked
     * resolves to nothing even though its own row is still present and unexpired. Revocation
     * already deletes that device's sessions, so this is a second, independent barrier -- and the
     * one that still holds if a session row is ever created by some future path that forgets to
     * check.
     */
    fun sessionByTokenHash(tokenHash: String): SessionRow? = tx {
        query(
            "SELECT s.account_id, s.device_id FROM sessions s " +
                "JOIN devices d ON d.account_id = s.account_id AND d.device_id = s.device_id " +
                "WHERE s.token_hash = ? AND s.expires_at > ? AND d.revoked_at IS NULL",
            tokenHash, clock.nowMillis(),
        ) { if (it.next()) SessionRow(it.getString(1), it.getString(2)) else null }
    }

    // ---------------------------------------------------------------------------------------
    // Records
    // ---------------------------------------------------------------------------------------

    /** The account's current cursor high-water mark. */
    fun lastSeq(accountId: String): Long = tx {
        query("SELECT last_seq FROM accounts WHERE account_id = ?", accountId) {
            if (it.next()) it.getLong(1) else 0L
        }
    }

    /**
     * Applies a batch of compare-and-set upserts in one transaction.
     *
     * Each item is applied if and only if its `baseSeq` equals the record's current head seq --
     * with 0 meaning "this record must not exist yet". An item whose base has moved is reported as
     * a conflict with the version that blocked it attached, and the rest of the batch still
     * applies: records are independent of each other, so refusing the whole batch would only make
     * the client resend work that was never in conflict. Applied items are numbered in list order.
     */
    fun upsertBatch(accountId: String, items: List<UpsertItem>): List<UpsertOutcome> = tx {
        var seq = query("SELECT last_seq FROM accounts WHERE account_id = ?", accountId) {
            if (it.next()) it.getLong(1) else 0L
        }
        val outcomes = items.map { item ->
            val head = headVersion(accountId, item.blindedId)
            val headSeq = head?.seq ?: 0L
            if (item.baseSeq != headSeq) {
                UpsertOutcome.Conflict(item.blindedId, head)
            } else {
                seq += 1
                update(
                    "INSERT INTO records(account_id, blinded_id, seq, envelope) VALUES (?, ?, ?, ?)",
                    accountId, item.blindedId, seq, item.envelope,
                )
                pruneHistory(accountId, item.blindedId)
                UpsertOutcome.Ok(item.blindedId, seq)
            }
        }
        update("UPDATE accounts SET last_seq = ? WHERE account_id = ?", seq, accountId)
        outcomes
    }

    /**
     * The head version of every record whose head seq is greater than [since], in seq order, at
     * most [limit] of them.
     *
     * Superseded versions are deliberately not returned. A pull wants the current state of each
     * record exactly once; the older versions are reachable through [history] and nowhere else.
     * Because head seqs only ever increase, a client that advances its cursor to the largest seq it
     * received can never step over a record it has not seen.
     */
    fun changesSince(accountId: String, since: Long, limit: Int): List<RecordVersion> = tx {
        query(
            "SELECT blinded_id, seq, envelope FROM records r " +
                "WHERE r.account_id = ? AND r.seq > ? AND r.seq = (" +
                "  SELECT MAX(r2.seq) FROM records r2 " +
                "  WHERE r2.account_id = r.account_id AND r2.blinded_id = r.blinded_id) " +
                "ORDER BY r.seq ASC LIMIT ?",
            accountId, since, limit,
        ) { rs -> buildList { while (rs.next()) add(readRecord(rs)) } }
    }

    /** The most recent [limit] versions of one record, newest first. */
    fun history(accountId: String, blindedId: String, limit: Int): List<RecordVersion> = tx {
        query(
            "SELECT blinded_id, seq, envelope FROM records " +
                "WHERE account_id = ? AND blinded_id = ? ORDER BY seq DESC LIMIT ?",
            accountId, blindedId, limit,
        ) { rs -> buildList { while (rs.next()) add(readRecord(rs)) } }
    }

    private fun headVersion(accountId: String, blindedId: String): RecordVersion? = query(
        "SELECT blinded_id, seq, envelope FROM records " +
            "WHERE account_id = ? AND blinded_id = ? ORDER BY seq DESC LIMIT 1",
        accountId, blindedId,
    ) { if (it.next()) readRecord(it) else null }

    /**
     * Drops versions of one record beyond the newest `historyDepth`.
     *
     * This is the only thing that keeps the store bounded: the protocol has no delete, so without
     * it a record edited daily for a year would carry 365 ciphertexts forever. It never touches the
     * head version, so it can never affect what a pull returns -- only how far back
     * `GET /v1/records/{id}/history` can see.
     */
    private fun pruneHistory(accountId: String, blindedId: String) {
        update(
            "DELETE FROM records WHERE account_id = ? AND blinded_id = ? AND seq NOT IN (" +
                "  SELECT seq FROM records WHERE account_id = ? AND blinded_id = ? " +
                "  ORDER BY seq DESC LIMIT ?)",
            accountId, blindedId, accountId, blindedId, historyDepth,
        )
    }

    private fun readRecord(rs: ResultSet) = RecordVersion(
        blindedId = rs.getString(1),
        seq = rs.getLong(2),
        envelope = rs.getBytes(3),
    )

    // ---------------------------------------------------------------------------------------
    // Tiny JDBC helpers. Every statement here is parameterised; no SQL is ever built by
    // concatenating a value, which is what keeps a blinded ID or a bearer token from being able to
    // mean anything to the database.
    // ---------------------------------------------------------------------------------------

    private fun <T> query(sql: String, vararg args: Any?, read: (ResultSet) -> T): T =
        connection.prepareStatement(sql).use { statement ->
            bind(statement, args)
            statement.executeQuery().use(read)
        }

    private fun update(sql: String, vararg args: Any?): Int =
        connection.prepareStatement(sql).use { statement ->
            bind(statement, args)
            statement.executeUpdate()
        }

    private fun bind(statement: java.sql.PreparedStatement, args: Array<out Any?>) {
        args.forEachIndexed { index, value ->
            when (value) {
                is String -> statement.setString(index + 1, value)
                is Long -> statement.setLong(index + 1, value)
                is Int -> statement.setInt(index + 1, value)
                is ByteArray -> statement.setBytes(index + 1, value)
                null -> statement.setNull(index + 1, java.sql.Types.NULL)
                else -> error("unsupported bind type ${value::class}")
            }
        }
    }
}
