package my.cheysoff.core_data.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import my.cheysoff.core_domain.model.SketchData
import my.cheysoff.core_domain.sync.Hlc

/**
 * One drawing's row, added in v10.
 *
 * ## `sortOrder`, not `order`
 *
 * The domain field and the wire protocol both call this `order` — see [SketchData.order] and
 * `PayloadFields.ORDER`, both already shipped and both protocol, not naming taste. `order` is a
 * SQL keyword, and while SQLite tolerates a quoted keyword as a column name, every hand-written
 * migration and every `@Query` that touches it forever after has to remember to quote it — a trap
 * for whoever writes the next migration and forgets. The SQL column is `sortOrder` instead; only
 * [toDomain] and the repository need to know the two names refer to the same value.
 *
 * ## No foreign key, no cascade
 *
 * There is deliberately no `FOREIGN KEY (noteId) REFERENCES notes(id)` and no `ON DELETE CASCADE`.
 * A cascade runs only on the device that performed the delete; the other device would still hold
 * the note's tombstone but know nothing about its sketches having vanished, and — because a
 * dirty sketch pushes independently of its note — would push them right back. Reconciling a
 * deleted note's sketches is Task 7's job, done through the sync protocol, not through SQLite
 * enforcing referential integrity for a single device.
 *
 * ## The sync columns
 *
 * `hlcMs`/`hlcCounter`/`hlcNode`/`fieldHlc`/`dirty`/`lastSyncedSeq` are the same six columns
 * `notes` and `folders` carry — see `NoteEntity`'s KDoc for what each means. They carry no SQL
 * `DEFAULT` here for the clock triple, unlike `notes`/`folders`: those three got a default because
 * `MIGRATION_6_7` added them to a table that already held rows with no clock to backfill from.
 * `sketches` is a brand-new, empty table — every row is inserted through [SketchDao.upsertSketch],
 * which always supplies real values — so there is nothing to backfill and nothing for a default to
 * paper over.
 *
 * `dirty` is the one exception, and it is not brand-new-table logic: it defaults to `1` for the
 * same reason `MIGRATION_6_7` gives `notes.dirty` the same default (see that migration's KDoc in
 * full). `0` would assert this device's sketch is already on the server; the first pull would then
 * read the server's silence about it as "deleted elsewhere" and erase it. The default is pinned in
 * three places that all have to agree — this DDL (`MIGRATION_9_10`), the Kotlin default below, and
 * `@ColumnInfo(defaultValue = "1")` — so that a wrong one fails Room's schema validation at
 * startup rather than at the first sync.
 *
 * Per-field clocks are not a formality here: `anchor` moves when text *above* the drawing is
 * edited, `strokes` moves only when someone draws, and the two are genuinely independent edits
 * that can happen on two devices at once. A single row clock would let one clobber the other; see
 * `FieldClocks.SKETCH_FIELDS`.
 */
@Entity(tableName = "sketches", indices = [Index("noteId")])
data class SketchEntity(
    @PrimaryKey val uuid: String,
    val noteId: String,
    val anchor: Int,
    val sortOrder: Int,
    val strokes: String,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0")
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,

    /** Row clock, physical component. See `NoteEntity.hlcMs`. */
    val hlcMs: Long = 0L,
    /** Row clock, logical component. See `NoteEntity.hlcCounter`. */
    val hlcCounter: Int = 0,
    /** Row clock, node component. See `NoteEntity.hlcNode`. */
    val hlcNode: String = "",
    /** Per-field clocks, serialised — see `FieldClocks.SKETCH_FIELDS`. */
    @ColumnInfo(defaultValue = "''")
    val fieldHlc: String = "",
    /**
     * True when this row holds changes the server has not acknowledged.
     *
     * **The default is 1 here, in the Kotlin default, and in `MIGRATION_9_10`. Changing any of
     * them to 0 is data loss, not a tidy-up** — see this class's own KDoc and `MIGRATION_6_7`.
     */
    @ColumnInfo(defaultValue = "1")
    val dirty: Boolean = true,
    @ColumnInfo(defaultValue = "0")
    val lastSyncedSeq: Long = 0L,
) {
    /** The row clock as one value. */
    fun rowHlc(): Hlc = Hlc(ms = hlcMs, counter = hlcCounter, node = hlcNode)

    /** Just the clock columns — see `NoteEntity.clocks`. */
    fun clocks(): RowClock = RowClock(hlcMs, hlcCounter, hlcNode, fieldHlc)
}

fun SketchEntity.toDomain() = SketchData(
    id = uuid,
    noteId = noteId,
    anchor = anchor,
    order = sortOrder,
    strokes = strokes,
    createdAt = createdAt,
    updatedAt = updatedAt,
    isDeleted = isDeleted,
    deletedAt = deletedAt,
)
