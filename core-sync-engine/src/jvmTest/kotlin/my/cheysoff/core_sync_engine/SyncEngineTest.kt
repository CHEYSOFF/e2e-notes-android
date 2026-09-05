package my.cheysoff.core_sync_engine

import kotlinx.coroutines.runBlocking
import my.cheysoff.core_domain.sync.ConflictCopies
import my.cheysoff.core_domain.sync.FieldClocks
import my.cheysoff.core_domain.sync.Hlc
import my.cheysoff.core_domain.sync.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pass loop's rules, one named test each.
 *
 * ## What this file is for, and what `ConvergenceTest` is for
 *
 * `ConvergenceTest` drives this same engine through thousands of seeded schedules and asks whether
 * N devices end up holding the same thing. It is the stronger test of the *merge*, and it is
 * useless for a rule like "the cursor is not persisted past a record that would not open", because
 * a simulation over plaintext records never produces one. So the split is: properties over there,
 * rules here, and every rule here is stated as one test whose name is the rule.
 *
 * Each of these was checked by breaking the code it covers and confirming that this test — by name
 * — is the one that goes red. A rule with no test that dies when the rule is broken is untested,
 * however much it looks tested; the table is in the pull request.
 */
class SyncEngineTest {

    // ── Pull ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a pulled record is merged and written`() = runBlocking {
        val store = RecordingStore()
        val engine = engine(store, ScriptedTransport(pages = listOf(openedPage(note(content = "hello")))))

        val outcome = engine.runPass()

        assertEquals(1, (outcome as SyncOutcome.Completed).stats.applied)
        assertEquals("hello", store.noteRow("n1")!!.record.text(FieldClocks.CONTENT))
        assertEquals("the row arrived from the server, so it is not something to push back", false, store.noteRow("n1")!!.dirty)
        assertEquals(1L, store.noteRow("n1")!!.lastSyncedSeq)
    }

    /**
     * The order that makes a crash cost one repeat rather than a lost record.
     *
     * Writing the cursor first and applying afterwards looks identical in every green test and is
     * a silent data loss the moment the process dies between the two.
     */
    @Test
    fun `the cursor is persisted only after the records below it are applied`() = runBlocking {
        val store = RecordingStore()
        val engine = engine(
            store,
            ScriptedTransport(pages = listOf(openedPage(note(uuid = "n1"), note(uuid = "n2")))),
        )

        engine.runPass()

        assertEquals(listOf("apply=n1 dirty=false seq=1", "apply=n2 dirty=false seq=2", "cursor=2"), store.writes)
    }

    /**
     * Process death mid-pass, including between a page being applied and the next one arriving.
     *
     * The engine keeps nothing between passes, so "resume" is not a feature it has — it is what
     * happens when a second engine is built over the same store. The check is that the interrupted
     * run and the uninterrupted one land in exactly the same place.
     */
    @Test
    fun `a pass interrupted by process death resumes to the same state`() = runBlocking {
        val stream = (1..6).map { seq ->
            IncomingRecord.Opened(seq.toLong(), note(uuid = "n$seq", content = "body-$seq"), null)
        }

        val uninterrupted = RecordingStore()
        engine(uninterrupted, PagingTransport(stream), pageLimit = 2).runPass()

        val torn = RecordingStore()
        // Dies on the second page request, having applied and committed the first page.
        val deferred = engine(torn, PagingTransport(stream, failChangesAtCall = 1), pageLimit = 2).runPass()
        assertTrue("the interrupted pass should have deferred", deferred is SyncOutcome.Deferred)
        assertEquals("the cursor must be at the last record actually applied", 2L, torn.cursor)

        // A new process: a new engine, a new transport, the same database.
        engine(torn, PagingTransport(stream), pageLimit = 2).runPass()

        assertEquals(uninterrupted.rows().keys, torn.rows().keys)
        assertEquals(uninterrupted.cursor, torn.cursor)
        uninterrupted.rows().forEach { (key, row) ->
            assertEquals(key, row.record, torn.rows().getValue(key).record)
            assertEquals(key, row.lastSyncedSeq, torn.rows().getValue(key).lastSyncedSeq)
        }
    }

    /**
     * §8's F1: a record that will not open is counted and skipped, and **the cursor does not go
     * past it**. Advancing over it would mean the record is never offered again, which for an
     * envelope that would have opened on the next attempt is silent data loss.
     */
    @Test
    fun `the cursor never advances past a record that would not open`() = runBlocking {
        val store = RecordingStore()
        val page = ChangePage(
            records = listOf(
                IncomingRecord.Opened(1L, note(uuid = "n1"), null),
                IncomingRecord.Faulted(2L, RecordFault.UNREADABLE),
                IncomingRecord.Opened(3L, note(uuid = "n3"), null),
            ),
            hasMore = false,
        )
        val engine = engine(store, ScriptedTransport(pages = listOf(page)))

        val outcome = engine.runPass() as SyncOutcome.Completed

        assertEquals(1, outcome.stats.unreadable)
        assertEquals("the cursor stops below the record that did not open", 1L, store.cursor)
        assertNotNull("the readable record after the fault is still applied", store.noteRow("n3"))
    }

    /**
     * One unreadable record is a corrupt row. A stream of them is a device that cannot read the
     * account at all, and grinding on is how the user finds out weeks later.
     */
    @Test
    fun `a stream of unreadable records halts the engine`() = runBlocking {
        val store = RecordingStore()
        val page = ChangePage(
            records = (1L..(SyncEngine.UNREADABLE_RECORD_LIMIT + 1L)).map {
                IncomingRecord.Faulted(it, RecordFault.UNREADABLE)
            },
            hasMore = false,
        )

        val outcome = engine(store, ScriptedTransport(pages = listOf(page))).runPass()

        assertEquals(HaltReason.RECORDS_UNREADABLE, (outcome as SyncOutcome.Halted).reason)
        assertEquals(HaltReason.RECORDS_UNREADABLE, store.halt)
    }

    /**
     * The behaviour this whole change exists for: a device a version behind keeps syncing.
     *
     * Before it, the first record of an unknown type froze the cursor — so ordinary notes *after*
     * it stopped arriving too — and the sixth halted the engine outright.
     */
    @Test
    fun `records of an unknown type are skipped and the cursor moves past them`() = runBlocking {
        val store = RecordingStore()
        val transport = ScriptedTransport(
            pages = listOf(
                ChangePage(
                    records = listOf(
                        IncomingRecord.Faulted(1L, RecordFault.UNKNOWN_TYPE),
                        IncomingRecord.Opened(2L, note(uuid = "n2"), null),
                    ),
                    hasMore = false,
                )
            )
        )

        val outcome = engine(store, transport).runPass()

        assertTrue("a skipped type must not stop the pass: $outcome", outcome is SyncOutcome.Completed)
        assertEquals("the note after it must still be applied", 1, (outcome as SyncOutcome.Completed).stats.applied)
        assertEquals("and it is counted, not silent", 1, outcome.stats.ignored)
        assertEquals("the cursor moves past both", 2L, store.cursor())
    }

    @Test
    fun `a stream of unknown types never halts`() = runBlocking {
        val store = RecordingStore()
        val many = (1..SyncEngine.UNREADABLE_RECORD_LIMIT * 3).map {
            IncomingRecord.Faulted(it.toLong(), RecordFault.UNKNOWN_TYPE)
        }
        val outcome = engine(store, ScriptedTransport(pages = listOf(ChangePage(many, hasMore = false))))
            .runPass()

        assertTrue("unknown types are not evidence of anything wrong: $outcome", outcome is SyncOutcome.Completed)
        assertEquals(many.size.toLong(), store.cursor())
    }

    /**
     * The other direction, and it matters as much: this change must not have made *damaged*
     * records skippable. Only testing the new branch would let a later edit quietly widen it.
     */
    @Test
    fun `an unreadable record still freezes the cursor and still halts in quantity`() = runBlocking {
        val store = RecordingStore()
        val many = (1..SyncEngine.UNREADABLE_RECORD_LIMIT + 2).map {
            IncomingRecord.Faulted(it.toLong(), RecordFault.UNREADABLE)
        }
        val outcome = engine(store, ScriptedTransport(pages = listOf(ChangePage(many, hasMore = false))))
            .runPass()

        assertEquals(HaltReason.RECORDS_UNREADABLE, (outcome as SyncOutcome.Halted).reason)
        assertEquals("the cursor must not have moved past a damaged record", 0L, store.cursor())
    }

    /**
     * §8's F3. A server cannot produce a mislabelled payload without the ARK, so this is a client
     * bug and it has to be loud rather than worked around.
     */
    @Test
    fun `a mislabelled record halts the engine immediately`() = runBlocking {
        val store = RecordingStore()
        val page = ChangePage(
            records = listOf(
                IncomingRecord.Faulted(1L, RecordFault.MISLABELLED),
                IncomingRecord.Opened(2L, note(uuid = "n2"), null),
            ),
            hasMore = false,
        )

        val outcome = engine(store, ScriptedTransport(pages = listOf(page))).runPass()

        assertEquals(HaltReason.RECORD_MISLABELLED, (outcome as SyncOutcome.Halted).reason)
        assertNull("nothing after the fault may be applied", store.noteRow("n2"))
    }

    /**
     * §8's F2. Decoding the fields this build recognises and pushing the result back is how an
     * older device deletes a newer one's data, silently and everywhere.
     */
    @Test
    fun `a payload version this build does not know halts rather than downgrading`() = runBlocking {
        val store = RecordingStore()
        val page = ChangePage(
            records = listOf(IncomingRecord.Faulted(1L, RecordFault.UNSUPPORTED_PAYLOAD_VERSION)),
            hasMore = false,
        )

        val outcome = engine(store, ScriptedTransport(pages = listOf(page))).runPass()

        assertEquals(HaltReason.UNSUPPORTED_PAYLOAD_VERSION, (outcome as SyncOutcome.Halted).reason)
        assertEquals(0L, store.cursor)
    }

    /**
     * §8's F7, the whole-server half: `409 cursor_ahead_of_server` is what a restored-from-backup
     * server looks like from outside.
     *
     * The cursor must be **left where it is**. Resetting it to zero against clean rows is
     * indistinguishable from "the account is empty", and the next pass is a mass delete.
     */
    @Test
    fun `a cursor ahead of the server halts without resetting the cursor`() = runBlocking {
        val store = RecordingStore()
        store.put(stored(note(uuid = "n1"), lastSyncedSeq = 9L))
        engine(store, ScriptedTransport(pages = listOf(openedPage(note(uuid = "n1"), firstSeq = 9L)))).runPass()
        assertEquals(9L, store.cursor)

        val transport = ScriptedTransport(
            onChanges = { throw SyncTransportException(TransportFault.CURSOR_AHEAD_OF_SERVER, "rolled back") },
        )
        val outcome = engine(store, transport).runPass()

        assertEquals(HaltReason.SERVER_ROLLED_BACK, (outcome as SyncOutcome.Halted).reason)
        assertEquals("the cursor is evidence, not something to repair", 9L, store.cursor)
    }

    /**
     * §8's F7, the record-level half: a clean row whose clock is above the version the server just
     * offered can only mean the server went backwards.
     */
    @Test
    fun `a rolled back record halts the engine and writes nothing`() = runBlocking {
        val store = RecordingStore()
        store.put(stored(note(content = "version two", rowClock = hlc(20)), dirty = false, lastSyncedSeq = 2L))
        val transport = ScriptedTransport(
            pages = listOf(openedPage(note(content = "version one", rowClock = hlc(10)))),
        )

        val outcome = engine(store, transport).runPass()

        assertEquals(HaltReason.SERVER_ROLLED_BACK, (outcome as SyncOutcome.Halted).reason)
        assertEquals("version two", store.noteRow("n1")!!.record.text(FieldClocks.CONTENT))
        assertEquals("the version this device already superseded is not what it now points at", 0L, store.cursor)
    }

    /**
     * Past a fault the cursor cannot advance, so fetching another page would apply records this
     * pass can never record having seen — and would do it again on every pass from now on.
     */
    @Test
    fun `a fault stops the pull from paging past it`() = runBlocking {
        val store = RecordingStore()
        val page = ChangePage(
            records = listOf(IncomingRecord.Faulted(1L, RecordFault.UNREADABLE)),
            hasMore = true,
        )
        val transport = ScriptedTransport(pages = listOf(page))

        engine(store, transport).runPass()

        assertEquals(1, transport.pulls.size)
    }

    /**
     * `MergeResult.NoChange` means the row's **data** must not move. Its `lastSyncedSeq` must, or
     * the next push is built on a version the server has already replaced and takes a guaranteed
     * `409` on every pass from now until someone edits the note.
     */
    @Test
    fun `a record that changes nothing still records the server version it came from`() = runBlocking {
        val store = RecordingStore()
        val settled = note(uuid = "n1", content = "same", rowClock = hlc(3))
        store.put(stored(settled, dirty = false, lastSyncedSeq = 1L))
        val transport = ScriptedTransport(pages = listOf(openedPage(settled, firstSeq = 4L)))

        val outcome = engine(store, transport).runPass() as SyncOutcome.Completed

        assertEquals(1, outcome.stats.unchanged)
        assertEquals(0, outcome.stats.applied)
        assertEquals(4L, store.noteRow("n1")!!.lastSyncedSeq)
        assertEquals(settled, store.noteRow("n1")!!.record)
    }

    @Test
    fun `a pull pages until the server says there is no more`() = runBlocking {
        val store = RecordingStore()
        val stream = (1..5).map { IncomingRecord.Opened(it.toLong(), note(uuid = "n$it"), null) }
        val transport = PagingTransport(stream)

        engine(store, transport, pageLimit = 2).runPass()

        assertEquals(listOf(0L, 2L, 4L), transport.pulls)
        assertEquals(5, store.rows().size)
        assertEquals(5L, store.cursor)
    }

    // ── Push ───────────────────────────────────────────────────────────────────────────────────

    /**
     * `baseSeq` is the row's own `lastSyncedSeq` and nothing else. Anything else is either a
     * needless conflict or, worse, a lost update.
     */
    @Test
    fun `a dirty row is pushed with its own lastSyncedSeq as the compare-and-set base`() = runBlocking {
        val store = RecordingStore()
        store.put(stored(note(uuid = "n1"), dirty = true, lastSyncedSeq = 7L))
        val transport = ScriptedTransport(
            pushResponses = listOf(PushResponse(listOf(PushAck.Accepted(RecordType.NOTE, "n1", 8L)))),
        )

        engine(store, transport).runPass()

        assertEquals(1, transport.pushes.size)
        assertEquals(7L, transport.pushes.single().single().baseSeq)
    }

    @Test
    fun `an accepted push clears dirty and records the seq`() = runBlocking {
        val store = RecordingStore()
        store.put(stored(note(uuid = "n1"), dirty = true))
        val transport = ScriptedTransport(
            pushResponses = listOf(PushResponse(listOf(PushAck.Accepted(RecordType.NOTE, "n1", 4L)))),
        )

        val outcome = engine(store, transport).runPass()

        assertEquals(1, (outcome as SyncOutcome.Completed).stats.pushed)
        assertEquals(false, store.noteRow("n1")!!.dirty)
        assertEquals(4L, store.noteRow("n1")!!.lastSyncedSeq)
    }

    /**
     * §3.2's two rules together. The user can type while a push is in flight; clearing `dirty`
     * unconditionally drops that edit forever, and skipping `lastSyncedSeq` because the guard
     * failed buys a guaranteed `409` on the next pass for nothing.
     *
     * The acknowledgement is guarded on the clock of the version that was **sent**, so the test
     * edits the row from inside the push call.
     */
    @Test
    fun `a row edited while its push was in flight stays dirty but still records the seq`() = runBlocking {
        val store = RecordingStore()
        store.put(stored(note(uuid = "n1", content = "sent", rowClock = hlc(1)), dirty = true))
        val transport = ScriptedTransport(
            pushResponses = listOf(PushResponse(listOf(PushAck.Accepted(RecordType.NOTE, "n1", 3L)))),
            // The editor's autosave lands after the envelope was sealed and before the ok is read.
            onPush = { store.put(stored(note(uuid = "n1", content = "typed later", rowClock = hlc(2)), dirty = true)) },
        )

        engine(store, transport).runPass()

        assertEquals("the newer edit must still be pushed", true, store.noteRow("n1")!!.dirty)
        assertEquals("but the seq is recorded either way", 3L, store.noteRow("n1")!!.lastSyncedSeq)
        assertEquals("typed later", store.noteRow("n1")!!.record.text(FieldClocks.CONTENT))
    }

    /**
     * A `409` is data. It carries the blocking version inline and that version goes through the
     * same merge call a pulled record takes — §3.2 rule 3, which exists because two merge paths
     * for one job drift apart.
     */
    @Test
    fun `a rejected push is merged through the same path as a pull`() = runBlocking {
        val store = RecordingStore()
        store.put(stored(note(uuid = "n1", content = "mine", rowClock = hlc(1)), dirty = true))
        val blocking = note(uuid = "n1", content = "theirs", rowClock = hlc(9))
        val transport = ScriptedTransport(
            pushResponses = listOf(
                PushResponse(listOf(PushAck.Conflicted(RecordType.NOTE, "n1", blocking, 5L))),
            ),
        )

        val outcome = engine(store, transport).runPass() as SyncOutcome.Completed

        assertEquals(1, outcome.stats.conflicts)
        assertEquals("theirs", store.noteRow("n1")!!.record.text(FieldClocks.CONTENT))
        assertEquals("the losing body is kept as a copy", 1, outcome.stats.conflictCopies)
        assertEquals(5L, store.noteRow("n1")!!.lastSyncedSeq)
    }

    /**
     * The server's response schema allows a conflict with no version attached. There is nothing to
     * merge, so the row stays exactly as it was and the next pass pulls the blocker the slow way.
     */
    @Test
    fun `a rejected push with no version attached leaves the row alone`() = runBlocking {
        val store = RecordingStore()
        store.put(stored(note(uuid = "n1", content = "mine"), dirty = true, lastSyncedSeq = 2L))
        val transport = ScriptedTransport(
            pushResponses = listOf(
                PushResponse(listOf(PushAck.Conflicted(RecordType.NOTE, "n1", current = null, currentSeq = 0L))),
            ),
        )

        engine(store, transport).runPass()

        assertEquals(true, store.noteRow("n1")!!.dirty)
        assertEquals(2L, store.noteRow("n1")!!.lastSyncedSeq)
        assertEquals("mine", store.noteRow("n1")!!.record.text(FieldClocks.CONTENT))
    }

    /**
     * A push conflict's `meta` reaches the store, and this is data loss rather than latency if it
     * does not.
     *
     * `Merge`'s `dirty = merged != remote.normalized()` means a conflict in which any local field
     * wins leaves the row dirty -- as here, where the local title survives -- and a dirty row is
     * pushed again on the next pass, re-serialising `meta` out of the local row. If the engine
     * passed `remoteMeta = null` the store would keep the row's stale value and that re-push would
     * overwrite the server's newer `meta` for every device on the account. Nothing else in this
     * suite would notice, because `meta` is invisible to `SyncRecord` by construction.
     */
    @Test
    fun `a rejected push carries the blocking version's meta to the store`() = runBlocking {
        val store = RecordingStore()
        // The local title is newer than the remote row, the local BODY is older than it. So the
        // merge takes one field from each side: the result differs from the remote (the row stays
        // dirty and will be pushed again -- the hazard) and differs from the local too (so this is
        // an `Applied`, which is what reaches `applyMerged` at all). A merge where the local wins
        // everything is a `NoChange` and never calls the store.
        store.put(
            stored(
                note(
                    uuid = "n1",
                    title = "mine",
                    content = "my body",
                    rowClock = hlc(9),
                    fieldClocks = mapOf(FieldClocks.CONTENT to hlc(1)),
                ),
                dirty = true,
            ),
        )
        val blocking = note(uuid = "n1", title = "theirs", content = "their body", rowClock = hlc(5))
        val transport = ScriptedTransport(
            pushResponses = listOf(
                PushResponse(
                    listOf(
                        PushAck.Conflicted(
                            RecordType.NOTE, "n1", blocking, 5L,
                            currentMeta = "written-by-a-newer-build",
                        ),
                    ),
                ),
            ),
        )

        engine(store, transport).runPass()

        assertTrue(
            "the merge must leave the row dirty, or this is not the hazard under test",
            store.noteRow("n1")!!.dirty,
        )
        assertEquals("written-by-a-newer-build", store.remoteMeta["n1"])
    }

    /** The server refuses a batch of more than 64 items, so the engine never sends one. */
    @Test
    fun `a push is split into batches the server will accept`() = runBlocking {
        val store = RecordingStore()
        repeat(5) { index ->
            store.put(stored(note(uuid = "n$index", rowClock = hlc(index + 1L)), dirty = true))
        }
        val transport = ScriptedTransport(pushResponses = listOf(PushResponse(emptyList())))

        engine(store, transport, batchLimit = 2).runPass()

        assertEquals(listOf(2, 2, 1), transport.pushes.map { it.size })
    }

    /**
     * The transport contract allows a response in any order. An engine that read the results by
     * position would acknowledge one row with another row's seq, which is a lost update that looks
     * like nothing at all.
     */
    @Test
    fun `an acknowledgement is matched to its row by identity, not by position`() = runBlocking {
        val store = RecordingStore()
        store.put(stored(note(uuid = "n1", rowClock = hlc(1)), dirty = true))
        store.put(stored(note(uuid = "n2", rowClock = hlc(2)), dirty = true))
        val transport = ScriptedTransport(
            pushResponses = listOf(
                PushResponse(
                    listOf(
                        PushAck.Accepted(RecordType.NOTE, "n2", 20L),
                        PushAck.Accepted(RecordType.NOTE, "n1", 10L),
                    )
                )
            ),
        )

        engine(store, transport).runPass()

        assertEquals(10L, store.noteRow("n1")!!.lastSyncedSeq)
        assertEquals(20L, store.noteRow("n2")!!.lastSyncedSeq)
    }

    /**
     * There is no delete endpoint. A tombstone is an ordinary dirty row that happens to carry
     * `isDeleted`, and the engine must have no opinion about it at all.
     */
    @Test
    fun `a tombstone is pushed as an ordinary row`() = runBlocking {
        val store = RecordingStore()
        store.put(stored(note(uuid = "n1", deleted = true, deletedAt = 99L), dirty = true))
        val transport = ScriptedTransport(
            pushResponses = listOf(PushResponse(listOf(PushAck.Accepted(RecordType.NOTE, "n1", 1L)))),
        )

        engine(store, transport).runPass()

        val pushed = transport.pushes.single().single().record
        assertEquals("1", pushed.text(FieldClocks.DELETED))
        assertEquals(false, store.noteRow("n1")!!.dirty)
    }

    /**
     * The lost acknowledgement, end to end: the server committed and the client never heard, so the
     * next push takes a `409` carrying this device's **own** record back.
     *
     * The merge of a record against itself must be quiet. In particular it must not read as a
     * contested body and mint a conflict copy of the note against itself, which would give the user
     * a duplicate every time a connection dropped at the wrong moment.
     */
    @Test
    fun `a lost push acknowledgement costs one conflict and no conflict copy`() = runBlocking {
        val store = RecordingStore()
        val written = note(uuid = "n1", content = "written once", rowClock = hlc(5))
        // The row as it is after a push the server committed and the client never read: still
        // dirty, still at baseSeq 0, while the server holds this exact record at seq 1.
        store.put(stored(written, dirty = true, lastSyncedSeq = 0L))
        val transport = ScriptedTransport(
            pushResponses = listOf(
                PushResponse(listOf(PushAck.Conflicted(RecordType.NOTE, "n1", written, 1L))),
            ),
        )

        val outcome = engine(store, transport).runPass() as SyncOutcome.Completed

        assertEquals(1, outcome.stats.conflicts)
        assertEquals("a record merged against itself must never look contested", 0, outcome.stats.conflictCopies)
        assertEquals("and the row must stop being dirty, or it is pushed forever", false, store.noteRow("n1")!!.dirty)
        assertEquals(1L, store.noteRow("n1")!!.lastSyncedSeq)
        assertEquals(1, store.rows().size)
    }

    // ── Conflict copies ────────────────────────────────────────────────────────────────────────

    @Test
    fun `a conflict copy is written alongside the merged row`() = runBlocking {
        val store = RecordingStore()
        store.put(stored(note(uuid = "n1", content = "mine", rowClock = hlc(1)), dirty = true))
        val transport = ScriptedTransport(
            pages = listOf(openedPage(note(uuid = "n1", content = "theirs", rowClock = hlc(9)))),
        )

        engine(store, transport).runPass()

        val copyId = ConflictCopies.idFor("n1", hlc(1))
        assertEquals("mine", store.noteRow(copyId)!!.record.text(FieldClocks.CONTENT))
        assertEquals("a copy has never been on the server", 0L, store.noteRow(copyId)!!.lastSyncedSeq)
        assertEquals(true, store.noteRow(copyId)!!.dirty)
    }

    /**
     * The copy's uuid is derived from the losing body, so the same conflict resolved a second time
     * — by a re-delivered record here, or by the mirror-image merge on the other device — names a
     * copy that already exists. Overwriting it would clobber one the user had since edited.
     */
    @Test
    fun `a conflict copy that already exists is not written again`() = runBlocking {
        val store = RecordingStore()
        store.put(stored(note(uuid = "n1", content = "mine", rowClock = hlc(1)), dirty = true))
        val copyId = ConflictCopies.idFor("n1", hlc(1))
        store.put(stored(note(uuid = copyId, content = "mine, since edited"), dirty = true))
        val transport = ScriptedTransport(
            pages = listOf(openedPage(note(uuid = "n1", content = "theirs", rowClock = hlc(9)))),
        )

        val outcome = engine(store, transport).runPass() as SyncOutcome.Completed

        assertEquals(0, outcome.stats.conflictCopies)
        assertEquals("mine, since edited", store.noteRow(copyId)!!.record.text(FieldClocks.CONTENT))
    }

    /**
     * The baseline advances against the record that **arrived**, never against the merged result.
     *
     * The two devices agreed on the incoming version; they have never agreed on a three-way merge
     * only this one performed. Taking the merged record's content clock marks this device's own
     * unpublished body as published, and the next merge then discards it without a copy — a body
     * the user typed, gone, with nothing anywhere saying so.
     *
     * The scenario needs two arrivals, because the damage is done by the first and only visible on
     * the second.
     */
    @Test
    fun `the baseline advances against the record that arrived, not the merged result`() = runBlocking {
        val store = RecordingStore()
        store.put(
            stored(
                note(uuid = "n1", content = "mine", rowClock = hlc(10)),
                dirty = true,
                contentBaseline = hlc(1),
            )
        )
        val transport = ScriptedTransport(
            pages = listOf(
                // Same body at an OLDER content clock, so the merge keeps this device's version of
                // `content` (clock 10) while the agreed version's is 5.
                openedPage(
                    note(
                        uuid = "n1",
                        content = "mine",
                        title = "renamed elsewhere",
                        rowClock = hlc(20),
                        fieldClocks = mapOf(FieldClocks.CONTENT to hlc(5)),
                    ),
                    firstSeq = 1L,
                ),
                // A genuinely concurrent body. This device's "mine" is still unpublished, so it
                // must survive as a copy.
                openedPage(note(uuid = "n1", content = "theirs", rowClock = hlc(30)), firstSeq = 2L),
            ),
        )
        val engine = engine(store, transport)

        engine.runPass()
        assertEquals(hlc(5), store.noteRow("n1")!!.contentBaseline)

        engine.runPass()

        assertEquals("theirs", store.noteRow("n1")!!.record.text(FieldClocks.CONTENT))
        val copy = store.noteRow(ConflictCopies.idFor("n1", hlc(10)))
        assertEquals("the unpublished body must not have been discarded", "mine", copy?.record?.text(FieldClocks.CONTENT))
    }

    // ── The pass, and stopping ─────────────────────────────────────────────────────────────────

    /**
     * Pull before push, always. Pushing first maximises the number of `409`s, because every
     * conflict the server would report is one this device could have merged a moment earlier.
     */
    @Test
    fun `a pass pulls before it pushes`() = runBlocking {
        val store = RecordingStore()
        store.put(stored(note(uuid = "n1"), dirty = true))
        val order = mutableListOf<String>()
        val transport = ScriptedTransport(
            pushResponses = listOf(PushResponse(listOf(PushAck.Accepted(RecordType.NOTE, "n1", 1L)))),
            onChanges = { order += "pull" },
            onPush = { order += "push" },
        )

        engine(store, transport).runPass()

        assertEquals(listOf("pull", "push"), order)
    }

    /**
     * A round costs a round trip, so it happens only when the push actually merged something —
     * that merged row is a version the server does not have and the next pull may be arguing with
     * it. A push that merely succeeded has nothing left to say.
     */
    @Test
    fun `a pass rounds again only when the push merged something`() = runBlocking {
        val quiet = RecordingStore()
        quiet.put(stored(note(uuid = "n1"), dirty = true))
        val quietTransport = ScriptedTransport(
            pushResponses = listOf(PushResponse(listOf(PushAck.Accepted(RecordType.NOTE, "n1", 1L)))),
        )
        engine(quiet, quietTransport).runPass()
        assertEquals("one pull, and no reason for a second", 1, quietTransport.pulls.size)

        val contested = RecordingStore()
        contested.put(stored(note(uuid = "n1", content = "mine", rowClock = hlc(1)), dirty = true))
        val blocking = note(uuid = "n1", content = "theirs", rowClock = hlc(9))
        val busyTransport = ScriptedTransport(
            pushResponses = listOf(
                PushResponse(listOf(PushAck.Conflicted(RecordType.NOTE, "n1", blocking, 5L))),
            ),
        )
        engine(contested, busyTransport, maxRounds = 3).runPass()
        assertEquals("a merged conflict is a reason to go round again", 2, busyTransport.pulls.size)
    }

    /**
     * A peer that conflicts with every push must not hold a pass open indefinitely. The cap is
     * three rounds; the next pass is a better place to keep arguing than this one.
     */
    @Test
    fun `a pass stops rounding at its cap`() = runBlocking {
        val store = RecordingStore()
        // The local row wins the merge, so it stays dirty and is pushed -- and refused -- again on
        // every round, which is the only way to reach the cap without a peer that is also lying.
        store.put(stored(note(uuid = "n1", content = "mine", rowClock = hlc(9)), dirty = true))
        val transport = AlwaysConflictTransport(note(uuid = "n1", content = "theirs", rowClock = hlc(1)))

        engine(store, transport, maxRounds = 3).runPass()

        assertEquals(3, transport.pulls)
        assertEquals(3, transport.pushes)
    }

    @Test
    fun `a halted engine refuses to run and does not touch the server`() = runBlocking {
        val store = RecordingStore()
        store.recordHalt(HaltReason.SERVER_ROLLED_BACK)
        val transport = ScriptedTransport()

        val outcome = engine(store, transport).runPass()

        assertEquals(HaltReason.SERVER_ROLLED_BACK, (outcome as SyncOutcome.Halted).reason)
        assertEquals(0, transport.pulls.size)
        assertEquals(0, transport.pushes.size)
    }

    /**
     * Clearing the halt is what makes it recoverable rather than terminal.
     *
     * The cause is usually still there, so the next pass usually stops on the same thing again --
     * which is correct, and is asserted separately below. What matters here is that the engine
     * *looks*, because the alternative is that a person who fixed the cause (updated the app after
     * an unsupported payload, re-paired after a revocation) has no way to say so short of a
     * reinstall that costs them their local library.
     */
    @Test
    fun `a cleared halt lets the engine run again`() = runBlocking {
        val store = RecordingStore()
        store.recordHalt(HaltReason.UNSUPPORTED_PAYLOAD_VERSION)
        val transport = ScriptedTransport()

        assertEquals(
            "precondition: it refuses while halted",
            HaltReason.UNSUPPORTED_PAYLOAD_VERSION,
            (engine(store, transport).runPass() as SyncOutcome.Halted).reason,
        )
        assertEquals(0, transport.pulls.size)

        store.clearHalt()
        val outcome = engine(store, transport).runPass()

        assertTrue("a cleared halt must run, not refuse: $outcome", outcome is SyncOutcome.Completed)
        assertEquals("and it must actually reach the server", 1, transport.pulls.size)
    }

    /**
     * The honest half of the promise: clearing a halt repairs nothing.
     *
     * If the condition is still true the engine finds it again and stops again, and the UI copy is
     * written against exactly that ("Try again", never "Fix"). A build that cleared a halt and then
     * *stayed* running against a rolled-back server would be the data-loss path the halt exists to
     * prevent.
     */
    @Test
    fun `clearing a halt whose cause remains halts again on the next pass`() = runBlocking {
        val store = RecordingStore()
        store.put(stored(note(content = "version two", rowClock = hlc(20)), lastSyncedSeq = 2L))
        val rolledBack = { ScriptedTransport(pages = listOf(openedPage(note(content = "version one", rowClock = hlc(10))))) }
        engine(store, rolledBack()).runPass()
        assertEquals(HaltReason.SERVER_ROLLED_BACK, store.halt())

        store.clearHalt()
        val outcome = engine(store, rolledBack()).runPass()

        assertEquals(
            "the same server, the same rollback, the same refusal",
            HaltReason.SERVER_ROLLED_BACK,
            (outcome as SyncOutcome.Halted).reason,
        )
        assertEquals(HaltReason.SERVER_ROLLED_BACK, store.halt())
    }

    /**
     * The halt outlives the process, because so does the thing that caused it. An engine that
     * forgot its halt on restart would resume syncing against precisely the server it refused to
     * trust.
     */
    @Test
    fun `a halt is persisted so a fresh engine after process death still refuses`() = runBlocking {
        val store = RecordingStore()
        store.put(stored(note(content = "version two", rowClock = hlc(20)), lastSyncedSeq = 2L))
        engine(store, ScriptedTransport(pages = listOf(openedPage(note(content = "version one", rowClock = hlc(10))))))
            .runPass()

        val afterRestart = ScriptedTransport()
        val outcome = engine(store, afterRestart).runPass()

        assertEquals(HaltReason.SERVER_ROLLED_BACK, (outcome as SyncOutcome.Halted).reason)
        assertEquals(0, afterRestart.pulls.size)
    }

    /**
     * Two overlapping passes would both read the dirty rows and both push them, and the second
     * would take a `409` against the first.
     */
    @Test
    fun `an overlapping pass is refused rather than queued`() = runBlocking {
        val store = RecordingStore()
        lateinit var engine: SyncEngine
        var reentrant: SyncOutcome? = null
        // Re-entering from inside a transport call is the only way to be *inside* a pass without
        // threads, and threads would make this test the flaky one it is trying not to be.
        val transport = ScriptedTransport(onChanges = { reentrant = runBlocking { engine.runPass() } })
        engine = engine(store, transport)

        engine.runPass()

        assertEquals(SyncOutcome.AlreadyRunning, reentrant)
    }

    /**
     * §8's F5. Three devices in one household unlock at breakfast, all sync, all get throttled at
     * the same instant and all are told to come back in five seconds; identical waits collide
     * again five seconds later, against a machine the user pays for themselves.
     */
    @Test
    fun `a rate limit ends the pass and asks the caller to wait longer than the server did`() = runBlocking {
        val store = RecordingStore()
        val transport = ScriptedTransport(
            onChanges = {
                throw SyncTransportException(TransportFault.RATE_LIMITED, "429", retryAfterMillis = 5_000L)
            },
        )
        val engine = engine(store, transport, retryPlan = RetryPlan(RetryJitter { it / 2 }))

        val outcome = engine.runPass() as SyncOutcome.Deferred

        assertEquals(TransportFault.RATE_LIMITED, outcome.fault)
        assertEquals(7_500L, outcome.retryAfterMillis)
        assertNull("a rate limit is not a halt", store.halt)
    }

    /**
     * A network failure is not a request to stay away. Backing off from one would stop the app
     * syncing the moment a train went into a tunnel.
     */
    @Test
    fun `a network failure defers without asking for a wait`() = runBlocking {
        val store = RecordingStore()
        val transport = ScriptedTransport(
            onChanges = { throw SyncTransportException(TransportFault.NETWORK, "no route") },
        )

        val outcome = engine(store, transport).runPass() as SyncOutcome.Deferred

        assertEquals(0L, outcome.retryAfterMillis)
        assertNull(store.halt)
    }

    @Test
    fun `a revoked device halts`() = runBlocking {
        val store = RecordingStore()
        val transport = ScriptedTransport(
            onChanges = { throw SyncTransportException(TransportFault.DEVICE_REVOKED, "revoked") },
        )

        val outcome = engine(store, transport).runPass()

        assertEquals(HaltReason.DEVICE_REVOKED, (outcome as SyncOutcome.Halted).reason)
    }

    // ── The clock ──────────────────────────────────────────────────────────────────────────────

    /**
     * Every clock this device is shown goes into the generator, **including one from a record that
     * was refused**. The server holds that record, so this device's next write has to sort above
     * it; a generator that had not seen it could mint a write that loses to its own older version
     * on the next sync.
     */
    @Test
    fun `every remote clock is reported to the generator, including a rejected one`() = runBlocking {
        val store = RecordingStore()
        store.put(stored(note(content = "version two", rowClock = hlc(20)), lastSyncedSeq = 2L))
        val clock = RecordingClock()
        val transport = ScriptedTransport(
            pages = listOf(openedPage(note(content = "version one", rowClock = hlc(10)))),
        )

        engine(store, transport, clock = clock).runPass()

        assertEquals(listOf(hlc(10)), clock.seen)
    }

    private fun engine(
        store: SyncStore,
        transport: SyncTransport,
        clock: ClockObserver = RecordingClock(),
        retryPlan: RetryPlan = RetryPlan(RetryJitter.NONE),
        batchLimit: Int = SyncEngine.DEFAULT_BATCH_LIMIT,
        pageLimit: Int = SyncEngine.DEFAULT_PAGE_LIMIT,
        maxRounds: Int = SyncEngine.DEFAULT_MAX_ROUNDS,
    ) = SyncEngine(
        store = store,
        transport = transport,
        clock = clock,
        retryPlan = retryPlan,
        batchLimit = batchLimit,
        pageLimit = pageLimit,
        maxRounds = maxRounds,
    )
}
