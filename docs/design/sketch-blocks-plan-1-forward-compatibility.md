# Forward compatibility & re-baseline — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A device can receive a record type it does not understand without freezing or halting, and can recover the records it skipped after it is upgraded.

**Architecture:** Split "a record I cannot read" from "a record I was not written for" at three layers — codec, transport, engine — and make only the second skippable. Then add a `dataVersion` to `sync_state` and a one-shot cursor reset on the first pass after it is bumped, so a device that skipped records back-fills them once it understands them.

**Tech Stack:** Kotlin Multiplatform (`:core-domain`, `:core-sync-codec`, `:core-sync-engine`), Room + SQLCipher (`:core-data`), plain SQLite over JDBC (`:desktop`), JUnit 4.

**Spec:** [`sketch-blocks.md`](sketch-blocks.md) — steps 1 and 2. Step 3 (sketches) is a separate plan and **must not start until this one is installed on both devices**.

## Global Constraints

- **This plan ships alone.** No sketch record type, no new payload field. Its whole purpose is to be running on every device *before* anything new exists to skip.
- `./gradlew verify` is the gate (unit + androidTest compile + instrumented on an attached device). The server suite is a separate build: `cd server && ./gradlew test`.
- Every mutation claim must be verified by breaking the code, confirming the **named** test fails, then reverting and confirming byte-identical with `diff -q`. A `--tests` filter that matches nothing prints no failures and looks like a pass — always confirm the test name appears in the output.
- Commit messages: no AI attribution, no `Co-Authored-By`. Author is `CHEYSOFF <66472023+CHEYSOFF@users.noreply.github.com>`.
- `--` is legal in Kotlin comments and **fatal inside XML comments**; it surfaces far from its cause.
- Kotlin/Native is a target: no `java.*` in `commonMain`. The `mingwX64` canary enforces this.

---

## File Structure

**Modified:**

| File | Responsibility after this plan |
|---|---|
| `core-sync-codec/src/commonMain/.../RecordPayload.kt` | Decode; now distinguishes an unknown `recType` from a damaged payload. |
| `core-sync-codec/src/jvmCommonMain/.../RecordCodec.kt` | Open; carries that distinction outward. |
| `core-sync-codec/src/jvmCommonMain/.../EnvelopeSyncTransport.kt` | Maps it to a transport-level fault. |
| `core-sync-engine/src/commonMain/.../SyncTransport.kt` | `RecordFault.UNKNOWN_TYPE`. |
| `core-sync-engine/src/commonMain/.../SyncOutcome.kt` | `PassStats.ignored`. |
| `core-sync-engine/src/commonMain/.../SyncStore.kt` | `dataVersion()` / `saveDataVersion()`. |
| `core-sync-engine/src/commonMain/.../SyncEngine.kt` | Skips unknown types; performs the one-shot re-baseline. |
| `core-data/.../local/SyncStateEntity.kt`, `SyncStateDao.kt`, `NoteDatabase.kt` | The `dataVersion` column and its migration. |
| `core-data/.../sync/RoomSyncStore.kt` | Implements the two new store methods. |
| `desktop/.../store/RecordStore.kt`, `desktop/.../sync/RecordSyncStore.kt` | The same, over JDBC. |

**No new production files.** Every change extends an existing seam.

---

### Task 1: The codec tells an unknown type from a damaged payload

**Files:**
- Modify: `core-sync-codec/src/commonMain/kotlin/my/cheysoff/core_sync_codec/RecordPayload.kt`
- Test: `core-sync-codec/src/jvmTest/kotlin/my/cheysoff/core_sync_codec/RecordPayloadUnknownTypeTest.kt` (create)

**Interfaces:**
- Produces: `PayloadResult.UnknownType(val wireKey: String)`, returned by `RecordPayloadCodec.decode` when `RecordType.fromWireKey` returns null.

- [ ] **Step 1: Write the failing test**

```kotlin
package my.cheysoff.core_sync_codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A record type this build does not know is not a damaged record.
 *
 * The distinction is the whole point: a damaged record is evidence something is wrong and the
 * engine must stop; a record written for a later build is evidence of nothing at all, and stopping
 * on it freezes the cursor and then halts a device whose only problem is that it has not been
 * updated yet.
 */
class RecordPayloadUnknownTypeTest {

    /** A payload identical in shape to a note's, but naming a type this build has never heard of. */
    private fun bytesWithRecType(wireKey: String): ByteArray =
        """
        {"v":1,"serializer":1,"recType":"$wireKey","uuid":"u1",
         "hlc":"1-0-node","fields":{},"clocks":{},"deleted":false}
        """.trimIndent().encodeToByteArray()

    @Test
    fun `an unknown record type decodes as UnknownType, not Malformed`() {
        val result = RecordPayloadCodec.decode(bytesWithRecType("sketch"))

        assertTrue("expected UnknownType, got $result", result is PayloadResult.UnknownType)
        assertEquals("sketch", (result as PayloadResult.UnknownType).wireKey)
    }

    @Test
    fun `a genuinely damaged payload is still Malformed`() {
        // A known type whose required keys are missing: this build should have been able to read
        // it and could not, which is a different fact and must keep its old, louder handling.
        val damaged = """{"v":1,"serializer":1,"recType":"note"}""".encodeToByteArray()

        assertTrue(RecordPayloadCodec.decode(damaged) is PayloadResult.Malformed)
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew :core-sync-codec:jvmTest --tests "my.cheysoff.core_sync_codec.RecordPayloadUnknownTypeTest"`

Expected: FAIL — `PayloadResult.UnknownType` does not resolve (compile error).

- [ ] **Step 3: Add the case and return it**

In `RecordPayload.kt`, beside `Malformed`:

```kotlin
    /**
     * The payload named a `recType` this build does not implement.
     *
     * Deliberately **not** [Malformed]. A malformed record is one this build should have been able
     * to read and could not, which is evidence of corruption or a wrong key and is why the engine
     * refuses to page past it. This is the opposite: the record decrypted, authenticated and parsed
     * far enough to say what it is, and this build simply has no representation for it. Nothing is
     * wrong, and treating it as damage freezes the cursor of a device whose only fault is being a
     * version behind.
     */
    data class UnknownType(val wireKey: String) : PayloadResult
```

And replace the `fromWireKey` line:

```kotlin
        val recTypeKey = root.getValue(KEY_REC_TYPE).jsonPrimitive.content
        val recType = RecordType.fromWireKey(recTypeKey)
            ?: return PayloadResult.UnknownType(recTypeKey)
```

- [ ] **Step 4: Run it and watch it pass**

Run: `./gradlew :core-sync-codec:jvmTest --tests "my.cheysoff.core_sync_codec.RecordPayloadUnknownTypeTest"`

Expected: PASS, both tests. Confirm both names appear in the output — a filter matching nothing also "passes".

- [ ] **Step 5: Commit**

```bash
git add core-sync-codec/src/commonMain/kotlin/my/cheysoff/core_sync_codec/RecordPayload.kt \
        core-sync-codec/src/jvmTest/kotlin/my/cheysoff/core_sync_codec/RecordPayloadUnknownTypeTest.kt
git commit -m "Tell an unknown record type from a damaged one, in the codec"
```

---

### Task 2: Carry the distinction out through the codec and transport

**Files:**
- Modify: `core-sync-codec/src/jvmCommonMain/kotlin/my/cheysoff/core_sync_codec/RecordCodec.kt`
- Modify: `core-sync-codec/src/jvmCommonMain/kotlin/my/cheysoff/core_sync_codec/EnvelopeSyncTransport.kt`
- Modify: `core-sync-engine/src/commonMain/kotlin/my/cheysoff/core_sync_engine/SyncTransport.kt`
- Test: `core-sync-codec/src/jvmTest/kotlin/my/cheysoff/core_sync_codec/EnvelopeSyncTransportTest.kt` (extend)

**Interfaces:**
- Consumes: `PayloadResult.UnknownType` (Task 1).
- Produces: `OpenResult.UnknownType(val wireKey: String)`; `RecordFault.UNKNOWN_TYPE`.

- [ ] **Step 1: Write the failing test**

Append to `EnvelopeSyncTransportTest`, along with the two helpers it needs — the existing file has
fixtures for sealing a *known* type, and neither of these exists yet:

```kotlin
    /**
     * A sealed record naming a type this build does not implement.
     *
     * Sealed by hand rather than through `RecordPayloadCodec.encode`, which cannot express an
     * unknown type — the only way to produce these bytes is to write them the way a later build
     * would.
     */
    private fun sealedRecordWithRecType(wireKey: String): RemoteRecord {
        val json = """
            {"v":1,"serializer":1,"recType":"$wireKey","uuid":"u1",
             "hlc":"1-0-node","fields":{},"clocks":{},"deleted":false}
        """.trimIndent().encodeToByteArray()
        val blindedId = codec.blindedIdOf(wireKey, "u1")
        return RemoteRecord(seq = 1L, blindedId = blindedId, envelope = codec.sealRaw(blindedId, json))
    }

    /** The transport under test, serving exactly [records] from one page. */
    private fun transportOver(vararg records: RemoteRecord): EnvelopeSyncTransport = ...
```

`codec.sealRaw` does not exist either. Add it to `RecordCodec` as an `internal` test seam that seals
already-encoded plaintext, or — preferably — build the envelope directly with `RecordEnvelope.seal`
in the test and leave production code untouched. Prefer the second: a production method that exists
only for tests is a method someone will eventually call in production.

```kotlin
    @Test
    fun `a record of an unknown type arrives as UNKNOWN_TYPE, not UNREADABLE`() {
        // UNREADABLE means "I should have been able to read this and could not", and the engine
        // reacts by refusing to page past it. A type from a later build earns neither reaction.
        val page = transportOver(sealedRecordWithRecType("sketch")).changesSince(0, 32)

        val faulted = page.records.single() as IncomingRecord.Faulted
        assertEquals(RecordFault.UNKNOWN_TYPE, faulted.fault)
    }
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew :core-sync-codec:jvmTest --tests "my.cheysoff.core_sync_codec.EnvelopeSyncTransportTest"`

Expected: FAIL — `RecordFault.UNKNOWN_TYPE` does not resolve.

- [ ] **Step 3: Add the case at both layers**

`SyncTransport.kt`, in `RecordFault`:

```kotlin
    /**
     * The record opened and parsed, and names a type this build does not implement.
     *
     * The only fault the engine may page **past**. See [SyncEngine]'s pull loop for why that is
     * safe here and for nothing else: a record this build cannot represent could not have been
     * stored even if it had been accepted, so advancing past it loses nothing that was ever going
     * to be kept.
     */
    UNKNOWN_TYPE,
```

`RecordCodec.kt`, in `OpenResult`:

```kotlin
    /** Decrypted, authentic, well-formed, and of a type this build does not implement. */
    data class UnknownType(val wireKey: String) : OpenResult
```

and in `open`:

```kotlin
                is PayloadResult.UnknownType -> OpenResult.UnknownType(result.wireKey)
```

`EnvelopeSyncTransport.kt`, in the `when` over `OpenResult`:

```kotlin
                    is OpenResult.UnknownType ->
                        IncomingRecord.Faulted(remote.seq, RecordFault.UNKNOWN_TYPE)
```

- [ ] **Step 4: Run it and watch it pass**

Run: `./gradlew :core-sync-codec:jvmTest --tests "my.cheysoff.core_sync_codec.EnvelopeSyncTransportTest"`

Expected: PASS. The compiler's exhaustiveness check on the `when` will also flag any `OpenResult` branch missed elsewhere — fix those rather than adding an `else`.

- [ ] **Step 5: Commit**

```bash
git add core-sync-codec/src/jvmCommonMain core-sync-engine/src/commonMain/kotlin/my/cheysoff/core_sync_engine/SyncTransport.kt \
        core-sync-codec/src/jvmTest
git commit -m "Carry the unknown-type distinction out to the transport"
```

---

### Task 3: The engine skips an unknown type instead of freezing on it

**Files:**
- Modify: `core-sync-engine/src/commonMain/kotlin/my/cheysoff/core_sync_engine/SyncOutcome.kt`
- Modify: `core-sync-engine/src/commonMain/kotlin/my/cheysoff/core_sync_engine/SyncEngine.kt:183-203`
- Test: `core-sync-engine/src/jvmTest/kotlin/my/cheysoff/core_sync_engine/SyncEngineTest.kt` (extend)

**Interfaces:**
- Consumes: `RecordFault.UNKNOWN_TYPE` (Task 2).
- Produces: `PassStats.ignored: Int`.

- [ ] **Step 1: Write the failing tests**

```kotlin
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
        val many = (1..UNREADABLE_RECORD_LIMIT * 3).map {
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
        val many = (1..UNREADABLE_RECORD_LIMIT + 2).map {
            IncomingRecord.Faulted(it.toLong(), RecordFault.UNREADABLE)
        }
        val outcome = engine(store, ScriptedTransport(pages = listOf(ChangePage(many, hasMore = false))))
            .runPass()

        assertEquals(HaltReason.RECORDS_UNREADABLE, (outcome as SyncOutcome.Halted).reason)
        assertEquals("the cursor must not have moved past a damaged record", 0L, store.cursor())
    }
```

- [ ] **Step 2: Run them and watch them fail**

Run: `./gradlew :core-sync-engine:jvmTest --tests "my.cheysoff.core_sync_engine.SyncEngineTest"`

Expected: FAIL — `PassStats.ignored` and `RecordFault.UNKNOWN_TYPE` unresolved in the test.

- [ ] **Step 3: Add the counter and the branch**

`SyncOutcome.kt`, in `PassStats`, after `unreadable`:

```kotlin
    /**
     * Records skipped because this build does not implement their type. The cursor DID advance
     * past these — unlike [unreadable] — so they will not be offered again.
     *
     * Counted rather than dropped silently because "your other device is writing things this one
     * cannot show you" is a fact a person may need, and because a number that is quietly always
     * non-zero is how a rollout mistake stays invisible.
     */
    val ignored: Int = 0,
```

Leave `movedSomething` alone: an ignored record moved nothing, and counting it would make a caller that loops until quiescence loop forever.

`SyncEngine.kt`, in the `when (incoming.fault)`:

```kotlin
                        // The one fault the cursor may pass. A record this build cannot represent
                        // would not have been stored even if it had been accepted, so nothing is
                        // lost by moving on -- and freezing here is what halted a device whose
                        // only problem was being one version behind. Task 7's re-baseline is how
                        // it recovers these once it understands them.
                        RecordFault.UNKNOWN_TYPE -> {
                            stats = stats.copy(ignored = stats.ignored + 1)
                            if (!frozen) committable = incoming.seq
                        }
```

- [ ] **Step 4: Run them and watch them pass**

Run: `./gradlew :core-sync-engine:jvmTest --tests "my.cheysoff.core_sync_engine.SyncEngineTest"`

Expected: PASS, with all three new names in the output.

- [ ] **Step 5: Mutation-verify the pair**

Change `RecordFault.UNKNOWN_TYPE ->` to also set `frozen = true`. Run the suite. Expected: `records of an unknown type are skipped and the cursor moves past them` FAILS.

Then change the `UNREADABLE` branch to advance `committable`. Run. Expected: `an unreadable record still freezes the cursor and still halts in quantity` FAILS.

Revert both:

```bash
git diff --stat   # must be empty for SyncEngine.kt before continuing
```

- [ ] **Step 6: Commit**

```bash
git add core-sync-engine/src
git commit -m "Skip a record type this build does not implement, rather than freezing on it"
```

---

### Task 4: `dataVersion` on the phone

**Files:**
- Modify: `core-data/src/main/java/my/cheysoff/core_data/data/local/SyncStateEntity.kt`
- Modify: `core-data/src/main/java/my/cheysoff/core_data/data/local/SyncStateDao.kt`
- Modify: `core-data/src/main/java/my/cheysoff/core_data/data/local/NoteDatabase.kt`
- Test: `core-data/src/test/java/my/cheysoff/core_data/RoomSyncStoreTest.kt` (extend)

**Interfaces:**
- Produces: `SyncStateDao.dataVersion(accountId): Int?` and `SyncStateDao.saveDataVersion(accountId, version)`.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun `a data version round-trips`() = runTest {
        assertNull("a device that has never pulled has no version", database.syncStateDao.dataVersion(account))

        database.syncStateDao.saveDataVersion(account, 2)

        assertEquals(2, database.syncStateDao.dataVersion(account))
    }

    /**
     * Saving the version must not invent a cursor. A row conjured here would claim this device had
     * pulled up to 0 on an account it has never contacted, and `takeSnapshotOnce` reads a cursor of
     * 0 as "before the first pull".
     */
    @Test
    fun `saving a data version does not disturb the cursor`() = runTest {
        store.saveCursor(12L)

        database.syncStateDao.saveDataVersion(account, 2)

        assertEquals(12L, store.cursor())
    }
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew :core-data:testDebugUnitTest --tests "my.cheysoff.core_data.RoomSyncStoreTest"`

Expected: FAIL — `dataVersion` unresolved.

- [ ] **Step 3: Add the column, the migration and the DAO methods**

`SyncStateEntity.kt`, a new property:

```kotlin
    /**
     * The record-format generation this device last completed a pull under, or `0` before one has
     * been recorded.
     *
     * Not on the wire and not per-record: it is a fact about *this install's* build, and it exists
     * so that a device which skipped record types it did not understand can re-pull them once it
     * does. See `SyncEngine`'s re-baseline.
     */
    @ColumnInfo(defaultValue = "0") val dataVersion: Int = 0,
```

`NoteDatabase.kt` — bump `NOTE_DATABASE_VERSION` to `9`, and add:

```kotlin
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_state ADD COLUMN dataVersion INTEGER NOT NULL DEFAULT 0")
                // Existing rows are declared current rather than left at 0. A device upgrading to
                // this build cannot have skipped anything: at this generation there is no record
                // type it does not implement. Leaving them at 0 would make every existing install
                // re-pull its whole account once, for nothing.
                db.execSQL("UPDATE sync_state SET dataVersion = ${SyncEngine.DATA_VERSION}")
            }
        }
```

Add `MIGRATION_8_9` to `ALL_MIGRATIONS`. `MigrationChainTest` will fail on the JVM if it is forgotten.

`SyncStateDao.kt`:

```kotlin
    @Query("SELECT dataVersion FROM sync_state WHERE accountId = :accountId")
    suspend fun dataVersion(accountId: String): Int?

    /**
     * An UPDATE, not an upsert, for the reason [clearHalt] gives: a device with no row has never
     * pulled, and inventing one would fabricate a cursor of 0 for an account it knows nothing
     * about. A device with no row also needs no re-baseline — its next pull starts at 0 anyway.
     */
    @Query("UPDATE sync_state SET dataVersion = :version WHERE accountId = :accountId")
    suspend fun saveDataVersion(accountId: String, version: Int)
```

- [ ] **Step 4: Run it and watch it pass, then export the schema**

Run: `./gradlew :core-data:testDebugUnitTest --tests "my.cheysoff.core_data.RoomSyncStoreTest"`
Then: `./gradlew :core-data:assembleDebug` and confirm `core-data/schemas/…/9.json` was written.

Expected: PASS, and the schema JSON committed alongside — Room fails the build if the entity and the migration disagree.

- [ ] **Step 5: Commit**

```bash
git add core-data/src core-data/schemas
git commit -m "Record which format generation a device last pulled under"
```

---

### Task 5: `dataVersion` on the desktop

**Files:**
- Modify: `desktop/src/main/kotlin/my/cheysoff/desktop/store/RecordStore.kt` (`migrate()`, plus two accessors)
- Test: `desktop/src/test/kotlin/my/cheysoff/desktop/store/RecordStoreDataVersionTest.kt` (create)

**Interfaces:**
- Produces: `RecordStore.dataVersion(accountId): Int?`, `RecordStore.saveDataVersion(accountId, version)`.

- [ ] **Step 1: Write the failing test**

```kotlin
package my.cheysoff.desktop.store

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordStoreDataVersionTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var store: RecordStore
    private val account = "account-under-test"

    @Before fun setUp() {
        store = RecordStore.open(folder.newFolder("vault").toPath().resolve("records.db"))
    }

    @After fun tearDown() = store.close()

    @Test
    fun `a data version round-trips`() {
        assertNull(store.dataVersion(account))
        store.saveDataVersion(account, 2)
        assertEquals(2, store.dataVersion(account))
    }

    /** Same rule as the phone's: recording a version must not invent a cursor. */
    @Test
    fun `saving a data version does not disturb the cursor`() {
        store.saveCursor(account, 12L)
        store.saveDataVersion(account, 2)
        assertEquals(12L, store.cursor(account))
    }

    /** A vault written before this column existed must open, not throw. */
    @Test
    fun `a store opened twice keeps its version`() {
        store.saveDataVersion(account, 2)
        store.close()
        store = RecordStore.open(folder.root.toPath().resolve("vault").resolve("records.db"))
        assertEquals(2, store.dataVersion(account))
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew :desktop:test --tests "my.cheysoff.desktop.store.RecordStoreDataVersionTest"`

Expected: FAIL — `dataVersion` unresolved.

- [ ] **Step 3: Add the column and the accessors**

In `migrate()`, after the `sync_state` CREATE — using the existing `hasColumn` guard, for the reason its neighbour states (a swallowed `SQLException` would also swallow a disk failure):

```kotlin
            if (!hasColumn("sync_state", "data_version")) {
                statement.executeUpdate("ALTER TABLE sync_state ADD COLUMN data_version INTEGER")
            }
```

Nullable here, where the phone's is `NOT NULL DEFAULT 0`: an existing desktop row predates the column and `NULL` says "never recorded" honestly, which is the same state the phone spells as an absent row.

```kotlin
    fun dataVersion(accountId: String): Int? =
        connection.prepareStatement("SELECT data_version FROM sync_state WHERE account_id = ?")
            .use { statement ->
                statement.setString(1, accountId)
                statement.executeQuery().use {
                    if (!it.next()) null else it.getInt(1).takeUnless { _ -> it.wasNull() }
                }
            }

    /** An UPDATE, never an upsert: see [clearHalt] for why a missing row must stay missing. */
    fun saveDataVersion(accountId: String, version: Int) {
        connection.prepareStatement("UPDATE sync_state SET data_version = ? WHERE account_id = ?")
            .use { statement ->
                statement.setInt(1, version)
                statement.setString(2, accountId)
                statement.executeUpdate()
            }
    }
```

- [ ] **Step 4: Run it and watch it pass**

Run: `./gradlew :desktop:test --tests "my.cheysoff.desktop.store.RecordStoreDataVersionTest"`

Expected: PASS, all three names in the output.

- [ ] **Step 5: Commit**

```bash
git add desktop/src
git commit -m "Record the format generation on the desktop store too"
```

---

### Task 6: Put the version on the `SyncStore` contract

**Files:**
- Modify: `core-sync-engine/src/commonMain/kotlin/my/cheysoff/core_sync_engine/SyncStore.kt`
- Modify: `core-data/src/main/java/my/cheysoff/core_data/data/sync/RoomSyncStore.kt`
- Modify: `desktop/src/main/kotlin/my/cheysoff/desktop/sync/RecordSyncStore.kt`
- Modify: `core-sync-engine/src/jvmTest/kotlin/my/cheysoff/core_sync_engine/EngineFixtures.kt`
- Modify: `core-sync-engine/src/jvmTest/kotlin/my/cheysoff/core_sync_engine/harness/ReplicaStore.kt`

**Interfaces:**
- Consumes: Tasks 4 and 5.
- Produces: `SyncStore.dataVersion(): Int`, `SyncStore.saveDataVersion(version: Int)`.

- [ ] **Step 1: Add to the interface**

```kotlin
    /**
     * The format generation this device last completed a pull under, or [SyncEngine.DATA_VERSION]
     * when nothing has been recorded.
     *
     * The default matters: a store with no row for this account has never pulled, so its next pull
     * starts at 0 and fetches everything anyway. Reporting `0` there would send it through a
     * re-baseline that could not possibly find anything it had missed.
     */
    suspend fun dataVersion(): Int

    /** Records that a pull completed under [version]. Written only after a completed pass. */
    suspend fun saveDataVersion(version: Int)
```

- [ ] **Step 2: Implement in `RoomSyncStore`**

```kotlin
    override suspend fun dataVersion(): Int =
        syncStateDao.dataVersion(accountId) ?: SyncEngine.DATA_VERSION

    override suspend fun saveDataVersion(version: Int) =
        syncStateDao.saveDataVersion(accountId, version)
```

- [ ] **Step 3: Implement in `RecordSyncStore`**

```kotlin
    override suspend fun dataVersion(): Int =
        store.dataVersion(accountId) ?: SyncEngine.DATA_VERSION

    override suspend fun saveDataVersion(version: Int) = store.saveDataVersion(accountId, version)
```

- [ ] **Step 4: Implement in both test fakes**

In `EngineFixtures.kt`'s `RecordingStore` and in `harness/ReplicaStore.kt`:

Name the field **`storedDataVersion`**, not `dataVersion`: it would otherwise collide with the
method, and Task 7's tests set it by that name.

```kotlin
    var storedDataVersion: Int = SyncEngine.DATA_VERSION

    override suspend fun dataVersion(): Int = storedDataVersion

    override suspend fun saveDataVersion(version: Int) {
        storedDataVersion = version
    }
```

- [ ] **Step 5: Compile everything**

Run: `./gradlew :core-sync-engine:jvmTest :core-data:compileDebugUnitTestKotlin :desktop:compileTestKotlin`

Expected: BUILD SUCCESSFUL. Any other `SyncStore` implementation will fail here, which is the point of not giving these methods defaults.

- [ ] **Step 6: Commit**

```bash
git add core-sync-engine core-data desktop
git commit -m "Put the format generation on the SyncStore contract"
```

---

### Task 7: The one-shot re-baseline

**Files:**
- Modify: `core-sync-engine/src/commonMain/kotlin/my/cheysoff/core_sync_engine/SyncEngine.kt`
- Test: `core-sync-engine/src/jvmTest/kotlin/my/cheysoff/core_sync_engine/SyncEngineRebaselineTest.kt` (create)

**Interfaces:**
- Consumes: `SyncStore.dataVersion()` / `saveDataVersion()` (Task 6).
- Produces: `SyncEngine.DATA_VERSION` (`const val`, currently `1`).

- [ ] **Step 1: Write the failing tests**

```kotlin
package my.cheysoff.core_sync_engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recovering records a previous build skipped.
 *
 * The forward-compatibility change lets a device page past record types it does not implement,
 * which leaves its cursor beyond records it never stored. Without this, upgrading that device would
 * never show it those records: they are behind its cursor forever.
 *
 * Re-pulling from 0 is safe against the rollback guard, and the reason is worth keeping in front of
 * whoever changes this: `changesSince` serves **head** versions only. So every record either
 * arrives at the clock this device already holds — equal, not lower, so
 * `remote.rowClock < local.rowClock` is false — or newer. Nothing that a clean row would reject.
 * This is NOT the cursor reset that `RejectReason.ROLLBACK_SUSPECTED` forbids: that one is a
 * response to a server that has stopped being trustworthy, where an emptied account would read as
 * "delete everything". Here the client asks for the replay and the server is known-good.
 */
class SyncEngineRebaselineTest {

    @Test
    fun `a device behind the current generation re-pulls from zero`() = runBlocking {
        val store = RecordingStore()
        store.saveCursor(50L)
        store.storedDataVersion = SyncEngine.DATA_VERSION - 1
        val transport = ScriptedTransport()

        engine(store, transport).runPass()

        assertEquals("the pull must start from the beginning", 0L, transport.pulls.single().since)
        assertEquals("and the generation is now recorded", SyncEngine.DATA_VERSION, store.storedDataVersion)
    }

    @Test
    fun `a device already at the current generation does not re-pull`() = runBlocking {
        val store = RecordingStore()
        store.saveCursor(50L)
        store.storedDataVersion = SyncEngine.DATA_VERSION
        val transport = ScriptedTransport()

        engine(store, transport).runPass()

        assertEquals("an ordinary pass resumes at the cursor", 50L, transport.pulls.single().since)
    }

    /**
     * The version is written only by a pass that finished. A re-baseline interrupted halfway
     * through must run again, or the device records that it has caught up while still missing the
     * records the interruption cut off.
     */
    @Test
    fun `an interrupted re-baseline does not record the generation`() = runBlocking {
        val store = RecordingStore()
        store.saveCursor(50L)
        store.storedDataVersion = SyncEngine.DATA_VERSION - 1
        val transport = ScriptedTransport(failOnPull = SyncTransportException.Unreachable)

        val outcome = engine(store, transport).runPass()

        assertTrue("precondition: the pass did not complete", outcome !is SyncOutcome.Completed)
        assertEquals(
            "so the next launch must re-baseline again",
            SyncEngine.DATA_VERSION - 1,
            store.storedDataVersion,
        )
    }

    @Test
    fun `a re-baseline only happens once`() = runBlocking {
        val store = RecordingStore()
        store.storedDataVersion = SyncEngine.DATA_VERSION - 1
        store.saveCursor(50L)
        val transport = ScriptedTransport()

        engine(store, transport).runPass()
        engine(store, transport).runPass()

        assertEquals(0L, transport.pulls[0].since)
        assertTrue("the second pass resumes normally", transport.pulls[1].since > 0L)
    }
}
```

`ScriptedTransport` does not record what it was asked for, and these tests assert on exactly that.
Extend it first — a fixture change, no production code:

```kotlin
    /** Every `changesSince` call, in order. The tests assert on `since`, not just on the result. */
    val pulls = mutableListOf<PullRequest>()

    data class PullRequest(val since: Long, val limit: Int)

    /** When set, every pull throws it — for testing what a pass that never completes leaves behind. */
    var failOnPull: SyncTransportException? = null

    override suspend fun changesSince(since: Long, limit: Int): ChangePage {
        pulls += PullRequest(since, limit)
        failOnPull?.let { throw it }
        return pages.getOrElse(pulls.size - 1) { ChangePage(emptyList(), hasMore = false) }
    }
```

Check `SyncTransportException`'s actual cases before using `Unreachable` in the test above; use
whichever case the file defines for a connection that did not complete.

- [ ] **Step 2: Run them and watch them fail**

Run: `./gradlew :core-sync-engine:jvmTest --tests "my.cheysoff.core_sync_engine.SyncEngineRebaselineTest"`

Expected: FAIL — `SyncEngine.DATA_VERSION` unresolved.

- [ ] **Step 3: Implement**

In `SyncEngine`'s companion:

```kotlin
        /**
         * The record-format generation this build implements. Bump it in the same commit that adds
         * a record type or changes a payload's shape, and never otherwise: every device that has
         * pulled under a lower number re-pulls its whole account once, which is cheap for a small
         * library and is not free for a large one.
         */
        const val DATA_VERSION = 1
```

In `pull()`, replace `val startCursor = store.cursor()` with:

```kotlin
        // A generation behind means this device paged past record types it did not implement, so
        // its cursor is beyond records it never stored. One pull from 0 gets them; see this file's
        // rebaseline test for why that cannot trip the rollback guard.
        val rebaselining = store.dataVersion() < DATA_VERSION
        val startCursor = if (rebaselining) 0L else store.cursor()
```

and in `finishPull`, where a pass is being reported as `Completed`:

```kotlin
        // Only on a completed pass. A re-baseline cut short by a dropped connection must run again;
        // recording the generation here would tell the device it had caught up while the records
        // the interruption cost it are still behind its cursor.
        if (rebaselining) store.saveDataVersion(DATA_VERSION)
```

Thread `rebaselining` into `finishPull` as a parameter rather than a field — the engine is used concurrently only under its own mutex, but a field here would be state that outlives the pass that owns it.

- [ ] **Step 4: Run them and watch them pass**

Run: `./gradlew :core-sync-engine:jvmTest --tests "my.cheysoff.core_sync_engine.SyncEngineRebaselineTest"`

Expected: PASS, all four names in the output.

- [ ] **Step 5: Mutation-verify**

Move `store.saveDataVersion(DATA_VERSION)` so it runs on every pass rather than only completed ones. Expected: `an interrupted re-baseline does not record the generation` FAILS. Revert; `git diff --stat` empty.

- [ ] **Step 6: Commit**

```bash
git add core-sync-engine/src
git commit -m "Re-pull once after a format generation bump, so skipped records come back"
```

---

### Task 8: Say it out loud — the ignored count reaches the user

The spec asks for the count to be *visible*, and Task 3 only reaches `PassStats`, which no screen
reads. Without this, a device a version behind shows nothing at all to explain why its notes are
missing drawings — which is the exact class of silent absence this codebase keeps having to remove.

**Files:**
- Modify: `core-domain/src/commonMain/kotlin/my/cheysoff/core_domain/sync/SyncController.kt` (`SyncPassSummary`)
- Modify: `app/src/main/java/my/cheysoff/notes/sync/DefaultSyncController.kt` (`describe`)
- Modify: `feature-settings/src/main/java/my/cheysoff/feature_settings/model/SyncRow.kt` (`syncStatusLine`)
- Test: `feature-settings/src/test/java/my/cheysoff/feature_settings/SyncRowTest.kt` (extend)

**Interfaces:**
- Consumes: `PassStats.ignored` (Task 3).
- Produces: `SyncPassSummary.ignored: Int`.

- [ ] **Step 1: Write the failing test**

```kotlin
    /**
     * A device a version behind must say so. The records are not lost and nothing is broken, but
     * "some notes here are missing parts this app cannot show" is a fact the person needs in order
     * to know the answer is to update -- and it is invisible on the screen that would otherwise
     * report a completely successful sync.
     */
    @Test
    fun `a pass that ignored records says so, and points at the reason`() {
        val line = syncStatusLine(
            SyncStatus.IDLE,
            SyncPassState.Completed(SyncPassSummary(received = 4, applied = 3, ignored = 1)),
        )

        assertTrue("the count must appear: $line", line.contains("1"))
        assertTrue(
            "and it must name the cause rather than sounding like damage: $line",
            line.contains("newer version", ignoreCase = true),
        )
        assertFalse("it is not a failure: $line", line.contains("failed", ignoreCase = true))
    }
```

Check `SyncStatus`'s actual constant for the idle-with-a-completed-pass case before using
`SyncStatus.IDLE`; the enum in `SyncRow.kt` is the authority.

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew :feature-settings:testDebugUnitTest --tests "my.cheysoff.feature_settings.SyncRowTest"`

Expected: FAIL — `SyncPassSummary` has no `ignored`.

- [ ] **Step 3: Carry the count through and word it**

`SyncController.kt`, in `SyncPassSummary`:

```kotlin
    /** Records skipped because this build does not implement their type. See `PassStats.ignored`. */
    val ignored: Int = 0,
```

`DefaultSyncController.describe`: copy `stats.ignored` into the summary alongside the other counts.

`SyncRow.kt`, in `syncStatusLine`'s builder, beside the existing `parts`:

```kotlin
        // Worded as a fact about this app, not about the data: the records are fine, they are on
        // the other device, and the fix is to update this one. "Couldn't read" would describe
        // damage and send someone looking for a problem that does not exist.
        if (summary.ignored > 0) add("skipped ${summary.ignored} needing a newer version of the app")
```

- [ ] **Step 4: Run it and watch it pass**

Run: `./gradlew :feature-settings:testDebugUnitTest --tests "my.cheysoff.feature_settings.SyncRowTest"`

Expected: PASS. The existing `SyncRowTest` cases must still pass unmodified — they enforce that no
status line ever claims more than an event that happened.

- [ ] **Step 5: Commit**

```bash
git add core-domain feature-settings app
git commit -m "Say when a pass skipped records this build is too old to show"
```

---

### Task 9: Full verification and the rollout gate

- [ ] **Step 1: Clear stale results and run the gate**

```bash
rm -rf */build/test-results
./gradlew verify
```

Expected: BUILD SUCCESSFUL, instrumented suites included.

- [ ] **Step 2: Count honestly**

```bash
find . -path "*/build/test-results/*" -name "TEST-*.xml" -not -path "./.claude/*" -not -path "./server/*" \
  | xargs grep -ho 'tests="[0-9]*"\|failures="[0-9]*"\|errors="[0-9]*"' \
  | awk -F'"' '{a[$1]+=$2} END {for (k in a) print k, a[k]}'
```

Then confirm every module with a test source directory reported. A module that silently ran nothing looks exactly like a module that passed.

- [ ] **Step 3: Confirm the payload is unchanged**

`RecordPayloadWireFormatTest` and `RecordPayloadWireContractTest` must pass **unmodified**. This plan adds no field and no type; if either golden test needed editing, something in it was wrong.

- [ ] **Step 4: Commit, PR, merge**

- [ ] **Step 5: Install on BOTH devices before starting the sketch plan**

Desktop: `./gradlew :desktop:packageMsi`, back up `%LOCALAPPDATA%\Manana-vault` first, uninstall and reinstall, confirm `records.db` is the same size afterwards.

Phone: build and install with the device attached.

**The sketch plan must not merge until this is running on both.** The whole value of this work is being installed *before* there is anything to skip; shipping sketches first makes it retroactive and useless.
