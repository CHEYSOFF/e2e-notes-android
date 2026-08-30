# E2E-encrypted multi-device sync — feasibility & architecture

> Status: **research / not scheduled.** Written 2026-08-30 against `master` @ `00df3e4`.
> No implementation exists. The recommended first step (a user-facing Trash feature)
> is deliberately independent of sync — see "Recommended first slice".

## Context

Mañana currently stores notes in a SQLCipher database whose 32-byte key is generated randomly on-device, wrapped by a 6-digit PIN (PBKDF2-210k + AES-GCM) and optionally by a biometric Keystore key. There is no account, no server, no recovery, and no network code of any kind — the manifest declares **zero permissions**.

The goal is to sync notes between the user's own Android devices through a **self-hosted server that never sees plaintext or keys**, with devices paired **directly** (QR code, device-to-device) so the "no recovery, server knows nothing" posture is preserved.

This document is the research deliverable: is it feasible, what does it cost, and what should be built first. **No implementation is proposed yet.**

---

## Verdict

**Feasible. The cryptography is the easy part** — a few hundred lines of pure-JVM code using primitives already on the platform. The work is elsewhere:

1. **The schema has no concept of change.** No tombstones, no folder timestamps, no revision, no device identity — and by deliberate design (PR #32) four metadata writes leave *no trace at all*. This is the bulk of the effort and it is entirely offline, testable work.
2. **A guaranteed data-corruption bug on day one** (verified below) that must be fixed before a single byte syncs.
3. **A pre-existing vulnerability that sync would amplify** from one device to the whole account.

---

## Three findings that drive the design

### 1. Blank notes would resurrect forever — verified

`NotesListViewModel.createNewNote()` **persists a blank note before navigating**:

```kotlin
val newNote = Note(id = UUID.randomUUID().toString(), title = "", content = "")
notesRepository.saveNote(newNote)      // committed to the DB
_events.send(NavigateToNote(newNote.id))
```

If the user backs out without typing, `SingleNoteViewModel:213` **hard-deletes** it. That is the only `deleteNote` call site in the app; there is no user-facing note delete (only `DeleteFolder`).

With sync and no tombstone, the sequence is: blank row committed → pushed to server → user backs out → local `DELETE` → next pull **resurrects the blank note**, on every device, forever. This is certain, not hypothetical, and it is why tombstones are non-negotiable.

### 2. The locked-database constraint — with an important correction

`DataModule.provideNoteDatabase` throws when `currentPassphrase()` is null, and `MainApplication` locks on `onStop`. But the precise behaviour is subtler than "no DB while locked":

- **Cold start while locked** → the first DB access throws. Real hard constraint.
- **After any unlock in the process** → `NoteDatabase` is a Hilt `@Singleton`, so the provider never re-runs, and SQLCipher's `SupportOpenHelperFactory` *retains* the passphrase internally (a limitation already documented at `DataModule.kt:64-69`). The DB therefore keeps working after `lock()`.

So background sync is *accidentally* possible today — but only by depending on a known limitation that is already slated for removal. **Do not build on it.** Design for foreground-only sync (§7).

### 3. `allowBackup` + a 6-digit PIN — fix before sync, not after

`AndroidManifest.xml` has `allowBackup="true"` with both rules files left as empty AS templates, so `secret_shared_prefs` (holding `pin_ct`, `pin_salt`, `pin_iv`, `pin_iters`) is in scope for Auto Backup and D2D transfer. The PIN is 6 digits over PBKDF2-210k — an offline attacker who obtains `pin_ct` exhausts the keyspace quickly, and `LockoutPolicy` is app-side code they never run.

Today that costs one device's notes. **After sync, the same `pin_ct` unwraps the account root key and therefore every device's notes, retrievable from the server indefinitely.** Sync converts a per-device weakness into a whole-account one.

*(Mitigating factor: the Keystore `MasterKey` is non-exportable, so a restored prefs file is already undecryptable on a new device — that is exactly what `SecureUnlockManager.wasStateReset` handles. Excluding the files makes this explicit rather than incidental and removes the exfiltration path.)*

---

## Key hierarchy

**Do not sync the SQLCipher passphrase.** It is a whole-*file* key, not a record key; device B already has its own (overwriting it makes B's local DB unopenable); and a shared file key can never be rotated without every device rekeying simultaneously.

Introduce a separate root:

```
ARK — Account Root Key, 32 bytes, SecureRandom, created ONCE on the first device.
      Never sent to the server in any form.
  ├─ K_content = HKDF(ARK, "manana/sync/v1/content")     record AEAD key
  ├─ K_id      = HKDF(ARK, "manana/sync/v1/recordid")    blinded record IDs
  └─ accountId = HKDF(ARK, "manana/sync/v1/account")     server-visible handle (128 bits)

dbPassphrase — unchanged, per-device.
```

`accountId` being a one-way derivation makes trust-on-first-use account claiming safe: nobody can squat an account they cannot name.

**Store ARK wrapped under the device's existing DB passphrase:** `K_arkwrap = HKDF(dbPassphrase, "…/arkwrap")`, stored as `ark_ct`/`ark_iv` in `secret_shared_prefs`. This means ARK becomes available at exactly the moment the DB does, via **both** PIN and biometric unlock, with **zero changes to the unlock flows** — no parallel biometric wrap, no second migration. `SecureUnlockManager` gains one `currentArk()` method mirroring `currentPassphrase()`.

Derive **per-record keys** `HKDF(K_content, blindedId)` — nearly free, and it removes any GCM nonce-reuse concern entirely.

**Worth surfacing in the UI:** a second paired device becomes the only backup that exists. Lose your PIN on device A and device B still holds ARK and a full replica.

---

## Pairing: two QR codes, fully offline

The QR code *is* an authenticated channel — exploit that rather than layering a SAS over a server-mediated exchange.

```
Device B (new)                          Device A (has ARK)
1. ephemeral P-256 (eB,EB); sid=16B
   QR1 = {sid, EB, serverUrl, spkiPin}  ──scan──▶
                                        2. ephemeral (eA,EA); Z=ECDH(eA,EB)
                                           Ks = HKDF(Z, salt=sid, info=…‖EA‖EB)
                                           seal = AES-GCM(ARK‖accountId‖cfg) under Ks,
                                                  AAD = "manana/pair/v1"‖sid
                        ◀──scan──          QR2 = {sid, EA, nonce, seal}
3. derive Ks, open seal (abort loudly on tag failure)
4. both show 6-digit SAS from HKDF(ARK, sid, "…/confirm"); user compares
5. B wraps ARK under HKDF(its OWN dbPassphrase); provisions its device key
```

**Why not one QR carrying ARK directly** (10% of the work): the entire account, forever, with no possible rotation, rendered as pixels on a screen. One photograph is permanent total compromise. Out of character for this app. The second scan removes the failure mode.

MITM is structurally impossible here — the only key B must authenticate is `EB`, which A obtains by *looking at B's screen*. The SAS is therefore a mis-scan/wrong-phone check, not the MITM defence. `sid` in both the HKDF salt and the GCM AAD gives replay protection; both QRs expire after ~120s; `FLAG_SECURE` on both screens.

**Primitives — all available at minSdk 31 from google()/mavenCentral():**

| Need | Choice | Note |
|---|---|---|
| ECDH | **P-256 via plain JCA** (`KeyPairGenerator("EC")` + `KeyAgreement("ECDH")`) | platform since API 23, **zero dependencies** |
| HKDF-SHA256 | **hand-roll** in `core-crypto` over `javax.crypto.Mac`, ~30 lines | keeps the pure-JVM/unit-testable property `PassphraseCipher` already has; RFC 5869 vectors are public |
| Record AEAD | AES-256-GCM via JCA | already used |
| Device identity key | **EC P-256 in AndroidKeyStore**, `SHA256withECDSA` | Keystore Ed25519 is API 33+, above our floor |
| QR render | `com.google.zxing:core` (pure Java) → Compose `ImageBitmap` | avoid `zxing-android-embedded` (drags in an Activity) |
| QR scan | CameraX + ZXing over the `ImageAnalysis` Y-plane | avoid ML Kit: +2.5 MB and a Google dependency in an app whose premise is talking to nobody |

⚠️ **Deliberately not X25519**: JCA `"XDH"` support arrived around API 33, *above* minSdk 31. Verify before choosing 25519 over P-256. Also add an explicit on-curve check when decoding the peer's public key rather than trusting `KeyFactory` to reject invalid points.

**New permissions: `INTERNET` and `CAMERA`** — taking the app from zero to two. If "zero permissions" is a trust property worth keeping, a product flavour split is possible but carries real CI cost.

---

## Record envelope

```
envelope := ver(1B) ‖ nonce(12B) ‖ ciphertext ‖ tag(16B)
key      := HKDF(K_content, "manana/rec/v1" ‖ blindedId)      per-record
nonce    := 12 random bytes                                   NOT a counter
AAD      := ver ‖ recType ‖ blindedId ‖ hlc
blindedId:= HMAC(K_id, recType‖":"‖uuid)[0..16]  base64url    raw UUID never leaves the device
```

Payload is versioned JSON (kotlinx-serialization) with **per-field HLCs**, plus a `del` tombstone flag and padding to 256-byte buckets (kills the "shopping list vs. diary entry" size distinction).

Binding `hlc` into the AAD is what lets a client reject a **server rollback** — the old blob is genuinely authentic, so AEAD alone cannot detect it. ⚠️ The `hlc` must also travel *outside* the envelope (the client reads it before decrypting), so the client **must** compare outer against inner after decryption and reject mismatches. Easy to get wrong.

**Random nonces, not counters:** with per-record keys each key sees ~1 message per version, so the birthday bound is a non-issue — while a counter restored from a backup would silently and catastrophically break that record.

---

## Schema gaps — and the insight that dissolves the PR #32 tension

**Blocking (sync is incorrect or destructive without these):**

| Gap | Fix |
|---|---|
| No note tombstones | `isDeleted`, `deletedAt`; soft-delete in `RoomNotesRepository`; `WHERE isDeleted = 0` in reads |
| No folder tombstones | same on `folders` (deletion here is user-facing) |
| `clearFolder` leaves no trace | the mass `UPDATE … SET folderId = NULL` must bump revisions, or un-filing never propagates and other devices re-file into a deleted folder |
| `FolderEntity` has **no timestamps at all** | add `createdAt`/`updatedAt`/`hlc`/tombstone fields |
| No device identity | `deviceId` in `secret_shared_prefs` (**not** the DB — must be readable while locked) |
| Wall-clock ordering | Hybrid Logical Clock `(ms, counter, deviceId)` — `System.currentTimeMillis()` is user-settable, which the codebase *already* defends against in `lockoutRemainingMillis()` |
| No dirty tracking | `dirty`, `lastSyncedSeq` per row; `sync_state` table |

**The key insight — `updatedAt` and the sync clock are different fields.**

PR #32 deliberately made `setNotePinned`/`setNoteFavorite`/`setNoteFolder` *not* bump `updatedAt`, so pinning doesn't reorder a newest-first list. That looks like a head-on collision with sync's need for a version marker. It isn't — `updatedAt` is being asked to answer two different questions. **Split them:**

- **`updatedAt`** — unchanged. Bumped only by `upsertNote`. Drives `ORDER BY updatedAt DESC`. PR #32's behaviour preserved bit-for-bit.
- **`hlc`** — new. Bumped by **every** write including the four metadata paths. Never read by the UI, never used for sorting.

So `setNotePinned` becomes `UPDATE notes SET isPinned=?, hlcMs=?, hlcC=?, dirty=1 WHERE id=?`. List order untouched, sync gets its marker. This dissolves the tension completely.

**Nice-to-have:** stable checklist item IDs (today `parseChecklist` mints a fresh UUID per line on *every read* and throws it away — items have no identity even across reloads); `exportSchema = true` before adding migrations to a DB you cannot recreate.

All changes are additive `ALTER TABLE … ADD COLUMN` with defaults — the safe kind, matching the existing migration pattern.

---

## Conflict resolution

The data shape is close to worst-case for automatic merging: `content` is an **HTML blob**, `checklist` is a line-oriented blob **with no stable item IDs**.

**Recommended: field-level LWW registers + CAS conflict detection + conflict copies.**

- Each field carries its own HLC; merge field-by-field taking the higher clock. Pin-on-phone + edit-on-tablet merges **losslessly** — whole-record LWW would throw one away, and those metadata ops are exactly what users do casually on multiple devices.
- Detect real conflicts structurally, not by guessing: each row remembers `lastSyncedSeq`, push sends it as `baseSeq`, server rejects with `409` if it moved. A 409 on a dirty row is an unambiguous conflict.
- **If `content` itself is contested on both sides, write the loser out as a conflict copy** (`"Title (conflict — Pixel 7, 30 Aug 14:32)"`, new UUID). Unglamorous, and correct — nothing is ever silently discarded.
- Deletion is just another LWW field. Because delete is *soft*, the deleting device still holds the content, so a resurrection is a genuine undelete rather than a blank note.

**Rejected: a text CRDT over `content`.** No dependency-light JVM implementation (Automerge ships per-ABI JNI natives on top of the SQLCipher `.so`s), and more fundamentally the field is **HTML** — character-level merge of concurrent markup edits reliably produces malformed markup. A proper tree CRDT plus a mapping to `richeditor-compose`'s serialization is a multi-month project.

**What this costs, plainly:** editing the same note body on two offline devices produces a conflict copy, not a merge. Checklists merge as a whole blob until item IDs are stable.

---

## The locked-database problem

**Recommended for v1: foreground-only sync.** Sync on unlock, on a timer while foregrounded, and on pull-to-refresh.

Not a compromise — for a personal notes app across 2–3 devices you open the app *because* you want a note, so a sync completing in the first few hundred ms of the session is nearly indistinguishable from continuous sync. Security model unchanged: no new key material at rest, no lock relaxation.

Needs one clean addition: an **app-scoped `CoroutineScope`** (or a `@Singleton` coordinator with a `SupervisorJob`), because today all async work is `viewModelScope` and would be cancelled mid-sync by navigation.

**Deferred (phase 5, only if foreground proves insufficient): a ciphertext outbox/inbox.** The insight is that *uploading and downloading do not need the content key* — seal records while unlocked, let a WorkManager job move the sealed envelopes while locked, decrypt on next unlock. Architecturally clean; costs a no-user-auth Keystore signing key (a thief with an unlocked device could pull account ciphertext — useless without ARK, but it makes revocation matter).

**Rejected: relaxing lock-on-background.** It is one of the app's genuinely strong properties and it is cheap. Trading it for sync latency is a bad exchange.

---

## Server contract (the user implements this)

A dumb, append-only, per-account blob store with optimistic concurrency — implementable in ~500 lines over SQLite. It never learns what a note is.

**Auth without ever seeing a key:** TOFU account claim on the unguessable `accountId`, then **device enrolment by vouching** — an enrolled device signs `("authorize", accountId, newPubKey, ts)` with its Keystore EC key. The server ends up holding a set of public keys and **no secrets at all**, so a full server compromise yields zero write or impersonation ability. Session tokens (24h) avoid an ECDSA op per request.

```
POST   /v1/account                 claim accountId, enrol first device
POST   /v1/devices/authorize       vouched enrolment
GET    /v1/devices                 list;  DELETE /v1/devices/{id}  revoke
POST   /v1/session                 challenge → signature → bearer token
GET    /v1/changes?since=<cursor>  incremental pull, ordered by per-account monotonic seq
POST   /v1/records                 batch upsert with baseSeq CAS; per-item ok/conflict,
                                   conflicting envelope returned inline
GET    /v1/records/{id}/history    last N versions — the safety net against a client merge bug
```

There is deliberately **no delete endpoint** — deletes are ordinary upserts whose payload carries `del: true`. The server cannot tell the difference, which is both the privacy and the simplicity property.

The cursor must be the server's **monotonic `seq`, not a timestamp** (timestamps collide and go backwards). Rate-limit and return `429` with `Retry-After`, honoured with jitter — otherwise three devices synchronise their retries into a herd against the user's own VPS.

**What the server operator still learns:** record count, approximate sizes (mitigated by padding), which record changed and when, edit frequency and time-of-day patterns, device count. State this honestly; it cannot be eliminated.

---

## Effort

| Phase | Content | Est. |
|---|---|---|
| 0 | Schema & change tracking (tombstones, HLC, dirty, deviceId, migrations) | 3–5 d |
| 1 | Crypto core (HKDF, ARK, envelope, blinded IDs) — all pure-JVM | 3–4 d |
| 2 | Pairing (CameraX, QR, ECDH, SAS, device key) | 5–8 d |
| 3 | **Sync engine** (coordinator, push/pull, CAS, merge, conflict copies) | 8–12 d |
| 4 | Server (user writes; parallel with 1–3) | 3–5 d |
| 5 | Background sync (optional) | 4–6 d |
| 6 | Hardening (`allowBackup`, PIN length, cert pinning, revocation UI) | 3–5 d |

**~29–45 engineering days.** Phase 3 is where estimates go wrong — assume it doubles.

---

## Recommended first slice: ship "Trash", not sync

**Phase 0 alone, as a user-facing Trash feature.** Soft delete replaces hard delete; a Trash screen lists deleted notes and folders with Restore and Delete Forever; auto-purge after 30 days.

Why this is the right first move:

- **Standalone value today.** Deleting a folder currently destroys it and un-files its notes irreversibly behind one confirm dialog, with no undo.
- It lands the **entire blocking set** — tombstones, folder timestamps, the `updatedAt`/`hlc` split, `deviceId`, the `clearFolder` fix — with **zero cryptography, zero networking, and no new permissions**.
- Every piece is JVM-testable. No camera, no server, no second device.
- **If sync is never built, none of it is wasted.**

Second slice would be Phase 1 (crypto core), for the same reason: entirely pure-JVM and fully verifiable before a byte crosses a network, in the style `PassphraseCipher` already establishes.

---

## Risks

| Risk | Severity | Mitigation |
|---|---|---|
| **A merge bug propagates everywhere in seconds.** Today a bug damages one device; with sync it wipes the account on all devices with no undo. | Highest | server-side version history; conflict copies (never discard); a local snapshot before first pull; a dry-run mode |
| `allowBackup` + 6-digit PIN (§3 above) — sync widens it from one device to the whole account | High | fix **before** sync: exclude the prefs/DB from backup, allow a longer PIN |
| Tombstone purge resurrection — a device offline longer than the purge window restores everything it deleted | High | refuse to sync past a staleness threshold; force a re-baseline |
| Silent field loss when an older app version re-serialises a newer payload (`ignoreUnknownKeys` drops fields) | High | refuse to sync on payload-version mismatch; never silently downgrade |
| ARK regenerated by accident — forks the account into two undecryptable halves | High | same "created in exactly ONE place" discipline as `setupPin`, plus a test that generation is unreachable when `ark_ct` exists |
| Nonce reuse if someone later "optimises" to counters | Catastrophic if hit | random nonces + per-record keys; document why |

**Genuinely hard:** the sync engine's state machine (partial pushes, mid-sync process death, a remote update landing while the 300 ms autosave debounce is mid-flight on an open note); multi-device convergence testing (no integration-test infrastructure exists — the `androidTest` dirs hold only `ExampleInstrumentedTest`).

**Verify before relying on:** the exact API level for JCA `"XDH"`; whether `richeditor-compose` rc14's HTML output round-trips byte-stably across app versions — if it re-serialises differently after an update, **every note on every device looks dirty at once** and stampedes the server. Cheap to test, expensive to discover late.

---

## Open questions before any of this is scheduled

1. **Is the `richeditor-compose` HTML round-trip byte-stable?** If `setHtml(x)` → `toHtml()` does not reproduce `x`, the first edit to each note rewrites its stored HTML. Worse, if a *library upgrade* changes the serialization, every note on every device goes dirty at once and stampedes the server.

   Attempted to probe this from the UI (open a note, type a character, delete it, observe whether the note re-sorts). **It does not isolate the variable** — the 300 ms autosave fires on each keystroke, so a save happens regardless of whether the round-trip is stable, and the SQLCipher database cannot be read from outside the app to compare bytes. Answering this properly needs a small instrumented test asserting `toHtml(setHtml(x)) == x` over representative note bodies, plus a repeat after any `richeditor-compose` version bump. Worth writing before Phase 3.
2. **Does the Trash feature stand on its own?** It is recommended here as the first slice precisely because it does — but it should be justified as a product decision, not smuggled in as sync groundwork.
3. **Is "zero declared permissions" a property worth preserving?** Sync needs `INTERNET` and `CAMERA`. A product-flavour split can keep an offline variant, at real CI cost.

## Files this would eventually touch

- `core-crypto/…/SecureUnlockManager.kt` — ARK generation, `ark_ct` wrap, `currentArk()`
- `core-data/…/local/NoteDao.kt` — `hlc`/`dirty` bump on all six write paths; soft delete; `WHERE isDeleted = 0`
- `core-data/…/local/NoteDatabase.kt` — `MIGRATION_4_5`, `exportSchema = true`; `FolderEntity` gains its whole timestamp set
- `core-data/…/di/DataModule.kt` — the locked-DB constraint lives here
- `app/…/MainApplication.kt` — where an app-scoped sync scope would go
- `app/src/main/AndroidManifest.xml` — `allowBackup` fix first; `INTERNET` + `CAMERA` later
