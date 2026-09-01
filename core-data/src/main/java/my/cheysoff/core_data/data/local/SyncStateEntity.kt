package my.cheysoff.core_data.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Where this device has got to in the account's history, added in v7.
 *
 * One row per account. In practice there is exactly one — an install holds one Account Root Key
 * and therefore one `accountId` — but the account is the natural key rather than a hardcoded row
 * id, so an install that re-pairs onto a different account starts from a fresh cursor instead of
 * inheriting a meaningless one. Wrongly inheriting it is the expensive direction: a cursor from
 * another account is ahead of nothing, and pulling from it would skip that account's entire
 * history and leave the device convinced it was up to date.
 *
 * It is its own table rather than columns on `notes` or `folders` because it is neither — it is a
 * fact about the *connection*, and cramming it into a row would mean picking an arbitrary row to
 * carry it.
 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    /**
     * The server-visible account handle, `HKDF(ARK, "manana/sync/v1/account")`, hex or base64url
     * as the sync engine renders it. Opaque here; this table never derives or checks it.
     */
    @PrimaryKey val accountId: String,

    /**
     * The highest server `seq` this device has pulled AND committed.
     *
     * A **sequence number, not a timestamp.** The distinction matters because the obvious wrong
     * implementation — "everything changed since time T" — is unimplementable against a server
     * whose clock this client does not trust, and produces silent gaps when two records land in
     * the same millisecond.
     *
     * Advanced only after the transaction that applied a record commits, so a crash re-pulls the
     * last record rather than skipping it. That costs nothing, because applying an already-applied
     * remote record is a no-op.
     */
    val cursor: Long = 0L,

    /**
     * Wall-clock time of the last successful pull, for UI copy ("Last synced …") and for nothing
     * else. Never used to decide what to fetch — that is [cursor]'s job, precisely because this
     * value comes from a user-settable clock.
     */
    val lastPullAt: Long = 0L,

    /**
     * The name of the `HaltReason` the sync engine stopped on, or `''` while it is healthy.
     * Added in v8.
     *
     * Persisted rather than held in memory because every event that causes one — a server restored
     * from a backup, a payload from a newer build — is still true after a restart. An engine that
     * forgot its halt on process death would resume syncing against precisely the server it refused
     * to trust. It is stored as the enum's `name` rather than its ordinal so that reordering the
     * enum is not a silent reinterpretation of every stored halt, and an unrecognised value reads
     * as "halted for a reason this build does not know", which is still a halt.
     *
     * It lives beside the cursor because it is the same kind of fact — something true of this
     * device's *connection* to one account, not of any note — and because dropping the row is how
     * a re-baseline clears both at once.
     */
    @ColumnInfo(defaultValue = "''")
    val haltReason: String = "",
)
