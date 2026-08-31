package my.cheysoff.core_data.data.local

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
)
