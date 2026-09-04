package my.cheysoff.core_data.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import my.cheysoff.core_data.data.local.NoteDatabase
import my.cheysoff.core_data.data.local.RowClock
import my.cheysoff.core_data.data.local.SketchDao
import my.cheysoff.core_data.data.local.SketchEntity
import my.cheysoff.core_data.data.local.toDomain
import my.cheysoff.core_data.data.sync.SyncClock
import my.cheysoff.core_data.data.sync.SyncStamp
import my.cheysoff.core_domain.model.SketchData
import my.cheysoff.core_domain.repository.SketchesRepository
import my.cheysoff.core_domain.sync.FieldClocks
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `RoomNotesRepository`'s pattern, applied to one table: allocate one clock per write, read the
 * row's previous clocks inside the transaction, then write the row stamped with the new row clock
 * and a `fieldHlc` that says which fields actually changed. See that class's KDoc for the argument
 * in full — it is not repeated here.
 *
 * [clock] is the same `SyncClock` singleton `RoomNotesRepository` mints from (Hilt binds it once).
 * Sharing it is required, not incidental: two generators would each keep their own counter and
 * could issue the same clock twice for two different writes, which is the one thing an HLC cannot
 * tolerate. Seeding is still per-table — [stamp] folds in this table's own highest clock — because
 * `HlcGenerator.observe` takes the max of what it is shown, so seeding from `sketches` in this
 * repository and from `notes`/`folders` in the other leaves the shared generator seeded from the
 * true maximum across all three tables regardless of which repository happens to seed first.
 */
@Singleton
class RoomSketchesRepository @Inject constructor(
    private val sketchDao: SketchDao,
    private val database: NoteDatabase,
    private val clock: SyncClock,
) : SketchesRepository {

    private val seedMutex = Mutex()

    @Volatile
    private var seeded = false

    /** See `RoomNotesRepository.stamp` — identical reasoning, this table's own high-water mark. */
    private suspend fun stamp(): SyncStamp {
        if (!seeded) {
            seedMutex.withLock {
                if (!seeded) {
                    sketchDao.highestRowClock()?.let { clock.observe(it.rowHlc()) }
                    seeded = true
                }
            }
        }
        return clock.next()
    }

    override fun getSketchesForNote(noteId: String): Flow<List<SketchData>> =
        sketchDao.getSketchesByNoteId(noteId).map { list -> list.map { it.toDomain() } }

    override suspend fun saveSketch(sketch: SketchData) {
        val stamp = stamp()
        // Same reason RoomNotesRepository.saveNote is a transaction: the field-clock computation
        // is read-modify-write, and the read must not be able to see a different row than the one
        // the write below updates.
        database.withTransaction {
            val prior = sketchDao.sketchRow(sketch.id)
            sketchDao.upsertSketch(
                SketchEntity(
                    uuid = sketch.id,
                    noteId = sketch.noteId,
                    anchor = sketch.anchor,
                    sortOrder = sketch.order,
                    strokes = sketch.strokes,
                    createdAt = sketch.createdAt,
                    updatedAt = sketch.updatedAt,
                    isDeleted = sketch.isDeleted,
                    deletedAt = sketch.deletedAt,
                    hlcMs = stamp.hlc.ms,
                    hlcCounter = stamp.hlc.counter,
                    hlcNode = stamp.hlc.node,
                    fieldHlc = fieldHlc(prior?.clocks(), touchedFields(prior, sketch), stamp),
                    dirty = true,
                    // A row that has never been pushed reads 0 here, which is also what a genuine
                    // pre-existing row's own value already is when nothing above touched it — this
                    // write is not the one that clears it, only a push acknowledgement is.
                    lastSyncedSeq = prior?.lastSyncedSeq ?: 0L,
                )
            )
        }
    }

    /**
     * The seam Task 7 deferred from Task 5: a soft delete for one sketch, stamped by this
     * repository rather than by the caller. Mirrors `RoomNotesRepository.deleteNote` — read the
     * row's prior clocks inside the transaction, then write the tombstone with a fresh stamp and
     * `dirty = 1`, using [TOMBSTONE_FIELDS] so only the DELETED field's clock moves.
     *
     * `SketchData.createdAt`/`updatedAt` are caller-owned (unlike `Note`, whose repository stamps
     * `updatedAt` itself — see this class's KDoc), which is exactly why this exists instead of
     * leaving callers to fake a delete with `saveSketch(copy(isDeleted = true))`: that path would
     * hand `deletedAt` and the clock to the caller too, the same trap plan 3 was warned about.
     */
    override suspend fun deleteSketch(id: String) {
        val stamp = stamp()
        database.withTransaction {
            val prior = sketchDao.rowClock(id)
            sketchDao.softDeleteSketch(
                uuid = id,
                timestamp = stamp.wallMs,
                hlcMs = stamp.hlc.ms,
                hlcCounter = stamp.hlc.counter,
                hlcNode = stamp.hlc.node,
                fieldHlc = fieldHlc(prior, TOMBSTONE_FIELDS, stamp),
            )
        }
    }

    private fun fieldHlc(
        prior: RowClock?,
        touched: Set<String>,
        stamp: SyncStamp,
    ): String = FieldClocks.stamp(
        previousSerialized = prior?.fieldHlc ?: "",
        previousRowClock = prior?.rowHlc(),
        allFields = FieldClocks.SKETCH_FIELDS,
        touched = touched,
        newClock = stamp.hlc,
    )

    /**
     * Which of [SketchEntity]'s clocked fields this particular save actually changed — see
     * `RoomNotesRepository.savedNoteFields` for why "wrote" and "changed" are not the same
     * question and why the difference matters: claiming a clock for a value this write only copied
     * back unchanged is exactly what lets a concurrent edit on the other field be discarded on the
     * next merge. `anchor` and `strokes` are the pair this table exists to keep independent — see
     * `SketchEntity`'s KDoc.
     */
    private fun touchedFields(prior: SketchEntity?, sketch: SketchData): Set<String> {
        if (prior == null) return FieldClocks.SKETCH_FIELDS

        val touched = mutableSetOf(FieldClocks.UPDATED_AT)
        if (prior.noteId != sketch.noteId) touched += FieldClocks.NOTE_ID
        if (prior.anchor != sketch.anchor) touched += FieldClocks.ANCHOR
        if (prior.sortOrder != sketch.order) touched += FieldClocks.ORDER
        if (prior.strokes != sketch.strokes) touched += FieldClocks.STROKES
        if (prior.isDeleted != sketch.isDeleted || prior.deletedAt != sketch.deletedAt) {
            touched += FieldClocks.DELETED
        }
        return touched
    }

    private companion object {
        /**
         * What [deleteSketch] writes: the tombstone and nothing else. Mirrors
         * `RoomNotesRepository.TOMBSTONE_FIELDS` — `updatedAt` is NOT here because trashing a
         * sketch is not editing it.
         */
        val TOMBSTONE_FIELDS = setOf(FieldClocks.DELETED)
    }
}
