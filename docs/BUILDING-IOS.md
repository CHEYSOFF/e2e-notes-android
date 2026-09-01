# Building Mañana for iOS

This is written for someone opening this branch cold on a Mac, and its first job is to tell you
what you are holding.

**No Apple binary produced by this branch has ever been run.** Every line of the iOS port was
written on a Windows machine. A surprising amount of it *compiles* there — Kotlin/Native
cross-compiles Apple `klib`s from any host — but linking a framework, launching a simulator and
running a test all need macOS, and none of those has happened. Read [§1](#1-what-is-evidenced) before
you trust anything here, and run [§3](#3-the-first-twenty-minutes) before you write any code on top
of it.

---

## 1. What is evidenced

Three levels, and the difference between them matters.

| | What it means | What is at this level |
|---|---|---|
| **Tested** | Runs, on this machine, with assertions | All of `commonMain`: the crypto composition, the record payload codec, the record store, the `NotesRepository` over it |
| **Compiled** | Type-checks against the real Apple bindings; has never executed | Every `appleMain` / `iosMain` source set, the Compose UI included |
| **Written** | Neither | `iosApp/iOSApp.swift`, `Info.plist`, the Xcode project you are about to create |

Concretely:

**Tested (1,024 JVM tests, 0 failures, 1 skipped).**

- `GaloisCounterMode` — the AES-GCM construction the Apple build uses. Checked against the published
  McGrew & Viega GCM vectors *and* differentially against the JVM's own `AES/GCM/NoPadding` over 250+
  random keys, nonces, associated data and plaintexts, in both directions.
- `PlatformCryptoKnownAnswerTest` — HMAC-SHA256 (RFC 4231), PBKDF2-HMAC-SHA256 (RFC 7914 §11),
  AES-256-GCM (McGrew & Viega). Lives in `commonTest`, so it will run on Apple too.
- `ProtocolVectorsTest` — the whole key hierarchy, blinded IDs, associated data, a real envelope, a
  real ARK wrap, a real sealed device label, against a frozen snapshot. Also `commonTest`.
- `RecordPayloadTest`, `RecordStoreTest`, `RecordNotesRepositoryTest` — the payload format down to
  its exact bytes, and the store against a real SQLite database.

**Compiled but never run.** `PlatformCrypto.apple.kt`, `SyncEngine.apple.kt` (the certificate pin),
`PlatformClock.apple.kt`, `RecordDriver.apple.kt`, `Keychain.kt`, `ArkVault.kt`,
`MainViewController.kt`, and every composable in `:ios-app`.

**The single most important consequence**: the untested Apple crypto surface is not "an AEAD". It is
four thin calls — `CCRandomGenerateBytes`, `CCHmac`, `CCKeyDerivationPBKDF`, `CCCrypt` in ECB mode —
and each of them either matches a published vector on the first run or does not. [§3](#3-the-first-twenty-minutes)
is how you find out.

---

## 2. Prerequisites

| | Version | Why |
|---|---|---|
| macOS | 13+ | Xcode 15's floor |
| Xcode | 15 or 16 | Anything that can target iOS 15 and build for Apple silicon |
| JDK | 17 | AGP 9 needs 17+; the repo builds on 17 |
| Gradle | wrapper | 9.4.1, in the repo — do not use a system Gradle |
| Mac | **Apple silicon** | See below |

**An Intel Mac cannot run the app.** Compose Multiplatform 1.12.0 publishes no `iosX64` artifacts;
adding that target to `:ios-app` fails resolution for `compose.runtime`, `foundation`, `ui` and
`material3` alike. The *library* modules keep their `iosX64` target and are fine, so the shared code
is still checked for Intel — but the UI can only run on an Apple-silicon simulator or on a device.

Install [`kdoctor`](https://github.com/Kotlin/kdoctor) and run it once. It catches the boring
environment problems (missing command-line tools, a `JAVA_HOME` pointing at the wrong JDK, CocoaPods
noise) faster than a failing build will.

**Network.** `settings.gradle.kts` puts a Google mirror of Maven Central first, because the machine
this was developed on gets a 403 from `repo.maven.apache.org`. On a normal connection that mirror is
simply a fast cache and nothing needs changing.

---

## 3. The first twenty minutes

Do these in order. Each one answers a specific question, and a failure at step *n* makes step *n+1*
meaningless.

### Step 1 — does the shared code still build and pass off Apple?

```bash
./gradlew test
```

Expect **BUILD SUCCESSFUL**, 1,024 tests and one skip (`SyncServerContractTest`, which is opt-in
behind `-PsyncContract` because it starts the real server). This is the same command that passes on Windows
and it touches no Apple target. If it fails here it is not an iOS problem.

### Step 2 — does the Apple crypto agree with the JVM?

**This is the question the whole port turns on.** If an iPhone derives different keys or writes
different envelopes, it cannot read a note the Android phone wrote, and the failure looks like data
corruption rather than a crypto mismatch.

```bash
./gradlew :core-crypto-shared:macosArm64Test
```

`macosArm64` is in the build for exactly this: it is the only Apple target whose tests run **without
a simulator**, so this is the shortest path from a clone to an answer. It runs
`PlatformCryptoKnownAnswerTest` and `ProtocolVectorsTest` — both `commonTest` — against the
CommonCrypto actuals.

Read the result like this:

| What fails | What is wrong |
|---|---|
| `RFC 4231 …` | `CCHmac`. Check the algorithm constant and the key/message argument order. |
| `PBKDF2-HMAC-SHA256 …` | `CCKeyDerivationPBKDF`. Most likely `passwordLen` (it is **bytes**, not characters) or the PRF constant. |
| `GCM test case 15/16` | The AES-ECB call. Almost certainly `kCCOptionECBMode` picking up PKCS#7 padding, or a short write. |
| `the account keys derive from the ARK` (and HMAC passes) | The HKDF composition, which is common code — should be impossible; look for a stale klib. |
| `a committed envelope opens to its payload` (and GCM passes) | Associated data or the per-record key derivation. |
| `the PIN wrap opens` **alone** | See below — this one may not be a bug. |

That last row: `PassphraseCipher` derives from the user's PIN, and the character-to-byte conversion
inside PBKDF2 belongs to the JCA provider on the JVM and to this code on Apple. They agree for ASCII,
and every PIN this app accepts is digits. A PIN wrap also never leaves the device. So a failure there
with everything else green is worth investigating and is **not** an interop break.

Then run the same suite for the simulator, which is a different code path only in that it is a
different target triple:

```bash
./gradlew :core-crypto-shared:iosSimulatorArm64Test
./gradlew :core-domain:macosArm64Test :core-sync-engine:macosArm64Test
```

### Step 3 — does everything link?

```bash
./gradlew :ios-app:linkDebugFrameworkIosSimulatorArm64
```

This is the first time an Apple *linker* has seen this code. Compilation was checked on Windows;
linking was not.

---

## 4. Creating the Xcode project

**The `.xcodeproj` is deliberately not committed, and that is a judgement rather than an oversight.**
A `project.pbxproj` is a graph of a few hundred cross-referenced object IDs. Hand-writing one on a
machine that cannot open Xcode produces a file that is either right or is a bad afternoon, and
"discovering the architecture is wrong" is much cheaper to recover from than "debugging a build
system somebody generated blind". What *is* committed is everything that is not the project file:
`iosApp/iOSApp.swift`, `iosApp/Info.plist`, `iosApp/Configuration/Config.xcconfig`.

Fifteen minutes, once:

1. **Xcode → File → New → Project → iOS → App.**
   - Product Name: `iosApp`
   - Interface: **SwiftUI**, Language: **Swift**
   - Save it into `iosApp/` in this repo, so the folder ends up as
     `iosApp/iosApp.xcodeproj` beside the files already there.
2. **Delete** the generated `ContentView.swift` and the generated `iosAppApp.swift`, and **add** the
   committed `iOSApp.swift` (right-click the group → Add Files to "iosApp"…). Replace the generated
   `Info.plist` with the committed one, or point the target's `INFOPLIST_FILE` at it.
3. **Attach the xcconfig.** Select the *project* (not the target) → Info → Configurations → set both
   Debug and Release to `Configuration/Config.xcconfig`. That is where the bundle id, the deployment
   target and — the one personal value — your `TEAM_ID` live. Leave `TEAM_ID` empty for the
   simulator; it needs no signing.
4. **Add the Gradle build phase.** Target → Build Phases → `+` → New Run Script Phase. Drag it so it
   runs **before** "Compile Sources". Script:

   ```sh
   cd "$SRCROOT/.."
   ./gradlew :ios-app:embedAndSignAppleFrameworkForXcode
   ```

   Under "Input Files" leave it empty for now; Xcode will warn about unspecified outputs and that
   warning is safe to ignore until the build works.

   This task is registered automatically by the `binaries.framework { }` block in
   `ios-app/build.gradle.kts`. It reads `PLATFORM_NAME`, `ARCHS` and `CONFIGURATION` from Xcode's
   environment, so it builds the right slice for whatever you have selected. If it complains that
   those are missing, the script phase is running outside Xcode's environment — usually because it
   was added to the wrong target.

5. **Framework search paths.** Target → Build Settings → search "Framework Search Paths", add:

   ```
   $(SRCROOT)/../ios-app/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)
   ```

   and set "Other Linker Flags" to include `-framework MananaApp`.

6. Build and run on an **Apple-silicon simulator**.

If you would rather not do this by hand: the
[Kotlin Multiplatform wizard](https://kmp.jetbrains.com/) generates a project with exactly this
shape. Generate one, then point its Gradle task at `:ios-app` and copy in `iOSApp.swift`.

---

## 5. What I expect to break, in order

Written before any of it was run, so treat the ordering as a prediction rather than a report.

**1. The Darwin certificate pinner.** `core-sync-net/src/appleMain/.../SyncEngine.apple.kt` imports
`io.ktor.client.engine.darwin.certificates.CertificatePinner`. That import *resolves* at compile
time, which is genuinely reassuring, but nothing has confirmed that it **rejects** a bad pin rather
than logging and continuing. This is the highest-stakes unverified thing in the branch: a pin that
matches everything produces a working app with no pinning at all, and nothing about the app looks
different.

Check it properly, and check the negative case:

```
1. Pair with the real server. Confirm sync works.
2. Flip one byte of `spkiPinSha256` in the paired ServerHint.
3. Confirm the connection now FAILS, and that the failure surfaces as
   SyncException.PinMismatch and not SyncException.Network.
```

Step 3 failing to fail is the bug. If it happens, the fallback is a `handleChallenge` block that
evaluates the trust chain with `SecTrustEvaluateWithError` and then compares a SHA-256 of the DER
`SubjectPublicKeyInfo`. Note that `SecKeyCopyExternalRepresentation` returns a bare PKCS#1 key (RSA)
or `04‖X‖Y` (EC) — **not** an SPKI — so the ASN.1 header has to be prepended per algorithm and key
size before the digest means anything. Getting that wrong is the classic iOS pinning bug and its
failure mode is "accepts everything".

**2. `classifyTransportFailure` on Darwin.** It matches Ktor's Darwin exception by *simple class
name* containing "Darwin", then looks for `NSURLErrorDomain` / `-999` / `-1202` in the message. Both
halves are guesses. Print the exception Ktor actually throws on a pin failure and replace the match
with the real type. Until then a pin failure may be reported as a network error, which is the wrong
direction: it tells a user to check their wifi when their server has been impersonated.

**3. Compose layout.** Nothing has been rendered. Expect, roughly in order:
   - Safe-area insets applied twice or not at all. `iOSApp.swift` calls `.ignoresSafeArea(.all)` and
     the Compose side uses `WindowInsets.safeDrawing`; exactly one of them should be doing the work.
   - The keyboard covering the caret in `EditorScreen`. `imePadding()` and iOS's own
     keyboard-avoidance have to agree about who moves the view.
   - The keypad in `UnlockScreen` being the wrong size on a small phone — it is fixed-size and has
     never met an SE.

**4. The Keychain.** `Keychain.kt` builds `CFDictionary`s by hand. Two things to confirm:
   - An item stored on one launch comes back on the next. (Set a PIN, force-quit, relaunch: it should
     ask you to *enter* a PIN, not to choose one.)
   - The accessibility attribute is the one that was asked for. `security dump-keychain` on a
     simulator, or a breakpoint on the `SecItemAdd` status.

   The `cfString`/`cfData` helpers deliberately leak their `CFBridgingRetain` references — bounded to
   three per session, and explained in the file. If you tighten that, be careful: `CFDictionaryAddValue`
   retains its keys and values, and releasing in the wrong order is a use-after-free rather than a
   leak.

**5. `NativeSqliteDriver` and the Documents directory.** The database lands where SQLiter puts it by
default, which is backed up to iCloud. `RecordDriver.apple.kt` argues why that discloses nothing
(every row is a sealed envelope) and says what to change if you disagree.

**6. Threading.** `RecordStore` is given `Dispatchers.Default.limitedParallelism(1)`. Kotlin/Native's
memory model is fine with shared state now, but SQLiter has its own opinions about connections and
threads; if you see "database is locked", this is where to look.

---

## 6. What the iOS app does not have

The Android app is a full notes app. This is a floor. The gap, so it is a list rather than a
surprise:

| Android has | iOS | Note |
|---|---|---|
| Rich-text editor (`richeditor-compose`) | **Plain text, and HTML notes are read-only** | The most important gap. See below. |
| Folders, chips, filtering | No | The store and the model support them fully |
| Trash UI | No | `RecordNotesRepository` implements the whole Trash contract |
| Search | No | |
| Pinned pager, Recent grid, checklist dots | No | A flat list |
| Favourites, colours, sort order | No | All present in the data layer |
| Biometric unlock | No | PIN only |
| Unlock attempt throttling | No | |
| Pairing / sync | No | `:core-sync-net` and `:core-sync-engine` build for iOS; nothing wires them up |
| Urbanist type | System face | Needs a Compose resources pipeline |

**The rich-text gap deserves its own paragraph**, because it is the one that can lose data. Android
stores note bodies as HTML with `contentFormat = HTML` recorded on the row. A plain-text editor that
opened one of those, showed `<b>hello</b>` as six visible characters and saved it back would destroy
the formatting *on every device on the account*. So `EditorScreen` refuses: an HTML note is
**read-only**, with a line saying why. That is a bad experience and a recoverable one, which is the
trade `NoteContentFormat`'s own KDoc argues for. `richeditor-compose` publishes iOS artifacts, so
closing this properly is a real and reasonably short piece of work — and it is the first thing to do.

One more, smaller: `AppModel.newNote()` mints an id as `"note-$millis"` because this project has no
multiplatform UUID yet. Two notes created in the same millisecond on one device would collide. Not
reachable by tapping; not a property to ship.

---

## 7. Things that were learned the hard way

Recorded so nobody re-derives them.

### Apple klibs cross-compile; frameworks do not

`./gradlew :core-crypto-shared:compileKotlinIosArm64` **works on Windows and Linux.** Kotlin/Native
ships the Apple platform libraries as prebuilt klibs, so `appleMain` is type-checked against the real
CommonCrypto, Security and Foundation bindings anywhere. `link*`, `*Test` and anything touching Xcode
need macOS. `kotlin.native.ignoreDisabledTargets=true` in `gradle.properties` is what stops the second
group failing a build that only wanted the first; it changes nothing on a Mac.

This is why the iOS code in this branch is in better shape than "written blind" would suggest, and it
is worth keeping: a CI job on any host can catch an `appleMain` compile break.

### CommonCrypto has no GCM in the Kotlin/Native bindings

This is the finding that shaped the crypto. `platform.CoreCrypto` exposes ECB, CBC, CFB, CFB8, CTR,
OFB and RC4 — and **no GCM at all**: no `kCCModeGCM`, no `CCCryptorGCM`, no
`CCCryptorGCMOneshotEncrypt`/`Decrypt`. Verified by reading the klib's symbol table:

```bash
for f in ~/.konan/kotlin-native-prebuilt-*/klib/platform/ios_simulator_arm64/\
org.jetbrains.kotlin.native.platform.CommonCrypto/default/linkdata/package_platform.CoreCrypto/*.knm; do
  strings "$f" | grep -oE 'k?CC[A-Za-z0-9_]+'
done | sort -u | grep -i gcm
```

Empty output means the finding still holds. **Re-run this after any Kotlin version bump**: if GCM
appears, `GaloisCounterMode` can be retired in favour of the platform's own, which would be strictly
better.

Given no GCM, the options were CryptoKit (Swift-only, so it would need an Objective-C shim in the
Xcode project and would make the *app* supply the library's crypto), a cinterop `.def` re-declaring
the deprecated `CCCryptorGCM` (needs the Apple SDK headers, so it gives up the cross-compilation
above — and that function writes the computed tag out on decrypt instead of checking it), or building
GCM over CommonCrypto's AES. The third was chosen, and the reason it is defensible is that the
hand-written half is `commonMain` and therefore testable on the JVM against a reference
implementation. `GaloisCounterMode`'s KDoc has the full argument.

### Kotlin/Native rejects commas in backticked test names

`` fun `AES-256, no AAD`() `` is fine on the JVM and is `Name contains illegal characters: ","` on
Native. Any test in `commonTest` has to avoid them.

### `@JvmInline` needs qualifying in `commonMain`

It resolves bare on JVM and Android (default import) and does not on Native. `SyncApi.kt` writes
`@kotlin.jvm.JvmInline` for that reason.

### Compose Multiplatform 1.12.0 has no `iosX64`

Already covered in [§2](#2-prerequisites), repeated here because it is the kind of thing that gets
"fixed" by adding the target back.

---

## 8. Where the pieces are

```
core-domain          models, Merge, Hlc            android, jvm, mingwX64, 4 Apple targets
core-crypto-shared   the crypto, one impl          android, jvm, 4 Apple targets
  commonMain/platform/PlatformCrypto.kt      the 4 expects — read this first
  jvmCommonMain/…/PlatformCrypto.jvmCommon.kt  JCA, unchanged behaviour
  appleMain/…/PlatformCrypto.apple.kt          CommonCrypto
  commonMain/platform/GaloisCounterMode.kt     AES-GCM over a block cipher
  commonTest/…                                 the two vector suites
core-sync-net        Ktor + the wire format       android, jvm, 4 Apple targets
  commonMain/wire/RecordPayload.kt             the sealed payload (plan §5.1)
  appleMain/…/SyncEngine.apple.kt              the certificate pin — check this
core-sync-engine     the sync pass loop           android, jvm, mingwX64, 4 Apple targets
core-store           records(blinded_id, …)       jvm, 4 Apple targets
ios-app              Compose UI                   iosArm64, iosSimulatorArm64
iosApp/              Swift entry point, Info.plist, xcconfig
```

The `mingwX64` canary on `:core-domain` and `:core-sync-engine` is still worth keeping even now that
Apple targets compile: it fails in seconds, on any machine, without downloading an Apple
platform-library set, and it keeps working if a future Kotlin release drops Apple klib
cross-compilation.
