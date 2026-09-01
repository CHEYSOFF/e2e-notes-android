# The desktop foundation

What `:desktop` is, what it deliberately does differently from the phone, and where the seams are
for the pairing flow and the two-pane UI.

This is the layer *beneath* the UI: a window that opens, takes a passphrase, unlocks an encrypted
local store, and hands out a `my.cheysoff.core_domain.repository.NotesRepository`. Everything above
that — the note list, the editor, the two panes — is not here.

---

## 1. The module

`:desktop` is plain Kotlin/JVM. It is the only module in the build with no Android plugin at all,
and it reaches the rest of the project through the two multiplatform modules:

| Dependency | For |
| --- | --- |
| `:core-domain` | `Note`, `Folder`, `NotesRepository`, `Hlc`/`HlcGenerator`, `FieldClocks`, `TrashPolicy` |
| `:core-crypto-shared` | `PassphraseCipher`, `ArkCipher`, `AccountRootKey`, `RecordEnvelope`, `BlindedRecordId`, `HlcNode` |

No crypto is written in this module. Every primitive comes from `:core-crypto-shared`, so a record
sealed on the desktop and a record sealed on the phone are the same bytes.

Packaging is configured for **msi**, **dmg** and **deb**. jpackage builds the installer of the host
OS only, so on this machine `packageMsi` is the one that runs; the macOS block is filled in anyway,
because a bundle ID that changes after a release is a different application to the OS and the first
person with a Mac should not have to invent one. Signing and notarization are not configured — a
half-configured signing block fails the build on every machine without a Developer ID certificate.

---

## 2. Unlock: a passphrase, not the phone's PIN

**On the phone**, `PassphraseCipher` wraps the database passphrase under PBKDF2(PIN), and that wrap
lives in `EncryptedSharedPreferences` — a file encrypted under a master key in the hardware Keystore
that cannot be exported. Copying the file yields ciphertext whose key is not in it. The only attack
is on-device, one guess at a time, through `LockoutPolicy`. Six digits is enough.

**On the desktop** there is no such anchor. `vault.json` is an ordinary file: copy it and every
input to the derivation is in the attacker's hands. The entire cost of a guess is then the PBKDF2
work factor — a linear cost against an exponentially small keyspace.

The arithmetic is worse than "hours on a GPU". PBKDF2-HMAC-SHA256 at `i` iterations costs `2i`
SHA-256 compressions per guess. A single current consumer GPU does on the order of 2×10^10
SHA-256/s, so at the phone's 210 000 iterations it tests ~5×10^4 candidates per second. A six-digit
PIN is 10^6 candidates: **about twenty seconds**. At 600 000 iterations it is about a minute. No
iteration count fixes a keyspace that small — surviving one GPU for a day would need ~10^9
iterations, which is minutes of unlock time on the user's own machine.

So the fix is a bigger secret, not a bigger work factor. `PassphrasePolicy` enforces two **shape**
rules and claims nothing more: at least 12 characters, and not digits alone (a PIN with extra
digits). It cannot measure entropy and does not pretend to; the first-run screen says so in words.

The iteration count is raised to **600 000** anyway — the current OWASP figure, against the phone's
210 000, which is the previous one. It buys a factor of 2.9: nothing against a weak passphrase and
unnecessary against a strong one, but it is the only cost an offline guess pays here and it costs
about a third of a second once per launch. `PinWrap` records the count it was made with and
`unwrapWithPin` derives with `wrap.iterations`, so changing it never strands an existing vault. That
is also what made the parameter safe to add to `PassphraseCipher.wrapWithPin`, whose default is
unchanged and whose Android callers are untouched.

### Key hierarchy

```
passphrase --PBKDF2(600k)--> K_wrap --AES-256-GCM--> vaultKey (32 random bytes)
vaultKey   --HKDF---------> K_arkwrap --AES-256-GCM--> ARK
ARK        --HKDF---------> K_content, K_id, accountId      (AccountRootKey)
ARK + deviceId --HKDF-----> hlcNode                          (HlcNode)
```

The random `vaultKey` in the middle is the same shape `SecureUnlockManager` uses, for the same
reason: the phone needs one stable key that both the PIN path and the biometric path can produce,
and the desktop needs one that both the passphrase path and the OS credential store can produce.

`vault.json` holds both wraps plus the KDF parameters and a random per-install `deviceId`. It leaks
the salt, the IVs, the iteration count, and that a Manana vault exists. Nothing that identifies the
account.

---

## 3. The OS credential store

A convenience layer over the passphrase, never a replacement. Behind one interface
(`CredentialStore`) with three implementations:

| Host | Implementation | Notes |
| --- | --- | --- |
| Windows | `DpapiCredentialStore` | `Crypt32.CryptProtectData` via JNA; the blob is written to `remember.dpapi` in the vault directory |
| macOS | `MacKeychainCredentialStore` | `security -i` with the command on stdin, so the secret is never in the argument vector |
| anything else | `NoCredentialStore` | says false and stores nothing |

Two rules hold across all of them, and both are tested:

1. **The passphrase wrap is never removed.** A vault copied to another machine still opens with the
   passphrase. A DPAPI blob does not travel and a Keychain item does not travel; if either were the
   only copy of the key, moving the directory would destroy it.
2. **A failure costs convenience and nothing else.** `remember` returning false, `recall` returning
   null, an administrator resetting the Windows password (which discards the DPAPI master key) — all
   of them end at the passphrase prompt, and the false from `remember` is shown to the user rather
   than swallowed. There is deliberately **no** file-backed fallback: a fallback for a credential
   store is a place to put a secret the OS is not protecting.

Linux gets `NoCredentialStore` rather than a Secret Service client. libsecret is present on GNOME
and KDE and absent on a headless box, so a real implementation needs a runtime probe *and* this
fallback; shipping the fallback alone is honest about what has been built.

**The macOS implementation has never run on a Mac.** Its tests drive an injected command runner and
prove the argument construction and the failure handling; they prove nothing about macOS.

---

## 4. The local store is record-shaped

```sql
records(blinded_id TEXT PRIMARY KEY, envelope BLOB NOT NULL, dirty INTEGER NOT NULL, last_synced_seq INTEGER)
```

The same shape `server/.../SyncStore.kt` holds. Each row is a `RecordEnvelope`: AES-256-GCM under a
per-record key derived from `K_content`, padded to 4 KiB buckets, filed under
`base64url(HMAC(K_id, recType ‖ ":" ‖ uuid)[0..16])`. There is no SQLCipher and none is wanted — a
file-level cipher would be a second, weaker layer over content that is already sealed.

Two things follow, and both are the reason:

- **At-rest security on desktop equals on-the-wire security.** Someone who copies `records.db` has
  exactly what the server operator has, and the server operator is the adversary this protocol was
  designed against.
- **The desktop is a sync replica by construction.** The sync engine will push these rows and store
  what it pulls into these rows. Nothing translates between a local schema and a wire format, so
  there is no translation to get wrong.

`last_synced_seq` is NULL rather than 0 for "the server has no version of this record"; the phone
spells the same state as 0 because its column is `NOT NULL`. Invisible to the protocol — `seq` is
the server's counter and starts at 1.

### What it costs

**No SQL over note content.** `WHERE title LIKE ?` is not expressible. Search, sort and filter all
happen in memory over decrypted records.

**Everything is decrypted at unlock.** Opening the vault is O(records): one HKDF and one AES-GCM
open each, plus 4 KiB of padding stripped per record. Roughly 30 µs per record on the development
machine — a thousand notes is about 30 ms, ten thousand about a third of a second.

**The memory cost is the sharper limit.** Every note's plaintext is resident for the whole session:
ten thousand notes averaging 2 KB is about 20 MB of heap, a hundred thousand is 200 MB plus the
padding overhead of reading them.

**Where this stops being fine** is therefore somewhere in the tens of thousands of notes. The fix
when it comes is not a different table — it is decrypting lazily and keeping an in-memory index of
just the fields the list screen shows, which is a change local to `RecordNotesRepository`.

### The payload

`RecordPayloadCodec` implements `docs/design/e2e-sync-phase3-plan.md` §5.1. The phone's
`RecordCodec` does not exist yet, so **this is the first implementation of that format** and it is
written to the plan rather than to what was convenient, so the phone can adopt it unchanged. Two
places where the plan needed a decision made:

- **`del` versus the `isDeleted` column.** §5.1 carries both. Rather than pick one and diverge, both
  are written and a payload where they disagree is refused — the redundancy is a consistency check
  instead of a second source of truth.
- **`serializer`.** Version 1 is `richeditor-compose` 1.1.0's `toHtml()`. A record at a version this
  build does not implement is refused, not re-saved, because rc14 and 1.1.0 escape text differently.

Decoding is strict, per the plan: an unknown top-level key, an unknown column or an unknown clock
field is a refused record, never a silently dropped field. That is the "silent field loss when an
older app re-serialises a newer payload" risk from the architecture doc, and a tolerant decoder plus
a re-serialise is exactly how it happens.

### The repository

`RecordNotesRepository` implements `NotesRepository` — the interface the Android app already uses,
unchanged. Reads are projections of an in-memory snapshot; writes take one mutex, re-seal the whole
record and put one row.

The write rules are transcribed from `RoomNotesRepository` and `NoteDao` rather than reinvented,
including the ones that look like quirks: `saveNote` does not own `isFavorite` or the tombstone,
`deleteNote` is guarded on `isDeleted = 0` so a second delete cannot restart the retention window,
`restoreNote` is deliberately *not* guarded, a folder delete stamps the folder and every unfiled
note with one clock, and the `ORDER BY` clauses are reproduced with their tie-breakers. Field clocks
go through `FieldClocks.serialize`/`stamp`/`parse` — the string round trip is wasted work and that
is the point: `stamp`'s rule is subtle enough that a second implementation would be a second thing
to keep correct.

Records that will not open are counted in `LoadDiagnostics` and otherwise left completely alone:
not deleted, not repaired, not re-sealed. Three of the four kinds name a record whose plaintext some
*other* build can still read.

`purgeNote` and `purgeFolder` destroy the row, inheriting the resurrection hazard
`e2e-sync-phase3-plan.md` §5.4 names for the phone's four hard `DELETE`s. Inherited rather than
solved on purpose: diverging here would mean two devices with different ideas of what "delete
forever" does.

---

## 5. First run must not quietly fork the account

The ARK is created once, on the first device, and reaches others by pairing. A desktop that mints
its own has not joined the account — it has started a second one, and the two can never merge.

`AccountRootKey.generateArk()` is called in **exactly one place**: `DesktopVault.setUp`, and only
when `AccountOrigin.CREATED_HERE` was asked for and no header exists. The guard is the file, not an
in-memory flag, because the file is what a second process and a second launch both see.

`UnlockResult.Damaged` is a distinct, terminal outcome for a header that will not parse or an ARK
that will not unwrap, and **nothing repairs it**. On a device that has never paired, that header
holds the only copy of the account key; an app that offers to "reset" it destroys an account while
looking helpful. The damaged screen has no buttons.

The first-run screen leads with pairing and makes standalone a text link behind a dialog that says
what it does in plain words. That is the only moment at which the choice is reversible.

### The seam for pairing

```kotlin
vault.setUp(passphrase, AccountOrigin.PAIRED, arkFromThePhone)
```

Already implemented, already tested (`a paired setup adopts the supplied ARK rather than minting
one`), and it refuses to mint a key of its own — passing `PAIRED` without an ARK is an
`IllegalArgumentException`, not a silent standalone setup. What the pairing agent adds is the
transport that produces `arkFromThePhone`, and a button on `FirstRunScreen` that is currently
disabled.

---

## 6. What is left for the UI agent

- `AppController.Screen.Open.repository` is a `NotesRepository`. Not a wrapper, not a desktop
  variant — the same type every existing ViewModel is written against.
- `UnlockedScreen` is a placeholder and is labelled as one in its own KDoc. It exists so the
  foundation could be driven end to end.
- `ui/Theme.kt` **copies** seven colour values from `core-ui/.../theme/Color.kt`, because `:core-ui`
  is an Android library and cannot be on this module's classpath. That duplication is a design-system
  decision (move the palette to a multiplatform module, or pin the copy with a test), so this module
  holds the smallest set the three foundation screens need and no typography scale, shape system or
  component styling to have to un-duplicate later.
