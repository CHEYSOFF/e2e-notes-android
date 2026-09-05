# Image attachments — design

**Status: decided. Supersedes the 2026-08 recommendation-only version of this file
(see git history), which was written before the sync engine existed.**

The 2026-08 analysis asked one question — files on disk versus bytes in the database —
and answered it correctly. That answer stands and §1 keeps it. Everything after §1 is
new: sync is real now, sketches shipped a child-record pattern this feature should
mirror, and two of the old document's load-bearing claims turned out to be wrong.

---

## 1. Why the bytes go in the database (unchanged)

Notes live in a SQLCipher-encrypted database. **SQLCipher encrypts the database file and
nothing else.** An image written to `filesDir/attachments/` is a plaintext JPEG sitting
next to an encrypted database — the PIN, the PBKDF2 wrap at 210,000 iterations, the
Keystore biometric key and the lockout policy all still work perfectly and protect
nothing about that image. A user who set a PIN reasonably believes their notes are
encrypted at rest; a photo of a passport in a note would not be, and nothing would look
wrong.

The file design also costs a key derivation inside `SecureUnlockManager`, a hand-rolled
chunked-AEAD file cipher (new, security-critical, silent on nonce reuse), a decrypting
image loader with its disk cache explicitly disabled, new backup-exclusion rules, and an
orphan sweep. Roughly 8–10 days, and the riskiest days are the crypto ones.

A `BLOB` column in a separate `attachments` table is not "base64 in the content string".
It is a different row in a different table, read only when an image is actually rendered.
It inherits encryption at rest by construction, inherits the `notes.db*` backup exclusion
PR #36 already wrote, and needs no new cryptographic code at all.

**Decision: BLOB in an `attachments` table.** This has not changed.

### Two claims from that document that were wrong

- **"Lifecycle is already handled — `ON DELETE CASCADE` plus the existing purge."**
  A SQL cascade is invisible to the other device. Device A deletes a note, the cascade
  silently drops its attachment rows, A pushes the note tombstone and nothing else — and
  B, which never heard that the attachments died, pushes them back on its next pass. The
  attachment reappears attached to a deleted note and is unreachable forever. Deletion
  must be a **tombstone cascade**, with each attachment's own HLC minted through the
  shared generator. This is the exact bug the sketch work hit; §6 restates the fix.

- **"Sync gets a real answer — an attachment row fits the existing envelope."**
  It does not. `ServerConfig.maxEnvelopeBytes` is 256 KiB, and a 200–600 KB import
  base64s to 270–800 KB. §4 is about that number and it is the largest single decision
  in this document.

---

## 2. Shape: an attachment is a child record, like a sketch

Sketches (PR #102/#103) established the pattern and attachments are structurally the
same thing: a record that belongs to a note, carries an anchor and an order, is
tombstoned when its note is, and renders in a rail below the note body.

Attachments therefore **mirror `RecordType.SKETCH` field for field** rather than
inventing a shape. Same anchor semantics, same ordering helper, same tombstone cascade,
same "rendered below the body, not interleaved" placement (§8).

**Ruling: do not generalise sketches and attachments into one "note child record" type
now.** The similarity is obvious and a shared abstraction is probably right eventually —
the user has also asked for checklists to become records, which would be the third. But
generalising today means rewriting a record type that shipped and was reviewed this week,
on a live sync protocol, with two devices already holding data in it. The cost of waiting
is one duplicated pattern; the cost of being wrong is a protocol break. The duplication is
recorded in §11 as the trigger for doing it once checklists arrive.

---

## 3. Import: what actually gets stored

Never the original. Every import is decoded, downscaled, re-encoded, and capped.

```
long edge      <= 1600 px
format         JPEG, always
alpha          flattened onto white
hard cap       1 MiB (1,048,576 bytes) of encoded image
thumbnail      long edge <= 320 px, JPEG q70, cap 64 KiB
```

**The quality ladder.** Encode at q85. Over the cap? q75, then q65, then q55. Still over?
Drop the long-edge cap by 30% (1600 to 1120 to 784) and run the ladder again. Reject after
the third dimension step. A 12 MP phone photo lands at 1600/q85 in the 400–700 KB range,
so the ladder's later rungs exist for pathological inputs (noisy, high-detail, already
recompressed) rather than for ordinary photos.

The ladder's *decisions* are pure and unit-testable — given a source size and an encoded
byte count, what is the next step — and live in `commonMain`. The encode itself is
platform code. Splitting it this way is what makes the cap testable without a bitmap.

**Ruling: JPEG always, alpha flattened to white.** Screenshots and PNG logos lose
transparency. The alternative is carrying a second format through the ladder (PNG has no
quality knob, so its ladder is dimensions-only), a second decode path, and a `mimeType`
that actually varies. For a notes app the cost is a white box behind a transparent logo;
the benefit is one format everywhere. `mimeType` is still stored, so a second format later
is additive rather than a migration.

**No original-resolution copy.** If that is ever wanted, it is the file-based design in
§1 and the full crypto bill; it is not a column that can be added later without one.

---

## 4. The envelope cap, and a bug that already exists

A 1 MiB image base64s to 1,398,102 bytes. `maxEnvelopeBytes` is 256 KiB. Something has
to give, and there are only two candidates.

**Chunking** — split the image across N records of at most 192 KiB so every envelope fits
the existing cap — needs no server change at all. It costs a reassembly protocol, a
partial-arrival UI state, per-chunk tombstones, and a new class of bug where an attachment
exists but is missing chunk 3 forever.

**Raising the cap** costs a config change and a redeploy of the user's own VPS, plus two
fixes to byte budgets. Those two fixes are the deciding argument, because **they are
already bugs today**:

| | today | |
|---|---|---|
| push batch | 64 items x 256 KiB = **16 MiB** | `maxRequestBytes` is 4 MiB, so `413` |
| pull page | 32 records x 256 KiB x 4/3 = **11 MiB** | client cap is 16 MiB — no server-side byte budget at all |

Neither fires today because notes are small. Both are real, both are latent, and both must
be fixed for images regardless of which option is chosen. Chunking hides them again behind
a smaller record; raising the cap forces them into the open and fixes them.

**Decision: one record per attachment, raise the caps, fix the byte budgets.**

### The numbers, and why each holds

| knob | from | to | where |
|---|---|---|---|
| `MAX_ATTACHMENT_BYTES` | — | 1 MiB | client, `commonMain` |
| `maxEnvelopeBytes` | 256 KiB | **2 MiB** | server, `MANANA_MAX_ENVELOPE_BYTES` |
| `maxRequestBytes` | 4 MiB | **8 MiB** | server, `MANANA_MAX_REQUEST_BYTES` |
| `maxChangesBytes` | *(none)* | **8 MiB** | server, new |
| push byte budget | *(none)* | **3 MiB** | `SyncEngine` |

- **Push.** 3 MiB of envelopes becomes 4 MiB base64 becomes ~4.1 MiB of JSON, against an
  8 MiB request cap. The batch is cut at whichever of 3 MiB or 64 items comes first.
- **Pull.** 8 MiB of envelopes becomes ~10.7 MiB base64, under the client's existing 16 MiB
  `DEFAULT_MAX_RESPONSE_BYTES`, which therefore does not change. The server stops filling a
  page once the budget is hit and **always returns at least one record**, or a single
  oversized record would stall the cursor forever. The client already pages by cursor until
  a page comes back empty, so a short page is ordinary and needs no client change.

### The deploy is not part of this work

The server config change is committed but **not deployed** — VPS work waits for the user's
return. Until it is deployed, an attachment envelope over 256 KiB is rejected by the live
server. That must be survivable, so:

**Requirement: a per-item `envelope_too_large` rejection must not halt the pass.** The row
stays dirty, the item is counted, the pass continues, and the attachment syncs on the first
pass after the redeploy. Attachments work locally on each device in the meantime. This is
correct behaviour whatever the server is running, and it is the one thing that makes
shipping the client ahead of the server safe.

### What the server operator still learns

Padding is 4 KiB buckets, so an attachment's size is revealed to within 4 KiB — the server
learns roughly how large each photo is, and that a given record is photo-sized rather than
note-sized. That is a real leak, it is inherent to storing an image as a record, and it is
stated here rather than hidden.

---

## 5. Schema

```sql
CREATE TABLE attachments (
    uuid          TEXT    NOT NULL PRIMARY KEY,
    noteId        TEXT    NOT NULL,
    anchor        INTEGER NOT NULL,
    sortOrder     INTEGER NOT NULL,
    mimeType      TEXT    NOT NULL,
    width         INTEGER NOT NULL,
    height        INTEGER NOT NULL,
    bytes         BLOB    NOT NULL,
    thumbWidth    INTEGER NOT NULL,
    thumbHeight   INTEGER NOT NULL,
    thumbBytes    BLOB    NOT NULL,
    createdAt     INTEGER NOT NULL,
    updatedAt     INTEGER NOT NULL,
    isDeleted     INTEGER NOT NULL DEFAULT 0,
    deletedAt     INTEGER,
    hlcMs         INTEGER NOT NULL,
    hlcCounter    INTEGER NOT NULL,
    hlcNode       TEXT    NOT NULL,
    fieldHlc      TEXT,
    dirty         INTEGER NOT NULL DEFAULT 1,
    lastSyncedSeq INTEGER
);
CREATE INDEX index_attachments_noteId ON attachments(noteId);
```

Following the sketch table exactly: `uuid` as the primary key and `sortOrder` rather than `order` (a SQL keyword), both matching `sketches`, **no
foreign key and no cascade** (§6), and `dirty DEFAULT 1` pinned in all three places — the
entity, the migration SQL, and the exported schema JSON — because a row that defaults to
clean is a row that never syncs, and nothing fails loudly when it happens.

`MIGRATION_10_11`. No `byteCount` column: `length(bytes)` answers it, and a stored count is
one more thing that can disagree with the bytes.

**The thumbnail is not an optimisation, it is the CursorWindow fix.** Android's
`CursorWindow` is ~2 MB and a row larger than it cannot be read through a normal cursor;
the codebase has already met this in
`Migration4to5Test.aNoteLargerThanTheCursorWindowDoesNotAbortTheMigration`. A 1 MiB blob
plus overhead sits under it, but only just, and only for one row at a time. **No query that
returns more than one row may select `bytes`.** The rail, the note list and every preview
read `thumbBytes` (at most 64 KiB); `bytes` is selected by exactly one DAO method, by id,
for the full-screen viewer. That rule is what keeps the cap safe rather than lucky.

---

## 6. Sync

`RecordType.ATTACHMENT`, `wireKey = "attachment"`, mirroring `SKETCH`.

```
ATTACHMENT_FIELDS = { noteId, anchor, order, image, thumb, updatedAt, deleted }

image   -> 4 parts: base64url(bytes), mimeType, width, height
thumb   -> 3 parts: base64url(thumbBytes), thumbWidth, thumbHeight
deleted -> 2 parts: isDeleted, deletedAt      (existing)
```

Bytes and their dimensions are **one `FieldValue`**, for the same reason `content` and
`contentFormat` are: the merge takes a field from one side or the other, so packing them
together makes it physically impossible to end up with one device's pixels described by
another device's dimensions. `RecordType.partCount` gains branches for `IMAGE` and `THUMB`.

Base64 is `Base64Url` from `core-crypto-shared/commonMain` — unpadded RFC 4648 §5, already
the project's canonical encoder, already tested. No new dependency.

No conflict copy: like a sketch, `image` is a plain LWW field, not a text two people typed
into concurrently.

**`DATA_VERSION` 2 to 3.** A device on the older build sees `recType = "attachment"`,
cannot map it, and — thanks to PR #101 — skips it as `RecordFault.UNKNOWN_TYPE`, counts it
as `ignored`, and does *not* freeze or halt. Bumping the generation is what makes it pull
from cursor 0 once after upgrading and pick up every attachment it skipped. This is
precisely the mechanism PR #101 was built for; not bumping it would leave those attachments
invisible forever on the upgraded device.

**Deletion is a tombstone cascade, never a SQL cascade.** `deleteNote` tombstones the note's
live attachments using the *note's* `deletedAt`, each with its own HLC minted through
`syncClock.observe()` — never minted locally, or a later `restoreNote` can mint below the
tombstone and the restore looks fine on this device while the other device keeps the image
deleted. `restoreNote` restores those whose `deletedAt` is at or after the note's.
`purgeNote` hard-deletes, scoped to that note's id. All three mirror the sketch
implementations, which have tests proving another note's rows survive.

---

## 7. Import path (Android)

`ActivityResultContracts.PickVisualMedia` — the photo picker needs **no runtime permission
and no manifest permission**. The app's permission count does not change. This is worth
protecting: `READ_MEDIA_IMAGES` would be a visible regression in an app whose premise is
talking to nobody.

Decode, downscale, ladder, thumbnail, insert — off the main thread, with a progress
indication and a failure message that says which of "too large" or "not an image" it was.
EXIF orientation is applied during decode; a sideways photo is the most common import bug
there is.

**The blank-note guard.** A brand-new note containing only an attachment must not be
discarded on back-out. Both halves of the sketch fix apply: the guard must count
attachments, *and* the pending save job must be joined before the purge, or the purge races
the insert.

---

## 8. Placement and UI

**Attachments render in a rail below the note body, alongside sketches — not interleaved
with the text.** The reasoning is the sketch amendment's, unchanged: the body is a single
`BasicRichTextEditor`, so interleaving needs either a marker in the serialised HTML (an
attachment-unaware build re-serialises it away and orphans the image) or several editors
(which breaks cursor movement, undo history and saving). The `anchor` is still recorded, so
inline placement stays a later view-only change with no migration.

- **Rail:** thumbnails, ordered by `sortOrder` then `createdAt`, sharing the ordering
  helper's shape with `SketchOrdering`.
- **Tap:** full-screen viewer, `ContentScale.Fit`, pinch-zoom and pan via
  `detectTransformGestures`. An image viewer without zoom is the wrong feature.
- **Delete:** from the viewer, confirmed. Attachments are **not** in Trash —
  `TrashEntryKind` is `{NOTE, FOLDER}` — which is the same known gap sketches have,
  recorded in §11 rather than fixed here.
- **Desktop: render only.** The rail and the viewer work; there is no import. This mirrors
  the sketch decision and keeps one platform's image-encoding stack out of this scope.

Gesture note, learned the expensive way on the sketch canvas: the `onDragStart` of
`detectDragGestures` only fires past touch slop, so a tap target inside a gesture-handling
surface needs `detectTapGestures`, not a drag handler.

---

## 9. What is deliberately not here

Video and audio. PDFs and arbitrary files. Original-resolution storage. Inline placement in
the text flow. Desktop import. Attachments in Trash. Multi-select import. Editing or
cropping an imported image. Sharing an attachment out to another app.

Each is additive. None of them changes the schema or the record shape decided above, except
original-resolution storage, which is the file-based design in §1.

---

## 10. Verification

- The ladder is pure and gets a unit test per rung, including the reject case.
- The migration gets the instrumented test the other nine have, spreading `ALL_MIGRATIONS`
  rather than hand-building a list — the hand-built lists broke on the schema-9 bump and
  were converted for this reason.
- Tombstone cascade, restore and purge each get a test proving another note's attachments
  are untouched.
- Byte-budget batching gets a test that a batch of large envelopes is split by bytes, not by
  count.
- The server's changes budget gets a test that a single oversized record is still returned
  rather than stalling the cursor.
- `RecordPayloadWireFormatTest` must be untouched by every commit: the existing wire format
  does not change, only what is carried in it.

---

## 11. Recorded follow-ups

- **Generalise sketches, attachments and checklists into one note-child-record type.**
  Trigger: checklists becoming records, which the user has already asked for. Doing it then
  means one migration for three types instead of two migrations.
- **Neither sketches nor attachments are in Trash.** Delete confirms and is then final. The
  real fix is a new `TrashEntryKind`, a restore path, purge integration, and a list UI that
  can render a drawing or a thumbnail.
- **Deploy the server config change** (`MANANA_MAX_ENVELOPE_BYTES=2097152`,
  `MANANA_MAX_REQUEST_BYTES=8388608`) — until then attachments sync no further than the
  device that made them.
- **`TOMBSTONE_FIELDS` is duplicated** across record types; a third type makes it a third
  copy.
