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
./gradlew test          # 113 tests
./gradlew run           # starts on 127.0.0.1:8080
./gradlew installDist   # build/install/manana-sync-server/bin/manana-sync-server
```

Requires a JDK 17 or newer. Dependencies resolve through the same Google-hosted Maven Central mirror
the Android build uses, which is why `settings.gradle.kts` lists it first — see the comment there.

## Stack

Kotlin/JVM, [Ktor](https://ktor.io) on the CIO engine, SQLite through `org.xerial:sqlite-jdbc`,
`kotlinx.serialization` for JSON. Seven dependencies, one module, no ORM, no migration framework, no
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
| `MANANA_DEBUG` | unset | `1` enables `DEBUG` log lines |

### Backups

Copy `sync.db` (and its `-wal` file, or checkpoint first by stopping the process). The file contains
only ciphertext and metadata — see the privacy section — so it does not need to be treated as more
sensitive than the server itself. It is **not** a backup of the user's notes in any useful sense: it
is undecryptable without the Account Root Key, which exists only on the paired devices.

---

## Pointing a client at it

Set the base URL to wherever the proxy terminates, e.g. `https://notes.example.com`. The QR pairing
payload already carries a `serverUrl` and an SPKI pin
(`feature-pairing/.../protocol/PairingWire.kt`), so a second device learns the address from the
first.

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

`POST /v1/session` is two round trips rather than one because a challenge/response handshake needs
two; the design document's single `POST /v1/session` line is implemented as
`/v1/session/challenge` followed by `/v1/session`.

**There is no delete endpoint, and there must never be one.** A deletion is an ordinary upsert whose
*plaintext* carries a tombstone flag, sealed inside the envelope. The server cannot tell it apart
from an edit and has no code that could. That is simultaneously the privacy property — deletions are
not observable — and the simplicity property: there is one write path, so there is one thing to get
right.

### Errors

Every failure is `{"error":"<code>","message":"<safe text>"}` with a matching HTTP status. Codes in
use: `malformed_request`, `malformed_base64`, `invalid_account_id`, `invalid_public_key`,
`invalid_label`, `invalid_device_id`, `invalid_blinded_id`, `invalid_rec_type`, `invalid_hlc`,
`invalid_base_seq`, `invalid_envelope`, `invalid_cursor`, `invalid_limit`, `empty_batch`,
`batch_too_large`, `duplicate_record_in_batch`, `payload_too_large`, `rate_limited`, `unauthorized`,
`bad_signature`, `bad_challenge`, `stale_timestamp`, `replay_detected`, `device_revoked`,
`unknown_device`, `unknown_record`, `account_exists`, `device_exists`, `cursor_ahead_of_server`,
`internal_error`.

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
    account_id TEXT    NOT NULL,
    device_id  TEXT    NOT NULL,              -- server-generated, 16 random bytes base64url
    public_key BLOB    NOT NULL,              -- SEC1 uncompressed P-256, 65 bytes. A PUBLIC key.
    label      TEXT    NOT NULL,              -- client-supplied, plaintext, operator-visible
    created_at INTEGER NOT NULL,
    revoked_at INTEGER,                       -- NULL while active
    PRIMARY KEY (account_id, device_id),
    FOREIGN KEY (account_id) REFERENCES accounts(account_id)
);
CREATE UNIQUE INDEX devices_key ON devices(account_id, public_key);

CREATE TABLE records (
    account_id  TEXT    NOT NULL,
    blinded_id  TEXT    NOT NULL,             -- HMAC(K_id, recType‖":"‖uuid)[0..16], base64url
    seq         INTEGER NOT NULL,             -- per-account monotonic; the cursor
    rec_type    TEXT    NOT NULL,             -- opaque label, stored as sent, never interpreted
    hlc         TEXT    NOT NULL,             -- opaque; the client binds it into its AEAD AAD
    envelope    BLOB    NOT NULL,             -- sealed ciphertext. Never parsed. Never opened.
    received_at INTEGER NOT NULL,
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
```

`records` is append-only: a new version is a new row with a new `seq`. The only rows the server ever
deletes are housekeeping — expired challenges, expired sessions, expired replay-cache entries, and
record versions beyond `MANANA_HISTORY_DEPTH`. Nothing deletes a record.

---

## What a server operator can and cannot see

This is the honest list. It is not softened, and none of it can be eliminated by this server.

### Can see

- **How many records the account holds**, and when each one was created.
- **Approximate size of each record.** The client pads plaintext to 256-byte buckets before sealing,
  so this is a bucket count rather than a byte count — but a long note is still visibly longer than a
  short one.
- **Which record changed, and exactly when.** `blinded_id` is stable for the life of a record, so the
  operator sees a per-record edit history: this record was written at 08:14, 08:15 and 23:40.
- **Edit frequency and time-of-day patterns**, for the account as a whole and per record. Over weeks
  that is a sleep schedule and a working week.
- **How many devices are enrolled**, when each was added, when each was revoked, and each device's
  **public key**.
- **Any device label a client sends**, in plaintext. The client chooses this string; if it sends
  "Vova's Pixel 7", the operator reads "Vova's Pixel 7".
- **`recType` for every record**, in plaintext. Today that distinguishes a note from a folder, so the
  operator knows how many folders exist and when they change.
- **The `hlc` string of every version**, in plaintext, because the client must read it *before*
  decrypting. If the client puts a device identifier in the node component of its hybrid logical
  clock — and the natural implementation does — then the operator learns **which device made each
  edit**. A client that does not want that must use a per-account pseudonym for the node component;
  this is called out in the Phase 3 plan.
- **Whether a push conflicted**, and therefore that two devices edited the same record concurrently.
- **IP addresses, connection times and TLS metadata**, at the proxy, like any HTTP service.

### Cannot see

- **The content of any note**: title, body, checklist, colour, folder membership, favourite or pinned
  state, and every other field. All of it is inside the sealed envelope.
- **Whether a write was an edit or a deletion.** There is no delete endpoint; a tombstone is an
  ordinary upsert whose flag is inside the ciphertext.
- **The note's real UUID.** Records are filed under `HMAC(K_id, …)`, and `K_id` is derived from the
  Account Root Key, which never leaves a paired device.
- **Anything correlating two accounts.** A different ARK gives a different `K_id`, so the same note
  on two accounts has completely unrelated blinded IDs.
- **Any key that would let the operator write.** The `devices` table holds public keys only. A full
  compromise of this server — database, process memory, everything — yields **no ability to forge a
  write, enrol a device or impersonate one**, because there is no private key anywhere in it. It
  does yield the ability to *withhold* data, to serve an old version (which the client detects by
  binding `hlc` into its AEAD associated data), and to delete the store outright.
- **A usable session token from the database.** `sessions` stores SHA-256 digests; the token itself
  exists only in the client's memory and in the `Authorization` header of a live request. Reading
  the database therefore does not let the operator resume a session — though an operator who
  controls the process can of course read live headers.

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

113 tests. Every endpoint has happy-path and rejection coverage. The properties with a test of their
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
- `OpacityTest` — a sealed sentinel appears in no response body and in no byte of the SQLite file;
  an envelope round-trips unmodified; an envelope that is not a valid seal is stored anyway, which
  is how "the server does not parse envelopes" is asserted.
- `ValidationTest` — oversized bodies and envelopes, malformed base64, absurd cursors, unknown
  accounts, out-of-range limits, path traversal in a record ID.
- `LoggingTest` — no identifier and no envelope reaches a log line.
- `RateLimitTest` — `429` with a non-zero `Retry-After`; the budget refills; a throttled request
  does no work.

### Mutation evidence

The security tests were checked by breaking the production code and confirming a *named* test fails.
Each mutation was reverted immediately afterwards.

| Mutation | Test that caught it |
|---|---|
| `authorizeDevice` ignores the result of `P256Verify.verify` | `DeviceTest.authorizeSignedByAKeyThatIsNotTheVoucherIsRejected`, and 3 others |
| `SyncStore.upsertBatch` applies every item regardless of `baseSeq` | `RecordsTest.aStaleBaseSeqIsRejectedWithTheConflictingEnvelopeInline`, and 3 others |
| `changesSince` filters and orders on `received_at` instead of `seq` | `CursorTest.theCursorIsNotATimestampSoSimultaneousWritesStillOrder`, and 3 others |
| `authorizeDevice` drops the `voucher.revokedAt != null` check | `DeviceTest.aRevokedDeviceCannotVouchForANewDevice` |
| `claimReplaySlot` always returns true | `DeviceTest.replayingAValidAuthorizeIsRejected` |
| `readBounded` ignores the size cap | `ValidationTest.anOversizedBodyIsRejectedWithoutBeingProcessed` |
| `RequestLog.request` logs the real path instead of the route template | `LoggingTest.logLinesNameRouteTemplatesAndNeverRealPaths` |

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
