# Image attachments — design for issue #11

**Status: design only. Not scheduled, nothing implemented.**

The issue proposes copying picked images into app-internal storage and referencing
them from the note. That is the right instinct for keeping the database small, and
it is the wrong instinct for this particular app, because it quietly moves the
user's data outside the only thing that encrypts it.

This document works out what the file-based design actually costs, proposes a
cheaper alternative that is also *more* secure, and sizes both.

## The problem the issue does not mention

Notes live in a SQLCipher-encrypted database. That encryption is the app's central
promise — it is why there is a PIN, a PBKDF2 key wrap at 210,000 iterations, a
biometric key in the Android Keystore, and a lockout policy.

**SQLCipher encrypts the database file and nothing else.** An image written to
`filesDir/attachments/` is a plaintext JPEG sitting next to an encrypted database.
Every mechanism above still works perfectly and protects nothing about that image.

That is worse than not shipping the feature. A user who has set a PIN reasonably
believes their notes are encrypted at rest; a photo of a passport in a note would
not be. The failure is silent — nothing looks wrong.

Two concrete leaks follow immediately:

1. **Backup.** `AndroidManifest.xml` still sets `allowBackup="true"`, and
   `res/xml/data_extraction_rules.xml` excludes only `sharedpref/secret_shared_prefs.xml`
   and the four `notes.db*` files (PR #36). It excludes nothing under
   `domain="file"`. Attachment files would flow straight into Android Auto Backup
   and device-to-device transfer, while the notes referencing them would not.
2. **Sync.** `docs/design/e2e-sync-architecture.md` has no concept of a binary
   blob. Records are small versioned JSON payloads padded to 256-byte buckets and
   sealed in an in-memory `RecordEnvelope`. There is no blob endpoint in the
   server contract and no streaming construction. Attachments are not "a column
   the sync design will pick up later"; they are their own design problem.

## What encryption at rest for files would actually require

### A key, which means touching the unlock path

`SecureUnlockManager` holds a 32-byte SQLCipher passphrase, available only while
unlocked, exposed as `currentPassphrase()`. Today it has exactly one consumer:
`DataModule` building the SQLCipher open helper.

Attachments would need a second. The right shape is a derived subkey rather than
reuse of the passphrase itself —
`K_att = HKDF(passphrase, "manana/attachments/v1")`, mirroring the key hierarchy
already sketched in `core-crypto/sync/AccountKeys.kt` — so that the two
cryptosystems stay separable and the attachment key rotates with the passphrase.

That is a small change, but it is a change to `SecureUnlockManager`, the class that
can render every note unreadable. It is the last place to make a hurried change.

### A streaming file cipher, which does not exist here

Nothing in the repo can encrypt a file. The inventory:

- `PassphraseCipher` — PBKDF2 + AES-GCM over a byte array, sized for a 32-byte
  passphrase.
- `RecordEnvelope` (`core-crypto/sync/`) — AES-256-GCM, whole payload in memory,
  padded to 256-byte buckets. Built for small JSON records, has no production call
  sites, and would need every attachment held in memory twice to seal or open.
- `EncryptedSharedPreferences` — not a file API.

So one of:

- **`androidx.security.crypto.EncryptedFile`** — already on the classpath
  transitively. But it keys off an Android Keystore `MasterKey`, *not* off the DB
  passphrase, so attachments would be readable whenever the device is unlocked,
  even while the app is locked and the notes are not. That is a weaker guarantee
  for the more sensitive data, and it decouples attachment access from the PIN
  entirely. It is the quick option and it is the wrong one.
- **Chunked AES-GCM, hand-rolled** — 64 KiB chunks, a random per-file nonce
  prefix plus a chunk counter, a tag per chunk, a header carrying version and
  nonce, and the chunk index and a final-chunk flag in the AAD so a truncated or
  reordered file fails to open. This is correct and it is new
  security-critical code. Nonce reuse here is catastrophic and silent.

Either way this needs its own test suite: round-trip, wrong key, flipped
ciphertext bit, truncated file, reordered chunks, zero-length file.

### A decrypting image loader, with its caches disabled

richeditor-compose 1.1.0 does provide the seam: `RichSpanStyle.Image` carries a
`model`, and `LocalImageLoader` / `ImageLoader.load(model): ImageData` lets the app
resolve it. So `<img src="attachment://<uuid>">` in the stored HTML can be resolved
by a custom loader. That part is clean.

The trap is caching. Coil is not currently a dependency, and adding it brings a
**disk cache that is on by default** — it would write decoded copies of exactly the
images we just encrypted into `cacheDir`, in plaintext, outside the database and
outside the backup exclusions. It must be explicitly disabled, and it is precisely
the kind of default that gets silently restored by a later refactor. The memory
cache also needs clearing on `lock()`.

### And the rest

- **Backup rules**: `<exclude domain="file" path="attachments/"/>` in both
  `cloud-backup` and `device-transfer`, mirroring PR #36's reasoning.
- **Lifecycle**: Trash is a soft delete with a retention window, so files must
  outlive the note and be removed by `purgeExpiredTrash`, not by the delete.
- **Orphans**: an image inserted and then removed before save leaves a file with
  no reference. Needs a sweep, which needs a source of truth for "which files are
  referenced" — an argument for the attachments table over parsing HTML.
- **Previews**: the list shows text snippets. An image note wants a thumbnail,
  which is a second decrypt path and a second cache.

### Size

| | |
|---|---|
| `attachments` table, `MIGRATION_6_7`, schema JSON, migration test | 0.5 d (well-templated) |
| Key derivation + a new `SecureUnlockManager` accessor | 0.5 d |
| Streaming file cipher + its test suite | **2–3 d, security-critical** |
| Photo picker, downscale, write path | 1 d |
| Coil + decrypting `ImageLoader`, caches disabled | 1 d |
| Backup rules + verifying the exclusion actually holds | 0.5 d |
| Trash integration + orphan sweep | 1–1.5 d |
| List thumbnails | 1 d |
| **Total** | **~8–10 days**, plus an unwritten sync design |

That is a week and a half, and the riskiest days are the crypto ones. Per the
project's own test — if it is a week, write the design and do not build it — this
should not be built as specified.

## The alternative: put the bytes in the database

The issue rules out base64 in the content string, and it is right to: that bloats
every list query and every note load with data no preview needs.

But **a `BLOB` column in a separate `attachments` table is not that.** It is a
different row, in a different table, read only when an image is actually rendered.
And it collapses almost all of the work above:

```sql
CREATE TABLE attachments (
    id TEXT NOT NULL PRIMARY KEY,
    noteId TEXT NOT NULL,
    mimeType TEXT NOT NULL,
    width INTEGER NOT NULL,
    height INTEGER NOT NULL,
    byteCount INTEGER NOT NULL,
    createdAt INTEGER NOT NULL,
    bytes BLOB NOT NULL,
    FOREIGN KEY(noteId) REFERENCES notes(id) ON DELETE CASCADE
);
CREATE INDEX index_attachments_noteId ON attachments(noteId);
```

What this buys:

- **Encryption at rest for free, and correctly.** SQLCipher already encrypts the
  whole database file. No new cipher, no new key, no change to
  `SecureUnlockManager`, no second cryptosystem to keep in step with the first.
  Attachments become exactly as secure as notes, by construction rather than by
  vigilance.
- **Backup is already handled.** `notes.db*` is already excluded. Nothing new
  leaks, and no new rule can be forgotten.
- **Lifecycle is already handled.** `ON DELETE CASCADE` plus the existing purge.
  No orphan sweep, because there are no loose files to orphan — a deleted row
  takes its bytes with it.
- **Sync gets a real answer.** An attachment row is a record with a byte payload,
  which fits the existing envelope far better than a file does. Padding buckets
  still need revisiting for large payloads, but there is no separate blob store to
  design.

What it costs:

- **Database size.** The DB grows by roughly the total attachment bytes. For a
  personal notes app this is the intended tradeoff; SQLite handles multi-hundred-MB
  files without complaint.
- **The CursorWindow limit.** Android's `CursorWindow` is ~2 MB, and a row larger
  than it cannot be read through a normal cursor. The codebase has met this before
  — see `Migration4to5Test.aNoteLargerThanTheCursorWindowDoesNotAbortTheMigration`.
  The fix is a hard cap: downscale on import to a long edge of ~1600 px at JPEG
  q80, which lands typical photos at 200–600 KB, and reject anything still over
  ~1 MB after downscaling. Downscaling is wanted regardless — the issue asks about
  it as an open question.
- **Write amplification.** SQLite rewrites the row and the WAL on update. Fine for
  insert-once, read-many attachments.

### Size

| | |
|---|---|
| `attachments` table, `MIGRATION_6_7`, schema JSON, migration test | 0.5 d |
| Photo picker + downscale + size cap (pure, unit-testable) | 0.5 d |
| DAO, repository method, domain model | 0.5 d |
| Toolbar button, `LocalImageLoader` resolving `attachment://` from the DAO | 1 d |
| List thumbnails | 0.5 d |
| **Total** | **~3 days, no new crypto** |

## Recommendation

**Do not build the file-based design in the issue.** It is 8–10 days, its riskiest
component is new security-critical crypto, it needs a change to
`SecureUnlockManager`, and it silently breaks encryption at rest if any one of
several defaults is left wrong.

**Consider the BLOB-in-table design instead.** It is roughly three days, adds no
cryptographic code at all, inherits encryption and backup exclusion and cascade
deletion from machinery that already exists and is already tested, and gives sync
a tractable story. The price is database size and a size cap on imports, both of
which are acceptable for a personal notes app and one of which the issue already
wanted.

If attachments are eventually expected to be large — video, scanned documents,
originals rather than downscaled copies — then the file-based design becomes
necessary and the full 8–10 days is the honest price. That is the fact that would
change this recommendation, and it is worth deciding before either path is started.

## What would have to be true to revisit

- Attachments need to exceed ~1 MB each → files, and the full crypto bill.
- `SecureUnlockManager` is free to change (it is currently being worked on for the
  sync seam) → removes one blocker from the file design, but not the crypto.
- The sync design grows a blob store for its own reasons → shares the cost.
