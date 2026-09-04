# Sketches: the data path — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A sketch is a first-class record — its own type, its own row, its own clocks — that merges and syncs between a phone and a desktop, with stroke geometry that encodes identically on both.

**Architecture:** `RecordType.SKETCH` is a child record referencing its note by uuid, carrying the drawing as one clocked string field. Placement is an integer block index stored on the sketch, never a marker in the note's text, so a build without sketch support can still read and edit the note. The stroke encoding is integer-only and byte-stable, because a re-encoding that differed by one character would make every sketch dirty at once.

**Tech Stack:** Kotlin Multiplatform (`:core-domain`, `:core-sync-codec`, `:core-sync-engine`), Room + SQLCipher (`:core-data`), SQLite over JDBC (`:desktop`), JUnit 4.

**Spec:** [`sketch-blocks.md`](sketch-blocks.md) — step 3, excluding the "Drawing UX" and "Rendering elsewhere" sections, which are plan 3.

**Two spec requirements are deliberately NOT here, and belong to plan 3.** Named so they cannot fall
between the two plans:

- **Maintaining the anchor when text is edited.** This plan makes the anchor storable, countable and
  clampable; it does not make a device that inserts a paragraph above a drawing renumber it. That is
  triggered by editing, so it lives with the editor. Until it exists, a drawing's position drifts
  whenever text above it changes — on *every* device, not only old ones, which is worse than the
  spec's stated cost. Plan 3 must close it.
- **The capture-time size guard.** The spec refuses a stroke set beyond a stated limit "at capture
  time rather than discovering it at push time, where the failure is a `413` the user cannot act
  on". There is no capture in this plan. Plan 3 owns it.

**Prerequisite, already met:** forward compatibility + `dataVersion` (PR #101) is merged **and installed on both devices**. That is what makes this plan safe to ship one device at a time. Do not start if either device is behind.

## Global Constraints

- **`SyncEngine.DATA_VERSION` must go from `1` to `2` in the same commit that adds `RecordType.SKETCH`.** Its KDoc says so. A device that pulls sketches under generation 1 and skips them would never re-baseline to pick them up.
- **Integer-only in the stroke encoding.** Kotlin/JVM and Kotlin/Native do not format floats identically; one differing character makes every sketch on the account dirty at once and stampedes the server.
- `commonMain` must contain no `java.*` — the `mingwX64` canary enforces it.
- `./gradlew verify` is the gate (unit + androidTest compile + instrumented on an attached device). Run it **in the foreground**; a backgrounded Gradle run has lost its result twice in this project.
- A `--tests` filter that matches nothing prints no failures and looks like a pass. Always confirm the test's name appears in the output, and run the full module suite before reporting.
- Room migrations are historical statements: **never interpolate a mutable constant into migration SQL.**
- Commit messages: descriptive prose, no AI attribution, no `Co-Authored-By`.
- `--` is legal in Kotlin comments and fatal inside XML comments.

---

## File Structure

**Create:**

| File | Responsibility |
|---|---|
| `core-domain/src/commonMain/.../sketch/StrokeCodec.kt` | Encode/decode stroke geometry. Pure, integer-only, byte-stable. |
| `core-domain/src/commonMain/.../sketch/Stroke.kt` | `Stroke`, `Point`, `Sketch` — the in-memory shape. |
| `core-domain/src/commonMain/.../sketch/NoteBlocks.kt` | Count a note body's top-level blocks, for anchoring. One definition, both platforms. |
| `core-data/.../local/SketchEntity.kt`, `SketchDao.kt` | The `sketches` table. |
| `core-sync-codec/.../SketchRecords.kt` | `SketchRow` ⇄ payload, mirroring `NoteRecords`. |

**Modify:** `FieldClocks.kt`, `SyncRecord.kt` (`RecordType`), `PayloadFields.kt`, `SyncRecords.kt` (`FIELD_TO_COLUMNS`), `SyncEngine.kt` (`DATA_VERSION`), `NoteDatabase.kt` (`MIGRATION_9_10`), `RoomSyncStore.kt`, `RoomNotesRepository.kt`, and the desktop's `RecordSyncStore.kt` / `RecordNotesRepository.kt`.

---

### Task 1: The stroke encoding

The one piece with no precedent in the repo, and the one whose mistakes are permanent — stored bytes outlive every decision around them.

**Files:**
- Create: `core-domain/src/commonMain/kotlin/my/cheysoff/core_domain/sketch/Stroke.kt`
- Create: `core-domain/src/commonMain/kotlin/my/cheysoff/core_domain/sketch/StrokeCodec.kt`
- Test: `core-domain/src/commonTest/kotlin/my/cheysoff/core_domain/sketch/StrokeCodecTest.kt`
  — **`commonTest`, not `jvmTest`.** The whole reason this encoding emits no floats is that
  Kotlin/JVM and Kotlin/Native format them differently; a test that runs only on the JVM proves
  nothing about the property it exists to protect. `:core-domain` currently declares only a
  `jvmTest` source set, so **this task adds `commonTest` with the `kotlin-test` dependency** and the
  test goes there, where both `jvmTest` and `mingwX64Test` pick it up. Use `kotlin.test` assertions
  (`kotlin.test.assertEquals`), not JUnit's — JUnit does not exist on Native.

**Interfaces:**
- Produces: `Sketch(width: Int, height: Int, strokes: List<Stroke>)`, `Stroke(colorArgb: Long, width: Int, points: List<Point>)`, `Point(x: Int, y: Int)`; `StrokeCodec.encode(Sketch): String` and `StrokeCodec.decode(String): Sketch?` (null for anything unparseable).

The format, from the spec:

```
1|3277x4096|ff2c1ab0,24:0,0;120,40;250,90|ffdcdcdc,48:900,300;880,420
```

Version, then `WxH` in canvas units, then one `|`-separated stroke per drawing stroke: ARGB hex, comma, nib width, colon, then `;`-separated points **delta-encoded** from the previous point (the first is absolute).

- [ ] **Step 1: Write the failing tests**

```kotlin
package my.cheysoff.core_domain.sketch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The stored form of a drawing.
 *
 * Byte-stability is the property that matters most here and is the least obvious. Every device
 * decodes a sketch and re-encodes it whenever it merges or re-saves; if that round trip differed by
 * one character, every sketch on the account would go dirty at once and be pushed again. The same
 * failure already threatens this codebase through `richeditor`'s HTML round trip, which is why the
 * encoding below emits no floats at all — Kotlin/JVM and Kotlin/Native do not format them
 * identically, and the desktop and the phone would disagree about bytes that mean the same picture.
 */
class StrokeCodecTest {

    private val sample = Sketch(
        width = 3277,
        height = 4096,
        strokes = listOf(
            Stroke(colorArgb = 0xff2c1ab0, width = 24, points = listOf(Point(0, 0), Point(120, 40), Point(370, 130))),
            Stroke(colorArgb = 0xffdcdcdc, width = 48, points = listOf(Point(900, 300), Point(880, 420))),
        ),
    )

    /** The golden fixture. If this changes, every stored sketch is re-encoded — see the class doc. */
    private val goldenText =
        "1|3277x4096|ff2c1ab0,24:0,0;120,40;250,90|ffdcdcdc,48:900,300;-20,120"

    @Test
    fun `encodes to the pinned format`() {
        assertEquals(goldenText, StrokeCodec.encode(sample))
    }

    @Test
    fun `decodes the pinned format back to the same drawing`() {
        assertEquals(sample, StrokeCodec.decode(goldenText))
    }

    @Test
    fun `re-encoding a decoded sketch is byte-identical`() {
        val once = StrokeCodec.encode(sample)
        val twice = StrokeCodec.encode(StrokeCodec.decode(once)!!)
        assertEquals("a round trip must not move a single byte", once, twice)
    }

    @Test
    fun `points after the first are stored as deltas`() {
        // 120,40 -> 370,130 is +250,+90. Storing absolutes would roughly double a long stroke.
        assertTrue(StrokeCodec.encode(sample).contains("250,90"))
    }

    @Test
    fun `an empty sketch round-trips`() {
        val empty = Sketch(width = 3277, height = 4096, strokes = emptyList())
        assertEquals(empty, StrokeCodec.decode(StrokeCodec.encode(empty)))
    }

    @Test
    fun `nothing unparseable throws`() {
        // Decode is fed bytes that came off a network from another device. A malformed sketch is
        // one record to refuse, never a reason to take down a sync pass.
        listOf(
            "", "1", "1|", "1|3277", "1|axb|ff0000,1:0,0", "1|1x1|nothex,1:0,0",
            "1|1x1|ff0000,x:0,0", "1|1x1|ff0000,1:0", "1|1x1|ff0000,1:a,b",
            "2|1x1|ff0000,1:0,0", "1|1x1|ff0000,1:0,0;",
        ).forEach { assertNull("should not decode: <$it>", StrokeCodec.decode(it)) }
    }

    @Test
    fun `a future version is refused rather than guessed at`() {
        assertNull(StrokeCodec.decode("2|1x1|ff0000,1:0,0"))
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `./gradlew :core-domain:jvmTest --tests "my.cheysoff.core_domain.sketch.StrokeCodecTest"`
Expected: FAIL — nothing resolves.

- [ ] **Step 3: Implement the model and the codec**

`Stroke.kt`:

```kotlin
package my.cheysoff.core_domain.sketch

/**
 * One point, in canvas units — never pixels.
 *
 * The canvas is an integer grid whose long edge is always 4096, so a drawing renders identically on
 * a phone and a 27-inch monitor and the stored form does not depend on the device that drew it.
 */
data class Point(val x: Int, val y: Int)

/** One continuous mark: a colour, a nib width in canvas units, and the path it took. */
data class Stroke(val colorArgb: Long, val width: Int, val points: List<Point>)

/**
 * A whole drawing. [width] and [height] are the canvas it was drawn on, so a renderer can letterbox
 * it into whatever space it has rather than distorting it.
 */
data class Sketch(val width: Int, val height: Int, val strokes: List<Stroke>)
```

`StrokeCodec.kt` — implement `encode`/`decode` to the format above. Requirements the tests pin:
- version prefix `1`; any other version decodes to null;
- `colorArgb` as lowercase 8-digit hex, no `0x`;
- first point absolute, the rest deltas;
- **no float ever reaches the output**;
- every malformed input returns null rather than throwing — wrap the parse and return null on any failure.

- [ ] **Step 4: Run them and watch them pass**

Run: `./gradlew :core-domain:jvmTest --tests "my.cheysoff.core_domain.sketch.StrokeCodecTest"`
Expected: PASS, with all seven names in the output.

- [ ] **Step 5: Prove it is float-free and Native-identical**

Run: `./gradlew :core-domain:allTests` — this runs the tests on **both** the JVM and mingwX64.

**Check the Native target actually ran your tests, do not assume it.** Before this task
`:core-domain` had no `commonTest`, so `mingwX64Test` had nothing to run and reported success by
having nothing to do. Confirm the test names appear in the mingwX64 results
(`core-domain/build/test-results/mingwX64Test/`), and say the count in your report. A green Native
task with zero tests is the exact shape of false evidence this project has been bitten by twice.

- [ ] **Step 6: Commit**

```bash
git add core-domain/src/commonMain/kotlin/my/cheysoff/core_domain/sketch core-domain/src/jvmTest/kotlin/my/cheysoff/core_domain/sketch
git commit -m "Encode a drawing as integers, byte-stably"
```

---

### Task 2: Counting a note's blocks, for the anchor

A sketch stores *where* it sits as an index over the note's top-level blocks. Both platforms must count identically or the same note shows the drawing in two places.

**Files:**
- Create: `core-domain/src/commonMain/kotlin/my/cheysoff/core_domain/sketch/NoteBlocks.kt`
- Test: `core-domain/src/commonTest/kotlin/my/cheysoff/core_domain/sketch/NoteBlocksTest.kt`
  — `commonTest` for the same reason as Task 1: "both platforms count identically" is the
  requirement, and a JVM-only test cannot check it. The source set exists after Task 1.

**Interfaces:**
- Produces: `NoteBlocks.count(content: String, format: NoteContentFormat): Int` and `NoteBlocks.clamp(anchor: Int, blockCount: Int): Int`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package my.cheysoff.core_domain.sketch

import my.cheysoff.core_domain.model.NoteContentFormat
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where a drawing sits in a note's text.
 *
 * The index is stored on the sketch and never in the note's body, so a build without sketch support
 * reads and edits the note normally and cannot damage a reference it does not hold. The cost is
 * that such a build's edits shift the blocks this counts, so a drawing can render a paragraph away
 * from where it was put — misplaced, never lost.
 *
 * Both platforms must agree exactly. A disagreement here is the same note showing the drawing in
 * two different places on two devices, which no test on either platform alone would catch.
 */
class NoteBlocksTest {

    @Test
    fun `html blocks are its top-level elements`() {
        val html = "<p>One</p><p>Two</p><ul><li>a</li><li>b</li></ul>"
        assertEquals("the list is one block, not two", 3, NoteBlocks.count(html, NoteContentFormat.HTML))
    }

    @Test
    fun `plain text blocks are its lines`() {
        assertEquals(3, NoteBlocks.count("one\ntwo\nthree", NoteContentFormat.PLAIN))
    }

    @Test
    fun `an empty body has no blocks`() {
        assertEquals(0, NoteBlocks.count("", NoteContentFormat.HTML))
        assertEquals(0, NoteBlocks.count("", NoteContentFormat.PLAIN))
    }

    @Test
    fun `content it cannot parse still returns a usable count`() {
        // Never throws: an unparseable body must cost a drawing its position, never its existence.
        val count = NoteBlocks.count("<p>unclosed<div>", NoteContentFormat.HTML)
        assertEquals(true, count >= 0)
    }

    @Test
    fun `an anchor past the end clamps to the last block`() {
        assertEquals(3, NoteBlocks.clamp(anchor = 99, blockCount = 3))
        assertEquals(0, NoteBlocks.clamp(anchor = -4, blockCount = 3))
        assertEquals(2, NoteBlocks.clamp(anchor = 2, blockCount = 3))
    }

    @Test
    fun `an anchor of zero means before the first block`() {
        assertEquals(0, NoteBlocks.clamp(anchor = 0, blockCount = 3))
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `./gradlew :core-domain:jvmTest --tests "my.cheysoff.core_domain.sketch.NoteBlocksTest"`

- [ ] **Step 3: Implement**

Count HTML by scanning for opening tags in a fixed block-level set — `p`, `div`, `h1`..`h6`, `ul`, `ol`, `blockquote`, `pre` — at nesting depth zero, so a `<li>` inside a `<ul>` does not count. Plain text counts non-empty lines. `clamp` coerces into `0..blockCount`. **Never throw**: on any parse difficulty return the count reached so far.

Write the block-tag set as a named constant with a comment saying that changing it moves every existing sketch, and that it must be changed on both platforms in the same release or they disagree.

- [ ] **Step 4: Run them on both targets**

Run: `./gradlew :core-domain:allTests`, and again confirm the names appear in the mingwX64 results —
the claim in this task's title is precisely that the two platforms agree.

- [ ] **Step 5: Commit**

```bash
git add core-domain/src/commonMain/kotlin/my/cheysoff/core_domain/sketch/NoteBlocks.kt core-domain/src/commonTest/kotlin/my/cheysoff/core_domain/sketch/NoteBlocksTest.kt
git commit -m "Count a note's blocks the same way on both platforms"
```

---

### Task 3: The record type, its fields, and the generation bump

**Files:**
- Modify: `core-domain/.../sync/FieldClocks.kt`, `core-domain/.../sync/SyncRecord.kt` (`RecordType`)
- Modify: `core-sync-codec/.../PayloadFields.kt`, `core-sync-codec/.../SyncRecords.kt` (`FIELD_TO_COLUMNS`)
- Modify: `core-sync-engine/.../SyncEngine.kt` (`DATA_VERSION`)
- Test: `core-domain/src/jvmTest/.../sync/` and `core-sync-codec/src/jvmTest/...`

**Interfaces:**
- Produces: `RecordType.SKETCH` (wire key `"sketch"`); `FieldClocks.SKETCH_FIELDS`; `PayloadFields.SKETCH_COLUMNS`; `SyncEngine.DATA_VERSION == 2`.

Field keys: `noteId`, `anchor`, `order`, `strokes`, plus the existing `updatedAt` and `deleted`.
Payload columns: those four, plus `createdAt`, `updatedAt`, `isDeleted`, `deletedAt`.

- [ ] **Step 1: Write the failing tests**

```kotlin
    @Test
    fun `a sketch's clocked fields and payload columns line up`() {
        // The two vocabularies differ on purpose -- `deleted` is one clock over two columns -- and
        // this pins that the difference is only ever that one.
        assertEquals(
            setOf("noteId", "anchor", "order", "strokes", "updatedAt", "deleted"),
            RecordType.SKETCH.fields,
        )
        assertEquals(
            setOf("noteId", "anchor", "order", "strokes", "createdAt", "updatedAt", "isDeleted", "deletedAt"),
            PayloadFields.columnsOf(RecordType.SKETCH),
        )
    }

    @Test
    fun `every clocked sketch field maps to columns`() {
        // A field with no column mapping makes `SyncRecords.fromPayload` return null for every
        // sketch ever sent -- silently, since a null there is "a record to skip".
        RecordType.SKETCH.fields.forEach { field ->
            assertNotNull("no column mapping for '$field'", SyncRecords.columnsFor(field))
        }
    }

    @Test
    fun `the data generation was bumped with the new type`() {
        // Not decoration. A device that pulls sketches while still at generation 1 skips them, and
        // without a bump it would never re-baseline to pick them up -- the exact hole PR #101 exists
        // to close.
        assertEquals(2, SyncEngine.DATA_VERSION)
    }
```

`SyncRecords.columnsFor(field)` does not exist — add it as an `internal` accessor over the existing private `FIELD_TO_COLUMNS`, rather than making the map itself public.

- [ ] **Step 2: Run them and watch them fail**

- [ ] **Step 3: Implement**

Add the four constants to `FieldClocks` and `PayloadFields`, `SKETCH_FIELDS` and `SKETCH_COLUMNS` as `linkedSetOf` in serialisation order, the `RecordType.SKETCH` entry, the `columnsOf` branch, the four `FIELD_TO_COLUMNS` entries (each one column — no sketch field spans two), and `DATA_VERSION = 2` **in this same commit**.

`RecordType.partCount` needs no change: no sketch field spans two columns.

- [ ] **Step 4: Run the full affected suites**

Run: `./gradlew :core-domain:jvmTest :core-sync-codec:jvmTest :core-sync-engine:jvmTest`
Expected: PASS. Adding an enum case will break exhaustive `when`s — fix each at its site rather than adding `else`, and report which files needed it.

- [ ] **Step 5: Confirm the golden note payload did not move**

Run: `./gradlew :core-sync-codec:jvmTest --tests "*RecordPayloadWireFormatTest*"`
Expected: PASS **unmodified**. Adding a type must not change a note's bytes; if it did, something was added to the wrong set.

- [ ] **Step 6: Commit**

```bash
git add core-domain core-sync-codec core-sync-engine
git commit -m "Add the sketch record type, and bump the format generation with it"
```

---

### Task 4: Payload mapping for sketches

**Files:**
- Create: `core-sync-codec/src/commonMain/kotlin/my/cheysoff/core_sync_codec/SketchRecords.kt`
- Test: `core-sync-codec/src/jvmTest/kotlin/my/cheysoff/core_sync_codec/SketchRecordsTest.kt`

**Interfaces:**
- Consumes: `RecordType.SKETCH`, `PayloadFields.SKETCH_COLUMNS` (Task 3); `StrokeCodec` (Task 1).
- Produces: **`SketchData`** — create it in this task, at
  `core-domain/src/commonMain/kotlin/my/cheysoff/core_domain/model/SketchData.kt`, alongside `Note`
  and `Folder`:

```kotlin
package my.cheysoff.core_domain.model

/**
 * One drawing, as a record: its identity, where it belongs, and its geometry as stored text.
 *
 * [strokes] is deliberately the ENCODED string rather than a parsed `Sketch`. This type crosses the
 * repository and sync boundaries, where the value is only ever moved, merged and compared -- and
 * comparing decoded geometry for equality is both slower and less strict than comparing the bytes
 * that will actually be written. Decode at the edge that draws it. See `StrokeCodec`.
 */
data class SketchData(
    val id: String,
    val noteId: String,
    /** Index over the owning note's top-level blocks. See `NoteBlocks`. */
    val anchor: Int,
    /** Position among sketches sharing one anchor; ties break by [id] so both devices agree. */
    val order: Int,
    val strokes: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
)
```

  and `SketchRow(sketch: SketchData, dirty: Boolean, lastSyncedSeq: Long)` plus
  `SketchRecords.toPayload(row, createdAt)` / `SketchRecords.fromPayload(payload): SketchRow?`,
  mirroring `NoteRecords`.

Read `NoteRecords.kt` first and mirror its shape exactly, including its "null rather than a default" rule: a numeric column that will not parse means a record to refuse, not a zero to substitute.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun `a sketch round-trips through a payload`() {
        val row = SketchRow(
            sketch = SketchData(
                id = "s1",
                noteId = "n1",
                anchor = 2,
                order = 0,
                strokes = "1|3277x4096|ff2c1ab0,24:0,0;120,40",
                createdAt = 1_700_000_000_000,
                updatedAt = 1_700_000_001_000,
                isDeleted = false,
                deletedAt = null,
            ),
            dirty = true,
            lastSyncedSeq = 0L,
        )

        val back = SketchRecords.fromPayload(SketchRecords.toPayload(row, createdAt = row.sketch.createdAt))

        assertEquals(row.sketch, back?.sketch)
    }

    @Test
    fun `an unparseable anchor refuses the record rather than defaulting`() {
        // Substituting 0 would silently move someone's drawing to the top of the note, on every
        // device, with nothing anywhere saying why.
        val payload = payloadWith(anchor = "not-a-number")
        assertNull(SketchRecords.fromPayload(payload))
    }
```

- [ ] **Step 2: Run it and watch it fail**
- [ ] **Step 3: Implement**, mirroring `NoteRecords`.
- [ ] **Step 4: Run the full `:core-sync-codec:jvmTest` suite and quote the total.**
- [ ] **Step 5: Commit**

```bash
git commit -m "Map a sketch to and from its payload"
```

---

### Task 5: The `sketches` table

**Files:**
- Create: `core-data/src/main/java/my/cheysoff/core_data/data/local/SketchEntity.kt`, `SketchDao.kt`
- Modify: `core-data/.../local/NoteDatabase.kt` (entity list, `NOTE_DATABASE_VERSION` → 10, `MIGRATION_9_10`, `ALL_MIGRATIONS`)
- Test: `core-data/src/test/java/my/cheysoff/core_data/SketchDaoTest.kt`

Columns, mirroring `notes`' sync bookkeeping exactly:

```
sketches(uuid TEXT PK, noteId TEXT NOT NULL, anchor INTEGER NOT NULL, `order` INTEGER NOT NULL,
         strokes TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
         isDeleted INTEGER NOT NULL DEFAULT 0, deletedAt INTEGER,
         hlcMs INTEGER NOT NULL, hlcCounter INTEGER NOT NULL, hlcNode TEXT NOT NULL,
         fieldHlc TEXT NOT NULL DEFAULT '', dirty INTEGER NOT NULL DEFAULT 1,
         lastSyncedSeq INTEGER NOT NULL DEFAULT 0)
```

**`dirty` DEFAULTs to 1**, pinned in the DDL, the Kotlin default and `@ColumnInfo(defaultValue = "1")` — for the reason `MIGRATION_6_7` documents: `0` declares the library already uploaded and lets the first pull erase it. `order` is a SQL keyword; quote it or name the column `sortOrder` and map it — decide, and say which in your report.

Index `noteId`. **No foreign key and no `ON DELETE CASCADE`** — see Task 7.

- [ ] **Step 1: Write the failing test** — insert, read back, `dirty` defaults to 1, soft delete hides it from the by-note query, and a sketch survives a store reopened over the same database.
- [ ] **Step 2: Run it and watch it fail**
- [ ] **Step 3: Implement** the entity, the DAO, `MIGRATION_9_10` (a plain `CREATE TABLE` — additive, nothing to backfill), the version bump and the `ALL_MIGRATIONS` entry.
- [ ] **Step 4: Run `:core-data:testDebugUnitTest` in full**, and confirm `core-data/schemas/.../10.json` was exported and is committed.
- [ ] **Step 5: Instrumented check.** Run `./gradlew :core-data:connectedDebugAndroidTest`. The hand-built migration chains were converted to spread `ALL_MIGRATIONS` in PR #101, so they should pick this up with no edit — **confirm that, and say so in your report.** If any test needed a manual edit, that conversion regressed and is a finding.
- [ ] **Step 6: Commit**

```bash
git add core-data
git commit -m "Give sketches a table"
```

---

### Task 6: Wiring sketches through the sync store

**Files:**
- Modify: `core-data/.../sync/RoomSyncStore.kt`, `core-data/.../sync/RecordRows.kt`
- Modify: `desktop/.../sync/RecordSyncStore.kt`
- Test: extend `core-data/src/test/java/my/cheysoff/core_data/RoomSyncStoreTest.kt`

`RoomSyncStore`'s `load`, `dirtyRecords`, `applyMerged`, `recordSeen` and `acknowledgePush` all branch on `RecordType`. Add the `SKETCH` branch to each, mirroring `FOLDER` (no conflict copies — only notes have bodies worth preserving that way).

The desktop stores records as sealed envelopes rather than typed rows, so `RecordSyncStore` needs no per-type branch for storage — but confirm its `open`/`put` path carries `createdAt` for sketches the way it does for notes.

- [ ] **Step 1: Write the failing test** — a dirty sketch appears in `dirtyRecords`; a merged remote sketch is written and readable; an acknowledged push clears `dirty` only if the row clock still matches.
- [ ] **Step 2: Run it and watch it fail**
- [ ] **Step 3: Implement**
- [ ] **Step 4: Run `:core-data:testDebugUnitTest` and `:desktop:test` in full**
- [ ] **Step 5: Commit**

---

### Task 7: Deletion is reconciled, never cascaded

**Files:**
- Modify: `core-data/.../RoomNotesRepository.kt` (or wherever note deletion lives)
- Test: `core-data/src/test/java/my/cheysoff/core_data/SketchDeletionTest.kt`

A note's deletion must tombstone its sketches as **separate records with their own tombstones**, not via `ON DELETE CASCADE`.

**Why this is not a preference.** A build without sketch support can delete a note without knowing sketches exist. A cascade would run on the deleting device and nowhere else, so the other device keeps sketches pointing at a deleted note — and pushes them back. The rule that both builds can honour is: *a sketch whose note is deleted is treated as deleted*, applied by any device that understands sketches when it observes the note's tombstone.

- [ ] **Step 1: Write the failing tests**

```kotlin
    @Test
    fun `deleting a note tombstones its sketches in the same transaction`() { /* ... */ }

    @Test
    fun `a sketch whose note is already deleted is treated as deleted on arrival`() { /* ... */ }

    @Test
    fun `a sketch whose note this device does not have yet is kept, not discarded`() {
        // Records arrive in seq order, not in dependency order. Discarding a sketch because its
        // note has not landed yet deletes a drawing over an ordering accident.
    }
```

Fill each body in fully when you write it — no placeholder bodies in the committed test.

- [ ] **Step 2-4: fail, implement, pass** (full `:core-data:testDebugUnitTest`)
- [ ] **Step 5: Commit**

---

### Task 8: A sketch crosses between two devices

The task that proves the plan. Extend the existing harness rather than building a new one.

**Files:**
- Modify: `core-data/src/test/java/my/cheysoff/core_data/TwoDeviceSyncTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
    @Test
    fun `a sketch drawn on one device arrives on the other`() { /* strokes identical byte-for-byte */ }

    @Test
    fun `editing a sketch does not make its note dirty`() {
        // The whole reason a sketch is its own record rather than a field on the note.
    }

    @Test
    fun `a sketch and its note's text merge without contending`() {
        // Text edited on one device, drawing edited on the other, both offline. Both survive.
    }

    @Test
    fun `deleting the note on one device removes its sketches on the other`() { /* ... */ }
```

- [ ] **Step 2-4: fail, implement, pass**
- [ ] **Step 5: Commit**

---

### Task 9: Full verification

- [ ] **Step 1:** `rm -rf */build/test-results && ./gradlew verify` — **foreground**, with the emulator attached.
- [ ] **Step 2:** Count honestly across every module; audit that each module with test sources reported. `server` is legitimately absent (separate build).
- [ ] **Step 3:** Confirm `RecordPayloadWireFormatTest` passes unmodified — a note's bytes must not have moved.
- [ ] **Step 4:** Confirm `SyncEngine.DATA_VERSION == 2` and that `MIGRATION_9_10` is in `ALL_MIGRATIONS`.
- [ ] **Step 5:** Commit, PR, merge.

**Do not install this on only one device and leave the other.** Both devices tolerate the unknown type now — that is what PR #101 bought — but the device left behind will skip every sketch until it is upgraded, and will then re-baseline once to collect them. That is the designed behaviour, not a bug; just know it will happen.
