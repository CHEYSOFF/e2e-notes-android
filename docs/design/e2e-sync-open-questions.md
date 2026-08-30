# E2E sync — closing the open questions

> Status: **research complete.** Written 2026-08-31 against `master` @ `3a2d157`.
> Companion to `e2e-sync-architecture.md`, which is the accepted design. This document answers
> the questions that document left open, and the corrections it produced have been applied to
> `e2e-sync-architecture.md` in place — the two are not meant to disagree anywhere.
>
> Everything below was checked. Where something could not be checked here, it says so and says
> what would settle it. Nothing is asserted from recollection.

---

## 1. JCA `"XDH"` / X25519 — what is the real minimum API?

**Question.** `e2e-sync-architecture.md` chose P-256 over X25519 on the belief that JCA `"XDH"`
"arrived around API 33, *above* minSdk 31", and flagged it "verify before relying on". It made the
same claim about Keystore Ed25519. Both drive Phase 2's key agreement and device-identity key, so
both are blocking.

**Method.** Two independent local sources, no network, no recollection:

1. `C:/Users/slobb/AppData/Local/Android/Sdk/platforms/android-37.0/data/api-versions.xml` —
   Google's generated record of the API level at which each class entered the public SDK.
2. The `android.jar` class lists for the installed platforms, which are the compile classpath
   itself: `unzip -l platforms/android-32/android.jar` vs `platforms/android-34/android.jar`.

**Evidence.**

From `api-versions.xml`:

| Class | `since` |
|---|---|
| `java/security/spec/NamedParameterSpec` (fields `X25519`, `X448`, `ED25519`, `ED448`) | **33** |
| `java/security/interfaces/XECKey`, `XECPublicKey`, `XECPrivateKey` | **33** |
| `java/security/spec/XECPublicKeySpec`, `XECPrivateKeySpec` | **33** |
| `java/security/interfaces/EdECKey`, `EdECPublicKey`, `EdECPrivateKey` | **33** |
| `java/security/spec/EdECPoint`, `EdECPublicKeySpec`, `EdECPrivateKeySpec` | **33** |

The platform jars agree exactly. Grepping `android-32/android.jar` for `XEC`, `EdEC` and
`NamedParameterSpec` returns **no such classes** (only substring noise — `ImageDecoder`,
`ThreadPoolExecutor`). The same grep on `android-34/android.jar` returns **all eleven**:

```
java/security/spec/NamedParameterSpec.class
java/security/spec/XECPublicKeySpec.class      java/security/spec/XECPrivateKeySpec.class
java/security/spec/EdECPoint.class
java/security/spec/EdECPrivateKeySpec.class    java/security/spec/EdECPublicKeySpec.class
java/security/interfaces/XECKey.class
java/security/interfaces/XECPublicKey.class    java/security/interfaces/XECPrivateKey.class
java/security/interfaces/EdECKey.class
java/security/interfaces/EdECPublicKey.class   java/security/interfaces/EdECPrivateKey.class
```

So the classes genuinely appear between API 32 and API 34, consistent with `since="33"`.

The app's floor is `minSdk = 31` (`app/build.gradle.kts:20`). Note that every *library* module
declares `minSdk = 24` (`core-crypto/build.gradle.kts:17` and six siblings); the merged APK floor
is 31, but code written in `core-crypto` — which is where the architecture doc puts HKDF and the
crypto core — compiles against a declared floor of **24**, so lint there is stricter still.

**What this does and does not prove.** It proves the *SDK* position beyond argument: below API 33
you cannot name `NamedParameterSpec.X25519`, cannot declare an `XECPublicKey`, and cannot build an
`XECPublicKeySpec`. It does **not** prove that no security provider registers the string `"XDH"`
at runtime on an API 31 device — that is a separate, unresolved question. It could not be settled
here: only the `android-33` system image is installed, and this SDK has no `cmdline-tools`, so
`sdkmanager` is unavailable to fetch an API-31 image. **What would settle it:** an API 31 or 32
emulator (or a physical Android 12 device) running
`Security.getProviders().flatMap { it.services }.filter { it.type == "KeyPairGenerator" }`.

It does not need settling, because it cannot change the answer: even if the provider registered
`"XDH"` at API 31, you could only reach it by reflection, and no one should hand-roll an X25519
key-agreement path through reflection to save nothing.

**Recommendation — the P-256 choice stands. Use it, unconditionally, with no version check.**

- **ECDH:** `KeyPairGenerator.getInstance("EC")` with `ECGenParameterSpec("secp256r1")`, and
  `KeyAgreement.getInstance("ECDH")`. Platform since API 23, zero dependencies, one code path.
- **Device identity:** EC P-256 in `AndroidKeyStore` with `SHA256withECDSA`. Keystore Ed25519 is
  unreachable for the same reason — `KeyGenParameterSpec` would need `NamedParameterSpec.ED25519`
  passed as its algorithm parameter spec, and that class is API 33.
- Choosing X25519 would mean shipping **both** curves (an API-33 branch plus a P-256 fallback for
  API 31–32), i.e. strictly more code, more review surface and two protocol variants to keep
  compatible across a device pair, in exchange for no security gain — both are ≈128-bit.

**The one thing P-256 costs, which must be paid explicitly.** X25519 is safe against invalid-curve
attacks by construction; P-256 is not. When decoding the peer's public key out of the QR payload,
**validate the point**: check it is not the identity, that its coordinates are in `[0, p)`, and
that it satisfies the curve equation `y² ≡ x³ − 3x + b (mod p)` — do not assume
`KeyFactory.generatePublic(X509EncodedKeySpec(…))` rejects an off-curve point. This is ~15 lines
against `ECNamedCurveSpec`/`EllipticCurve` and it is not optional: a device that performs ECDH
against an attacker-chosen invalid point can leak its private scalar over repeated attempts.
The architecture doc already flags this at line 128; it is restated here because it is the whole
of P-256's downside.

---

## 2. Is `richeditor-compose`'s HTML round trip stable?

**Question.** The architecture doc calls this "the single largest sync risk": if
`setHtml(x) → toHtml()` does not reproduce `x`, opening a note rewrites its stored bytes; and if a
*library upgrade* changes the serialisation, every note on every device goes dirty at once and
stampedes the server.

**Method.** An instrumented test, because the library needs a real runtime. Added as
`feature-notes/src/androidTest/java/my/cheysoff/feature_notes/RichEditorHtmlRoundTripTest.kt`
(kept — see "Disposition" below). Built with
`./gradlew :feature-notes:assembleDebugAndroidTest` and run with
`adb shell am instrument -w -r -e class … androidx.test.runner.AndroidJUnitRunner` against
`emulator-5554` (API 33, x86_64). `connectedAndroidTest` was not needed; the direct
`am instrument` path worked first time and is faster to iterate.

The harness mirrors what the app actually does: `SingleNoteScreen.kt:250` sets
`richTextState.config.listIndent = 18` **before** `setHtml`, and that value is serialised back out,
so a round trip performed without it is not the round trip the app performs.

Twenty-eight representative bodies were run: plain paragraphs, `<b>`/`<strong>`, `<i>`/`<em>`,
`<u>`, `h1`–`h3`, ordered and unordered lists, nested and triply-nested formatting, Cyrillic,
mixed scripts with CJK and emoji, escaped and raw entities, `<br>`, `<span style>`, a 200-word
paragraph, and whitespace edge cases.

### Result A — under the pinned version, `1.0.0-rc14`

**Idempotent after one pass in 28 of 28 cases. Byte-identical on the first pass in only 16 of 28.**
Raw output, verbatim from logcat (`|` delimiters are the test's, not part of the strings):

```
CASE empty
  IN   ||
  OUT1 |<p></p>|                              identical=false idempotent=true
CASE two-paragraphs
  IN   |<p>First.</p><p>Second.</p>|
  OUT1 |<p>First&period;</p><p>Second&period;</p>|   identical=false idempotent=true
CASE bold-strong
  IN   |<p><strong>bold</strong></p>|
  OUT1 |<p><b>bold</b></p>|                   identical=false idempotent=true
CASE italic-em
  IN   |<p><em>italic</em></p>|
  OUT1 |<p><i>italic</i></p>|                 identical=false idempotent=true
CASE cyrillic
  IN   |<p>Привет, мир — это заметка.</p>|
  OUT1 |<p>&Pcy;&rcy;&icy;&vcy;&iecy;&tcy;&comma; &mcy;&icy;&rcy; &mdash; &ecy;&tcy;&ocy; &zcy;&acy;&mcy;&iecy;&tcy;&kcy;&acy;&period;</p>|
                                              identical=false idempotent=true
CASE cyrillic-bold
  IN   |<p><b>Заголовок</b> и обычный текст</p>|
  OUT1 |<p><b>&Zcy;&acy;&gcy;&ocy;&lcy;&ocy;&vcy;&ocy;&kcy;</b> &icy; &ocy;&bcy;&ycy;&chcy;&ncy;&ycy;&jcy; &tcy;&iecy;&kcy;&scy;&tcy;</p>|
                                              identical=false idempotent=true
CASE mixed-scripts
  IN   |<p>ASCII, Кириллица, 日本語, emoji 🚀</p>|
  OUT1 |<p>ASCII&comma; &Kcy;&icy;&rcy;&icy;&lcy;&lcy;&icy;&tscy;&acy;&comma; 日本語&comma; emoji 🚀</p>|
                                              identical=false idempotent=true
CASE span-style
  IN   |<p><span style="font-weight: bold;">styled</span></p>|
  OUT1 |<p><b>styled</b></p>|                 identical=false idempotent=true
CASE whitespace-runs
  IN   |<p>a    b\tc</p>|
  OUT1 |<p>a b c</p>|                         identical=false idempotent=true
CASE leading-trailing-space
  IN   |<p> padded </p>|
  OUT1 |<p>padded </p>|                       identical=false idempotent=true
```

Byte-identical on the first pass: `plain-paragraph`, `bold-b`, `italic-i`, `underline`, `h1`, `h2`,
`h3`, `unordered-list`, `ordered-list`, `list-then-paragraph`, `nested-formatting`,
`triple-nested`, `entities-escaped` (`a &lt; b &amp; c &gt; d`), `entities-raw-ampersand`,
`quote-entities` (`&quot;` survives), `line-break` (`<br>` stays unclosed), `long-paragraph`.

**Reading of Result A.** Byte-identity on hand-written HTML is *not* the property sync needs, and
its absence here is harmless: the app never stores hand-written HTML. It stores `toHtml()` output
(`SingleNoteScreen.kt:173`, `:263`), and `toHtml()` output is a **fixed point** — feeding it back
in reproduces it exactly, in every case tested, including after three passes. So on rc14, opening
an unchanged note and re-serialising it produces the bytes already on disk, and a diff-based sync
would correctly see it as clean. Two existing behaviours reinforce that: `.drop(1)` at
`SingleNoteScreen.kt:267` means merely opening a note emits nothing at all, and
`hasUnsavedContent()` (`SingleNoteViewModel.kt:720-728`) skips the upsert when live state equals
the baseline.

One incidental but real finding: rc14's entity escaping inflates Cyrillic roughly **sixfold**.
`<p>Привет, мир — это заметка.</p>` is 32 characters stored as 150. For a Russian-language user
that is a 6× tax on database size, on sync payload size, and on the 256-byte padding buckets the
architecture doc specifies.

### Result B — the upgrade risk is real, and it is not hypothetical

The doc's worst case was "if a library upgrade changes the serialization". That upgrade already
exists. `maven-metadata.xml` on the mirror lists `1.0.0` and `1.1.0` released after the pinned
`1.0.0-rc14`. The version was temporarily bumped to `1.1.0`, the same test rebuilt and rerun on
the same emulator, and then reverted.

**`1.1.0` produces different bytes for the same input.** It abandoned rc14's aggressive entity
escaping almost entirely:

| Case | rc14 output | 1.1.0 output |
|---|---|---|
| `<p>First.</p><p>Second.</p>` | `<p>First&period;</p><p>Second&period;</p>` | `<p>First.</p><p>Second.</p>` |
| `<p>Привет, мир — это заметка.</p>` | `<p>&Pcy;&rcy;&icy;&vcy;&iecy;&tcy;&comma; …&period;</p>` | `<p>Привет, мир — это заметка.</p>` |
| `<p>He said &quot;hi&quot; &amp; left</p>` | `<p>He said &quot;hi&quot; &amp; left</p>` | `<p>He said "hi" &amp; left</p>` |

1.1.0 escapes only `&lt;`, `&gt;` and `&amp;`. It is internally idempotent (28 of 28, same as
rc14) and it still **decodes rc14's output correctly** — a dedicated test feeds literal rc14 bytes
in and asserts on the recovered plain text:

```
RC14IN  |<p>&Pcy;&rcy;&icy;&vcy;&iecy;&tcy;&comma; &mcy;&icy;&rcy; &mdash; &ecy;&tcy;&ocy; &zcy;&acy;&mcy;&iecy;&tcy;&kcy;&acy;&period;</p>|
RC14TXT |Привет, мир — это заметка.|
RC14OUT |<p>Привет, мир — это заметка.</p>|
```

No characters are lost. But **every note containing a full stop, a comma, a quote, an em-dash or
any Cyrillic letter re-serialises to different bytes** — which is essentially every note in the
library.

**Verdict.** The architecture doc's risk assessment was correct, and the failure mode is live
rather than speculative. On the pinned version the round trip is a fixed point and sync is safe;
across the rc14 → 1.1.0 boundary it is not, and a naive byte-diff sync would mark the entire
library dirty on the first launch after that upgrade.

**Recommendations.**

1. **Do not let a `richeditor` version bump and a sync rollout ship in the same release.** Pin the
   version in `gradle/libs.versions.toml:30` and treat a change to that line as a schema change.
2. **Add a `contentSerializerVersion` to the note payload** (not necessarily a column — the
   architecture doc already puts versioned JSON inside the envelope). A device that receives a
   record serialised by a different editor version must compare *decoded text*, not bytes, before
   deciding the record changed.
3. **Make the upgrade an explicit, once-only, offline re-baseline, not a sync event.** On first
   launch after a serializer bump: re-serialise every note locally, write the new bytes, and mark
   the rows `dirty` **without** bumping their HLC or `updatedAt` — the content did not change, only
   its encoding. Then push them at a throttled rate. This is exactly the "local snapshot before
   first pull" discipline the doc already recommends for merge bugs, applied to a second cause.
4. Independently of sync, **upgrading to 1.1.0 is attractive on its own merits** for a
   Cyrillic-writing user: it removes a ~6× storage inflation on note bodies. Doing it *before*
   sync ships costs one local rewrite pass and nothing else. Doing it after costs a full-library
   push on every device. **If the upgrade is going to happen, it should happen first.**
5. Re-run `RichEditorHtmlRoundTripTest` on every `richeditor` version change. That is what it is
   for, and it is why it was kept.

**Disposition of the test harness.** **Kept**, as
`feature-notes/src/androidTest/java/my/cheysoff/feature_notes/RichEditorHtmlRoundTripTest.kt`. It
is genuinely reusable: it asserts only the fixed-point property sync depends on (never the *shape*
of the output, which would make it a change-detector for the library's formatting), it carries a
corpus that took real work to assemble, and it holds captured rc14 bytes as a permanent
backward-compatibility guard. Four tests, all passing on the pinned rc14 and all passing on 1.1.0.
Nothing was left behind that is not committed.

---

## 3. Multi-device convergence testing

**Question.** The architecture doc lists this as "genuinely hard" and notes that no
integration-test infrastructure exists.

**That premise is now stale.** `master` @ `3a2d157` has:

- `core-data/src/androidTest/`: `Migration4to5Test.kt`, `Migration5to6Test.kt`, `NoteDaoTest.kt`
  (336 lines) — real Room-on-device tests, not templates.
- `feature-notes/src/test/`: `FakeNotesRepository.kt`, `FakeSettingsRepository.kt`,
  `MainDispatcherRule.kt`, plus `NotesListViewModelTest.kt` (549 lines),
  `SingleNoteViewModelTest.kt` (1097 lines), `TrashViewModelTest.kt` (319 lines).
- `Migration5to6Test.kt:22-30` records that `androidx.room:room-testing:2.8.4` **does** resolve
  (from `google()`, which was never blocked), correcting an earlier comment that said otherwise.

`FakeNotesRepository`'s design note is the relevant precedent: its flows are `MutableStateFlow`s
the test drives, and a `gate`/`release` pair parks a named suspend call so a test can hold one
write open and watch what happens around it. That is already deterministic-interleaving testing.
The convergence harness below is the same idea with more replicas.

### Recommended: an in-process N-replica simulation over a fake transport

**Precondition, and it is the whole trick: make the merge a pure function.** Put `Hlc`, the record
model with its per-field clocks, and `merge(local, remote): MergeResult` in `core-domain` (or a new
`core-sync`) with no Android, no Room, no coroutines — the same discipline `PassphraseCipher`,
`LockoutPolicy` and `TrashPolicy` already follow, and the reason those have real unit tests. Once
merge is pure, convergence is a JVM property test and needs no emulator at all.

**Four pieces, all test-source-set:**

1. **`FakeServer`** — a `Map<blindedId, MutableList<StoredRecord>>` plus a monotonic per-account
   `seq`. Implements exactly the contract in `e2e-sync-architecture.md`: `GET /v1/changes?since=`
   returns records ordered by `seq`; `POST /v1/records` takes a `baseSeq` per item and returns
   `ok` or `409` with the conflicting record inline. It can be told to inject `429`, to reorder a
   batch, to drop a response *after committing*, and to roll back to an earlier version.
   ~150 lines.
2. **`Replica`** — an in-memory record store, a `SyncEngine`, a `deviceId`, and an injectable
   clock that the test can skew forwards and backwards independently per replica. ~100 lines.
3. **`Schedule`** — a list of operations (`Edit(r, note, field, value)`, `Pin`, `Delete`,
   `Restore`, `Push(r)`, `Pull(r)`, `CrashDuringPush(r)`, `Partition(r)`) executed in a
   deterministic order chosen from a seed.
4. **The property**: run a random schedule, then run every replica to quiescence (push, pull,
   push, pull until nothing is dirty and no cursor advances), then assert **every replica holds a
   byte-identical record set**. Print the seed on failure so any counterexample replays exactly.

**What it catches** — and these are the bugs that actually happen:

- Non-commutative merge (`merge(a,b) ≠ merge(b,a)`), which shows up as two devices settling on
  different states and neither being "wrong".
- Non-idempotent apply — replaying an envelope already applied changes state. Guaranteed to be hit
  in production by a retry after a dropped response, which the fake server can inject on demand.
- Order dependence with **three or more** replicas. Two devices almost always converge by luck;
  three is where field-level LWW breaks.
- HLC ties. Two devices writing in the same millisecond must break the tie on `deviceId`
  deterministically, or they diverge silently. A random schedule with a coarse clock hits this
  constantly; real-world testing almost never does.
- Deletion losing to a stale field write — the resurrection class the doc's Finding 1 is about.
- CAS/409 handling: does a rejected push retry correctly, and does the retry preserve the local
  edit rather than the fetched one?
- Mid-push process death after the server commits but before the client records `lastSyncedSeq`.
- Server rollback: the doc binds `hlc` into the AAD to detect it (line 146) and warns that the
  outer/inner comparison is "easy to get wrong". The fake server can serve an authentic older
  version on demand, which is the only cheap way to test that check exists.

**What it does not catch, and must not be claimed to:**

- Anything about real HTTP — TLS, certificate pinning, timeouts, truncated bodies, real `429`
  timing and the retry-herd behaviour the doc worries about (line 231).
- Room and SQLCipher semantics: transaction boundaries, and specifically the ordering hazard
  `SingleNoteViewModel.kt:657-675` already documents — *"Room's invalidation emission is not
  ordered against the write coroutine … writer-first is near-certain in practice, not enforced
  here."* A sync engine writing behind the user's back makes that a live race, and only a real
  Room test can exercise it.
- The Android lifecycle: `MainApplication.onStop` → `lock()` (`MainApplication.kt:38-43`) →
  `AppNavHost` clearing the ViewModelStore, cancelling in-flight work mid-sync.
- Real clock behaviour — NTP steps, a user setting the date back.
- The crypto itself. Envelope construction, nonce handling and AAD binding want their own
  pure-JVM tests with fixed vectors; the simulation should run over plaintext records so that a
  convergence failure is never confused with a decryption failure.

**Cost:** 2–3 days once merge is pure, and the purity is work Phase 3 must do anyway. This is
comfortably a "days, not weeks" answer.

### Second tier: two emulators, as a smoke test only

`emulator -avd migration_test -read-only` can be started twice, giving `emulator-5554` and
`emulator-5556` off the single installed `android-33` system image, with no extra download. That
buys real Room, real SQLCipher, real lifecycle and a real pairing flow.

Use it for **one scripted happy path** — pair, edit on both, converge, verify — and for the
lifecycle races the simulation cannot reach. Do **not** try to make it the convergence proof:
interleavings are not controllable, failures are not reproducible, and each run costs minutes. A
convergence bug found here is a bug the simulation should have found and did not; that is the
signal to take from it.

### Explicitly not recommended

A third emulator, or CI-hosted device farms. The marginal bug caught does not pay for the
infrastructure at this project's size, and the simulation covers N replicas for free.

---

## 4. Field-level LWW + HLC against the schema as it now is

**Question.** The architecture doc's §"Schema gaps" was written before Trash shipped. What does
Phase 0 still owe sync, exactly?

### What Trash already delivered

Database is at **version 6** (`NoteDatabase.kt:12`, `exportSchema = true`, schemas committed at
`core-data/schemas/…/5.json` and `6.json`).

`NoteEntity` (`NoteEntity.kt:9-27`) — 12 columns: `id`, `title`, `content`, `contentFormat`,
`checklist`, `isPinned`, `isFavorite`, `folderId`, `createdAt`, `updatedAt`, `isDeleted`,
`deletedAt`.

`FolderEntity` (`FolderEntity.kt:8-20`) — 7 columns: `id`, `name`, `colorArgb`, `createdAt`,
`updatedAt`, `isDeleted`, `deletedAt`.

Against the doc's blocking table: note tombstones **done**, folder tombstones **done**, folder
timestamps **done** (except `hlc`), `clearFolder` traceability **done**
(`NoteDao.kt:161` now `UPDATE notes SET folderId = NULL, updatedAt = :timestamp …`),
`exportSchema = true` **done**. `deviceId`, HLC, `dirty`/`lastSyncedSeq` and `sync_state`:
**nothing exists**. A repo-wide grep for `deviceId`, `hlc`, `lastSyncedSeq`, `sync_state` returns
only `contentDirty`, a Compose-local `AtomicBoolean` in `SingleNoteScreen.kt` that has nothing to
do with sync.

### The exact remaining columns

The architecture doc specifies **per-field HLCs inside the encrypted payload** (line 144) and a
**single row-level `hlc` bound into the envelope AAD** (line 140). Those are different objects and
the schema owes both — but the per-field clocks do not need to be columns. Sixteen clock columns
on `notes` would be unindexable, unqueryable and a migration nightmare; one opaque blob is
sufficient because nothing ever queries by a field clock.

`MIGRATION_6_7`, additive `ALTER TABLE … ADD COLUMN` only, on **both** `notes` and `folders`:

| Column | Type | Default | Why |
|---|---|---|---|
| `hlcMs` | `INTEGER NOT NULL` | `0` | HLC physical component; row-level, goes in the AAD |
| `hlcCounter` | `INTEGER NOT NULL` | `0` | HLC logical component |
| `hlcNode` | `TEXT NOT NULL` | `''` | device that minted the clock; **the tie-breaker** |
| `fieldHlc` | `TEXT NOT NULL` | `''` | serialised per-field clock map; `''` means "every field is at the row clock" |
| `dirty` | `INTEGER NOT NULL` | **`1`** | see the warning below |
| `lastSyncedSeq` | `INTEGER NOT NULL` | `0` | CAS baseline sent as `baseSeq` |

⚠️ **`dirty` must default to `1`, not `0`.** Every row that exists at migration time has never
been pushed. A `DEFAULT 0` migration silently declares the user's entire pre-sync library already
uploaded, and the first pull then deletes or overwrites it against an empty server. This is the
single most destructive way to get Phase 0 wrong and it is one character in the DDL.

Plus a new table, which is not a note or folder and should not be crammed into either:

```sql
CREATE TABLE IF NOT EXISTS sync_state (
    accountId  TEXT    NOT NULL PRIMARY KEY,
    cursor     INTEGER NOT NULL DEFAULT 0,   -- server seq, NOT a timestamp
    lastPullAt INTEGER NOT NULL DEFAULT 0
)
```

And **`deviceId` outside the database**, in `secret_shared_prefs` alongside the existing keys
(`SecureUnlockManager.kt:374` `PREFS_NAME`, keys at `:395-405`), because it must be readable while
the app is locked and the database is not (`DataModule.kt:61-62` throws).

### The exact write paths that must bump them

Every mutating query in the two DAOs, with what each currently does and what it owes:

**`NoteDao`** — `core-data/src/main/java/my/cheysoff/core_data/data/local/NoteDao.kt`

| Method | Lines | Sets `updatedAt` today | Must additionally set |
|---|---|---|---|
| `insertNote` | `:59-60` | n/a | **Delete it.** See hazard 2 below. |
| `upsertNote` | `:76-100` | yes | `hlcMs/hlcCounter/hlcNode`, `fieldHlc` for `title`,`content`,`contentFormat`,`checklist`,`isPinned`,`folderId`, `dirty = 1` |
| `softDeleteNote` | `:107-108` | no | row clock, `fieldHlc[isDeleted]`, `dirty = 1` |
| `restoreNote` | `:111-112` | no | row clock, `fieldHlc[isDeleted]`, `dirty = 1` |
| `purgeNote` | `:115-116` | hard `DELETE` | must become unreachable for a pushed row — hazard 3 |
| `purgeNotesDeletedBefore` | `:134-138` | hard `DELETE` | must gate on "tombstone acknowledged by server" — hazard 4 |
| `setNoteFolder` | `:140-141` | **no, by design (PR #32)** | row clock, `fieldHlc[folderId]`, `dirty = 1` |
| `setNoteFavorite` | `:143-144` | **no, by design** | row clock, `fieldHlc[isFavorite]`, `dirty = 1` |
| `setNotePinned` | `:146-147` | **no, by design** | row clock, `fieldHlc[isPinned]`, `dirty = 1` |
| `clearFolder` | `:161-162` | **yes** | row clock, `fieldHlc[folderId]`, `dirty = 1` — **mass update**, see hazard 5 |

**`FolderDao`** — `core-data/src/main/java/my/cheysoff/core_data/data/local/FolderDao.kt`

| Method | Lines | Sets `updatedAt` today | Must additionally set |
|---|---|---|---|
| `upsertFolder` | `:28-39` | yes | row clock, `fieldHlc[name,colorArgb]`, `dirty = 1` |
| `softDeleteFolder` | `:45-46` | no | row clock, `fieldHlc[isDeleted]`, `dirty = 1` |
| `restoreFolder` | `:52-53` | no | row clock, `fieldHlc[isDeleted]`, `dirty = 1` |
| `purgeFolder` | `:56-57` | hard `DELETE` | hazard 3 |
| `purgeFoldersDeletedBefore` | `:64-68` | hard `DELETE` | hazard 4 |

**The doc's `updatedAt`/`hlc` split is confirmed and still correct** — verbatim, unchanged:

```kotlin
@Query("UPDATE notes SET folderId  = :folderId  WHERE id = :noteId")   // NoteDao.kt:140
@Query("UPDATE notes SET isFavorite = :isFavorite WHERE id = :noteId") // NoteDao.kt:143
@Query("UPDATE notes SET isPinned   = :isPinned   WHERE id = :noteId") // NoteDao.kt:146
```

The insight dissolves the PR #32 tension exactly as the doc claims. One correction: the doc says
**four** metadata writes leave no trace; it is now **three**. `clearFolder` was fixed
independently, and `NoteDao.kt:149-159` reaches the doc's own conclusion in its own words.

**The injection seam.** `RoomNotesRepository` is where the clock enters:

- `saveNote` `:41-53` and `saveFolder` `:91-102` each call `System.currentTimeMillis()` inline.
  That call is the seam — replace it with an injected `HlcGenerator` that returns
  `(wallMs, updatedAt)` together, so `updatedAt` and the HLC can never disagree about which write
  they describe.
- `deleteFolder` `:104-116` runs `clearFolder(id, now)` and `softDeleteFolder(id, now)` inside one
  `database.withTransaction` sharing a single `now`. The HLC must likewise be allocated **once per
  transaction**, not once per statement, or the two halves of one user action land at different
  points in the account's history.
- `purgeExpiredTrash(now)` `:132-142` already takes its clock as a parameter — the only injectable
  clock in the repository today, and the pattern the rest should follow.

### Five hazards Phase 0 must handle, none of which are in the architecture doc

1. **`upsertNote`'s conflict branch deliberately refuses to write some fields.** Its KDoc
   (`NoteDao.kt:61-74`) states the rule: *"The tombstone columns follow the same 'not ours to
   write' rule as isFavorite … the conflict branch leaves isDeleted/deletedAt exactly as it found
   them. So a save that races a delete cannot resurrect the note."* This is correct for the editor
   and **fatal for sync**: applying a remote record through `upsertNote` would silently drop an
   incoming `isFavorite`, `isDeleted` or `deletedAt`. A merged remote record needs its **own**
   `applyRemote` query that writes every column including the sync columns, and it must be the
   only path that does.

2. **`insertNote` (`NoteDao.kt:59-60`) is dead code and a landmine.** Grep finds the declaration
   and no caller. It is `@Insert(onConflict = REPLACE)`, and REPLACE is DELETE-then-INSERT — it
   would wipe `createdAt`, both tombstone columns and every sync column added above.
   `FolderDao.kt:20-22` records that this is exactly why folders abandoned REPLACE. Delete
   `insertNote` in Phase 0 rather than leave it for a future implementer to reach for.

3. **The blank-note discard is still a hard `DELETE` with no tombstone.** The architecture doc's
   Finding 1 said `SingleNoteViewModel:213` hard-deletes; that line and method no longer exist.
   The path is now `SingleNoteViewModel.kt:536` calling `purgeNote`, behind an explicit
   `openedForNewNote` nav flag (`:266`) rather than a timestamp inference — and its own comment
   says *"purgeNote, NOT deleteNote: this is a discard, not a deletion the user asked for."* The
   shape of the bug is unchanged: if that blank row was already pushed, the next pull resurrects
   it forever. **Fix:** allow the purge only while the row has never been pushed
   (`dirty = 1 AND lastSyncedSeq = 0`); otherwise soft-delete it like anything else.

4. **Auto-purge only runs when the user opens the Trash screen** (`TrashViewModel.kt:43-59`, which
   says so plainly: *"Trash is never swept while the user stays out of this screen, so an expired
   note can sit in the database indefinitely"*). The architecture doc's "tombstone purge
   resurrection" risk therefore has a trigger that may never fire on one device and fire daily on
   another — the worst case for convergence. A tombstone must not be purged on **age** alone; it
   must be purged only once the server has acknowledged it and every enrolled device's cursor has
   passed it, or the purge window must be enforced as a hard refusal-to-sync staleness threshold
   as the doc suggests.

5. **`clearFolder` is a mass update and needs one clock, not N.** It rewrites every note in a
   folder in a single statement (`NoteDao.kt:161`). Under an HLC, either all affected rows share
   one `(ms, counter)` — cheap, and semantically right since it is one user action — or the
   counter advances per row, which requires a per-row statement and turns a folder delete into an
   O(n) write. Choose the shared clock, and record the choice.

### Two data-shape facts a merge design must accept

- **Restoring a folder brings it back empty.** `deleteFolder` unfiles its notes and nothing
  remembers which they were (`FolderDao.kt:48-53`, `NotesRepository.kt:46-52`). Device A restoring
  a folder and device B re-filing a note into it do not compose into anything sensible. Either
  accept it explicitly, or make `clearFolder` record the prior `folderId` so restore can undo it —
  which is a Phase 0 decision, not a Phase 3 one.
- **`checklist` has no stable item identity in storage.** `parseChecklist`
  (`ChecklistItem.kt:36-45`) mints a fresh UUID per line on every read, and `ChecklistItem.id` is
  documented as ephemeral and never serialised. `mergeChecklist`
  (`SingleNoteViewModel.kt:158-186`) preserves ids across a Room re-emission *within a live editor
  session* by positional matching, and concedes in its own KDoc that *"a genuinely reordered list
  does get fresh ids"* — so it gives sync nothing durable. The checklist merges as a whole blob
  under LWW, exactly as the doc says.
- **`contentFormat` must travel with `content` in the payload.** `NoteDao.kt:69-70` and
  `SingleNoteViewModel.kt:724-726` both state that the two must never drift; a body read back with
  the wrong parser is silent corruption. It is not optional payload.

### One thing the doc does not say and should

When a remote record's `content` wins the merge, **`updatedAt` must be taken from the remote record
too**, as an ordinary LWW field tied to the content clock. If each device keeps its own local
`updatedAt` for a body it received rather than authored, the two devices show the same notes in
different orders forever — a visible divergence in the one field the user actually looks at.

---

## 5. Claims in `e2e-sync-architecture.md` that are now stale or wrong

Verified file-by-file against `master` @ `3a2d157`. The doc was written against `00df3e4`; 63
commits have landed since, including Trash (#49), search (#48), the settings screen (#51), the
backup-rules hardening (#36) and the Maven Central mirror (#62). All corrections listed here have
been applied to `e2e-sync-architecture.md`.

| Doc location | Claim | Verdict |
|---|---|---|
| `:3` | written against `master` @ `00df3e4` | **Stale.** Master is `3a2d157`. |
| `:28` | "four metadata writes leave *no trace at all*" | **Wrong — three.** `clearFolder` now bumps `updatedAt` (`NoteDao.kt:161`). |
| `:41-43` | `createNewNote` snippet | **Inexact.** Still persists a blank note (`NotesListViewModel.kt:369-380`) but the event is now `NavigateToNote(newNote.id, isNew = true)`. |
| `:46` | "`SingleNoteViewModel:213` **hard-deletes**" | **Wrong line and wrong method.** `:213` is now a KDoc block. The discard is `purgeNote` at `SingleNoteViewModel.kt:536`. (It *was* correct at `00df3e4`.) |
| `:46` | "there is no user-facing note delete (only `DeleteFolder`)" | **Wrong.** Editor overflow → "Move to Trash" (`SingleNoteScreen.kt:634-661`) → `SingleNoteIntent.DeleteNote` → soft delete. Plus a whole Trash screen with Restore and Delete Forever. |
| `:46` | "the only `deleteNote` call site" | **Still true** — `SingleNoteViewModel.kt:508`, sole caller. |
| `:48` | blank notes resurrect forever | **Narrowed, not dead.** Tombstones exist; the hard-`DELETE`-without-tombstone shape survives in `purgeNote`, `purgeNotesDeletedBefore`, `purgeFolder`, `purgeFoldersDeletedBefore`. |
| `:52-57` | locked-database constraint | **Fully correct, line numbers exact.** `DataModule.kt:61-62` throws; `MainApplication.kt:38-43` locks on `onStop`; the SQLCipher limitation comment is still at `DataModule.kt:64-69`. |
| `:59-65`, `:273` | "`allowBackup="true"` with both rules files left as empty AS templates" | **Wrong — fixed by PR #36.** `data_extraction_rules.xml:15-29` excludes `secret_shared_prefs.xml` and all four `notes.db*` files from **both** `<cloud-backup>` and `<device-transfer>`; `backup_rules.xml:28-34` mirrors it. `allowBackup` is still `true` (`AndroidManifest.xml:6`) but the key material and the database are out of scope. The doc's own §65 mitigation was adopted almost verbatim as the rationale comment. |
| `:16`, `:130` | "zero permissions" | **Still true.** `grep -rn "uses-permission"` across all eight manifests → no matches. No network code anywhere. |
| `:61` | prefs keys, PBKDF2-210k, `LockoutPolicy` | **True.** `SecureUnlockManager.kt:395-398`; `PassphraseCipher.kt:34` `ITERATIONS = 210_000`; `LockoutPolicy.kt:9-12`. One nuance: the unwrap path reads the iteration count *from prefs* (`SecureUnlockManager.kt:361`), so 210k is what new wraps use. |
| `:158-161` | tombstone and folder-timestamp rows | **Done** by `MIGRATION_5_6`, except `hlc` on `FolderEntity`. |
| `:160` | "`clearFolder` leaves no trace" | **Done.** |
| `:162-164` | `deviceId`, HLC, `dirty`/`lastSyncedSeq` | **Still outstanding — nothing exists.** |
| `:163` | the clock defence lives in `lockoutRemainingMillis()` | **Imprecise.** The pure function is `LockoutPolicy.remainingMillis` (`LockoutPolicy.kt:49`); `SecureUnlockManager.lockoutRemainingMillis()` (`:269`) wraps it. There is now a second such defence the doc predates: `TrashPolicy.isExpired`. |
| `:175` | `exportSchema = true` as a nice-to-have | **Done** (`NoteDatabase.kt:12`); schemas committed. |
| `:175` | checklist items "have no identity even across reloads" | **True in storage; too strong in-session** — `mergeChecklist` preserves ids positionally within a live editor session. |
| `:204` | "today all async work is `viewModelScope`" | **Still true.** The only `CoroutineScope(` in the repo is `rememberCoroutineScope()` in `AuthScreen.kt:297`. No app scope, no `SupervisorJob`, no WorkManager. |
| `:253-264`, `:290` | "ship Trash first" / "does Trash stand on its own?" | **Done and moot** — shipped as PR #49. |
| `:259` | "deleting a folder … destroys it irreversibly … with no undo" | **Wrong.** Folder delete is soft and restorable from Trash. |
| `:279` | "the `androidTest` dirs hold only `ExampleInstrumentedTest`" | **Wrong.** `core-data/src/androidTest/` holds three real tests. `feature-notes/src/test/` holds fakes, a dispatcher rule and ~2000 lines of ViewModel tests. |
| `:281`, `:287` | the `richeditor` round-trip is unverified | **Now answered — see §2.** |
| `:289` | "the 300 ms autosave fires on each keystroke, so a save happens regardless" | **Misleading.** There are two chained 300 ms *trailing* debounces (`SingleNoteScreen.kt:135` serialise, `SingleNoteViewModel.kt:677-683` save), `.drop(1)` means opening a note emits nothing, and `hasUnsavedContent()` (`:720-728`) skips the upsert when state matches the baseline — so a fast type-then-delete can produce no write at all. The doc's *conclusion* (write an instrumented test) was right for a different reason: the probe is timing-dependent, not unconditional. |
| `:296` | "`hlc`/`dirty` bump on all six write paths" | **Wrong count.** `NoteDao` has **ten** write methods; `FolderDao` has five. Full inventory in §4. |
| `:297` | "`MIGRATION_4_5`, `exportSchema = true`; `FolderEntity` gains its whole timestamp set" | **All already landed.** The next migration is `MIGRATION_6_7`. |
| `:7-12` | the doc's own "Update" note | **Accurate but incomplete.** It omits the backup-rules fix and `exportSchema = true`, both of which change its own findings; it says Trash covered the folder-timestamp row when `hlc` did not ship; and it does not mention that the 30-day auto-purge only fires on Trash-screen open. |

Two facts the doc has no idea about that a sync design needs, restated here because they are the
kind of thing that gets discovered the expensive way: **auto-purge only runs when the Trash screen
is opened**, and **restoring a folder brings it back empty**. Both are covered in §4.

---

## Method notes and limits

- Everything about API levels came from the installed SDK, not from memory or the web:
  `api-versions.xml` and the `android.jar` class lists.
- The round-trip results are raw logcat output from `emulator-5554` (API 33, x86_64), reproduced
  above without editing. The 1.1.0 comparison was made by temporarily editing
  `gradle/libs.versions.toml:30`, rebuilding, rerunning, and reverting — that line is unchanged in
  this branch.
- The codebase audit was done file-by-file with line citations; the load-bearing claims (the
  backup rules, `clearFolder`, the three `setNote*` queries, both upserts) were read directly a
  second time before being written down here.
- **Not determined:** whether any provider registers `"XDH"` at runtime on API 31/32. See §1 for
  what would settle it and why it does not change the recommendation.
- **Not determined:** whether `richeditor` `1.0.0` (the stable release between rc14 and 1.1.0)
  serialises like rc14 or like 1.1.0. Only rc14 and 1.1.0 were run. If the project ever considers
  `1.0.0` specifically, run `RichEditorHtmlRoundTripTest` against it first; it takes ten minutes.
