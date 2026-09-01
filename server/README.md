# Mañana sync server

Phase 4 of [`docs/design/e2e-sync-architecture.md`](../docs/design/e2e-sync-architecture.md): a dumb,
append-only, per-account blob store with optimistic concurrency. **It never learns what a note is.**

It stores sealed envelopes under blinded record IDs, hands them back in the order it received them,
and refuses a write whose compare-and-set base has moved. It cannot decrypt anything, it does not
parse an envelope, and it holds no secret that would let an attacker who reads its database write or
impersonate a device.

---

## This build is standalone, on purpose

`server/` has its own `settings.gradle.kts`, its own Gradle wrapper, and **is not listed in the
repository root's `settings.gradle.kts`**. It is not an Android module and nothing in the Android
build references it. `./gradlew :app:assembleDebug` from the repository root behaves identically
whether or not this directory exists.

Build and test it from inside `server/`:

```
cd server
./gradlew test          # 123 tests
./gradlew run           # starts on 127.0.0.1:8080
./gradlew installDist   # build/install/manana-sync-server/bin/manana-sync-server
```

Requires a JDK 17 or newer. Dependencies resolve through the same Google-hosted Maven Central mirror
the Android build uses, which is why `settings.gradle.kts` lists it first — see the comment there.

## Stack

Kotlin/JVM, [Ktor](https://ktor.io) on the CIO engine, SQLite through `org.xerial:sqlite-jdbc`,
`kotlinx.serialization` for JSON. Six dependencies, one module, no ORM, no migration framework, no
DI container. The schema is six `CREATE TABLE IF NOT EXISTS` statements executed at start-up and
every query is a hand-written parameterised statement.

Ktor was chosen over a servlet container or a hand-rolled `com.sun.net.httpserver` because the
project's owner is a Kotlin developer who will maintain this, because `ktor-server-test-host` makes
every endpoint testable in-process without binding a port, and because its routing DSL is small
enough that the whole HTTP surface fits in one readable file. SQLite was chosen because the workload
is one person's notes on two or three devices: a single file, no server to run alongside this one,
and a `.db` an operator can copy as a backup.

## Running it for real

```
MANANA_HOST=127.0.0.1 MANANA_PORT=8080 MANANA_DB=/var/lib/manana/sync.db \
  build/install/manana-sync-server/bin/manana-sync-server
```

**This process speaks plain HTTP and must sit behind a TLS-terminating reverse proxy.** Bearer
tokens and sealed envelopes both travel in the clear over a bare HTTP hop; the envelopes are useless
to an eavesdropper, the tokens are not. It binds `127.0.0.1` by default so that exposing it directly
has to be a deliberate act (`MANANA_HOST=0.0.0.0`), and it logs a warning when you make that act.

An nginx front end:

```nginx
server {
    listen 443 ssl http2;
    server_name notes.example.com;

    ssl_certificate     /etc/letsencrypt/live/notes.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/notes.example.com/privkey.pem;

    client_max_body_size 8m;   # must exceed MANANA_MAX_REQUEST_BYTES

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}
```

> **Note on rate limiting behind a proxy.** The rate limiter keys unauthenticated requests on the
> address Ktor reports for the connection. Behind a proxy that is the proxy's own address, so every
> unauthenticated caller shares one bucket. Authenticated requests take a second permit keyed on the
> account, which is unaffected. If you expose this to more than your own devices, rate-limit at the
> proxy as well.

### Configuration

Every value is optional; an unparseable one stops start-up rather than silently falling back.

| Variable | Default | Meaning |
|---|---|---|
| `MANANA_HOST` | `127.0.0.1` | bind address |
| `MANANA_PORT` | `8080` | bind port |
| `MANANA_DB` | `sync.db` | SQLite file, or `:memory:` |
| `MANANA_MAX_REQUEST_BYTES` | `4194304` | hard cap on any request body |
| `MANANA_MAX_ENVELOPE_BYTES` | `262144` | hard cap on one sealed envelope |
| `MANANA_MAX_BATCH_ITEMS` | `64` | items in one `POST /v1/records` |
| `MANANA_HISTORY_DEPTH` | `10` | versions retained per record |
| `MANANA_SIGNATURE_WINDOW_MS` | `300000` | how far a signed request's `ts` may be from server time |
| `MANANA_SESSION_TTL_MS` | `86400000` | bearer token lifetime |
| `MANANA_RATE_LIMIT_PER_MINUTE` | `120` | token-bucket refill rate |
| `MANANA_RATE_LIMIT_BURST` | `120` | token-bucket capacity |
| `MANANA_PAIRING_TTL_MS` | `120000` | how long a deposited pairing bundle stays collectable |
| `MANANA_MAX_PAIRING_BLOB_BYTES` | `8192` | hard cap on one pairing bundle, decoded |
| `MANANA_MAX_LIVE_PAIRINGS` | `1000` | unexpired pairings allowed at once, across every caller |
| `MANANA_PAIRING_DEPOSIT_PER_MINUTE` | `6` | deposit-only bucket, per IP. An honest pairing uses one |
| `MANANA_PAIRING_DEPOSIT_BURST` | `6` | deposit-only bucket capacity |
| `MANANA_DEBUG` | unset | `1` enables `DEBUG` log lines |

### Backups

Copy `sync.db` (and its `-wal` file, or checkpoint first by stopping the process). The file contains
only ciphertext and a little structural metadata — see the privacy section — so it does not need to
be treated as more sensitive than the server itself. It is **not** a backup of the user's notes in
any useful sense: it is undecryptable without the Account Root Key, which exists only on the paired
devices.

The file deliberately holds **no per-version timestamps**. A copy of it shows the order in which an
account's edits arrived, because `seq` is monotonic, but not the times of day they arrived at. That
narrows an old backup or a stolen disk to "this record was edited nine times"; it does not stop an
operator who watches the live traffic, and it does not stop one who chooses to add their own
logging.

---

## Pointing a client at it

Set the base URL to wherever the proxy terminates, e.g. `https://notes.example.com`. The QR pairing
payload already carries a `ServerHint` -- a `url` and an optional `spkiPinSha256`
(`feature-pairing/.../protocol/PairingWire.kt:278-297`) -- so a second device learns the address
from the first.

The flow a client performs, in order:

1. **First device** — derive `accountId = HKDF(ARK, "manana/sync/v1/account")`, then
   `POST /v1/account` signing `("claim", accountId, devicePublicKey, ts)` with its own Keystore
   key. Keep the returned `deviceId`.
2. **Every later device** — pair over QR to obtain the ARK, then have the *existing* device call
   `POST /v1/devices/authorize`, signing `("authorize", accountId, newPubKey, ts)`. Keep the
   returned `deviceId`.
3. **Each session** — `POST /v1/session/challenge`, sign the returned challenge, `POST /v1/session`,
   use the returned token as `Authorization: Bearer …` for 24 hours.
4. **Sync** — `GET /v1/changes?since=<cursor>` then `POST /v1/records` with a `baseSeq` per item.

### The canonical signed message — implement this exactly

Every signature is `SHA256withECDSA` (DER, base64url) over these bytes, and the server builds them
the same way in `SignedMessage.kt`. Getting one byte wrong means every signature is rejected, and
there is no negotiation step to fall back to.

```
message := lp("manana/sync/v1/sig") ‖ lp(purpose) ‖ lp(field₁) ‖ … ‖ lp(fieldₙ)
lp(s)   := uint16be(len(utf8(s))) ‖ utf8(s)

claim     : purpose = "claim",     fields = accountId, devicePublicKeyB64, ts (decimal string)
authorize : purpose = "authorize", fields = accountId, newPublicKeyB64,    ts (decimal string)
session   : purpose = "session",   fields = accountId, deviceId,           challenge
```

Every field is length-prefixed so the encoding is injective: without the prefixes,
`("authorize", "AB", "C")` and `("authorize", "A", "BC")` are the same bytes, and a signature over
one would verify as the other. The domain string is prefixed for the same reason, and `purpose` is
its own field so a `session` signature can never be replayed as an `authorize`.

Public keys are SEC1 **uncompressed** P-256 points — `0x04 ‖ X(32) ‖ Y(32)`, 65 bytes — base64url
encoded. That is exactly what `feature-pairing/.../protocol/P256.encodePublicKey` emits. Binary
fields (public keys, signatures, envelopes) are all unpadded base64url, RFC 4648 §5.

### Endpoints

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `GET` | `/healthz` | none | liveness; returns `{"status":"ok","version":…}` and nothing else |
| `POST` | `/v1/account` | self-signed | TOFU claim; enrols the first device. `201`, or `409 account_exists` |
| `POST` | `/v1/devices/authorize` | voucher signature | vouched enrolment. `201`, or `409 device_exists` |
| `GET` | `/v1/devices` | bearer | list devices, including revoked ones |
| `DELETE` | `/v1/devices/{id}` | bearer | revoke; kills that device's sessions in the same transaction |
| `POST` | `/v1/session/challenge` | none | single-use nonce for a device to sign |
| `POST` | `/v1/session` | challenge signature | returns a 24h bearer token |
| `GET` | `/v1/changes?since=&limit=` | bearer | incremental pull, ordered by `seq` |
| `POST` | `/v1/records` | bearer | batch upsert with per-item `baseSeq` CAS |
| `GET` | `/v1/records/{id}/history?limit=` | bearer | the last N versions of one record |
| `POST` | `/v1/pair/{sid}` | none | leave one sealed pairing bundle. `201`, or `409 pairing_exists` |
| `GET` | `/v1/pair/{sid}` | none | collect it, once. `200`, or `404 no_pairing` |

`POST /v1/session` is two round trips rather than one because a challenge/response handshake needs
two; the design document's single `POST /v1/session` line is implemented as
`/v1/session/challenge` followed by `/v1/session`.

### The pairing rendezvous

The two `/v1/pair/{sid}` routes are a dead drop with a two-minute lease, and they are the only
unauthenticated routes that store anything. They exist because a **laptop has no camera**.

Two phones pair entirely offline: the new device shows a QR code, the device holding the account key
scans it and shows a second QR code carrying the sealed account key, and the new device scans that.
No server is involved and none of it reaches this file. That flow is unchanged.

A laptop breaks the second scan. So the second leg — and only the second leg — comes through here:
the phone POSTs the **byte-for-byte payload of the second QR code** under `sid`, and the laptop
polls for it. The first leg is untouched, which matters more than it sounds: the QR code remains an
authenticated visual channel, and it is the reason a man in the middle is structurally impossible.
The only key the phone has to authenticate is the laptop's ephemeral public key, and it gets it by a
human pointing a camera at the laptop's screen.

What this server holds in between is AES-256-GCM ciphertext under a key derived from an ECDH between
two ephemeral P-256 keys, one of which was only ever displayed on a screen. **There is no code here
that could open it and no key here that would help.** What it does learn is in "Can see" below, and
it is not nothing.

Rules, all of them enforced and each with a named test in `PairingRendezvousTest`:

| Rule | Value | Why |
|---|---|---|
| TTL | `MANANA_PAIRING_TTL_MS`, 120 s | Matches the client's own `CODE_TTL_MILLIS`. Measured from the deposit; the client refuses a late bundle on its own monotonic clock regardless. |
| Single use | collect deletes in the same transaction | Closes the window at the first successful read rather than at the TTL. Costs a retry: a collect whose *response* is lost has still consumed the blob, and the pairing restarts. |
| First write wins | `409` on a second deposit | Stops someone who guessed a `sid` from replacing a real bundle with a decoy, which would make a legitimate pairing die with the protocol's most alarming message. |
| Size cap | `MANANA_MAX_PAIRING_BLOB_BYTES`, 8 KiB | Deliberately looser than the ~4.5 KiB a maximal frame encodes to. This server does not parse the frame and must not start; the tight, protocol-derived check is the client's. |
| Global capacity | `MANANA_MAX_LIVE_PAIRINGS`, 1000 | The only bound that survives an attacker with many addresses. Caps the table at roughly 8 MB whoever is writing. Exceeded ⇒ `503 pairing_capacity`. |
| Deposit rate | `MANANA_PAIRING_DEPOSIT_PER_MINUTE`, 6/min/IP | Its own bucket, far tighter than the general limiter. An honest pairing deposits once; collecting polls dozens of times and stays on the general limiter. |
| `sid` shape | exactly 16 bytes, base64url | Pinned where a blinded record ID deliberately is not: a short `sid` would be a namespace small enough to sweep. |

**What an attacker who can guess or enumerate `sid` gets.** `sid` is 128 bits of `SecureRandom`, so
neither is a plan — but the answer should not rest on that. Collecting someone else's blob yields
ciphertext they cannot open and kills the pairing they interrupted: denial of service, not
disclosure. Depositing under a live `sid` is refused. Depositing at random to fill the disk is
bounded twice, per address and globally. None of it yields a note or a key.

**Over plain HTTP** an on-path attacker sees what this server sees and can additionally race the
collect or substitute the blob. Neither is a compromise — a stolen blob does not open without the
laptop's ephemeral private key, and a substituted one fails the GCM tag, which the client treats as
terminal and loud — but both are denial of service, and both are reasons the TLS proxy above is not
optional.

**There is no delete endpoint, and there must never be one.** A deletion is an ordinary upsert whose
*plaintext* carries a tombstone flag, sealed inside the envelope. The server cannot tell it apart
from an edit and has no code that could. That is simultaneously the privacy property — deletions are
not observable — and the simplicity property: there is one write path, so there is one thing to get
right.

### Errors

Every failure is `{"error":"<code>","message":"<safe text>"}` with a matching HTTP status. Codes in
use: `malformed_request`, `malformed_base64`, `invalid_account_id`, `invalid_public_key`,
`invalid_label`, `invalid_device_id`, `invalid_blinded_id`,
`invalid_base_seq`, `invalid_envelope`, `invalid_cursor`, `invalid_limit`, `empty_batch`,
`batch_too_large`, `duplicate_record_in_batch`, `payload_too_large`, `rate_limited`, `unauthorized`,
`bad_signature`, `bad_challenge`, `stale_timestamp`, `replay_detected`, `device_revoked`,
`unknown_device`, `unknown_record`, `account_exists`, `device_exists`, `cursor_ahead_of_server`,
`invalid_sid`, `invalid_sealed`, `sealed_too_large`, `pairing_exists`, `pairing_capacity`,
`no_pairing`, `internal_error`.

Request bodies are decoded **strictly**: an unknown JSON field is a `400`. On a client, ignoring
unknown fields loses user data quietly; on a server it means silently ignoring a field that a future
version may have made security-relevant.

### The cursor

`since` is the server's per-account monotonic `seq`, **never a timestamp**. `seq` is allocated inside
the same transaction that inserts the row it labels, so sequence numbers become visible to readers in
exactly the order they were allocated — otherwise a reader could observe seq 5 committed while seq 4
was still in flight, advance past 4, and never see that record again.

A pull returns each record's **head** version once, in `seq` order; superseded versions are reachable
only through the history endpoint. `nextCursor` is the largest `seq` returned (or `since`, when the
page is empty), and `hasMore` is true when the page was full.

A `since` greater than the account's high-water mark is answered with `409 cursor_ahead_of_server`
rather than an empty page. That happens when the server has been restored from an older backup or
when the client is pointed at a different server; both need the client to stop and re-baseline, and
"no changes" would let a rolled-back server look healthy indefinitely.

### Conflicts

Each item of `POST /v1/records` carries `baseSeq`, and is applied only if that still equals the
record's head `seq` — `0` asserting "this record does not exist yet". An item whose base has moved
comes back as `"status":"conflict"` with the blocking version's envelope inline, so the client can
merge without a second round trip. Items that did not conflict **are** applied: records are
independent, so refusing the whole batch would resend work that was never in conflict. The response
status is `409` if any item conflicted and `200` if none did; the per-item results have the same
shape either way.

### Rate limiting

A token bucket per key, in memory, refilling at `MANANA_RATE_LIMIT_PER_MINUTE`. Exhaustion is `429`
with a `Retry-After` in whole seconds, never `0`. Honour it **with jitter**: this is one person's VPS
and their devices all wake up together, so identical back-off schedules form a herd.

`POST /v1/pair/{sid}` draws on a **second, much tighter bucket** as well, keyed on the same address.
The two verbs are not alike: an honest pairing deposits exactly once, while the collecting side polls
every second and a half for up to two minutes. Sharing one bucket would either loosen the deposit
limit to the general rate or throttle every sync request down to the deposit rate. A deposit is also
the only unauthenticated request that makes this server *store* something, so it is the tap a
storage-exhaustion attempt has to come through.

---

## Storage schema

One SQLite file. Every statement in `SyncStore.kt` is parameterised; no SQL is ever built by
concatenating a value.

```sql
CREATE TABLE accounts (
    account_id TEXT    NOT NULL PRIMARY KEY,  -- base64url of 16 bytes; HKDF(ARK, ".../account")
    created_at INTEGER NOT NULL,              -- server clock, epoch ms
    last_seq   INTEGER NOT NULL               -- cursor high-water mark for this account
);

CREATE TABLE devices (
    account_id   TEXT    NOT NULL,
    device_id    TEXT    NOT NULL,            -- server-generated, 16 random bytes base64url
    public_key   BLOB    NOT NULL,            -- SEC1 uncompressed P-256, 65 bytes. A PUBLIC key.
    sealed_label BLOB    NOT NULL,            -- AES-GCM ciphertext. Constant length. Never opened.
    created_at   INTEGER NOT NULL,
    revoked_at   INTEGER,                     -- NULL while active
    PRIMARY KEY (account_id, device_id),
    FOREIGN KEY (account_id) REFERENCES accounts(account_id)
);
CREATE UNIQUE INDEX devices_key ON devices(account_id, public_key);

CREATE TABLE records (
    account_id  TEXT    NOT NULL,
    blinded_id  TEXT    NOT NULL,             -- HMAC(K_id, recType‖":"‖uuid)[0..16], base64url
    seq         INTEGER NOT NULL,             -- per-account monotonic; the cursor
    envelope    BLOB    NOT NULL,             -- sealed ciphertext. Never parsed. Never opened.
    PRIMARY KEY (account_id, blinded_id, seq),
    FOREIGN KEY (account_id) REFERENCES accounts(account_id)
);
CREATE INDEX records_by_seq ON records(account_id, seq);

CREATE TABLE sessions (
    token_hash TEXT    NOT NULL PRIMARY KEY,  -- SHA-256 hex of the bearer token. Not the token.
    account_id TEXT    NOT NULL,
    device_id  TEXT    NOT NULL,
    expires_at INTEGER NOT NULL
);

CREATE TABLE challenges (
    challenge  TEXT    NOT NULL PRIMARY KEY,  -- 32 random bytes base64url; deleted on first use
    account_id TEXT    NOT NULL,
    device_id  TEXT    NOT NULL,
    expires_at INTEGER NOT NULL
);

CREATE TABLE used_signatures (
    message_hash TEXT    NOT NULL PRIMARY KEY, -- SHA-256 of the canonical signed message
    expires_at   INTEGER NOT NULL
);

CREATE TABLE pairings (
    sid        TEXT    NOT NULL PRIMARY KEY,   -- base64url of 16 bytes, chosen by the NEW device
    sealed     TEXT    NOT NULL,               -- base64url ciphertext. Never opened, never parsed.
    expires_at INTEGER NOT NULL                -- deposit + MANANA_PAIRING_TTL_MS
);
CREATE INDEX pairings_by_expiry ON pairings(expires_at);
```

**`pairings` has no `account_id`, and cannot.** At the moment a blob is deposited the collecting
device does not have an account yet — that is the entire point of a pairing — so there is nothing to
scope the row to and no foreign key to hang it on. `sid` is 16 random bytes the new device minted
for one attempt and it names nothing else.

`sid` is stored as it arrives, where a session token is stored digested. The asymmetry is
deliberate: a token is a credential the server can verify without holding, so hashing it means a
database read yields nothing usable. Here the row already contains the blob that `sid` would
retrieve, so digesting the key would be protecting a secret against someone holding the thing it
unlocks.

**`records` has four columns because four is what the server acts on**, and that is the rule the
table is maintained under. It once carried `rec_type` and `hlc` as well: both were length-checked
on the way in, stored, and handed back unread — never a predicate, never a comparison, never an
input to a conflict decision. A column the server only carries is a column an operator can read for
free, so both moved inside the envelope. `received_at` went the other way: the server stamped it
rather than being told it, and nothing read it either, so it was dropped outright. **Do not add a
column here that no query reads.** `MetadataTest` in the test suite is what holds that line.

`records` is append-only: a new version is a new row with a new `seq`. The only rows the server ever
deletes are housekeeping — expired challenges, expired sessions, expired replay-cache entries, and
record versions beyond `MANANA_HISTORY_DEPTH`. Nothing deletes a record.

---

## What a server operator can and cannot see

This is the honest list. It is not softened. Everything on it has been checked against the code
rather than against the design, and where a leak was removable it was removed rather than described.

Two operators are worth separating, because the answers differ. **A live operator** watches requests
arrive and can log whatever they like; nothing in this section constrains them beyond what the
protocol itself refuses to send. **An operator with only the database** — an old backup, a stolen
disk, a hosting provider's snapshot — sees strictly less, and several items below say so explicitly.

### Can see

- **How many records the account holds**, and how many versions of each are retained -- up to
  `MANANA_HISTORY_DEPTH`, past which the count stops growing. The account's `last_seq` still counts
  every write it has ever accepted.
- **Approximate size of each record.** The client pads plaintext to 4 KiB buckets before sealing, so
  this is a bucket count rather than a byte count. A record's serialised payload spends several
  hundred bytes on field names and per-field clocks before any text, which leaves roughly three
  thousand characters of note body inside the first bucket: an empty note, a shopping list and a
  page of prose are all one bucket and all identical. Past that, length is revealed to within 4 KiB,
  and a note that crosses a boundary between two versions visibly grew.
- **Which record changed, and in what order.** `blinded_id` is stable for the life of a record, so
  the operator sees a per-record edit history: this record has been written nine times, and here is
  where each write falls in the account's global order. **This is the largest remaining leak** and
  the reasoning for not fixing it is in "Rejected" below.
- **When each edit happened — live.** A live operator times every request and, over weeks, that is a
  sleep schedule and a working week. The database itself holds **no per-version timestamp**, so a
  copy of `sync.db` yields the order of edits but not their times.
- **Which records were edited together.** A `POST /v1/records` batch groups the records a client
  pushed in one pass, and applies them in list order. That is a co-editing signal between records —
  "these three always change together" — and, if the client sends its dirty rows oldest-clock-first,
  the order within the batch leaks their relative edit times. A client can blunt the second half by
  shuffling each batch before sending it. It cannot blunt the first half without splitting batches,
  which costs round trips and does not help much: the requests still arrive seconds apart.
- **Which record a client asked about.** `GET /v1/records/{id}/history` names one blinded ID, so a
  live operator sees which record a client is interested in. The path is never written to the log
  (see below), so this does not reach an operator who only has the log file.
- **How many devices are enrolled**, when each was added, when each was revoked, and each device's
  **public key**.
- **That a given `(accountId, deviceId)` pair exists.** `POST /v1/session/challenge` is
  unauthenticated and answers `404` for an unknown pair. Both values are unguessable 128-bit
  strings, so this reveals nothing to a caller who does not already have them.
- **When the account was created.** `accounts.created_at`, and the creation and revocation times of
  every device, are server-clock timestamps in the database.
- **Whether a push conflicted**, and therefore that two devices edited the same record concurrently.
- **The byte size of each request and response**, from the log. For a pull that is the aggregate
  bucket count of the page, which the row sizes already give.
- **IP addresses, connection times and TLS metadata**, at the proxy, like any HTTP service.
- **That a pairing happened, and when.** A row appears in `pairings` and is collected. A live
  operator additionally sees **the two IP addresses involved and that they belong to one pairing** —
  on a home connection that is one address for both — and **the blob's size**, which varies by a few
  bytes with the account id and the client configuration sealed inside it. `sid` itself is in the
  database and in the URL; it is 16 random bytes minted for one attempt and means nothing anywhere
  else. It is never written to the log: the log line names the route template `POST /v1/pair/{sid}`,
  and `PairingRendezvousTest.noSidReachesALogLine` is the test for that.

  A pairing is not linkable to an account by this server. The blob carries the account id **inside
  the ciphertext**, and the paired device's later `POST /v1/account` or `POST /v1/devices/authorize`
  is a separate, signed request that shares no field with the pairing row. What a live operator can
  do is correlate by *time* — a pairing at 14:02 and an enrolment at 14:03 from the same address are
  obviously related — which is the same inference available from any two requests.

### Cannot see

- **The content of any note**: title, body, checklist, colour, folder membership, favourite or pinned
  state, and every other field. All of it is inside the sealed envelope.
- **Whether a record is a note or a folder.** `recType` used to travel in the clear, which told an
  operator how many folders an account had and when each changed. It does not travel at all now: it
  is inside the sealed payload, and it stays bound to the record because it is part of the
  blinded-ID HMAC message (`HMAC(K_id, recType‖":"‖uuid)`).
- **Which device made an edit.** The hybrid logical clock used to travel in the clear, and the node
  component of the natural implementation is a device identifier. The clock is inside the envelope
  now, so there is nothing to read — and, unlike the per-account-pseudonym mitigation the Phase 3
  plan describes, this holds regardless of what the client chooses to put in the node field.
- **What any device is called.** The label is sealed client-side by
  `core-crypto/.../sync/DeviceLabelCipher` under a key derived from the Account Root Key, and padded
  to a constant length, so the stored blob gives up neither the name nor its length. The server
  stores it and hands it back; it has no code that could do anything else with it.
- **Whether a write was an edit or a deletion.** There is no delete endpoint; a tombstone is an
  ordinary upsert whose flag is inside the ciphertext.
- **The note's real UUID.** Records are filed under `HMAC(K_id, …)`, and `K_id` is derived from the
  Account Root Key, which never leaves a paired device.
- **Anything inside a pairing bundle.** Not the Account Root Key, not the account id, not the client
  configuration. The blob is AES-256-GCM under a key derived from an ECDH between two ephemeral
  P-256 keys; the new device's public half travelled as a QR code on a screen and its private half
  never left that machine. There is no code in this server that could open it and no key here that
  would help — and the row is deleted at the first successful collect, so the window in which it is
  holding anything at all is a two-minute ceiling and usually a few seconds.
- **Which account a pairing belongs to.** See the note under "Can see": nothing links a `pairings`
  row to an account except the wall clock.
- **Anything correlating two accounts.** A different ARK gives a different `K_id`, so the same note
  on two accounts has completely unrelated blinded IDs.
- **The times of past edits, from the database alone.** `records` has no timestamp column. This is
  a defence against a leaked backup and nothing else: a live operator sees arrival times regardless.
- **Any key that would let the operator write.** The `devices` table holds public keys only. A full
  compromise of this server — database, process memory, everything — yields **no ability to forge a
  write, enrol a device or impersonate one**, because there is no private key anywhere in it. It
  does yield the ability to *withhold* data, to serve an old version, and to delete the store
  outright. Serving an old version is **not** caught by the envelope's own authentication: a
  replayed version is genuinely authentic, and the AEAD tag verifies. It is caught by the client
  comparing the clock inside the decrypted payload against its own row's when that row is not
  `dirty`, and by `409 cursor_ahead_of_server` for a whole-server rollback. See
  `docs/design/e2e-sync-phase3-plan.md` §4.
- **A usable session token from the database.** `sessions` stores SHA-256 digests; the token itself
  exists only in the client's memory and in the `Authorization` header of a live request. Reading
  the database therefore does not let the operator resume a session — though an operator who
  controls the process can of course read live headers.

### Rejected, with reasons

Not every leak above is unfixable in principle. These are the fixes that were considered against
this design and turned down, so that nobody has to rediscover why.

- **Rotating `blinded_id` per version**, to destroy the per-record edit history. This is the one
  change that would fix the largest remaining leak, and it breaks the server. The identifier is the
  *only* thing linking a new version to the record it supersedes, so a rotating one takes
  compare-and-set with it: every push becomes a create, `baseSeq` has nothing to compare against,
  and two devices editing the same record concurrently produce two unrelated rows instead of a
  `409`. Garbage collection goes too — `pruneHistory` cannot know which rows are superseded, so a
  store with no delete endpoint grows without bound. And a pull would return every version ever
  written, unlinked, for the client to decrypt and sort out, which is O(all history) forever rather
  than O(new). Sending the old identifier alongside the new one restores all three and re-links the
  chain, which is the thing rotation existed to break. A coarser variant — one identifier per record
  per day — keeps CAS inside a day but has the same problem at every boundary, plus a daily rewrite
  of every record that is itself a louder signal than the one it hides.
- **Decoy writes**, to bury the real edit history in noise. Cheap to implement and expensive
  forever: the protocol has no delete, so every decoy is permanent storage and permanent bandwidth,
  and decoys count against `MANANA_HISTORY_DEPTH`, which means they push real recoverable versions
  out of the history a user might actually need.
- **Batching and jitter**, to blur edit timing. Genuinely worth doing on the client and genuinely
  limited: batching collapses several edits into one arrival time, and jitter moves that arrival by
  minutes. Neither hides the daily envelope of activity — the hours at which pushes ever happen —
  because that is a property of when the user is awake, not of when any individual push fires. Only
  constant-rate cover traffic hides it, and that is the previous bullet with a schedule attached.
  Do not let a jitter setting be described as hiding when someone works.
- **Widening the signed message to cover the device label.** The canonical signed message is
  specified byte-for-byte above and implemented on both sides; the label has never been one of its
  fields. An attacker in the middle can therefore still substitute a *different* sealed blob at
  enrolment. They cannot substitute chosen text, because sealing needs the Account Root Key, so the
  outcome is a device that shows as unnamed. Trading a protocol change for "unnamed" versus
  "unnamed, and rejected" is not worth it.

### What the log file contains

One line per request: method, **route template**, status, duration, and request/response byte
counts. Route template, not path — `GET /v1/records/{id}/history`, never the blinded ID — because a
log full of those *is* the per-record edit history in a second place. No account ID, device ID,
device label, public key, token or envelope byte is ever written at any level. `LoggingTest` asserts
this against the real route table; `RequestLog` has no overload that accepts a path.

Ktor's own `CallLogging` plugin is deliberately not installed, and the bundled slf4j-simple is
configured to `WARN`, both because Ktor's request logging prints full paths.

---

## Tests

```
cd server && ./gradlew test
```

123 tests. Every endpoint has happy-path and rejection coverage. The properties with a test of their
own, by file:

- `CursorTest` — 60 concurrent pushes get contiguous distinct seqs; a puller interleaved with eight
  concurrent writers misses nothing; a frozen clock still produces a strict order (the cursor is not
  a timestamp); a cursor ahead of the server is refused.
- `RecordsTest` — CAS conflict returns the blocking envelope inline; a partial batch applies; there
  is no delete endpoint and a deletion is an ordinary upsert; one account never sees another's
  records.
- `DeviceTest` — a revoked device cannot vouch and cannot open a session; a replayed `authorize` is
  refused; a signature over a different key does not enrol this one.
- `SessionTest` — a replayed session request is refused; a failed attempt burns the challenge; the
  database stores only a digest of the token.
- `MetadataTest` — a request carrying a field that was removed from the wire (`recType`, `hlc`, a
  plaintext `deviceLabel`) is **rejected**, not tolerated; a record's key set on every path that
  returns one is exactly `{blindedId, seq, envelope}`; no record response carries a timestamp; a
  device row carries a sealed label and returns it byte for byte. These are the assertions that make
  putting a removed field back a failing test rather than a silent regression, since a reintroduced
  field would otherwise change no behaviour at all — the server would store it and echo it exactly
  as before.
- `OpacityTest` — a sealed sentinel appears in no response body and in no byte of the SQLite file;
  an envelope round-trips unmodified; an envelope that is not a valid seal is stored anyway, which
  is how "the server does not parse envelopes" is asserted.
- `ValidationTest` — oversized bodies and envelopes, malformed base64, absurd cursors, unknown
  accounts, out-of-range limits, path traversal in a record ID.
- `LoggingTest` — no identifier and no envelope reaches a log line.
- `RateLimitTest` — `429` with a non-zero `Retry-After`; the budget refills; a throttled request
  does no work.

### Mutation evidence

The security tests were checked the only way a test can be checked: by breaking the production code
and confirming that a *named* test fails. Each mutation was reverted immediately afterwards, and the
full suite is green on the committed code.

| Mutation | Tests that failed |
|---|---|
| `authorizeDevice` ignores the result of `P256Verify.verify` | `DeviceTest.authorizeSignedByAKeyThatIsNotTheVoucherIsRejected`, `DeviceTest.authorizeWithASignatureOverADifferentKeyIsRejected` |
| `SyncStore.upsertBatch` applies every item regardless of `baseSeq` | `RecordsTest.aStaleBaseSeqIsRejectedWithTheConflictingEnvelopeInline`, `RecordsTest.baseSeqZeroAgainstAnExistingRecordIsAConflict`, `RecordsTest.aBaseSeqAheadOfTheHeadIsAConflict`, `RecordsTest.aBatchAppliesTheItemsThatDidNotConflict`, `OpacityTest.noEndpointEverReturnsThePlaintextOfARecord` |
| `changesSince` orders on something other than `seq` (`blinded_id`) | `CursorTest.aPullerInterleavedWithConcurrentWritersMissesNothing`, `CursorTest.anUpdateMovesARecordToTheEndOfTheCursorOrder` |
| `authorizeDevice` drops the `voucher.revokedAt != null` check | `DeviceTest.aRevokedDeviceCannotVouchForANewDevice` |
| `claimReplaySlot` always returns true | `DeviceTest.replayingAValidAuthorizeIsRejected` |
| `readBounded` ignores both the declared and the actual size cap | `ValidationTest.anOversizedBodyIsRejectedWithoutBeingProcessed` |
| `RequestLog.request` is given the real request path instead of the route template | `LoggingTest.logLinesNameRouteTemplatesAndNeverRealPaths` |
| `UpsertRequestItem` gains a `recType` field again (with a default, so nothing else needs changing) | `MetadataTest.anUpsertItemCarryingARecTypeIsRejected` |
| `RecordDto` gains a `receivedAt` field again | `MetadataTest.aPulledRecordCarriesOnlyTheBlindedIdSeqAndEnvelope`, `MetadataTest.theConflictingVersionReturnedInlineCarriesOnlyThoseThreeFields`, `MetadataTest.aHistoryVersionCarriesOnlyThoseThreeFields`, `MetadataTest.noRecordResponseCarriesATimestamp` |
| `listDevices` truncates the sealed label instead of returning it whole | `MetadataTest.aSealedLabelIsStoredAndReturnedByteForByte`, `MetadataTest.aDeviceEnrolledWithoutALabelIsAccepted` |
| `sessionByTokenHash` drops the `d.revoked_at IS NULL` join condition | **Nothing failed.** See below. |
| `takePairing` drops `AND expires_at > ?` | `PairingRendezvousTest.aDepositIsNotCollectableAfterItsTtl` |
| `takePairing` no longer deletes the row it read | `PairingRendezvousTest.aDepositIsCollectableExactlyOnce`, `PairingRendezvousTest.aSidIsDepositableAgainOnceItsBlobHasBeenCollected`, `PairingRendezvousTest.unknownAndCollectedLookIdentical` |
| `putPairing` uses `INSERT OR REPLACE` instead of `INSERT OR IGNORE` | `PairingRendezvousTest.aSecondDepositCannotDisplaceTheFirst` |
| `putPairing` no longer sweeps expired rows | `PairingRendezvousTest.expiredDepositsAreSweptByALaterDeposit` |
| `depositPairing`'s size bound becomes `Int.MAX_VALUE` | `PairingRendezvousTest.anOversizedBlobIsRefusedAndNotStored` |
| `depositPairing`'s capacity bound becomes `Long.MAX_VALUE` | `PairingRendezvousTest.theTableCannotGrowPastTheGlobalCap`, `PairingRendezvousTest.capacityIsFreedWhenDepositsExpire` |
| `depositPairing` never charges the deposit bucket | `PairingRendezvousTest.depositsAreRateLimitedSeparatelyAndTightly` |
| `validSid` accepts any base64url instead of exactly 16 bytes | `PairingRendezvousTest.onlyASixteenByteSidIsAccepted` |
| the deposit route logs the real path instead of `POST /v1/pair/{sid}` | `PairingRendezvousTest.noSidReachesALogLine` |

The `changesSince` row used to read "filters and orders on `received_at`, and `nextCursor` becomes a
timestamp", and it failed six tests. That mutation is **no longer expressible**: `records` has no
timestamp column for a cursor to be built out of by mistake. It was replaced with the nearest thing
that still compiles, and re-run.

**One mutation survived, and it found a real gap.** Dropping `revoked_at IS NULL` from the session
lookup broke nothing, because revoking a device already deletes its sessions in the same
transaction — so the first barrier always fires and the second was never reached from the HTTP
surface. That is exactly the condition under which a defence quietly stops working.
`SessionTest.aSessionRowForARevokedDeviceDoesNotResolve` was added to reach past the first barrier:
it inserts a session row for a revoked device through the store directly, standing in for a future
code path that forgets to check, and asserts the token still does not resolve. Re-applying the same
mutation now fails that test.

---

## Deliberate limitations

- **No TLS in-process.** Use a reverse proxy. Adding certificate handling here would be a second
  place to get it wrong.
- **No multi-process deployment.** One SQLite file behind one in-process lock. Two instances pointed
  at the same file would break `seq` allocation ordering, which is the one thing the cursor depends
  on. Do not run two.
- **The rate limiter is in memory**, so a restart hands a caller one fresh bucket. That is worth far
  less than a table written on every request.
- **`historyDepth` versions are retained per record**, not all of them. The protocol has no delete,
  so an unbounded history would grow forever. Only the head version is ever returned by a pull, so
  this affects the history endpoint and nothing else.
- **No account deletion.** Removing an account means deleting rows from `sync.db` by hand, or
  deleting the file. Nothing in the HTTP surface can destroy data, which is deliberate.
