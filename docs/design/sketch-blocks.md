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

**The cursor question.** Advancing past a record this device will never store means that if the
device is later upgraded, it will not re-pull the sketches it skipped — its cursor is already beyond
them. This is accepted rather than solved: the alternative is freezing, which is the failure being
removed. The recovery is the existing re-baseline path, and in practice both devices are upgraded
together. It is stated here so nobody rediscovers it as a bug.

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

SKETCH_FIELDS = { noteId, strokes, order, updatedAt, deleted }
```

| Field | Parts | Notes |
|---|---|---|
| `noteId` | 1 | The owning note's uuid. Clocked, so moving a sketch merges normally. |
| `strokes` | 1 | The drawing, encoded as below. |
| `order` | 1 | Integer position among the sketches of one note. Ties are broken by uuid, so two devices that independently pick the same position still agree on the resulting sequence rather than showing two different orders. |
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

- **In the note editor**, a sketch is a block: the drawing rendered to fit the note width, tapped to
  reopen full-screen.
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
- **Two-device**: a sketch drawn on one device arrives on the other and renders identically, over
  the existing `TwoDeviceSyncTest` harness.
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
