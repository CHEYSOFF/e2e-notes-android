# Running the tests

> Status: **shipped.** Written 2026-08-31, against `master` @ `c391d8d`. Every number and command
> below was run on this machine on that date. Where this document contradicts an older one, the
> older one predates the Maven Central mirror in `settings.gradle.kts` — see §4.

## 1. The command

```
./gradlew verify
```

That is the whole thing. It runs, in one build:

| Half | What runs | Count on 2026-08-31 |
|---|---|---|
| JVM | every module's `test` | 568 tests |
| Compile | every module's `assembleDebugAndroidTest` | 9 source sets |
| Device | `connectedDebugAndroidTest` in every module that has instrumented tests | 30 tests across `:core-data` (22), `:feature-notes` (7), `:feature-auth` (1) |

Cold, on this machine, with an emulator already booted: **≈4 minutes.** Warm: about one.

`verify` **fails at configuration time if no device is attached**, before it runs anything, and
names the suites that would have been skipped. That is deliberate: a verification task that reports
`BUILD SUCCESSFUL` without having verified its most important half is the exact failure this task
was written to remove. To run the JVM and compile halves alone — no device available, or you only
want the fast feedback — pass:

```
./gradlew verify -PallowNoDevice
```

which prints a warning banner listing, by name, every instrumented source file it compiled and did
not run.

### Choosing the device

`connectedDebugAndroidTest` runs on **every** attached device, and this project is often developed
with both an emulator and a physical phone plugged in. Pin one:

```
ANDROID_SERIAL=emulator-5554 ./gradlew verify
```

AGP honours `ANDROID_SERIAL`, and so does `verify`'s own device detection, so the banner and the
run agree.

## 2. Why this exists

`Migration4to5Test` was **red for the entire life of schema v6** and nobody knew. Its Room builder
never gained `MIGRATION_5_6`, so all three of its tests failed with

```
java.lang.IllegalStateException: A migration from 4 to 6 was required but not found.
```

It still *compiled*, and `./gradlew test` does not run instrumented tests, so the only signal anyone
looked at stayed green for months. `Migration5to6Test` was in a worse position still: it had never
been executed at all, and passed on its first run by luck rather than by verification. Both were
found and fixed by [the test coverage review](test-coverage-review.md) §6, which is also where the
number "22 instrumented tests in `:core-data`" comes from.

A test that is never run is a comment. `verify` is the smallest mechanism that makes running them
the default rather than an act of remembering.

## 3. What `verify` does not do

Stated plainly, because a document that oversells a gate is worse than no document:

- **The compile half would not have caught the bug in §2.** `Migration4to5Test` compiled. Requiring
  every `androidTest` source set to assemble catches a source set that has stopped building — a
  real and recurring problem, and the whole subject of issue #53 — but it is a build-integrity
  check, not a test.
- **`MigrationChainTest` would not have caught it either.** That JVM test (added alongside this
  document, in `:core-data`) asserts `NoteDatabase.ALL_MIGRATIONS` runs unbroken from 1 to
  `NOTE_DATABASE_VERSION`. It catches a schema bump made without writing the matching migration —
  a different and equally fatal bug — but the migration in §2 *existed*; it was missing from a
  test's builder. Only running the test finds that.
- **`verify` runs only when somebody types it.** There is no CI in this repository (see §5), so
  nothing runs it for you on a push. The `.github/pull_request_template.md` checkbox is the only
  reminder, and a checkbox is a reminder, not a gate.
- **It does not measure coverage.** That is `./gradlew jacocoMergedReport`; see
  [code-coverage.md](code-coverage.md).
- **It does not run the sync-server contract test.** `verify` runs `test`, and
  `SyncServerContractTest` skips itself unless `-PsyncContract` is passed. See §3.1.

### 3.1 The one test `verify` deliberately skips

`:core-sync-net`'s `SyncServerContractTest` starts the **real** sync server from `server/` on a
random port and drives claim → session → push → pull → conflict → history → vouch → revoke through
the real HTTP client:

```
./gradlew :core-sync-net:jvmTest -PsyncContract
```

It is the only test in the repository where a client/server disagreement can fail — every other
sync test uses a fake transport, and a fake transport can only ever agree with whatever the client
believes. It has already earned that twice: once on a session request that carried no signature,
and once on a client still sending `recType` on a wire the server had stopped accepting. Both suites
were green.

`KtorHttpTransportTest` covers the layer *below* the fake transport — the real HTTP client against a
throwaway `com.sun.net.httpserver` on loopback — and it is **not** opt-in, so `verify` runs it. It
does not overlap with the contract test: it proves the transport puts the request on the socket and
reads the response back, and says nothing about whether the sync server agrees with the bodies.

It is opt-in because it needs a JDK 17 toolchain, a second Gradle build (`server/gradlew
installDist`, built on demand and then reused from `server/build/install/manana-sync-server`) and a
free TCP port, none of which a plain `./gradlew test` should require. Pre-build the image with
`cd server && ./gradlew installDist` if you would rather not pay for it inside the test.

## 4. What changed, and what is now obsolete

Both #53 and #58 were written when `repo.maven.apache.org` answered **403** to everything from this
network. `settings.gradle.kts` now lists Google's read-through mirror of Maven Central ahead of it,
and that single change invalidated most of the constraints those issues were built on. Verified on
2026-08-31 by running each:

| Claim in the older docs | Status today |
|---|---|
| Six modules cannot assemble `androidTest`, because Espresso's `hamcrest-integration` / `javawriter` 403 | **Obsolete.** `./gradlew assembleDebugAndroidTest` succeeds in all nine modules |
| `./gradlew connectedAndroidTest` "does not work here and cannot be made to" — AGP's Unified Test Platform resolves its own pinned toolchain from Maven Central | **Obsolete.** `:core-data:connectedDebugAndroidTest` runs 22/22 green |
| Instrumented tests must be driven by hand: `assembleDebugAndroidTest` + `adb install -r -t` + `adb shell am instrument -w` | **Obsolete**, and therefore no `scripts/run-instrumented-tests.sh` was written. Gradle does it |
| `kotlinx-coroutines-test` unavailable, so no ViewModel can be unit-tested | **Obsolete.** It is on three modules' test classpaths already |

Espresso was nonetheless dropped from six of the eight modules that declared it, replaced by
`androidx.test:runner`. Not because it cannot resolve any more — it can — but because every screen
in this app is Compose, `Espresso` appears nowhere in the repository, and the runner is the only
part of that dependency tree the instrumentation actually uses. `:feature-auth` and
`:feature-pairing` still declare it: both were being changed in parallel when this landed, and the
swap was left for whoever touches them next. `androidx-compose-ui-test-junit4` was left in place
everywhere it appears — it is equally unused today, but unlike Espresso it is the tool a Compose UI
test would actually be written with.

## 5. Why not CI

This repository has no `.github/workflows/`, and this change does not add one. The options were
weighed as follows.

**A GitHub Actions job with an emulator** (`reactivecircus/android-emulator-runner`) is the only
mechanism here that runs without anyone remembering to run it, which is a real advantage and the
reason it was considered first. It was rejected on three grounds:

1. **Runtime.** The instrumented half takes ~4 minutes locally against an *already booted*
   emulator. In Actions it also has to cold-boot an AVD and build from a cold Gradle cache; 20–30
   minutes per run is the honest estimate, on a private repository where Actions minutes are billed.
2. **Flakiness.** An emulator that fails to boot, or an `INSTALL_FAILED_*` on a busy runner, is a
   red build that says nothing about the code. A gate that cries wolf gets switched off, and a
   switched-off gate is worth less than a documented command.
3. **It is not where the loop is.** Work on this project is done locally, with an emulator already
   running, and merged by one person. The device is already there; what was missing was a single
   command that used it.

That reasoning is worth revisiting if the project gains a second contributor, or if the local
`verify` habit visibly fails to stick. A unit-test-only workflow — fast, cheap, reliable — would
also be a defensible thing to add on its own merits, but it would not close #58: it would run
exactly the tests that were already green while `Migration4to5Test` was red.

**A `check`-adjacent gate** — making `assembleDebugAndroidTest` a dependency of every module's
`check` — was rejected as a *replacement* for the above (it cannot catch §2's bug, per §3) and
folded into `verify` instead, so that `./gradlew build` does not pay for dexing and packaging nine
extra APKs.
