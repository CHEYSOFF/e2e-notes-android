# Desktop pairing: the server-assisted return leg

How a computer joins the phone's account, and why the design changes as little as it possibly can.

---

## 1. The problem

The Account Root Key is created once, on the first device, and reaches every other device by
pairing. A desktop that mints its own ARK has not joined the user's account — it has started a
second one, and the two can never be merged: records are sealed under keys derived from the ARK,
the two `accountId`s do not name the same bucket on the server, and neither half's plaintext is
recoverable from the other's key.

So "use this computer on its own" was, until this change, the *only* thing the desktop could do, and
it was the wrong thing for almost everybody.

## 2. Why the phone-to-phone handshake does not transfer

`:feature-pairing` implements a two-QR, fully offline exchange:

1. **B** (the new device) shows **QR1**: `sid`, its ephemeral P-256 public key `EB`, a server hint.
2. **A** (the device holding the ARK) scans QR1, does ECDH against `EB`, derives `Ks`, and shows
   **QR2**: `sid`, `EA`, a nonce, and the ARK sealed under `Ks`.
3. **B** scans QR2, derives the same `Ks`, opens the seal.
4. Both show a six-digit SAS derived from the ARK and `sid`; the user compares them.

Step 3 is where a laptop breaks. It has no camera anyone can rely on, and QR2 carries a sealed ARK —
several kilobytes — which is not something a person retypes.

## 3. The change

**Only the transport of step 3 changes.** Everything else is byte-identical.

1. **Desktop** generates an ephemeral P-256 pair and a random `sid`, and displays QR1 — now with the
   `url` field of `ServerHint` actually filled in. That field has been in the wire format since it
   was written and has been empty ever since; the format is not changing, a field is finally being
   used.
2. **Phone** scans QR1 exactly as it scans a phone's, derives `Ks` exactly as it does today, seals
   `ARK ‖ accountId ‖ config` exactly as it does today — and then, instead of rendering QR2, POSTs
   **that same QR2 payload** to `POST /v1/pair/{sid}`.
3. **Desktop** polls `GET /v1/pair/{sid}`, and feeds what comes back straight into
   `NewDeviceSession.onScanned` — the same method a camera frame reaches.
4. Both show the SAS. Unchanged.

`RendezvousProtocol.toBlob` / `fromBlob` are the whole of the difference: they strip and restore the
`MNP1:` prefix, which is a fast reject for a camera and means nothing over HTTP where the URL
already says what the bytes are.

### Two phones still never touch the network

`PairingViewModel` branches on one thing: whether the scanned QR1 named a usable server. A phone's
QR1 carries `ServerHint.NONE`, so a phone pairing with a phone takes the branch it always took,
renders QR2, and opens no socket. `PairingViewModelTest.pairingWithAnotherPhoneNeverTouchesTheNetwork`
is the regression test, and mutating the branch to always use the server fails it.

## 4. Why this does not weaken anything

**The QR remains an authenticated visual channel, and that is the whole argument.** The only key the
phone has to authenticate is `EB`, and it obtains `EB` by a human pointing a camera at the laptop's
screen. There is no channel for an attacker to interpose on — they would have to be physically
holding the laptop the user is looking at, at which point pairing is not the problem.

What travels through the server is AES-256-GCM ciphertext under `Ks`, and `Ks` comes from an ECDH
whose private halves never left either machine. The server holds ciphertext under a key it does not
have and cannot derive.

### What the server learns

Stating this precisely matters more than the reassurance above:

- that a pairing happened, and when;
- the IP addresses of the depositing and collecting devices, and that they belong to one pairing —
  on a home connection, one address for both;
- the blob's size, which varies by a few bytes with the account id and the config sealed inside it;
- `sid`, which is 16 random bytes minted for one attempt and means nothing anywhere else. It never
  reaches a log line: the log names the route template.

It does **not** learn the ARK, the account id, the config, or which account a pairing belongs to.
Nothing links a `pairings` row to an account except the wall clock.

### What plain HTTP costs

An on-path attacker sees what the server sees, and can additionally race the collect or substitute
the blob. Neither is a compromise: a stolen blob does not open without `eB`, and a substituted one
fails the GCM tag, which `NewDeviceSession` treats as terminal and loud. Both are denial of service.

**The phone refuses a plain `http://` address outright**, and says why. Not a policy this app
invents: Android has blocked cleartext HTTP by default since it started targeting API 28, so an
`http://` address would otherwise fail inside `HttpURLConnection` and surface as an unexplained
"cannot reach the server" that no amount of retrying fixes. The desktop accepts `http://` — its
platform does — and colours the host as a warning when it is not `https`.

That asymmetry is a real limitation, not a subtlety: **a LAN server without TLS can be paired to
from another JVM but not from a real Android phone.** The fix is a TLS-terminating proxy, which
`server/README.md` already requires for deployment.

## 5. The rendezvous endpoints

A dead drop with a two-minute lease, and the only unauthenticated routes that store anything. The
rules, and what each is for, are in `server/README.md` under "The pairing rendezvous"; every one of
them has a named test and a recorded mutation.

The short version: TTL matching the client's own, single-use collect, first-write-wins deposit, a
size cap, a global cap on live rows, and a deposit-only rate bucket much tighter than the general
one.

### Why `sid` is stored raw where a session token is digested

A token is a credential the server can verify without holding, so hashing it means a database read
yields nothing usable. Here the row *already contains* the blob that `sid` would retrieve, so
digesting the key would be protecting a secret against someone holding the thing it unlocks.

## 6. Why `:core-pairing` exists

The protocol — P-256, the wire format, the seal, the SAS, the QR codec — moved out of
`:feature-pairing` into a Kotlin Multiplatform module with Android and JVM targets, so the desktop
runs **the same code** rather than a second implementation of it.

That is not tidiness. Two implementations of one protocol each pass their own tests and disagree
only on a real phone and a real laptop; this project has already shipped that failure once, which is
what `KeyDerivation`'s KDoc is about. `:feature-pairing` keeps everything that needs Android:
CameraX, the `Bitmap` renderer, the Keystore identity key, the Compose screens, the Hilt bindings.

The desktop draws its QR with a Compose `Canvas` rather than a bitmap, because a desktop window is
resizable and a bitmap sized for one width is soft at the next. Both platforms use the same
`QrCodes.encode`, including its self-check that reads every symbol back before showing it.

## 7. Evidence

Beyond the unit suites, this was driven end to end against a real server twice:

- **Headless** (`PairingAgainstRealServer`, opt-in): a real `NewDeviceRendezvous` over a real
  `HttpRendezvousClient`, a real `AccountDeviceSession` sealing a real ARK, a real Ktor/SQLite
  server, then `DesktopVault.setUp(…, PAIRED, ark)` and a **reopen from disk** to prove the ARK that
  came off the wire is the one the passphrase unwraps.
- **Through the real UI** (`PhoneReadingAScreenshot` + `VaultArkFingerprint`, both opt-in): the
  desktop app launched, its pairing screen screenshotted, the QR decoded **out of those pixels**
  through the production `QrCodes.decodeLuminance`, the phone's half run against the real server,
  and then the SAS on the desktop's own screen compared with the phone's — followed by finishing the
  flow by hand and fingerprinting the vault the app wrote.

## 8. What is deliberately not here

- **No pairing from the desktop as the account holder.** The desktop can only be the *new* device.
  A desktop that already has an account has nothing to gain from being the one that shares it, and
  the role chooser would be a second place to get "which key survives" wrong.
- **No certificate pinning on the rendezvous.** Pinning belongs with the sync transport, which has a
  configured server and a pin that arrives authenticated. Here the address is an unauthenticated
  hint the user has just been shown and asked about, and the blob's confidentiality does not rest on
  the transport at all.
- **No automatic retry of a deposit.** A deposit that may or may not have landed is reported rather
  than repeated. The server refuses a second one, and a client that silently retried would turn a
  slow network into "pairing failed".
- **No device enrolment.** Pairing hands over the ARK. Registering the new device with the server
  (`POST /v1/devices/authorize`) is the sync engine's job and is not part of this flow.
