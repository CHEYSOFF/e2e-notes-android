# E2E sync — Phase 3: the client sync engine

> Status: **plan, not implementation.** Written 2026-08-31 on `sync-phase4-server`, immediately
> after implementing the reference server, against `master` @ `e8bf8f3`.
>
> Companions: [`e2e-sync-architecture.md`](e2e-sync-architecture.md) is the accepted design,
> [`e2e-sync-open-questions.md`](e2e-sync-open-questions.md) corrects several of its stale claims and
> is the authority where the two disagree, and [`../../server/README.md`](../../server/README.md) is
> the server contract this plan speaks to — it is implemented, tested and running, so the wire
> format is settled rather than proposed.

---

## 0. What this plan assumes, and what is actually built

Being precise about this is the point of the section. Phase 3 is the first phase that cannot be
built in isolation, and three of the four things it depends on are not finished.

### Built and merged

| Thing | Where | Status |
|---|---|---|
| HKDF-SHA256 | `core-crypto/.../sync/Hkdf.kt` | done, RFC 5869 vectors |
| `ARK → K_content, K_id, accountId` | `core-crypto/.../sync/AccountKeys.kt` | done; `derive` is called by `feature-pairing/.../SecureUnlockArkStore`, `K_content`/`K_id` still have no consumer |
| Blinded record IDs | `core-crypto/.../sync/BlindedRecordId.kt` | done |
| Record envelope (AES-256-GCM, per-record keys, AAD) | `core-crypto/.../sync/RecordEnvelope.kt` | done |
| 4 KiB bucket padding | `core-crypto/.../sync/RecordPadding.kt` | done |
| Sealed device labels | `core-crypto/.../sync/DeviceLabelCipher.kt` | done; called by `app/.../sync/ArkDeviceLabelSealer`, which is what `:core-sync-net` seals enrolment labels through |
| Protocol constants | `core-crypto/.../sync/SyncProtocol.kt` | done |
| QR pairing, ECDH, SAS, `ServerHint` | `feature-pairing/` | done |
| Device identity key (P-256, Keystore, `SHA256withECDSA`) | `feature-pairing/.../identity/DeviceIdentityKey.kt` | done; `sign()` is reached by `app/.../sync/KeystoreDeviceSigner` |
| The sync transport | `core-sync-net/` | done; `SyncApi`, `SyncHttpClient`, OkHttp, `SyncServerContractTest` against the real server |
| The server | `server/` | done, 123 tests |

### Not built — Phase 3 must either wait for it or build it

1. **The Phase 0 schema debt.** `deviceId`, the HLC columns, `dirty`, `lastSyncedSeq` and the
   `sync_state` table **do not exist anywhere in the codebase**. The exact list is in
   `e2e-sync-open-questions.md` §4 and is restated as §2 below because this plan's column names have
   to match it or nothing lines up. Tombstones, folder timestamps and `exportSchema = true` *did*
   ship with Trash (`MIGRATION_5_6`); the sync half did not.
2. ~~**ARK storage.**~~ **Built since.** `SecureUnlockManager` now owns `ensureArk()`,
   `currentArk()` and `adoptArk()`, wrapping the key under `HKDF(dbPassphrase, ".../arkwrap")` into
   `ark_ct`/`ark_iv`, with generation confined to the single call site `ensureArk()` guards. That was
   the hard prerequisite; `K_content` and `K_id` still have no consumer, because the merge engine
   that would use them is the part of this plan still unwritten.
3. **An app-scoped `CoroutineScope`.** The only `CoroutineScope(` in the repository is
   `rememberCoroutineScope()` in `AuthScreen.kt`. Everything else is `viewModelScope`, which is
   cancelled by navigation — mid-push. §7.
4. ~~**Any HTTP client at all.**~~ **Built since,** as `:core-sync-net`: OkHttp, `INTERNET`
   declared in that module's own manifest, and `CertificatePinner` wired to
   `ServerHint.spkiPinSha256`. What is still missing above it is a `ServerEndpoint` — nothing in the
   app can yet supply a server URL, so no `SyncApi` is bound.

**If Phase 3 starts before (1) lands, it will start by building it badly.** (2) and (4) shipped as
their own PRs, which is the recommendation this paragraph originally made; (1), the schema debt, is
the one that is still outstanding and it is still the thing to do first.

---

## 1. Module layout and the classes to write

### A new module: `core-sync`

Pure JVM, no Android, no Room, no coroutines in the merge path — the discipline `PassphraseCipher`,
`LockoutPolicy` and `TrashPolicy` already follow, and the reason those have real unit tests. This is
not architectural taste: `e2e-sync-open-questions.md` §3 shows that the N-replica convergence
harness is a cheap JVM property test **if and only if** `merge` is a pure function, and an emulator
matrix otherwise.

```
core-sync/
  clock/     Hlc.kt  HlcGenerator.kt
  model/     SyncRecord.kt  NotePayload.kt  FolderPayload.kt  FieldClocks.kt
  merge/     Merge.kt  MergeResult.kt  ConflictCopy.kt
  wire/      SyncApi.kt  SyncWire.kt  SyncError.kt
```

`core-data` and `feature-notes` depend on `core-sync`; `core-sync` depends on `core-domain` and
`core-crypto` and on nothing else.

> **[LANDED ELSEWHERE.]** The clock and merge halves of this layout are in **`core-domain`**, not a
> new `core-sync` module: `sync/Hlc.kt`, `HlcGenerator.kt`, `FieldClocks.kt`, `SyncRecord.kt`,
> `Merge.kt`, `MergeResult.kt`, `ConflictCopies.kt`. `e2e-sync-open-questions.md` §3 offers
> "`core-domain` (or a new `core-sync`)" and the constraint that actually matters is purity, which
> `core-domain` already has — it declares no Room, no Hilt and no coroutines. A ninth Gradle module
> would have bought a namespace and cost a build script. The transport half is `:core-sync-net`, as
> the note further down records.

### The classes, by name and job

| Class | Module | Responsibility |
|---|---|---|
| `Hlc(ms: Long, counter: Int, node: String)` | `core-sync` | one hybrid logical clock reading; `Comparable`, tie-broken on `node` |
| `HlcGenerator` | `core-sync` | `next(wallMs): Hlc` and `observe(remote: Hlc)`; the only thing that mints clocks. One instance, `@Singleton` |
| `FieldClocks` | `core-sync` | `Map<String, Hlc>` with a compact serialisation; what the `fieldHlc` column holds |
| `NotePayload` / `FolderPayload` | `core-sync` | `@Serializable` plaintext, versioned, carrying `recType`, `uuid` and the row clock |
| `SyncRecord` | `core-sync` | `(recType, uuid, rowHlc, fieldClocks, payload)` — the merge's unit |
| `Merge` | `core-sync` | **pure**: `merge(local: SyncRecord?, remote: SyncRecord): MergeResult` |
| `MergeResult` | `core-sync` | `Applied(record)`, `NoChange`, `ConflictCopy(winner, loser)`, `Rejected(reason)` |
| `SyncApi` | `core-sync` | interface over the endpoints the client uses; `SyncError` for every failure — **shipped, in `:core-sync-net`; see the note below** |
| `KtorSyncApi` | `core-data` | the implementation; owns the token, the `Retry-After` back-off and the SPKI pin — **shipped as `SyncHttpClient` in `:core-sync-net`, on OkHttp** |
| `RecordCodec` | `core-data` | seal/open, and the **only** place that recomputes the blinded ID against the opened payload (§4) |
| `SyncCursorDao` / `SyncStateEntity` | `core-data` | the `sync_state` table |
| `SyncQueries` | `core-data` | `dirtyNotes()`, `dirtyFolders()`, `applyRemoteNote()`, `applyRemoteFolder()` — the write path §5.6 |
| `SyncCoordinator` | `core-data` | the loop of §3; `@Singleton`, holds the app scope, gated on unlock |
| `SyncScheduler` | `app` | when the loop runs — unlock, foreground tick, pull-to-refresh |
| `SyncModule` | `core-data/di` | Hilt bindings |

> **The transport half of this table has since been built, and not quite where the table puts it.**
> It is its own Android library module, `:core-sync-net`, rather than living in `core-data`: it has
> no Room, no Hilt entry point and no Android dependency beyond the manifest's `INTERNET`
> permission, so putting it inside the persistence module would have made it untestable for no
> reason. `SyncApi` and `SyncHttpClient` are there, D1 was settled on OkHttp, and §3's "cache the
> token in memory only" is implemented as written. Everything above the transport — `Merge`,
> `RecordCodec`, the cursor, `SyncCoordinator` — is still a plan.
>
> Two shapes in this table moved with the wire. A record on the wire is `(blindedId, seq, envelope)`
> and nothing else, and the device label is sealed: `SyncApi.claimAccount` takes a name, seals it
> through a `DeviceLabelSealer` seam and sends base64url. §4 below is the authority on the envelope.

> **[IMPLEMENTED — `SyncCoordinator` is `SyncEngine`, in a new module `:core-sync-engine`.]**
> The table puts the coordinator in `core-data`, next to Room. It is instead its own Kotlin
> Multiplatform module with a **pure `commonMain`**: no Android, no Room, no Hilt, no OkHttp and no
> I/O of its own. Storage enters through `SyncStore` and the server through `SyncTransport`, both
> defined in that module and implemented outside it.
>
> The reason is the same one §9 gives for keeping `Merge` pure, and it is load-bearing rather than
> tasteful: with the coordinator inside `core-data` the N-replica harness could only reach it
> through Room, which makes it an emulator test. As written, `Replica` in the convergence harness
> drives the **real** `SyncEngine`, so convergence, commutativity, idempotence and determinism are
> properties of the shipped loop rather than of a second loop written to model it. The module
> carries the same `mingwX64` canary target `:core-domain` does.
>
> Two shapes moved with it:
>
>  - **The engine is never handed an envelope.** Opening one needs `K_content`, `K_id` and a JSON
>    parser, none of which exist in `commonMain`, so `SyncTransport` hands back either a record or
>    the *reason it could not* — `RecordFault.UNREADABLE`, `MISLABELLED`,
>    `UNSUPPORTED_PAYLOAD_VERSION`, which are F1, F3 and F2 exactly. The engine owns the policy for
>    each; §4's `RecordCodec` owns the decrypt, and is still to be written on the Android side.
>  - **The `429` back-off is split in two, and both halves are needed.** `:core-sync-net`'s
>    `RetryPolicy` spreads the retries of one request inside one call; the engine's `RetryPlan`
>    spreads the *next pass*, reporting `SyncOutcome.Deferred(retryAfterMillis)` rather than
>    sleeping. Without the first a `429` fails a request a two-second wait would have satisfied;
>    without the second the three devices that just exhausted their in-request budget line up and
>    do it again as a group.
>
> `SyncScheduler`, the app-scoped `CoroutineScope`, the unlock gate and the Hilt bindings (§7) are
> **not** built. The engine is a suspending object with a `Mutex`; nothing yet decides when to call
> it.

---

## 2. Schema — what Phase 0 owes, restated so this plan is self-contained

`MIGRATION_6_7`, additive `ALTER TABLE … ADD COLUMN` only, on **both** `notes` and `folders`:

| Column | Type | Default | Why |
|---|---|---|---|
| `hlcMs` | `INTEGER NOT NULL` | `0` | row clock, physical part; goes inside the sealed payload |
| `hlcCounter` | `INTEGER NOT NULL` | `0` | row clock, logical part |
| `hlcNode` | `TEXT NOT NULL` | `''` | minting device; **the tie-breaker** |
| `fieldHlc` | `TEXT NOT NULL` | `''` | serialised per-field clocks; `''` means "every field is at the row clock" |
| `dirty` | `INTEGER NOT NULL` | **`1`** | see below |
| `lastSyncedSeq` | `INTEGER NOT NULL` | `0` | the CAS baseline sent as `baseSeq` |

⚠️ **`dirty` must default to `1`.** Every row that exists at migration time has never been pushed. A
`DEFAULT 0` declares the user's entire pre-sync library already uploaded, and the first pull then
overwrites or deletes it against an empty server. One character in the DDL, and the most destructive
way to get Phase 0 wrong.

```sql
CREATE TABLE IF NOT EXISTS sync_state (
    accountId  TEXT    NOT NULL PRIMARY KEY,
    cursor     INTEGER NOT NULL DEFAULT 0,   -- server seq, NOT a timestamp
    lastPullAt INTEGER NOT NULL DEFAULT 0
)
```

> **[SHIPPED, plus two columns this table does not have — schema v8.]** Every column above is in
> v7 and behaves as written, `dirty DEFAULT 1` included. `MIGRATION_7_8` adds two more, both with
> an inert default:
>
>  - **`notes.contentSyncedHlc TEXT NOT NULL DEFAULT ''`** — the `content` clock of the newest
>    version this device and the server have agreed on. This is **decision D7, closed**. Without it
>    the merge cannot tell "both devices edited the body" from "I pinned it and they edited the
>    body", and the second case costs a duplicate note. `''` means *no agreement is recorded* and
>    is not the same as `Hlc.ZERO`: a zero clock would claim an agreement at the beginning of time,
>    and the merge would then discard an unpushed body believing it was already published.
>    `Migration7to8Test.everyMigratedRowHasNoRecordedContentBaseline` asserts the distinction
>    through `RoomSyncStore.load`, which is where it matters.
>  - **`sync_state.haltReason TEXT NOT NULL DEFAULT ''`** — the engine's halt, which has to outlive
>    the process or a restart resumes syncing against the server it refused to trust. Empty is
>    healthy; a value this build does not recognise is still a halt.

`deviceId` lives **outside the database**, in `secret_shared_prefs` next to the existing keys
(`EncryptedPrefsStore.kt:90`, `PREFS_NAME = "secret_shared_prefs"`), because the HLC needs it while the app is locked and
the database is not open. It is a locally generated random string and is **not** the server's
`deviceId`, which is server-assigned and only meaningful to the server. Keep both; do not conflate
them (§10, decision D4).

### The `updatedAt` / `hlc` split stays exactly as the architecture doc describes

`updatedAt` is bumped only by `upsertNote` and drives `ORDER BY updatedAt DESC`. `hlc` is bumped by
**every** write, including the three metadata paths (`setNotePinned`, `setNoteFavorite`,
`setNoteFolder`) that deliberately leave `updatedAt` alone. The UI never reads `hlc`. This is
confirmed against the current DAO in `e2e-sync-open-questions.md` §4 and it dissolves the PR #32
tension completely.

`clearFolder` is a **mass** update and must allocate **one** clock for the whole statement, shared
with the `softDeleteFolder` in the same `withTransaction` — it is one user action.
`RoomNotesRepository.deleteFolder:104-116` already shares a single `now` between them; the HLC
follows the same rule.

---

## 3. The push/pull loop

One pass, run to quiescence. `SyncCoordinator.syncOnce()` returns a `SyncOutcome` and never throws
past its own boundary.

```
0. PRECONDITIONS
   - SecureUnlockManager is unlocked (else return Skipped(Locked) -- see §7)
   - ARK is available; derive AccountKeys once per pass and destroy() at the end
   - a session token exists and is unexpired, else run the handshake (§3.1)

1. PULL
   cursor = sync_state.cursor
   loop:
     GET /v1/changes?since=cursor&limit=200
       409 cursor_ahead_of_server -> HALT the whole engine, surface to the user (§8, F7)
     for each record, in seq order:
       open the envelope (§4). If it will not open, HALT this record, count it, continue.
       merge (§5) inside ONE Room transaction per record
       cursor = record.seq          <-- only after the transaction commits
     persist cursor
     repeat while hasMore

2. PUSH
   items = dirtyNotes() + dirtyFolders(), oldest row clock first, chunked to 64
   for each chunk:
     POST /v1/records  { blindedId, baseSeq = row.lastSyncedSeq, envelope }
     for each per-item result:
       ok       -> lastSyncedSeq = seq, dirty = 0   (only if the row's hlc is unchanged, §3.2)
       conflict -> merge the inline `current` exactly as if it had arrived from a pull,
                   leave dirty = 1, and let the NEXT pass push the merged row

3. If anything was merged in step 2, loop back to 1. Cap at 3 iterations per pass.
```

**Pull before push, always.** Pushing first maximises the number of `409`s, because every conflict
the server would report is one the client could have merged locally a moment earlier. Pulling first
also means a device that has been offline for a week applies the world before it argues with it.

### 3.1 The session handshake

```
POST /v1/session/challenge  { accountId, deviceId }        -> { challenge, expiresAt }
sign SignedMessage.session(accountId, deviceId, challenge) with DeviceIdentityKey.sign()
POST /v1/session            { accountId, deviceId, challenge, signature } -> { token, expiresAt }
```

Cache the token in memory only, in `KtorSyncApi`. **Do not persist it**: it is a bearer credential,
the handshake costs one round trip and one ECDSA operation, and a token in
`secret_shared_prefs` is a token in an Auto Backup discussion. On `401` from any call, discard the
token, redo the handshake once, retry the call once, then give up for this pass.

The canonical signed-message encoding is specified byte-for-byte in `server/README.md` and
implemented in `server/.../SignedMessage.kt`. The client must reproduce it exactly; there is no
negotiation step and the failure mode is "every signature is rejected". Put it in `core-crypto` next
to `SyncProtocol` so both halves of the app can see it, and give it the same "changing this file is
a breaking protocol change" KDoc.

### 3.2 `baseSeq` bookkeeping — the part that is easy to get subtly wrong

`baseSeq` is `row.lastSyncedSeq`: the server `seq` of the version this device last agreed with. `0`
means "this record has never been on the server", which the server reads as "must not exist".

Three rules, each of which corresponds to a bug that is otherwise guaranteed:

1. **Clear `dirty` only if the row has not changed since the envelope was sealed.** The user can
   edit a note while its push is in flight. Seal the envelope and *capture the row clock at the same
   moment*; when the `ok` comes back, write
   `UPDATE … SET dirty = 0, lastSyncedSeq = :seq WHERE id = :id AND hlcMs = :sealedMs AND hlcCounter = :sealedCounter`.
   If the row moved, the update matches nothing, the row stays dirty, and the next pass pushes the
   newer version. Unconditionally clearing `dirty` here silently drops that edit forever.
2. **Always write `lastSyncedSeq` on `ok`, even when the row moved.** Otherwise the next push sends
   a stale `baseSeq` and takes a guaranteed `409` for no reason. Split the two updates if rule 1's
   guard fails.
3. **A `409` is not an error.** It is data. The `current` version comes back inline, so handle it in
   the same code path as a pulled record — do not add a second, subtly different merge path for the
   conflict case. `RecordsTest.aStaleBaseSeqIsRejectedWithTheConflictingEnvelopeInline` shows the
   exact response shape.

> **[SHIPPED, and rules 1 and 2 are one SQL statement — `NoteDao.acknowledgeNotePush`.]**
> Not two statements in a transaction. The distinction is the point of the whole section and it is
> the thing no engine-side test can reach: an implementation that runs
> `UPDATE … WHERE the clock matches` and then `UPDATE … SET lastSyncedSeq` satisfies both rules as
> written, passes every behavioural assertion about them, and is still wrong, because between the
> two the row can move and `dirty` has then been cleared for a version `lastSyncedSeq` does not
> describe.
>
> `RoomSyncStoreTest.the acknowledgement is one statement when the clock matches` is the check, and
> it is a real one rather than a reading of the SQL: an `AFTER UPDATE` trigger fires once per row
> per statement, so it counts update events. Breaking the rule into a `@Transaction` default method
> over two `@Query`s fails that test **and nothing else in the suite** — which is exactly the
> mutation the section warns about.
>
> One deviation, upwards: the guard compares all three clock components, not the two this section's
> example SQL names. The row clock *is* `(ms, counter, node)`, the counter is per-generator, and two
> nodes reaching the same `(ms, counter)` is not exotic — so comparing a prefix would read another
> device's write as "unchanged".

### 3.3 Crash points, and what each costs

| Dies at | Consequence | Why it is safe |
|---|---|---|
| after a merge commits, before `cursor` is persisted | that record is pulled and merged again next pass | merge must be **idempotent**: re-applying an already-applied remote record is `NoChange`. This is the single most important property in §5 |
| after the server commits a push, before the `ok` is read | the row stays `dirty` with a stale `lastSyncedSeq`; the next push takes a `409` and merges its own write back | the conflicting envelope is this device's own, so the merge is a no-op |
| mid-chunk | the applied prefix is committed, the rest is still dirty | the server applies a batch's non-conflicting items and reports per item; there is no half-applied item |

> **[IMPLEMENTED, with one rule this section does not state.]** All three rows are covered by
> `SyncEngine` holding **no state across passes** — the cursor, the dirty set and the halt all live
> in `SyncStore` — so "resume after process death" is not a feature the engine has; it is what
> happens when a second engine is built over the same store.
> `SyncEngineTest.a pass interrupted by process death resumes to the same state` runs an
> uninterrupted pass and a torn one and compares the two databases.
>
> The rule the plan does not state, and which the convergence harness cannot catch:
> **the `content` baseline advances against the record that ARRIVED, never against the merged
> result.** The merged record's content clock is `max` of the two sides', so on a merge this device
> wins it is *this device's own unpublished body*. Recording that as the agreed ancestor marks an
> unpublished body as published, and the next merge then discards it with no conflict copy — a body
> the user typed, gone, with nothing anywhere saying so.
>
> This survived the first mutation sweep. The harness could not kill it because the harness's own
> "no unpublished body was discarded" assertion consults the same baseline, so an inflated baseline
> excuses itself; it needs two arrivals and a direct assertion, which is
> `SyncEngineTest.the baseline advances against the record that arrived, not the merged result`.
> Worth recording as a hazard for anyone writing the Room-backed `SyncStore`: the baseline is not
> "the newest content clock this row has ever held".

---

## 4. The envelope, and the rollback check

This is the item the architecture doc flags as "easy to get wrong" and leaves un-owned. It is owned
here by `RecordCodec`, and it is the only place allowed to call `RecordEnvelope.open`.

### What is actually bound to what

`RecordEnvelope.seal/open` take `(kContent, blindedId, …)` and build the AAD from `ver ‖ blindedId`,
length-prefixed. **Nothing about a record travels beside its envelope except the blinded ID it is
filed under and the `seq` the server assigns it.** `recType` and `hlc` used to be arguments and AAD
components, which forced both onto the wire — a caller can only rebuild the AAD from values it holds
*before* decrypting — and the server, which never read either, stored them in the clear. They are
inside the sealed payload now.

So there is no outer `hlc` and therefore no outer-versus-inner comparison. The architecture doc
describes one; it no longer has anything to compare, and it must not be reintroduced as though it
were a security control. What replaces it is stronger on both counts:

- The clock the client sorts and merges on comes out of **authenticated plaintext**. Previously the
  client read the outer copy, and only an internal client-side assertion made the two agree.
- `recType` is still bound, because it is part of the blinded-ID HMAC message
  (`HMAC(K_id, recType ‖ ":" ‖ uuid)`) and `blindedId` both selects the per-record key and is the
  AAD. `RecordCodec` must **recompute** `BlindedRecordId.compute(kId, payload.recType, payload.uuid)`
  after opening and refuse the record unless it equals the blinded ID the record arrived under. That
  restores exactly the binding the AAD used to give, and adds the record `uuid`, which the AAD never
  covered.

### What actually defends against a rollback

A server restored from a backup, or a malicious one, can replay an *older authentic* envelope. **The
AAD never defended against this and could not**: a replayed version is exactly the tuple the client
sealed, so the tag verifies. That was as true when `hlc` was in the AAD as it is now — the binding
stopped the server *mislabelling* an envelope with another version's clock, an attack that no longer
exists because there is no outer label to mislabel.

Three checks, all in `RecordCodec.open`:

```kotlin
fun open(remote: RecordDto, local: SyncRecord?): OpenResult {
    val payload = RecordEnvelope.open(kContent, remote.blindedId, bytes)
        ?: return OpenResult.Unopenable          // wrong key, tampering, or a foreign account
    if (BlindedRecordId.compute(kId, payload.recType, payload.uuid) != remote.blindedId)
        return OpenResult.Mislabelled            // this payload is not the record it was filed as
    if (local != null && !local.dirty && payload.rowHlc < local.rowHlc)
        return OpenResult.Rollback               // the server handed back something we already superseded
    return OpenResult.Ok(payload)
}
```

The third check is the real rollback defence. `local.dirty` matters: if the row *is* dirty, a lower
remote clock is the ordinary "we have a newer local edit" case and the merge handles it. If the row
is clean, the only way its clock got ahead of the server's is that the server went backwards.

Its blind spot is worth stating: a record the client has **never seen** has no local clock to
compare against, so an old version of a record this device does not know about is undetectable at
the record level. `GET /v1/changes` covers the whole-server case that produces it — the server
answers `409 cursor_ahead_of_server` when the client's cursor exceeds its high-water mark, which is
what a restored-from-backup server looks like from outside. Both must halt the engine loudly
(§8, F7).

### Sealing

```
plaintext  = Json.encodeToString(NotePayload(...))        // carries recType, uuid and the row clock
blindedId  = BlindedRecordId.compute(kId, "note", uuid)   // raw UUID never leaves the device
envelope   = RecordEnvelope.seal(kContent, blindedId, plaintext)
```

`RecordEnvelope.seal` pads to 4 KiB buckets itself, which is sized so that a whole short note fits
one bucket — see `SyncProtocol.PADDING_BUCKET_BYTES`. `recType` is `"note"` or `"folder"` and goes
into the blinded-ID HMAC message and into the payload; it is not sent separately and the server has
no opinion about it. The row clock goes into the payload too. Neither has a server-enforced length
bound any more, because neither reaches the server.

The `hlc` node component is no longer visible to the operator, so the per-account-pseudonym
mitigation in D4 is no longer load-bearing for privacy. Keep a pseudonym anyway if it is already
built — it is still the right value for a tie-breaker, and it costs nothing — but the reason to have
one is now hygiene rather than disclosure.

---

## 5. Merge: field-level LWW with per-field HLCs

### 5.1 The payload

```kotlin
@Serializable
data class NotePayload(
    val v: Int = 1,                      // payload schema version
    val recType: String,                 // "note". Checked against the blinded ID on open (§4).
    val uuid: String,                    // the local record UUID. Never leaves here unsealed.
    val hlc: String,                     // the row clock (§4)
    val fields: Map<String, String>,     // canonical field name -> value, as text
    val clocks: Map<String, String>,     // canonical field name -> that field's Hlc
    val del: Boolean = false,            // tombstone. THE ONLY DELETE THE PROTOCOL HAS.
    val serializer: Int = 1,             // contentSerializerVersion, see 5.5
)
```

Field names for a note: `title`, `content`, `contentFormat`, `checklist`, `isPinned`, `isFavorite`,
`folderId`, `createdAt`, `updatedAt`, `isDeleted`, `deletedAt`. For a folder: `name`, `colorArgb`,
`createdAt`, `updatedAt`, `isDeleted`, `deletedAt`.

> **[SHIPPED as `:core-sync-codec`, and it is ONE implementation for both apps.]** The payload
> format was written first on the desktop (`desktop/store/RecordPayloadCodec.kt`, PR #87) and moved
> into a shared module unchanged, because a note written on the phone has to be readable on the
> laptop **byte for byte** and two implementations of one format is the failure this project keeps
> meeting. `RecordPayloadWireFormatTest` pins the exact bytes of a note and a folder, so a change to
> either end is a red test rather than an account nobody can open. The desktop's copy should be
> deleted in favour of this module when `desktop-integration` lands.
>
> The module also owns `SyncRecords`, the payload ⇄ `SyncRecord` conversion, and it is **lossy in
> one direction**: the payload carries `createdAt` (this section lists it, and the desktop's decoder
> refuses a payload without it) and `SyncRecord` does not, because `FieldClocks.NOTE_FIELDS`
> deliberately excludes it. A device receiving a record for the first time therefore has to choose a
> `createdAt`, and it chooses the record's `updatedAt` — the convention `ConflictCopies` already
> settled for the same problem. **This is a real gap, not a resolved one**: a note created on one
> device and edited before it reaches a second one carries a later `createdAt` there, so the two
> devices disagree about the "newest created" order. Closing it means giving `createdAt` a clock and
> a place in `RecordType.fields`, which is a change to the merge's field set and to every fixture
> that builds a record. See `RecordRows.createdAtFor`.

Decode with `ignoreUnknownKeys = false` and **refuse the record** on a `v` this build does not know,
rather than silently dropping fields — the "silent field loss when an older app re-serialises a
newer payload" risk in the architecture doc's table. A refused record must not advance `dirty` or
`lastSyncedSeq`; count it, surface it, and stop syncing rather than round-trip a lossy copy.

### 5.2 The rule

For each field independently: take the value whose clock is greater. `Hlc` compares
`(ms, counter, node)` lexicographically, so ties break deterministically on `node` and two devices
writing in the same millisecond cannot diverge. A field absent from `clocks` is at the row clock.

Pin-on-phone plus edit-on-tablet merges losslessly, which is the whole reason for field-level rather
than record-level LWW: those metadata gestures are exactly what people do casually on two devices.

### 5.3 Fields that must move together

- **`content` and `contentFormat` are one unit.** `NoteDao.kt:69-70` and
  `SingleNoteViewModel.kt:723-725` both state they must never drift; a body read with the wrong
  parser is silent corruption. Give them one shared clock entry, `content`, and merge them together
  or not at all.
- **`updatedAt` follows `content`.** When the remote `content` wins, take the remote `updatedAt`
  too. Otherwise two devices show the same notes in a different order forever — a visible divergence
  in the one field the user actually looks at.
  Implemented as an **effective clock**: each side offers its `updatedAt` at
  `max(its own updatedAt clock, its own content clock)`, and the higher offer wins. That keeps the
  rule true without breaking the two cases a blunter "the content winner decides" would break —
  `clearFolder` bumps `updatedAt` without touching the body, and the merged record's own offer has
  to reproduce, or the rule stops being idempotent. On a folder there is no `content` and it
  degenerates to an ordinary field. See `Merge.mergeUpdatedAt`.
- **`isDeleted` and `deletedAt` are one unit**, for the same reason.

### 5.4 Deletion

`del` is an ordinary LWW field. Because the delete is *soft*, the deleting device still holds the
content, so an undelete is a genuine restore rather than a blank note.

Four hard `DELETE`s exist today and each is a resurrection bug waiting to happen:
`purgeNote` (`NoteDao.kt:115-116`), `purgeNotesDeletedBefore`, `purgeFolder`,
`purgeFoldersDeletedBefore`.

- The blank-note discard (`SingleNoteViewModel.kt:536`) may purge **only** while the row has never
  been pushed: `WHERE id = :id AND dirty = 1 AND lastSyncedSeq = 0`. Otherwise soft-delete it.
- Age-based purge is not safe on its own. `TrashViewModel.kt:43-59` only sweeps when the user opens
  the Trash screen, so one device purges daily and another never does — the worst case for
  convergence. A tombstone may be purged only once **every enrolled device's cursor has passed the
  tombstone's `seq`**, which the client cannot know, or the staleness threshold must be enforced as
  a hard refusal to sync (§10, decision D3).

### 5.5 Fields that merge as a whole blob, and why

`checklist` has no stable item identity in storage: `parseChecklist` (`feature-notes/.../model/single/ChecklistItem.kt:37-46`)
mints a fresh UUID per line on every read. `mergeChecklist` (`SingleNoteViewModel.kt:170-186`)
preserves ids positionally within a live editor session and concedes in its own KDoc (`:167`) that *"a
genuinely reordered list does get fresh ids"*. So the checklist merges as one LWW value. Accept it
and say so in the UI copy if it ever bites.

`content` is HTML, and `serializer` exists because `richeditor-compose` **1.1.0** (the pinned
version as of `master` @ `e8bf8f3`, bumped from `1.0.0-rc14`) serialises the same content to
different bytes than rc14 did. A device that receives a record with a different `serializer` must
compare **decoded text**, not bytes, before deciding the content changed. A version bump is an
explicit, once-only, offline re-baseline: re-serialise locally, mark rows `dirty` **without**
bumping their HLC or `updatedAt`, then push throttled. It is not a sync event.

### 5.6 The write path — do not reuse `upsertNote`

`upsertNote`'s conflict branch **deliberately refuses to write `isFavorite`, `isDeleted` and
`deletedAt`** (`NoteDao.kt:62-90`): *"the conflict branch leaves isDeleted/deletedAt exactly as it
found them. So a save that races a delete cannot resurrect the note."* That is correct for the
editor and **fatal for sync** — applying a merged remote record through it silently drops exactly
the fields a remote delete or a remote favourite carries.

Write a dedicated `applyRemoteNote` / `applyRemoteFolder` that writes **every** column including the
sync columns, and make it the only path a merged record takes. Also delete the dead
`insertNote` (`NoteDao.kt:60`): it is `@Insert(REPLACE)`, REPLACE is DELETE-then-INSERT, it has
no callers, and it would wipe `createdAt`, both tombstone columns and every sync column.

> **[SHIPPED — `RoomSyncStore.applyMerged` is the only caller of `applyRemoteNote`.]** `insertNote`
> was already gone. The merged row and its conflict copy go in one `withTransaction`, because a
> winner written without the copy holding the body it displaced is the one outcome the whole
> conflict-copy design exists to prevent.
>
> **A second, symmetrical problem was found by running two devices, and it is in `saveNote`.**
> `upsertNote` writes six values on every save because it is one statement, and the repository was
> claiming a fresh clock for all six — including the ones it merely copied back out of its own stale
> row. That is the mirror image of the bug this section describes: not a write path that refuses a
> field it should write, but a write path that *asserts authorship* of a field it did not change.
> The consequence is the same shape and worse: pin a note on the phone, type into it on the tablet
> before the tablet has seen the pin, and the tablet's save carries `isPinned = false` at a newer
> clock and the pin is discarded. `RoomNotesRepository.savedNoteFields` now compares the row against
> the note being saved and stamps only what actually changed; `savedFolderFields` does the same for
> a rename racing a recolour. It costs one full-row read per autosave, taken deliberately —
> comparing only the cheap columns would leave `content` always claimed and re-open the same hole
> for the mirror gesture.
>
> `SAVE_NOTE_FIELDS`'s own KDoc already stated the rule ("listing a field here claims a clock for a
> value this write never set") and applied it only to the fields the statement omits. This applies
> it to the fields the statement writes unchanged, which is the same rule.

### 5.7 Idempotence, and how to be sure of it

Re-applying an already-applied remote record must produce `NoChange` and must not mark the row
dirty. This is not a nicety — §3.3 shows it is hit in production by any crash between commit and
cursor persistence, and by every retry after a dropped response. The N-replica harness in
`e2e-sync-open-questions.md` §3 exists mainly to hammer this and the commutativity property
(`merge(a,b) == merge(b,a)`), and both are free once `Merge` is pure.

---

## 6. Conflict copies

A conflict copy is written **only** when the `content` field is contested on both sides:

```
local.dirty AND local.clocks["content"] and remote.clocks["content"] are incomparable-in-practice
  -- i.e. both devices edited content since their last common ancestor, which is detectable as
     "the row is dirty in `content` AND the remote content clock is not an ancestor of ours"
```

Everything else merges silently. When it fires:

1. The **higher clock wins** and stays under the original UUID, so the record's identity on the
   server is stable and the other device converges on it.
2. The **loser is written as a new local note** with a fresh UUID, `dirty = 1`,
   `lastSyncedSeq = 0`, and title `"<original title> (conflict — <device label>, <dd MMM HH:mm>)"`.
   It pushes on the next pass and appears on every device.
3. Nothing is ever discarded. That is the whole point, and it is the mitigation for the highest-
   severity risk in the architecture doc: a merge bug that propagates in seconds to every device
   with no undo.

The conflict copy carries the loser's `content`, `contentFormat` and `checklist` and **not** its
metadata — no pin, no favourite, no folder. A duplicate note appearing pinned at the top of the list
is worse than one appearing in Recent.

Deduplicate: if a conflict copy for `(uuid, loserContentHlc)` already exists locally, do not write a
second one. Two devices can otherwise each write a copy of the other's loser and the account gains
two duplicates per conflict instead of one.

> **[IMPLEMENTED 2026-08-31 — three deviations, each forced by convergence.]** `Merge` and
> `ConflictCopies` in `core-domain/.../sync/` implement this section, with the following changes.
> All three were found by the convergence harness (`ConvergenceTest`), not by review.
>
> 1. **The copy's uuid is derived, not fresh:**
>    `UUID.nameUUIDFromBytes("manana/sync/v1/conflict-copy|<uuid>|<loserContentHlc>")`. Both devices
>    resolving one conflict then build the *same* record, so the deduplication rule above costs
>    nothing — the second copy to be pushed simply CASes into the first. A fresh random uuid makes
>    the two copies un-foldable, which is the two-duplicates-per-conflict outcome this section is
>    trying to avoid.
> 2. **The title suffix is the constant `" (conflict copy)"`, not `"(conflict — <device label>,
>    <dd MMM HH:mm>)"`.** A device label is per device and a formatted local time is per timezone,
>    so putting either into a **synced** field makes the two devices' copies differ permanently in
>    the one column the user reads. Provenance is not lost: the copy's row clock *is* the loser's
>    content clock, so it carries the minting node and the millisecond, and a UI can render them
>    locally without the two devices having to agree on how.
> 3. **An empty losing body produces no copy.** The rule is "never silently discard a user's text";
>    an empty body is not text. Without the exemption, a note that exists on two devices before
>    either has typed into it — the blank row `createNewNote` persists, or a note first touched by
>    a pin — gains a duplicate containing nothing.

---

## 7. Scheduling: foreground only, and why there is no choice

`DataModule.provideNoteDatabase` throws when `currentPassphrase()` is null
(`DataModule.kt:62`), and `MainApplication.onStop` locks (`MainApplication.kt:41`). A cold
start while locked therefore cannot open the database at all.

Sync *appears* to work after a lock today, because `NoteDatabase` is a Hilt `@Singleton` so the
provider never re-runs, and SQLCipher's `SupportOpenHelperFactory` retains the passphrase internally
— a limitation documented at `DataModule.kt:64-69` and explicitly slated for removal. **Do not build
on it.** A background sync that works only until that bug is fixed is a background sync that breaks
in a release nobody connects to it.

So:

- **Triggers:** on successful unlock; on a timer while the app is foregrounded (60 s is plenty); on
  pull-to-refresh in the notes list; after a successful pairing.
- **Scope:** an app-scoped `CoroutineScope(SupervisorJob() + Dispatchers.IO)` in `MainApplication`,
  injected into `SyncCoordinator`. Not `viewModelScope` — navigation cancels it mid-push, and §3.3
  shows what a cancelled push costs.
- **Gate:** `SyncCoordinator` checks `SecureUnlockManager` before every pass and returns
  `Skipped(Locked)` rather than touching the database. It must also **stop between chunks** when a
  lock arrives, not only at pass boundaries.
- **Mutual exclusion:** one pass at a time, a `Mutex` in the coordinator. Two overlapping passes
  would both read `dirty` rows and both push them, and the second would take a `409` against the
  first.
- **`Room` invalidation is not ordered against the write coroutine** (`SingleNoteViewModel.kt:664-675`:
  *"writer-first is near-certain in practice, not enforced here"*). A sync engine writing behind the
  user's back makes that a live race with the editor's 300 ms autosave. Applying a remote record to
  a note that is currently open must go through the editor's own merge path, or be deferred while
  that note has an open editor. **This is unresolved and is decision D5.**
- Background sync via a ciphertext outbox is Phase 5 and is explicitly out of scope. Do not
  half-build it.

> **[SHIPPED, minus the timer.]** `AppScopeModule` provides the
> `CoroutineScope(SupervisorJob() + Dispatchers.IO)` this section asks for, `SyncOnUnlock` collects
> `SecureUnlockManager.unlocked` on it and starts a pass on every unlock, and pull-to-refresh on the
> notes list runs one and waits for it. `DefaultSyncController` holds the gate (five separate
> preconditions, each with its own sentence for the user), the `Mutex`, and the account keys for
> exactly one pass — `AccountRootKey.derive` per pass, `destroy()` in a `finally`, rather than a
> `K_content` living in a `@Singleton` across every lock.
>
> **The 60-second foreground timer is deliberately not built.** Two triggers is enough to make sync
> real, and a timer is the piece most likely to be wrong in a way nobody notices — a pass every
> minute against a server that is rate-limiting, on a device whose screen is on but idle. It is one
> `while (isActive) { delay(60_000); requestSync() }` on the scope that already exists, and it
> should be added with a decision about what pauses it.
>
> **Nothing here relaxes the lock.** `MainApplication.onStop` still locks; a pass that starts after
> a background event finds no keys and reports `SyncPassState.Unavailable`.
>
> **D5 is still open and is still the one most likely to be discovered the expensive way.** A remote
> record applied to a note that is currently open goes straight into the row behind the editor's
> live state, exactly as this section warns. Nothing defers it and nothing routes it through the
> editor's merge path.

---

## 8. Failure modes

| # | Failure | Detection | Response |
|---|---|---|---|
| F1 | envelope will not open | `RecordEnvelope.open` returns null | count it, skip the record, **do not advance the cursor past it**; if more than a handful, halt and surface "records from this account cannot be read" |
| F2 | payload `v` is newer than this build | version check in `RecordCodec` | halt the engine; never round-trip a lossily decoded payload |
| F3 | the opened payload's `(recType, uuid)` does not hash to the blinded ID it arrived under | §4 check | halt; a server cannot produce this without the ARK, so it is a client bug and must be loud in a bug report, not silently repaired |
| F4 | `409` on push | per-item `status: "conflict"` | merge the inline `current`, keep `dirty`, retry next pass |
| F5 | `429` | HTTP status | honour `Retry-After` **with jitter**; three devices without jitter form a herd against one VPS |
| F6 | `401` mid-pass | HTTP status | re-handshake once, retry the call once, else abandon the pass |
| F7 | server rolled back | `409 cursor_ahead_of_server`, or the §4 rollback check | **halt the whole engine** and require an explicit user re-baseline. Never silently reset the cursor to 0: with `dirty = 0` rows that is indistinguishable from "the account is empty" and the next pass would be a mass delete |
| F8 | clock moved backwards | `HlcGenerator` sees `wallMs < lastMs` | keep `ms`, increment `counter`. Never emit a decreasing clock. The codebase already defends against a user-settable clock in `LockoutPolicy.remainingMillis` and `TrashPolicy.isExpired` |
| F9 | process death mid-pass | none needed | §3.3; idempotent merge covers all three cases |
| F10 | `richeditor` serialiser bump | `serializer` field differs | offline re-baseline, throttled push, no HLC bump (§5.5) |
| F11 | ARK missing or regenerated | `ark_ct` absent while `accountId` is known | refuse to sync and say so; a second `generateArk()` forks the account into two permanently unreadable halves |

> **[IMPLEMENTED: F1–F7 and F9.]** `SyncEngine` handles them as `HaltReason` (F2, F3, F7, a revoked
> device, and F1 once past `UNREADABLE_RECORD_LIMIT` = 5), `SyncOutcome.Deferred` (F5 with jitter,
> and every network failure) or ordinary flow (F4, F9). Each has one named test in `SyncEngineTest`.
>
> **F1's exact shape is worth restating**, because "count it, skip the record, do not advance the
> cursor past it" is three requirements in one line and the third is the one that matters: the
> engine still applies the readable records *after* the fault — re-applying them next pass is free,
> because the merge is idempotent — but the persisted cursor freezes at the record before it and
> paging stops. Nothing beyond a fault is ever recorded as delivered.
>
> **F8, F10 and F11 are not the engine's.** F8 is `HlcGenerator`'s and was already built; F10 is an
> offline re-baseline, deliberately not a sync event; F11 needs `SecureUnlockManager` and is above
> this layer.

---

## 9. Testing

Three tiers, in the order they pay for themselves:

1. **`Merge` unit tests and the N-replica convergence property** — pure JVM, no Android, no
   emulator, seeded schedules, print the seed on failure. The recipe is in
   `e2e-sync-open-questions.md` §3 and it is a days-not-weeks job **once `Merge` is pure**. It
   catches non-commutative merges, non-idempotent apply, three-replica order dependence, HLC ties,
   and deletion losing to a stale field write.

   > **[BUILT, and it now drives the coordinator too.]** `ConvergenceTest` and its harness live in
   > `core-sync-engine/src/jvmTest/`, not in `core-domain` — they moved there with `SyncEngine`,
   > because `core-domain` cannot depend on a module that depends on it. `Replica` no longer
   > hand-rolls a pull/push/apply loop; it implements `SyncStore` and `SyncTransport` and lets the
   > real engine drive. Two implementations of a loop whose whole job is to agree is the bug class
   > this project keeps meeting, and the harness's copy would have been the one that never ran in
   > production and therefore never got fixed.
   >
   > What the simulation still cannot reach is unchanged and is listed in
   > `e2e-sync-open-questions.md` §3: no crypto (records cross the transport seam as plaintext, so a
   > convergence failure is never confused with a decryption failure), no Room, no HTTP, no
   > lifecycle. The engine's own rule tests — the cursor rules, the halts, the `429`, the batch
   > splitting — are in `SyncEngineTest`, because a simulation over plaintext records never produces
   > a record that will not open.
2. **A contract test against the real server.** `server/` builds and runs standalone, so a JVM test
   in `core-sync` can start it on a random port with `MANANA_DB=:memory:` and drive the real HTTP
   surface. This is the only thing that catches a byte-level disagreement in the signed-message
   encoding, the SEC1 point encoding or the base64url variant — the failures that a fake server,
   written from the same misunderstanding as the client, will happily agree with.
3. **Two emulators, one scripted happy path.** `emulator -avd … -read-only` twice off the single
   installed `android-33` image. Pair, edit on both, converge, verify. A smoke test for real Room,
   real SQLCipher and the lifecycle races the simulation cannot reach — **not** the convergence
   proof. A convergence bug found here is one tier 1 should have found and did not.

---

## 10. Open decisions

These are the ones that should be settled before code is written, not discovered during it.

**D1 — HTTP client.** Ktor client, OkHttp, or `HttpURLConnection`? OkHttp brings certificate pinning
for `ServerHint.spkiPinSha256` for free and is the most boring option; Ktor client would share
serialisation code with the server. Either adds the app's **second** dependency-with-a-transitive-graph
and its first network permission. Recommendation: OkHttp, for the pinning.

**D2 — Does `accountId` claim happen at ARK creation or at first sync?** Claiming at creation means
a device that never syncs has still touched the server. Claiming at first sync means the TOFU race
between two freshly paired devices is real, and one of them gets `409 account_exists` and must
proceed as if it had been vouched for. Recommendation: claim at first sync, and treat
`409 account_exists` on `POST /v1/account` as a normal branch, not an error.

**D3 — Tombstone purge policy.** The server has no delete, so a tombstone pushed once is on the
server until its history depth expires it. Locally, purging on age alone is unsafe (§5.4). The two
candidates are "never purge tombstones that have been pushed" (simple, unbounded) and "refuse to
sync a device that has been offline longer than the retention window, and force a re-baseline"
(bounded, needs a re-baseline path that does not exist). **Unresolved.**

**D4 — HLC node identity.** The original reason for insisting on a per-account random pseudonym was
that the `hlc` string was plaintext to the operator. It no longer is — the clock is inside the sealed
payload (§4) — so this is now a question about the *account's own devices* rather than about the
server. A pseudonym is still the better default: it keeps a device identifier out of a value that
gets copied into every payload and every conflict copy. What is *not* settled is whether it should
be rotated when a device is revoked — rotating loses the tie-breaking history, not rotating means a
revoked device's edits stay attributable **to the other paired devices**.

**D5 — Applying a remote record to the note that is currently open.** The editor holds live state
behind two chained 300 ms trailing debounces and `Room`'s invalidation is not ordered against the
write coroutine. Options: defer remote writes for the open note until it closes (simple, can stall
indefinitely); route them through the editor's existing `mergeChecklist`-style path (correct,
invasive); or take the remote version and write the local one as a conflict copy (safe, noisy).
**Unresolved, and it is the one most likely to be discovered the expensive way.**

**D6 — Does restoring a folder need to restore its notes?** `deleteFolder` unfiles its notes and
nothing records which they were (`RoomNotesRepository.kt:104-116`, `FolderDao.kt:46`), so device A restoring a folder and device B
re-filing a note into it do not compose. Either accept it explicitly in the UI, or make
`clearFolder` remember the prior `folderId`. That is a **Phase 0** change, not a Phase 3 one, so it
has to be decided before the migration is written.

**D7 — Conflict-copy detection.** §6 describes the condition as "both devices edited `content` since
their last common ancestor", but the schema carries no ancestor — only `dirty` and one field clock.
The proposed test (`local.dirty` and the remote content clock is not one this device has already
seen) is sound but conservative: it produces a conflict copy in some cases where the two edits were
actually the same. Whether that is acceptable, or whether a per-field `lastSyncedHlc` is worth a
column, is open.

> **[STILL OPEN, but the merge is built for either answer.]** `Merge.merge` takes
> `LocalRecord.contentBaseline: Hlc?` — the `content` clock of the last version this device and the
> server agreed on. **Null** means no ancestor is recorded and the merge falls back to exactly the
> conservative rule above. **Non-null** makes the test precise, and the difference is measurable:
> `ConvergenceTest.aBaselineIsWhatStopsAPinFromCostingADuplicateNote` runs one schedule both ways
> and shows a pin costing a duplicate note in one and nothing in the other.
>
> Convergence holds either way — `convergenceHoldsWithoutContentBaselines` is a separate sweep for
> exactly that reason — so this is a **noise** decision, not a correctness one. Closing it means one
> more column (`contentSyncedHlc`) and passing it; nothing in the merge changes.
>
> Worth stating plainly, because it is the reason this cannot be solved in the merge: an HLC is a
> **total** order and therefore cannot express concurrency. "Both devices edited since their last
> common ancestor" is not derivable from two clocks, however carefully they are compared. It needs
> the ancestor written down, and one clock per record is the smallest form of that.

> **[CLOSED — `notes.contentSyncedHlc`, schema v8.]** The column is written by
> `RoomSyncStore.applyMerged`, `recordSeen` and `acknowledgePush`, from `Baselines.advance`, and
> read back by `load`. Nothing in the merge changed, as this section predicted.
>
> The decision to close it rather than ship the conservative fallback is a noise decision made
> against a concrete case: `TwoDeviceSyncTest.a pin does not cost a duplicate note` pins a note on
> one device while the other edits the body, over two real Room databases, and asserts one note
> rather than two. Without the column that test produces a duplicate — which is the exact gesture
> §5.2 names as the reason the design is field-level at all.
>
> `''` is the recorded absence and is **not** `Hlc.ZERO`; see the §2 block above for why the
> difference is the whole safety of the migration.
