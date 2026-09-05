package my.cheysoff.core_sync_engine.harness

import kotlinx.coroutines.runBlocking
import my.cheysoff.core_domain.sync.FieldClocks
import my.cheysoff.core_domain.sync.FieldValue
import my.cheysoff.core_domain.sync.HlcGenerator
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_domain.sync.SyncRecord
import my.cheysoff.core_domain.sync.SyncValues
import my.cheysoff.core_sync_engine.ClockObserver
import my.cheysoff.core_sync_engine.HaltReason
import my.cheysoff.core_sync_engine.PassStats
import my.cheysoff.core_sync_engine.RetryJitter
import my.cheysoff.core_sync_engine.RetryPlan
import my.cheysoff.core_sync_engine.StoredRecord
import my.cheysoff.core_sync_engine.SyncEngine
import my.cheysoff.core_sync_engine.SyncOutcome

/**
 * One simulated device: a record store, a transport, a clock, and **the real [SyncEngine]** driving
 * them.
 *
 * ## Why the engine is here rather than re-implemented
 *
 * This class used to hand-roll its own pull/push/apply loop. Two implementations of a loop whose
 * whole job is to agree is precisely the bug class this project keeps meeting, and the harness's
 * copy would have been the one that never ran in production and therefore never got fixed. So the
 * only sync code left in this file is the *local write path* — the part that produces the clocks
 * the engine then reasons about — and everything from "pull" onwards is [SyncEngine], the same
 * object the app will hold, behind [ReplicaStore] and [ReplicaTransport].
 *
 * That means the convergence, commutativity, idempotence and determinism properties in
 * `ConvergenceTest` are properties of the shipped engine, not of a model of it.
 *
 * ## What the write path faithfully reproduces
 *
 * What `RoomNotesRepository` does: allocate **one** stamp per user action from a single
 * `HlcGenerator`, recompute `fieldHlc` through the real `FieldClocks.stamp` (round-tripped through
 * the real `serialize`/`parse`, so a bug in either shows up here as a merge failure), and set
 * `dirty`. A harness that stamped its own clocks would be testing the merge against clocks no
 * device ever produces.
 *
 * ## What it does not reproduce, and must not be read as covering
 *
 * Room, SQLCipher, transactions, the invalidation race `SingleNoteViewModel` documents, the Android
 * lifecycle, real HTTP, real crypto and real clock steps. Those are named in
 * `e2e-sync-open-questions.md` §3 as the things the simulation cannot reach, and a green run here
 * says nothing about any of them.
 *
 * @param node this device's HLC node pseudonym; the tie-breaker two replicas writing in the same
 *   millisecond come apart on.
 * @param useBaselines whether this device's schema records a `content` baseline. Both modes are
 *   run: `true` is what closing decision D7 would buy, `false` is the schema as it stands at v7,
 *   and the point of running both is that convergence must hold either way while the number of
 *   conflict copies must not.
 */
class Replica(
    val name: String,
    val node: String,
    private val server: FakeServer,
    private val useBaselines: Boolean = true,
    private var wallMs: Long = 1_000L,
) {

    private val store = ReplicaStore(name = name, useBaselines = useBaselines)
    private val transport = ReplicaTransport(server)
    private val generator = HlcGenerator { node }

    private val engine = SyncEngine(
        store = store,
        transport = transport,
        // The engine never mints a clock; it only reports the ones it is shown, so that the next
        // local write cannot be minted below a record already accepted.
        clock = ClockObserver { generator.observe(it) },
        // No spread: a random wait would make a failing seed unreplayable, and nothing in this
        // harness ever waits anyway.
        retryPlan = RetryPlan(RetryJitter.NONE),
        // Small enough that the paging loop actually runs more than once on a busy account, which
        // is where a cursor that advances past an unapplied page would show up.
        pageLimit = 8,
    )

    /** Everything every pass on this device has done, added up. See [Simulation]'s counters. */
    var stats: PassStats = PassStats.NONE
        private set

    /** The server seq this device has pulled up to. */
    val cursor: Long get() = store.cursorNow()

    /**
     * Set when the engine refused something, which by `e2e-sync-phase3-plan.md` §8 F7 halts the
     * whole engine and requires an explicit user re-baseline. A halted replica does nothing
     * further, so a test that injects a rollback must not also expect convergence.
     */
    val halted: Boolean get() = store.haltedWith() != null

    /** Why it halted, or null. */
    val haltReason: HaltReason? get() = store.haltedWith()

    /** Every record this device holds, normalised so two replicas' snapshots are comparable. */
    fun snapshot(): Map<String, SyncRecord> =
        store.rows().mapValues { (_, row) -> row.record.normalized() }

    /** True while anything is waiting to be pushed. Half of the quiescence test. */
    fun hasDirtyRows(): Boolean = store.rows().values.any { it.dirty }

    /** The row for a record, or null. */
    fun row(type: RecordType, uuid: String): StoredRecord? =
        store.rows()[ReplicaStore.keyOf(type, uuid)]

    // ── The device clock ───────────────────────────────────────────────────────────────────────

    /**
     * Moves this device's wall clock by [deltaMs], which may be **negative**.
     *
     * A negative step is the interesting one: a manual change, an NTP correction, a dual-boot. The
     * generator absorbs it by keeping the last physical value and advancing its counter, so the
     * clocks it mints still increase — and the merge, which only ever compares clocks, is never
     * shown a record from this device that is older than one it already accepted.
     */
    fun advanceClock(deltaMs: Long) {
        wallMs += deltaMs
    }

    // ── Local writes, mirroring RoomNotesRepository ────────────────────────────────────────────

    /** `saveNote`: the editor's save. Touches everything a save writes, `updatedAt` included. */
    fun saveNote(uuid: String, title: String, body: String) = write(
        type = RecordType.NOTE,
        uuid = uuid,
        touched = SAVE_NOTE_FIELDS,
    ) { fields, stamp ->
        fields[FieldClocks.TITLE] = FieldValue.of(title)
        fields[FieldClocks.CONTENT] = FieldValue.of(body, "html")
        fields[FieldClocks.UPDATED_AT] = FieldValue.of(stamp.toString())
    }

    /** `setNotePinned`: a metadata gesture. Does NOT touch `updatedAt` — PR #32's rule. */
    fun setPinned(uuid: String, pinned: Boolean) = write(
        type = RecordType.NOTE,
        uuid = uuid,
        touched = setOf(FieldClocks.PINNED),
    ) { fields, _ -> fields[FieldClocks.PINNED] = FieldValue.of(SyncValues.of(pinned)) }

    /** `setNoteFavorite`. Also leaves `updatedAt` alone. */
    fun setFavorite(uuid: String, favorite: Boolean) = write(
        type = RecordType.NOTE,
        uuid = uuid,
        touched = setOf(FieldClocks.FAVORITE),
    ) { fields, _ -> fields[FieldClocks.FAVORITE] = FieldValue.of(SyncValues.of(favorite)) }

    /** `setNoteFolder`. Also leaves `updatedAt` alone. */
    fun setFolder(uuid: String, folderId: String?) = write(
        type = RecordType.NOTE,
        uuid = uuid,
        touched = setOf(FieldClocks.FOLDER),
    ) { fields, _ -> fields[FieldClocks.FOLDER] = FieldValue.of(folderId) }

    /**
     * `clearFolderForNote`: unfiling during a folder delete. The one mass edit that DOES bump
     * `updatedAt`, and the reason `updatedAt` cannot simply ride along with `content`.
     */
    fun clearFolder(uuid: String) = write(
        type = RecordType.NOTE,
        uuid = uuid,
        touched = setOf(FieldClocks.FOLDER, FieldClocks.UPDATED_AT),
    ) { fields, stamp ->
        fields[FieldClocks.FOLDER] = FieldValue.of(null)
        fields[FieldClocks.UPDATED_AT] = FieldValue.of(stamp.toString())
    }

    /** `softDeleteNote`. The tombstone flag and its stamp move together, as one value. */
    fun deleteNote(uuid: String) = write(
        type = RecordType.NOTE,
        uuid = uuid,
        touched = setOf(FieldClocks.DELETED),
    ) { fields, stamp ->
        fields[FieldClocks.DELETED] = FieldValue.of(SyncValues.TRUE, stamp.toString())
    }

    /** `restoreNote`: clears the stamp with the flag, so the next delete starts a new window. */
    fun restoreNote(uuid: String) = write(
        type = RecordType.NOTE,
        uuid = uuid,
        touched = setOf(FieldClocks.DELETED),
    ) { fields, _ -> fields[FieldClocks.DELETED] = FieldValue.of(SyncValues.FALSE, null) }

    /** `upsertFolder`. */
    fun saveFolder(uuid: String, folderName: String, colorArgb: Long?) = write(
        type = RecordType.FOLDER,
        uuid = uuid,
        touched = SAVE_FOLDER_FIELDS,
    ) { fields, stamp ->
        fields[FieldClocks.NAME] = FieldValue.of(folderName)
        fields[FieldClocks.COLOR] = FieldValue.of(colorArgb?.toString())
        fields[FieldClocks.UPDATED_AT] = FieldValue.of(stamp.toString())
    }

    /** `softDeleteFolder`. */
    fun deleteFolder(uuid: String) = write(
        type = RecordType.FOLDER,
        uuid = uuid,
        touched = setOf(FieldClocks.DELETED),
    ) { fields, stamp ->
        fields[FieldClocks.DELETED] = FieldValue.of(SyncValues.TRUE, stamp.toString())
    }

    /**
     * `RoomSketchesRepository.saveSketch`. `anchor`/`order` are left untouched — this harness
     * keeps exactly one sketch per note slot, so nothing here ever moves them, exactly as
     * `RoomSketchesRepository.touchedFields` only claims a field's clock when the write actually
     * changed it.
     */
    fun saveSketch(uuid: String, noteId: String, strokes: String) = write(
        type = RecordType.SKETCH,
        uuid = uuid,
        touched = SAVE_SKETCH_FIELDS,
    ) { fields, stamp ->
        fields[FieldClocks.NOTE_ID] = FieldValue.of(noteId)
        fields[FieldClocks.STROKES] = FieldValue.of(strokes)
        fields[FieldClocks.UPDATED_AT] = FieldValue.of(stamp.toString())
    }

    /**
     * One user action: allocate one clock, recompute the field clocks on top of the row's previous
     * ones, apply the change, mark the row dirty.
     *
     * The field-clock recomputation goes through `FieldClocks.serialize` and `parse` rather than
     * manipulating the map directly. It costs nothing and it means the harness is exercising the
     * same string the `fieldHlc` column stores — so an encoding bug that would corrupt a real row
     * shows up here as a convergence failure rather than passing unnoticed because the harness kept
     * its clocks in a nicer form than the database does.
     */
    private fun write(
        type: RecordType,
        uuid: String,
        touched: Set<String>,
        mutate: (fields: MutableMap<String, FieldValue>, wallMs: Long) -> Unit,
    ) {
        val key = ReplicaStore.keyOf(type, uuid)
        val existing = store.rows()[key]
        val stampMs = wallMs
        val clock = generator.next(stampMs)

        val fields = LinkedHashMap(existing?.record?.fields ?: defaultFields(type, stampMs))
        mutate(fields, stampMs)

        val nextClocks = FieldClocks.parse(
            FieldClocks.stamp(
                previousSerialized = FieldClocks.serialize(existing?.record?.fieldClocks ?: emptyMap()),
                previousRowClock = existing?.record?.rowClock,
                allFields = type.fields,
                touched = touched,
                newClock = clock,
            )
        )

        store.write(
            key,
            StoredRecord(
                record = SyncRecord(
                    type = type,
                    uuid = uuid,
                    rowClock = clock,
                    fieldClocks = nextClocks,
                    fields = type.fields.associateWith { fields.getValue(it) },
                ).validate().normalized(),
                dirty = true,
                // A brand-new row has never been on the server; an existing one keeps the baseline
                // it was pushed against. Resetting lastSyncedSeq here would tell the server an
                // already-uploaded record must not exist.
                lastSyncedSeq = existing?.lastSyncedSeq ?: 0L,
                contentBaseline = existing?.contentBaseline,
            ),
        )
    }

    /** A record's fields before its first write: the same defaults the entities declare. */
    private fun defaultFields(type: RecordType, stampMs: Long): Map<String, FieldValue> = when (type) {
        RecordType.NOTE -> mapOf(
            FieldClocks.TITLE to FieldValue.of(""),
            FieldClocks.CONTENT to FieldValue.of("", "html"),
            FieldClocks.CHECKLIST to FieldValue.of(""),
            FieldClocks.PINNED to FieldValue.of(SyncValues.FALSE),
            FieldClocks.FAVORITE to FieldValue.of(SyncValues.FALSE),
            FieldClocks.FOLDER to FieldValue.of(null),
            FieldClocks.UPDATED_AT to FieldValue.of(stampMs.toString()),
            FieldClocks.DELETED to FieldValue.of(SyncValues.FALSE, null),
        )

        RecordType.FOLDER -> mapOf(
            FieldClocks.NAME to FieldValue.of(""),
            FieldClocks.COLOR to FieldValue.of(null),
            FieldClocks.UPDATED_AT to FieldValue.of(stampMs.toString()),
            FieldClocks.DELETED to FieldValue.of(SyncValues.FALSE, null),
        )

        // Reached via `saveSketch` (Task 8). The defaults are `SketchEntity`'s own Kotlin
        // defaults, exactly as NOTE's and FOLDER's mirror their entities'.
        RecordType.SKETCH -> mapOf(
            FieldClocks.NOTE_ID to FieldValue.of(""),
            FieldClocks.ANCHOR to FieldValue.of("0"),
            FieldClocks.ORDER to FieldValue.of("0"),
            FieldClocks.STROKES to FieldValue.of(""),
            FieldClocks.UPDATED_AT to FieldValue.of(stampMs.toString()),
            FieldClocks.DELETED to FieldValue.of(SyncValues.FALSE, null),
        )

        // `AttachmentEntity`'s own Kotlin defaults, on the same principle. The two binary values
        // are empty base64url -- the empty string -- rather than a placeholder image: the harness
        // never inspects a value, only whether two replicas agree on it, and an empty encoding is
        // what a row carries before anything has been imported into it.
        RecordType.ATTACHMENT -> mapOf(
            FieldClocks.NOTE_ID to FieldValue.of(""),
            FieldClocks.ANCHOR to FieldValue.of("0"),
            FieldClocks.ORDER to FieldValue.of("0"),
            FieldClocks.IMAGE to FieldValue.of("", "", "0", "0"),
            FieldClocks.THUMB to FieldValue.of("", "0", "0"),
            FieldClocks.UPDATED_AT to FieldValue.of(stampMs.toString()),
            FieldClocks.DELETED to FieldValue.of(SyncValues.FALSE, null),
        )
    }

    // ── Driving the engine ─────────────────────────────────────────────────────────────────────

    /**
     * One full pass — `SyncEngine.runPass`. Returns true if anything moved.
     *
     * `runBlocking` because the engine suspends (a real transport does I/O) and JUnit 4 does not.
     * Nothing in this harness ever actually suspends, so the coroutine completes on the calling
     * thread and a whole seeded simulation still costs microseconds.
     */
    fun syncOnce(): Boolean = record(runBlocking { engine.runPass() })

    /** Half a pass: apply everything the server has that this device has not seen. */
    fun pull(): Boolean = record(runBlocking { engine.pullOnce() })

    /** Half a pass: send this device's dirty rows. */
    fun push(): Boolean = record(runBlocking { engine.pushOnce() })

    /**
     * A push whose acknowledgement never arrives, though the server committed it.
     *
     * `e2e-sync-phase3-plan.md` §3.3 lists this as an ordinary event, not a disaster: the row stays
     * dirty with a stale `lastSyncedSeq`, so the next push takes a `409` carrying this device's own
     * envelope back, and the merge of a record against itself has to be a no-op or the row is
     * pushed forever.
     */
    fun pushLosingTheAcknowledgement(): Boolean {
        transport.loseNextAcknowledgement = true
        return push()
    }

    /**
     * Rewinds the cursor to 0 and pulls everything again — every record this device has ever
     * applied, delivered a second time.
     *
     * The idempotence property's instrument. It is not a fault injection so much as an ordinary
     * Tuesday: `e2e-sync-phase3-plan.md` §3.3 lists a crash between a merge commit and the cursor
     * write as one of three routine ways a record is re-delivered, and the only thing that makes
     * any of them safe is that re-applying an already-applied record does nothing at all.
     */
    fun replayEverything(): Boolean {
        store.rewindCursor()
        return pull()
    }

    /** Folds one outcome's counts into [stats] and answers "did anything move". */
    private fun record(outcome: SyncOutcome): Boolean {
        val passStats = when (outcome) {
            is SyncOutcome.Completed -> outcome.stats
            is SyncOutcome.Deferred -> outcome.stats
            is SyncOutcome.Halted -> outcome.stats
            SyncOutcome.AlreadyRunning -> PassStats.NONE
        }
        stats += passStats
        return passStats.moved
    }

    private companion object {
        /** `RoomNotesRepository.SAVE_NOTE_FIELDS`, verbatim. */
        val SAVE_NOTE_FIELDS = setOf(
            FieldClocks.TITLE,
            FieldClocks.CONTENT,
            FieldClocks.CHECKLIST,
            FieldClocks.PINNED,
            FieldClocks.FOLDER,
            FieldClocks.UPDATED_AT,
        )

        /** `RoomNotesRepository.SAVE_FOLDER_FIELDS`, verbatim. */
        val SAVE_FOLDER_FIELDS = setOf(FieldClocks.NAME, FieldClocks.COLOR, FieldClocks.UPDATED_AT)

        /** The fields [Replica.saveSketch] actually changes. `anchor`/`order` are never among them. */
        val SAVE_SKETCH_FIELDS = setOf(FieldClocks.NOTE_ID, FieldClocks.STROKES, FieldClocks.UPDATED_AT)
    }
}
