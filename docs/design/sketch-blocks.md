# Sketch blocks — design

Hand-drawn scribbles inside a note: a child record carrying stroke geometry, drawn full-screen with
a finger, merged and synced like anything else.

This spec covers **forward compatibility** and **sketches**.

Photo attachments are deliberately not here. They already have a design —
[`image-attachments.md`](image-attachments.md) — whose recommendation (a `BLOB` in a table rather
than files, because a file would sit in plaintext beside the encrypted database) still stands. Two
parts of it predate the sync engine and are superseded by this document:

- It assumes `ON DELETE CASCADE` from notes to attachments. A cascade is invisible to the other
  device, which would resurrect the attachment on its next pull. Attachments need a tombstone per
  record, exactly as sketches do below.
- It could not know the envelope cap, which is now a real number: `maxEnvelopeBytes` is 256 KiB, and
  a 200–600 KB import does not fit. The cheap answer is to raise that config value (requests already
  allow 4 MiB) and keep a hard downscale cap, rather than to build chunking.

With those two corrections an attachment is the *same shape* as a sketch — a child record of a note
with one large field — so it needs no blob store, no upload route and no garbage collection. It
still deserves its own spec for the import, downscale and rendering path.

---

## Constraints this design answers to

| Fact | Where it comes from | Consequence |
|---|---|---|
| A sealed envelope caps at 256 KiB | `ServerConfig.maxEnvelopeBytes` | One sketch must stay far under this. It does: a scribble is single-digit KB. |
| An unknown `recType` halts the receiver | `RecordPayloadCodec.decode` → `RecordFault.UNREADABLE` | A new record type cannot be introduced safely today. See below. |
| Records are field-clocked and LWW-merged | `FieldClocks`, `Merge` | A sketch is one blob field, not per-stroke CRDT. |
| The server never deletes | `server/README.md` | Sketch deletion is a tombstone, as for notes and folders. |
| Only notes get conflict copies | `Merge`, `ConflictCopies` | A contested sketch resolves last-writer-wins. Mitigation below. |
| The app is KMP across JVM and Native | `core-domain`, `core-sync-engine` | The stroke encoding must be integer-only; float formatting is not identical across platforms. |

The user's stated use is **quick scribbles with a finger — seconds, not minutes**: circling a thing,
an arrow, a rough shape. Every UX decision below is scaled to that, and the features a drawing app
would normally have (layers, infinite canvas, zoom, pressure, shape tools) are absent on purpose.

---

## Step 1 — Forward compatibility, shipped before any sketch exists

### The hazard

A device receiving a record type it does not know produces
`PayloadResult.Malformed("unknown recType")`, which `EnvelopeSyncTransport` maps to
`RecordFault.UNREADABLE`. At `SyncEngine.kt:186` that does two things:

1. sets `frozen = true`, so the cursor **stops advancing at that record**; and
2. halts the engine with `RECORDS_UNREADABLE` once five have accumulated.

So the first sketches created on an upgraded device would freeze an un-upgraded one at the first of
them — no longer receiving even ordinary text notes — and then halt it permanently. It would present
exactly as "sync silently stopped".

This is not specific to sketches. It applies to attachments later, and to any new payload key, since
unknown keys are also `Malformed`.

### The change

Split "a record this build cannot read" from "a record this build was not written for".

- `RecordPayloadCodec.decode` gains `PayloadResult.UnknownType(wireKey)`, distinct from `Malformed`.
- `OpenResult.UnknownType` likewise, and `EnvelopeSyncTransport` maps it to a new
  `RecordFault.UNKNOWN_TYPE`.
- The engine treats `UNKNOWN_TYPE` as **skip and continue**: the cursor advances past it, it does
  not set `frozen`, it does not count toward `UNREADABLE_RECORD_LIMIT`, and it never halts. It is
  counted in `PassStats` as `ignored` so the number is visible rather than silent.

Malformed keeps its current meaning exactly: a record whose type this build *does* know but whose
shape is wrong is still a damaged record and still freezes.

**Why skipping is safe here and not for `UNREADABLE`.** An unreadable record is one this build
should have been able to open and could not, which is evidence the account key is wrong or the data
is corrupt — the stream of them is the early warning that `RECORDS_UNREADABLE` exists to catch. An
unknown type is the opposite: the record opened, authenticated, and parsed far enough to say what it
is. Nothing is wrong; this build simply has no use for it. Advancing past it loses nothing, because
a build that cannot represent the record could not have stored it anyway.

**The cursor question, and its answer.** Advancing past a record this device will never store means
its cursor ends up beyond records it did not keep. Upgrade the device later and it would never
re-pull them: the sketches it skipped would be permanently invisible to it, while being perfectly
present on the other device and on the server.

The fix is a **data version and a one-shot re-baseline**.

- `sync_state` gains `dataVersion`, the record-format generation this device last completed a pull
  under. It is a property of the build, bumped when a record type or payload shape is added — not a
  per-record field and not on the wire.
- On the first pass after a build whose `dataVersion` exceeds the stored one, the device resets its
  cursor to 0 exactly once, completes a full pull, then writes the new version. If the pass does not
  complete, the version is not written and the re-baseline is retried — it must be idempotent, and
  it is, because it is an ordinary pull.

**Why this does not trip the rollback guard, which is the part worth being careful about.**
`GET /v1/changes` serves the **head** version of each record and never history. So a pull from 0
returns, for every record, either the version this device already holds — in which case
`remote.rowClock` *equals* `local.rowClock`, the guard's test `remote.rowClock < local.rowClock` is
false, and the merge resolves to `NoChange` — or a newer one, which is an ordinary apply. Nothing
arrives older than what a clean row holds, because a clean row is by definition one the server has
acknowledged.

This must not be confused with the prohibition in `RejectReason.ROLLBACK_SUSPECTED`, which states
that a rollback "may not silently reset the cursor to 0". That is about resetting **in response to a
suspected rollback**, where the server is the thing that has stopped being trustworthy and a reset
would read an emptied account as "delete everything". Here the reset is client-initiated, the server
is known-good, and the replay is what was asked for. Same operation, opposite trust — and the code
that performs it should say which one it is at the call site.

**What is deliberately not done: ranking records by app version.** It is tempting to prefer records
written by a newer build. It would be wrong. Merge order is the HLC `(ms, counter, node)`, a total
order, and that totality is what makes the result convergent; a second ranking key that is not part
of that order would let a *newer* edit from an old build lose to an *older* edit from a new one, and
two devices applying it in different orders would stop converging. The hazard this would be reaching
for — an old build re-saving a newer record through a lossy parser — is already prevented, and more
strictly: an unknown payload version returns `UnsupportedVersion` and halts rather than
round-tripping the record.

### Rollout gate

**Both devices must be running this build before a single sketch record is created.** There is no
capability handshake in the protocol and this design does not add one — with two devices under one
person's control, installing before enabling is a smaller, more honest mechanism than negotiating
capabilities over the wire. Step 2 must not merge until step 1 is installed on both.

---

## Step 2 — Sketches

### The record

```
RecordType.SKETCH("sketch", FieldClocks.SKETCH_FIELDS)

SKETCH_FIELDS = { noteId, anchor, order, strokes, updatedAt, deleted }
```

| Field | Parts | Notes |
|---|---|---|
| `noteId` | 1 | The owning note's uuid. Clocked, so moving a sketch merges normally. |
| `strokes` | 1 | The drawing, encoded as below. |
| `anchor` | 1 | Where in the note's text the drawing sits — see below. |
| `order` | 1 | Position among sketches sharing one anchor. Ties are broken by uuid, so two devices that independently pick the same value still agree on the sequence. Included now rather than later because adding a field to a record's shape is a payload change and costs a whole format generation; one integer today is much cheaper than that. |
| `updatedAt` | 1 | As for notes. |
| `deleted` | 2 | `isDeleted`, `deletedAt` — the existing two-part shape. |

A sketch is a **child record**: its own row, its own clocks, its own dirty flag. Editing a drawing
does not make the note's text dirty, and two devices editing a note's prose and its sketch do not
contend at all — which is the whole reason for a separate record rather than a field on the note.

This is the first instance of a general pattern: *a block owned by a note, stored as its own
record*. `checklist` is a field on the note today and could later become `RecordType.CHECKLIST` with
the same shape, giving several checklists per note. This spec does **not** build a generic block
system — there is one block type and the abstraction would have exactly one implementation — but the
naming and the DAO shape should not make that later move awkward.

### Placement: the anchor lives on the sketch, never in the note's text

A drawing sits **between blocks of the note's text**, and the position is stored on the sketch
record as an index over the note's top-level blocks: `0` means before the first block, `k` means
after the `k`th. It is clamped to the note's current block count when rendered.

**Why not a marker in the HTML, which is the obvious way to do inline placement.** The note's
`content` would then carry something like `<img src="sketch://uuid">`. A build without sketch
support would load that HTML, fail to resolve the image, and — on the next text edit — re-serialize
through `toHtml()` without emitting it. The note would push back with the reference gone, the sketch
orphaned, and nothing anywhere reporting a problem. That is precisely the hazard
`SERIALIZER_VERSION`'s KDoc already names: *"a device that cannot render version N must refuse the
record rather than re-save it at version 1."* Honouring that rule would mean bumping the serializer
version for any note containing a drawing, which makes the **whole note**, text included, invisible
on a device that is behind.

Keeping the anchor on the sketch avoids both. The note's payload shape is unchanged, so a build
without sketch support sees the entire note, edits its text freely, and pushes it back safely — it
cannot damage a reference it never holds. What it can do is shift the blocks the anchor counts, so a
drawing may render a paragraph away from where it was placed. **Misplaced, never lost**, and only
while some device is behind; once both understand sketches, editing maintains the anchor and drift
stops.

Counting blocks must be one shared, deterministic function in `:core-domain` — both platforms must
agree, or the same note shows the drawing in two places. It must never throw: content it cannot
parse yields a count that still clamps to something sensible rather than losing the sketch. Its
exact definition (which tags count as blocks, how `PLAIN` content is divided) is settled in
implementation and pinned by golden tests, because a vague definition here is the one part of this
design that would rot silently.

### Stroke encoding

One string, versioned, integer-only, deterministic.

```
1|3277x4096|ff2c1ab0,24:0,0;120,40;250,90|ffdcdcdc,48:900,300;880,420
^ ^         ^        ^  ^
| |         |        |  points, delta-encoded from the previous point
| |         |        nib width in canvas units
| |         stroke colour, ARGB hex
| canvas size in canvas units (w x h)
version
```

- **Canvas units, not pixels.** The canvas is an integer grid whose **long edge is always 4096**;
  the short edge follows the aspect ratio of the screen it was drawn on and is recorded, so the
  example above is a portrait phone. A sketch therefore renders identically on a phone and a
  27-inch monitor, and the encoding does not depend on the device that drew it.
- **Integers only, everywhere.** Floats are not formatted identically across Kotlin/JVM and
  Kotlin/Native, and a re-encoding that differed by one character would make every sketch on the
  account dirty at once and stampede the server. This is the same failure the `richeditor` HTML
  round-trip already threatens; it is cheap to avoid here by never emitting a float.
- **Delta encoding** on points, which roughly halves the size of a typical stroke.
- **Byte-stability is a tested property**, not an aspiration: decode-then-encode must reproduce the
  input byte for byte, pinned by a golden fixture.

Capture stores a *simplified* polyline: raw touch points are reduced with Ramer–Douglas–Peucker at a
small epsilon before encoding. Rendering re-smooths with quadratic Bézier segments. This keeps the
stored form small and the drawn form smooth, and it means the smoothing algorithm can improve later
without rewriting stored data.

Version `1` carries no pressure. The user draws with a finger, so there is none to record; the
version marker exists so a later build can add it without ambiguity.

### Size

A dense scribble is on the order of 30 strokes × 40 points ≈ 8 KB encoded. The 256 KiB envelope cap
is ~30× that. A guard refuses a stroke set beyond a stated limit at capture time rather than
discovering it at push time, where the failure is a `413` the user cannot act on.

### Drawing UX (Android)

A full-screen canvas, opened from the note editor and returning to it. Everything on one bar:

- **Colour** — six swatches from the app's existing accent palette, no picker.
- **Nib** — three sizes.
- **Eraser** — *stroke-level*: touching a stroke removes that whole stroke. Pixel erasing with a
  fingertip on a phone is imprecise and slow, and for a scribble the unit a person means to remove
  is almost always the whole stroke.
- **Undo / redo** — unlimited within the session.
- **Done / Cancel** — cancel discards, with a confirm only if strokes exist.

No zoom, no pan, no layers, no shape tools. The canvas is exactly the screen. This is a decision to
revisit only if the user's actual use turns out to be handwriting rather than scribbling.

### Rendering elsewhere

- **In the note editor**, a sketch is a block between paragraphs at its anchor, rendered to fit the
  note width and tapped to reopen full-screen.
- **On the desktop**, sketches render and can be deleted and reordered, but **not drawn**. Drawing
  with a mouse is a poor experience and building it is not free; phone-first is the honest scope.
  The renderer is shared code, so the desktop gets display for very little.
- **In note cards** on the home screen, the first sketch may render as a thumbnail. Deferred — it
  interacts with the existing card layout rules and is not needed to use the feature.

### Merge

`strokes` is one clocked field merged last-writer-wins, like `checklist`. Two devices drawing on the
*same* sketch while both offline means one stroke set wins and the other is not shown.

**No conflict copy.** Conflict copies exist for note bodies, where the losing side is prose a person
wrote and cannot reconstruct. The machinery is real work — a derived identity so both devices name
the same copy, plus the copy's own lifecycle — and this design does not extend it to sketches.

The residual risk is mitigated rather than ignored: because a sketch is its own record, the losing
version is a previous version of that record, and the server already exposes
`GET /v1/records/{id}/history`. A drawing lost this way is recoverable, which is a different thing
from being gone. If concurrent sketch editing turns out to happen in practice, conflict copies are
the fix and the field is already shaped for it.

### Local storage

A new Room table `sketches`, mirroring the `notes` sync columns:

```
sketches(uuid PK, noteId, strokes, order, createdAt, updatedAt,
         isDeleted, deletedAt,
         hlcMs, hlcCounter, hlcNode, fieldHlc, dirty, lastSyncedSeq)
```

`dirty` defaults to `1`, for the reason `MIGRATION_6_7` documents: `0` would declare the library
already uploaded and let the first pull erase it. A schema migration adds the table; it is additive,
so it follows the existing pattern.

Deleting a note soft-deletes its sketches in the same transaction. They are separate records with
separate tombstones, so this is a real write per sketch rather than a cascade the sync layer cannot
see — a cascade the other device could not observe would resurrect them.

On the desktop the same data lives in the existing record store; no new table, since that store is
already record-shaped.

---

## Error handling

| Situation | Response |
|---|---|
| Stroke data that will not decode | The block renders as a placeholder naming the problem; the record is left untouched. Never silently dropped, never rewritten — rewriting a record this build misread is how one bad parse propagates to every device. |
| A sketch whose `noteId` names a note this device does not have | Kept, not shown. The note may arrive later in the same pass or a later one; discarding it would delete a drawing because two records arrived out of order. |
| A sketch whose note has been deleted | Treated as deleted, and tombstoned by the first device that observes the note's tombstone and understands sketches. **Not** a cascade: a build without sketch support can delete a note without knowing its sketches exist, so a local cascade would look right on one device and leave orphans on the other. Reconciliation, not cascade, is the only rule both can honour. |
| An anchor past the end of the text | Clamped to the last block. A drawing at a stale position is a drawing in the wrong place; a drawing dropped for an out-of-range integer is data loss. |
| Stroke set over the size guard | Refused at capture with a message, before it can reach a push. |
| A sketch record on a build without sketch support | Skipped and counted, per step 1. |

---

## Testing

Pure and JVM-testable, which is most of it:

- **Encoding**: round-trip byte-stability against a golden fixture; integer-only output; delta
  encoding correctness; refusal of malformed input at every position.
- **Simplification**: deterministic for a given input, and identical on JVM and Native (the
  `mingwX64` canary already enforces `commonMain` purity, so this is a test in `commonTest`).
- **Merge**: a sketch edit does not dirty its note; a note edit does not dirty its sketches;
  concurrent sketch edits converge to one winner on both devices.
- **Forward compatibility**: an unknown record type advances the cursor, does not freeze, does not
  count toward the unreadable limit, and does not halt — and a *malformed* record of a known type
  still does all four. Both directions matter; only testing the new one would let a future change
  quietly make every damaged record skippable.
- **Re-baseline**: a device whose `dataVersion` is behind resets its cursor once, re-pulls, and
  recovers records it previously skipped; a re-pull of records it already holds produces `NoChange`
  and no rollback rejection; the version is written only on a completed pass, so an interrupted
  re-baseline runs again; and a device already at the current version does not re-baseline.
- **Two-device**: a sketch drawn on one device arrives on the other and renders identically, over
  the existing `TwoDeviceSyncTest` harness.
- **Anchoring**: the block count is identical on both platforms for a corpus of golden bodies;
  an anchor beyond the current block count clamps instead of vanishing; editing text above a sketch
  moves its anchor; and a note edited by a build with no sketch support still round-trips
  byte-identically, since nothing about the sketch is in its content.
- **Deletion**: deleting a note tombstones its sketches, and they do not resurrect on the next pull.

Rendering is tested at the geometry level — the path a stroke set produces — not by comparing
pixels.

---

## What is deliberately not in this design

- Photo attachments — a separate spec, with the chunked blob protocol, quota and GC that sketches do
  not need.
- Drawing on the desktop.
- Zoom, pan, layers, shape tools, pressure, palm rejection.
- Sketch thumbnails on home-screen note cards.
- A generic block system. One block type does not justify the abstraction; the shape is chosen so
  that turning `checklist` into a second one later is a small change rather than a rewrite.
