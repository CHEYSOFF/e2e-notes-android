# Test coverage review

> Status: **research + one shipped test suite.** Written 2026-08-30 against `master` @ `26ac7d7`.
> Every resolution claim below was produced by running the build on this machine on that date, not
> by reading a version catalog. Where a claim contradicts a comment already in the repo, the
> contradiction is called out and the comment is corrected in the same PR.

## Verdict

The **pure** logic of this app is tested unusually well. 181 unit tests across 8 modules, and the
ones that matter are genuine regression tests written against bugs that actually shipped — several
of them state, in the test body, the wrong behaviour they replaced. That is rare and it should not
be undersold.

Three things are wrong with the picture anyway:

1. **The `androidTest` half of the repo does not run, and mostly does not even build.** Six of the
   seven modules with an `androidTest` source directory cannot assemble it in this environment. Of
   the two migration tests that can, one — `Migration4to5Test` — **has been failing since the v6
   schema bump** and nobody noticed, because a test that is never executed cannot go red. Found by
   running it (§6); fixed in this PR.
2. **Every stateful component is untested.** Not under-tested — untested. `SingleNoteViewModel`
   (729 lines), `NotesListViewModel` (381), `AuthViewModel` (388), `SecureUnlockManager` (407),
   `TrashViewModel` (157), `SettingsViewModel` (168), `NoteDao`, `FolderDao`, `RoomNotesRepository`
   and `DataStoreSettingsRepository` had, between them, zero direct coverage before this PR — which
   closes exactly one of those, `NoteDao`. The pure functions these classes *call* are well covered;
   the sequencing, latching and mutex discipline that decides whether a note survives is not.
3. **The offline story is more subtle than "no network".** Google's Maven works; Maven Central
   returns 403. That single fact decides which of the gaps below are fixable today and which are
   blocked, and it is the opposite of what two comments in the repo currently assert.

The single highest-value thing available today is **instrumented tests in `:core-data`** — the one
source set that builds — because that is where irreversible writes live. This PR ships the first
one.

---

## 1. What the build environment actually allows

Maven Central (`repo.maven.apache.org`) answers **403 Forbidden** for every artifact not already in
`~/.gradle/caches/modules-2/files-2.1/`. Google's Maven (`dl.google.com`, serving every `androidx.*`
coordinate) **works normally** — artifacts fetched during this review appeared in the cache with
today's timestamp.

`settings.gradle.kts` routes `androidx.*`, `com.android.*` and `com.google.*` to `google()` first,
so the split falls almost exactly along "androidx or not".

| Library | Coordinate | Served by | Usable today? | Evidence |
|---|---|---|---|---|
| JUnit 4 | `junit:junit:4.13.2` | cached | **yes** | already on every module's `testImplementation` |
| Room migration testing | `androidx.room:room-testing:2.8.4` | google() | **yes** | added to `:core-data` `androidTest`; `assembleDebugAndroidTest` **BUILD SUCCESSFUL**; the AAR landed in the cache during that run |
| AndroidX test core/runner/monitor/ext-junit | `androidx.test:*`, `androidx.test.ext:junit` | cached + google() | **yes** | `:core-data` `androidTest` builds and runs on device |
| Compose UI test manifest | `androidx.compose.ui:ui-test-manifest:1.11.2` | cached | **yes** | already a `debugImplementation` in `:app` |
| **Compose UI test** | `androidx.compose.ui:ui-test-junit4` (BOM 2026.05.01 → 1.11.2) | google() | **NO** | the AAR downloads fine, but it depends on `kotlinx-coroutines-test`, which does not. Adding *only* this to the otherwise-clean `:core-data` `androidTest` classpath produces exactly one failure: `Could not download kotlinx-coroutines-test-jvm-1.9.0.jar … 403` |
| **kotlinx-coroutines-test** | `org.jetbrains.kotlinx:kotlinx-coroutines-test` | Maven Central | **NO** | tried `1.10.2` and `1.9.0` (the version already in the cache for `-core`/`-android`); both 403 on the jar |
| **Turbine** | `app.cash.turbine` | Maven Central | **NO** | no `app.cash.turbine` directory in `files-2.1`, and Maven Central is 403 |
| **MockK / Mockito / Robolectric / Truth / AssertJ** | Maven Central | Maven Central | **NO** | none present in `files-2.1` |
| Espresso's own transitive deps | `org.hamcrest:hamcrest-library`, `hamcrest-integration`, `com.squareup:javawriter`, `com.google.code.findbugs:jsr305` | Maven Central | **NO** | 403 on each |

Three consequences follow, and they are the whole reason the plan in §5 looks the way it does.

### 1a. Six modules cannot assemble their `androidTest` source set at all

`androidTestImplementation(libs.androidx.espresso.core)` is declared in seven modules. Espresso
pulls `hamcrest-library`, `hamcrest-integration`, `javawriter` and `jsr305` from Maven Central, none
of which are cached. Verified by running `assembleDebugAndroidTest` per module:

| Module | `androidTest` assembles? |
|---|---|
| `core-data` | **yes** — the only one. It deliberately uses `androidx.test:runner` instead of Espresso |
| `app` | no |
| `core-ui` | no |
| `core-domain` | no |
| `core-crypto` | no |
| `feature-auth` | no |
| `feature-notes` | no |

Not one of those six contains anything but the generated `ExampleInstrumentedTest`, so nothing is
currently lost — but the door is bolted shut for anything anybody wants to add. **Swapping the
unused `espresso-core` for `androidx.test:runner` reopens it**: verified on `:feature-notes`, where
that one-line change turned the failure into `BUILD SUCCESSFUL`.

### 1b. Compose UI tests cannot be written, not merely "have not been"

The version catalog already declares `androidx-compose-ui-test-junit4` and `:app` already depends
on it, which reads like the tooling is in place. It is not: that dependency is precisely what makes
`:app:assembleDebugAndroidTest` fail. `ComposeTestRule` genuinely needs `kotlinx-coroutines-test`
(it drives the test clock through it), so excluding the transitive dependency would trade a build
error for a runtime one.

### 1c. No ViewModel can be unit-tested on the JVM

`viewModelScope` dispatches on `Dispatchers.Main`. Under a plain JVM unit test that resolves to the
Android main dispatcher, which needs a `Looper`. Probed directly with a throwaway test doing
`withContext(Dispatchers.Main) {}` in `:feature-notes`:

```
java.lang.IllegalStateException: Module with the Main dispatcher had failed to initialize.
  For tests Dispatchers.setMain from kotlinx-coroutines-test module can be used
Caused by: java.lang.RuntimeException: Method getMainLooper in android.os.Looper not mocked.
```

`Dispatchers.setMain` lives in `kotlinx-coroutines-test` (unavailable) and the alternative,
Robolectric, is also unavailable. So **every ViewModel gap in §4 is reachable only as an
instrumented test today**, which is exactly why §1a matters.

### Two comments in the repo that are now wrong

- `Migration5to6Test`'s header says `androidx.room:room-testing` "is not resolvable in this build
  environment (no network access to Maven, and the artifact is not in the local Gradle cache)". It
  resolves. Corrected in this PR.
- The task brief for this review stated that `Migration4to5Test` documents the
  `assembleDebugAndroidTest` + `adb shell am instrument` procedure. It does not — it documents only
  why it avoids `MigrationTestHelper`. The procedure is now written down, in `NoteDaoTest`.

---

## 2. What is tested — and would it fail?

The column that matters is the last one. "Characterization" means the assertion restates what the
code does; "regression" means there is a specific wrong behaviour it rejects.

| Suite | Tests | Covers | Verdict |
|---|---|---|---|
| `LockoutPolicyTest` | 21 | `lockoutUntil` backoff curve; `remainingMillis` across the wall/monotonic pair | **Regression, strong.** Pins the `shl` overflow that shortened lockouts around fail count 55–70, and the reboot-stale monotonic deadline that used to hold a user for `MAX_LOCK_MS` *repeatedly*. The last test explicitly explains why `<= MAX_LOCK_MS` would have passed under the buggy version and asserts `== 0` instead. |
| `KeyLossPolicyTest` | 4 | the predicate that decides whether to delete every wrap of the DB passphrase | **Regression, strong.** Both shipped mistakes are named and asserted against: `GeneralSecurityException` as the match (which collapsed the predicate into "any crypto error") and `KeyStoreException` as terminal. Also covers a cyclic cause chain. |
| `TrashPolicyTest` | 18 | `isExpired` / `purgeThreshold` / `daysRemaining` | **Regression.** Every "age unknown" case (null, 0, negative, future) is asserted to keep the row, and `purgeThreshold` is checked to agree with `isExpired` over a candidate set including both sides of the boundary. |
| `SingleNoteMergeTest` | 25 | `mergeIncomingNote`, `mergeChecklist`, `isDiscardableOnOpen`, `toEditorBaseline` | **Regression.** Notably honest: one test says in its own comment that a sibling test is characterization only (row-level equality would also pass it) and then adds the case that only per-field merging survives. `isDiscardableOnOpen` pins the `createdAt`-backfill false positive that hard-deleted real notes. |
| `EditorHistoryTest` | 17 | undo/redo stack, coalescing, redo-branch invalidation, `ChecklistItem` identity, bounded stack | **Regression.** Time is injected, so coalescing is deterministic rather than slept on. The "an undo is a barrier" test would fail if the barrier were removed. |
| `NoteSearchTest` | 26 | normalize / findMatchRanges / buildSnippet / matchPreview / searchPreviews | **Regression.** Covers the snippet-window clamp bug shape (a match outside the window must be dropped, not clamped) and asserts every highlight indexes inside its own snippet. |
| `NoteExportTest` | 17 | share text, `duplicateTitle`, `buildDuplicate` | **Regression** for the format-marker rules; the string-formatting half is closer to characterization but cheap and stable. |
| `LegacyContentFormatTest` | 6 | `looksLikeEditorHtml` | **Regression, strong.** Includes the `StringIndexOutOfBoundsException` shapes (`"<div"`, `"<h1"`) that rolled `MIGRATION_4_5` back on every launch, and deliberately pins the *known residual* false positive rather than hiding it. |
| `BiometricRowTest` | 9 | the settings biometric row's interactivity + copy | **Regression.** "Turning it off is always possible, whatever the device reports" is a real invariant with a real failure mode. |
| `PassphraseCipherTest` | 5 | PIN wrap/unwrap round trip, wrong PIN, tampering, salt/IV freshness | **Regression** for the crypto contract, though it does not cover the *stateful* wrap lifecycle (§4.3). |
| `TrashPolicyTest`'s neighbours: `NotesSortOrderTest` (6), `NotesSortOrderLabelsTest` (4), `ChecklistSerializationTest` (7), `NoteContentFormatMappingTest` (4), `FolderNameTest` (3), `FolderAccentColorTest` (3) | 27 | enum key stability, label distinctness, checklist round trip, storage-value mapping | **Mixed but useful.** `NotesSortOrderTest`'s "keys are decoupled from the constant names" is a genuine guard against a rename silently resetting everyone's preference. The label-length assertion is closer to a style lint. |
| `ExampleUnitTest` × 6 | 6 | `assertEquals(4, 2 + 2)` | **Dead weight.** 6 of the 181. Harmless, but they inflate the number. |

Instrumented, before this PR:

| Suite | Tests | State |
|---|---|---|
| `Migration4to5Test` | 3 | **Was failing.** See §6. |
| `Migration5to6Test` | 5 | Compiled but never executed until this review. **Passes** — first confirmed run. |
| `ExampleInstrumentedTest` × 6 | 6 | Cannot be assembled (§1a). |

---

## 3. What the test suite is *not* — a structural note

Every suite above tests a **pure function**. There is not one test in the repository that
constructs a real collaborator, drives a sequence of operations through it, and asserts on what
ended up on disk or in state. That is a coherent choice given §1c, and the codebase has clearly
been shaped to make it possible — `mergeIncomingNote`, `EditorHistory`, `LockoutPolicy`,
`TrashPolicy` and `isDiscardableOnOpen` were all extracted *out* of stateful classes so they could
be tested. The extraction was done well.

But the residue left behind in those classes is not incidental glue. It is the ordering.

---

## 4. What is not tested, ranked by risk

Ranking criterion: **how much unrecoverable data a bug there destroys, and how loudly.** This app
is offline, encrypted, has no server copy, no export-all, and no recovery. A silent write bug is
permanent.

### 4.1 `SingleNoteViewModel` — the write paths that move the baseline ⚠ highest

`SingleNoteMergeTest` covers `mergeIncomingNote` thoroughly. It does not touch the other half of the
same mechanism: **the baseline is moved by five different code paths, and the merge is only correct
if all five move it correctly.**

Untested, each with its failure mode:

| Behaviour | Where | If it breaks |
|---|---|---|
| `saveNote` folds the written fields into `baseline` inside the mutex, deliberately **excluding** `isFavorite` (the upsert doesn't write it) | `saveNote`, l. 677 | A baseline that claims a field the upsert didn't write makes the next Room echo look like an external change → the echo is adopted → a queued sibling write is reverted. Silently. |
| `writeMeta` folds in **only** the field its `persist` lambda wrote | `writeMeta`, l. 657 | Same class of bug; the code comment says so explicitly and nothing enforces it |
| `saveNote(debounce = true)` cancels the previous `saveJob` and persists the **latest** state after the delay, not a snapshot | `saveNote` | A snapshot-capturing regression means an older save overwrites newer keystrokes |
| `createdBlankNote` is latched **once**, on the first emission only | `init`, l. 323 | It gates `purgeNote` — a hard, undoable DELETE. Latching it on a later emission (after the user typed and cleared) makes an ordinary note purgeable by backing out |
| `BackClicked` joins `metaWriteJob`/`duplicateJob` before navigating | `BackClicked` | Navigation cancels `viewModelScope`; an unjoined in-flight UPDATE or INSERT is dropped |
| `applyRevision` bumps `contentRevision` so the screen re-seeds `RichTextState` | `applyRevision` | Undo appears to do nothing, or worse, state and display disagree while the DB holds the restored value |
| `duplicateNote` flushes the original first, and ignores a second tap while in flight | `duplicateNote` | Double-tap mints two copies; no flush means the copy can be *ahead* of its original after process death |

`SingleNoteMergeTest` proves the merge is right *given* a correct baseline. Nothing proves the
baseline is correct. That is the largest single hole in the repository.

**Blocked on `kotlinx-coroutines-test`** as a JVM test (§1c). Reachable today as an instrumented
test in `:feature-notes` once §1a is fixed — but the 300 ms autosave debounce is real time without
`TestCoroutineScheduler`, so the debounce-timing assertions specifically would be flaky and should
be left out of a first pass.

### 4.2 `NoteDao` / `RoomNotesRepository` — irreversible SQL ⚠ high — **partly closed by this PR**

The upsert's "not ours to write" rules (`isFavorite`, `isDeleted`, `deletedAt`, `createdAt`) exist
only as SQL, and only as *omissions* from an `ON CONFLICT` branch — the easiest thing in the file to
break by adding a line. Likewise `softDeleteNote`'s `AND isDeleted = 0` (without it, a repeat delete
restarts the 30-day retention) and `purgeNotesDeletedBefore`'s stamp guards (the only statement in
the app that destroys a note).

`Migration5to6Test` exercises soft delete/restore/`clearFolder` *after a migration*, which is not the
same as covering the ordinary write paths. **`NoteDaoTest`, added in this PR, closes this** — see §6.

Still open: `RoomNotesRepository.deleteFolder`'s transaction (unfile-then-flag must be atomic) and
`purgeExpiredTrash`'s two-table transaction.

### 4.3 `SecureUnlockManager` ⚠ high

`LockoutPolicy` and `KeyLossPolicy` are the pure decisions and both are well covered. The manager is
the part that can lose the database, and none of it is tested:

- `setupPin` is the **only** place a passphrase is ever created. Regenerating it on an install that
  already has one silently orphans `notes.db`. Nothing asserts it cannot.
- The migration path: `needsMigration()` → reuse the legacy `db_passphrase` rather than mint a new
  one. Getting this wrong is total, silent data loss for every pre-secure-unlock install.
- `openPrefs`'s cross-launch failure counter — the `OPEN_FAILURE_LAUNCHES = 5` budget, the
  `healthPrefs` commit that must survive the crash on the next line, and the reset-clears-counter
  path. This decides whether the app deletes every note.
- `unlockWithPin` clamping the persisted monotonic deadline to `MAX_LOCK_MS` at the write site,
  which is the bound `LockoutPolicy` *infers* when reading it back. The two halves are asserted
  independently and never together.
- `unlockWithBiometric` clearing the lockout counters, without which a fingerprint user gets locked
  out days later having failed nothing.

Instrumented-only (EncryptedSharedPreferences + Keystore), and `:core-crypto` currently can't
assemble `androidTest` (§1a).

### 4.4 `AuthViewModel` — the PIN buffer state machine ⚠ high

The comments in this file describe an unrecoverable failure explicitly: zeroing `pinBuffer` while a
derivation is reading it can "persist a wrap derived from a half-zeroed PIN, which locks the user
out of their database forever." The defence is a set of `isLoading` guards in `onDigit`,
`onBackspace` and `onDismissSheet` that must agree with each other, plus the `initialized` latch,
plus the `pinBuffer.copyOf()` snapshot discipline in `confirmPin`/`enterPin`.

None of it is tested. `feature-auth` has **zero** real unit tests — only `ExampleUnitTest`.

The state machine itself (SET_PIN → CONFIRM_PIN → mismatch → back to SET_PIN; dismiss semantics per
mode; `canDismissSheet`) is pure enough to extract, which would make it JVM-testable **today with
no new dependency**. That is the cheapest high-value win on this list.

### 4.5 `NotesListViewModel` ⚠ medium

- **Folder filtering.** `visiblePreviews` + the pinned/unpinned split is recomputed in four places
  (the notes flow, `FolderClicked`, `DeleteFolder`, and by implication `MoveNoteToFolder`). Any one
  of them getting out of step shows the wrong notes under a chip. Recoverable — the data is intact —
  but it looks exactly like data loss to a user.
- **`FolderClicked` toggle**: tapping the active folder clears the filter.
- **Sort switching** via `flatMapLatest` re-subscribing the Room flow, and the `distinctUntilChanged`
  in `DataStoreSettingsRepository` that stops an unrelated preference write from re-running the
  whole SELECT + HTML parse.
- **The search debounce pipeline**: `combine(searchQuery.debounce(300), allPreviewsFlow)` with
  `mapLatest`, and `selectBottomBarItem` clearing the query on the way *out* of the Search tab but
  not when opening a note from a result.

`searchPreviews` itself is covered by 26 tests; the pipeline around it is not. Needs Turbine or
`kotlinx-coroutines-test` to do properly — **neither is available**.

### 4.6 Room migrations ⚠ medium, but the *process* is the problem

The tests are good. The problem is that they only ran twice ever, and one of them was red the whole
time (§6). A migration test that is never executed is a comment.

`androidx.room:room-testing` **is available** (§1), so `MigrationTestHelper` is now an option for
5→6 and every future step: it would validate the migrated schema against the exported JSON rather
than against hand-copied DDL, and would catch an `ALTER` that disagrees with the entity in a way the
current test only catches indirectly (via Room's open-time validation).

### 4.7 Compose UI ⚠ low priority, and blocked anyway

Zero UI tests; `createComposeRule` appears nowhere. Blocked by §1b. Worth stating plainly that this
is *not* a matter of priorities: it cannot be done in this environment. Given that the riskiest
logic is not in composables, that is an acceptable place to be blocked.

### 4.8 Things that look like gaps but are not worth closing

- `NotePreviewUi.toUi()` / `previewSnippet` — one `HtmlCompat` call. Instrumented-only, low value.
- `pickMotivationalLine` — `LocalTime.now()` + `random()`. Untestable as written and the failure
  mode is a wrong greeting.
- `TrashViewModel.buildEntries` — worth testing, but it is `private` in the file, so covering it
  means making it `internal` first. Cheap; low risk if it breaks (Trash renders in the wrong order).

---

## 5. Prioritised plan

Effort is calendar-honest, including the read-the-code time.

### Worth doing now

| # | Work | Needs | Available? | Effort | Bug class it catches |
|---|---|---|---|---|---|
| **P0** | **`NoteDaoTest`** — upsert preservation rules, tombstone idempotence, purge threshold, ordering totality, metadata writes leaving no trace | JUnit + `androidx.test` | **yes** | ½ d | Silent, permanent data change: un-favoriting on autosave, a save resurrecting a trashed note, a purge taking a row it shouldn't. **Shipped in this PR.** |
| **P1** | **Un-block the six `androidTest` source sets** — swap the unused `espresso-core` for `androidx.test:runner` in `app`, `core-ui`, `core-domain`, `core-crypto`, `feature-auth`, `feature-notes` | none | **yes** | 1 h | Nothing directly. It is the precondition for P2, P3 and P5. Verified working on `:feature-notes`. |
| **P2** | **Extract the auth PIN state machine** out of `AuthViewModel` (mode transitions, `canDismissSheet`, the `isLoading` guards, digit/backspace bounds) into a pure `AuthKeypadState`, and test it on the JVM | none | **yes** | 1–2 d | A guard that disagrees with its sibling → `pinBuffer` zeroed mid-derivation → a wrap the user can never enter. Unrecoverable. |
| **P3** | **`SingleNoteViewModelTest` as an instrumented test** in `:feature-notes` with a fake `NotesRepository`: baseline movement after `saveNote`/`writeMeta`, the `createdBlankNote` latch, `applyRevision`'s `contentRevision` bump, `BackClicked`'s join-then-navigate. **Omit debounce-timing assertions** — without a virtual clock they will be flaky | P1 | after P1 | 3–4 d | The §4.1 table. The highest-value coverage in the repo. |
| **P4** | **Rewrite `Migration5to6Test` on `MigrationTestHelper`**, register `schemas/` as an androidTest asset dir, delete the hand-copied DDL | `room-testing` | **yes** | ½–1 d | A migration whose result disagrees with the exported schema. Also removes a class of test rot: the DDL no longer has to be kept in sync by hand |
| **P5** | **`SecureUnlockManagerTest` (instrumented)** in `:core-crypto`: setup-once, migration reuses the legacy passphrase, `openPrefs` counter across simulated launches, lockout persistence, biometric clearing the counters | P1 | after P1 | 2–3 d | Total, silent loss of the entire database |
| **P6** | **Run the instrumented suites in the merge checklist.** `./gradlew :core-data:assembleDebugAndroidTest` + `adb install` + `am instrument`, recorded as a step somewhere a human reads | none | **yes** | 1 h | Exactly the rot in §6. Any instrumented test not in this loop will be red within two months |
| **P7** | Delete the six `ExampleUnitTest` and six `ExampleInstrumentedTest` files | none | **yes** | 10 min | None. Stops "181 tests" from meaning slightly less than it says |

### Only worth it if something changes

| Work | Blocked on | Why it's blocked, precisely |
|---|---|---|
| JVM tests for **any** ViewModel | `kotlinx-coroutines-test` becoming resolvable | `Dispatchers.setMain` lives there; Robolectric (the alternative) is also unreachable. §1c |
| `NotesListViewModel` search-debounce / sort-switch tests | `kotlinx-coroutines-test` **and** ideally Turbine | Asserting on a debounced flow without a virtual clock means sleeping, which is flaky by construction. Instrumented + real delays is possible but not worth the flake |
| Any **Compose UI test** | `kotlinx-coroutines-test` | `ui-test-junit4` depends on it; excluding it trades a build error for a runtime one. §1b |
| Interaction-style tests (verify-this-was-called) | MockK or Mockito | Hand-written fakes work fine for everything proposed above, so this is a convenience, not a blocker |

Note that **one artifact — `kotlinx-coroutines-test` — gates almost the entire "blocked" column.**
If Maven Central access is ever restored even once, vendoring that jar (or running a single
warm-the-cache build) unlocks P-level work across three modules. That is worth doing deliberately
rather than waiting for it to happen by accident.

### Explicitly not recommended

- **Chasing a coverage percentage.** The uncovered lines are concentrated in six classes and are
  known by name; a number would add nothing.
- **Testing composables once §1b clears.** The risk in this app is in what gets written to SQLite,
  not in what gets drawn.
- **A `SingleNoteScreen` end-to-end test.** Too much machinery for too little; the P3 ViewModel
  tests reach the same logic without a device UI.

---

## 6. What this PR actually changed

### `Migration4to5Test` was broken — found by running it

`Migration4to5Test.openMigrated()` builds the Room database with migrations 1→2, 2→3, 3→4, 4→5. When
Trash bumped the schema to v6, `MIGRATION_5_6` was never added to that list. Room opens at the
*current* `@Database` version, so a hand-seeded v4 file has to walk the whole chain:

```
java.lang.IllegalStateException: A migration from 4 to 6 was required but not found.
```

All three tests in the class failed. It still compiled, so `./gradlew test` stayed green and CI —
which never runs instrumented tests — never saw it. First actual run:

```
Migration4to5Test: 3 failures / 3 tests
Migration5to6Test: 5 passed          (its first execution ever)
```

Fixed by appending `MIGRATION_5_6`, and by replacing the hard-coded `assertEquals(5, …version)` /
`assertEquals(6, …version)` in both files with a new `NOTE_DATABASE_VERSION` constant that
`@Database(version = …)` also reads — so the next schema bump breaks compilation instead of rotting
silently. The stale `room-testing` comment was corrected at the same time.

### `NoteDaoTest` — 14 new instrumented tests

`core-data/src/androidTest/java/my/cheysoff/core_data/NoteDaoTest.kt`, against an in-memory
unencrypted v6 database. Covers, per §4.2:

- `upsertNote` leaves `isFavorite` alone; cannot pull a note out of Trash; keeps `createdAt` and
  backfills a legacy `0`; inserts a new id alive and unfavorited
- `softDeleteNote` is idempotent (a second delete does not restart the retention window); `restoreNote`
  clears the stamp so the next delete starts a fresh one
- `purgeNotesDeletedBefore` takes only `isDeleted = 1` rows with `0 < deletedAt <= threshold` — a
  live row, an unstamped tombstone, a `0`-stamped tombstone and a fresh one all survive
- the three metadata UPDATEs leave `updatedAt` untouched (PR #32); `clearFolder` deliberately does
  not, and reaches trashed notes too
- every ordered read is a **total** order: rows tied on both timestamps come back in `id` order,
  untitled notes sink to the bottom of the title order, Trash puts unstamped rows last

**Proven able to fail.** Four mutations were applied to `NoteDao` and the suite re-run:

| Mutation | Result |
|---|---|
| add `isFavorite = excluded.isFavorite` to the conflict branch | `anUpsertLeavesIsFavoriteAlone` fails |
| drop `AND isDeleted = 0` from `softDeleteNote` | `aSecondDeleteDoesNotRestartTheRetentionWindow` fails: `expected:<5000> but was:<90000>` |
| drop the `id ASC` tiebreak from `getNotesByUpdatedAt` | `rowsTiedOnEveryTimestampAreStillOrderedById` fails: `expected:<[a, b, c]> but was:<[c, a, b]>` |
| add `updatedAt = 12345` to `setNoteFavorite` | `theThreeMetadataUpdatesDoNotBumpUpdatedAt` fails |

Exactly one test failed per mutation, with no collateral failures. The mutations were then reverted.

### Verification

```
./gradlew test                       BUILD SUCCESSFUL   (181 unit tests, unchanged)
./gradlew :app:assembleDebug         BUILD SUCCESSFUL
adb -s emulator-5554 shell am instrument -w \
  my.cheysoff.core_data.test/androidx.test.runner.AndroidJUnitRunner
                                     OK (22 tests)      (3 + 5 migration, 14 DAO)
```

Instrumented tests were run on `emulator-5554` only; the attached physical device was left alone.

`./gradlew :core-data:connectedDebugAndroidTest` does **not** work here, and it is worth recording
exactly why rather than repeating folklore: AGP's Unified Test Platform resolves its own toolchain
from Maven Central, and this environment 403s all of it — observed failures include
`com.google.dagger:dagger:2.48`, `com.google.auto.service:auto-service:1.1.1`,
`com.google.protobuf:protobuf-kotlin:3.24.4`, `com.google.api.grpc:proto-google-common-protos:2.17.0`,
`org.jetbrains.kotlin:kotlin-reflect:1.8.21` and `kotlinx-coroutines-core-jvm:1.7.3`. Note the pinned,
older versions: these are UTP's dependencies, not the project's, so nothing in the version catalog
can be changed to avoid them.

The `assembleDebugAndroidTest` + `adb install -r -t` + `adb shell am instrument -w` sequence is
therefore the supported path, and is written down in `NoteDaoTest`'s header so the next person does
not have to rediscover it.
