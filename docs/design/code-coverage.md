> **Status update (2026-08-31).** Two things changed since the measurement below.
>
> **1. Robolectric tests were being silently discarded.** `core-crypto` and `core-data` now run
> JVM tests under Robolectric. Robolectric does not execute the classes on the test classpath: it
> reads their bytes, rewrites them, and defines them in its own `SandboxClassLoader` with a
> protection domain that has no code source. The JaCoCo agent skips such classes unless
> `inclnolocationclasses=true`, and AGP hardcodes that option to `false` with no DSL to change it.
> The failure is silent — the tests run and pass and simply score nothing. Measured here: 62
> passing tests against `SecureUnlockManager` and `RoomNotesRepository` left both classes at
> **exactly 0 lines covered** (0/134 and 0/53). The root build script now rewrites that one flag on AGP's
> `-javaagent` argument (and pairs it with `excludes=jdk.internal.*`, without which the test JVM
> dies at startup). Anything measured before this was an undercount for any Robolectric test.
>
> **2. New numbers.** Line coverage **10.6% → 15.7%** (435/4115 → 649/4121); instruction 11.2%,
> branch 20.7%, method 20.5%. The movement is entirely in the two modules that own the user's data:
>
> | Package | Before | After |
> |---|---|---|
> | `core_crypto` (`SecureUnlockManager` was 0/144) | 22.2% | **78.4%** |
> | `core_data/data` (repositories) | **0%** | **54.7%** |
> | `core_data/data/local` (DAOs, exercised through the repository) | 46.6% | 66.9% |
> | `core_domain/model` (`TrashPolicy` reached from the repository tests) | 61.1% | 77.8% |
>
> `SecureUnlockManager` itself is **96.3%** (129/134); the five uncovered lines are the four that
> call straight into the Android Keystore and one defensive `error(...)`. `RoomNotesRepository` is
> **79.2%** (42/53) — every one of the eleven uncovered lines is a suspend function's closing
> brace, which JaCoCo cannot attribute through a Kotlin coroutine state machine.
>
> The one new red entry is `KeystoreEncryptedPrefsStore` at 0/16: the `MasterKey` and
> `EncryptedSharedPreferences` calls extracted out of `SecureUnlockManager` so the rest of it could
> be tested. Those sixteen lines were inside `SecureUnlockManager`'s 0/144 before, so nothing
> regressed — they are simply now named as what they are, code that only a real Keystore can run.
>
> **`connectedAndroidTest` also works now** (same mirror). It ran the whole instrumented suite on
> `emulator-5554` and immediately caught a real regression: `Migration4to5Test` had been broken
> since the v6 (Trash) change — it registered migrations only up to `MIGRATION_4_5` while the
> database moved to v6 — and nobody saw it because the task could not run at all.

> **Status update (2026-08-30, later the same day).** Everything below was written while
> `org.jacoco:org.jacoco.agent` could not be downloaded, so the document said no report could be
> produced here. That is no longer true. The blocker was never JaCoCo specifically: **this network
> gets HTTP 403 from Maven Central for every artifact**, and adding Google's read-through mirror of
> Central to `settings.gradle.kts` fixed it for JaCoCo and for `kotlinx-coroutines-test` alike.
>
> The report now runs. **First measured result: 10.6% line coverage** (435/4115 lines), 7.8%
> instruction, 17.2% branch, 13.2% method. §6 on how to read that number stands, and the measured
> per-package split below vindicates it: everything that scores is a pure function, and every
> ViewModel, repository, DI module and screen is at zero.
>
> | Package | Line coverage |
> |---|---|
> | `feature_notes/model/single` | 87.5% |
> | `core_domain/model` | 61.1% |
> | `feature_notes/model/list` | 54.2% |
> | `core_data/data/local` | 46.6% |
> | `core_crypto` (policies only) | 22.2% |
> | `feature_notes/ui/single` | 13.8% |
> | `core_data/data` (repositories) | **0%** |
> | `feature_auth/ui`, `feature_settings/ui`, `feature_notes/ui/list`, `ui/trash`, `ui/folder` | **0%** |
> | `notes/navigation`, all `di` packages | **0%** |

# Code coverage

> Status: **configuration shipped, never executed.** Written 2026-08-30 against `master` @ `26ac7d7`.
>
> **Read this first: no coverage report has ever been produced for this project, and none can be
> produced on the machine this was built on.** JaCoCo's runtime artifacts are served from Maven
> Central, which answers `403 Forbidden` here for anything not already in the Gradle cache, and
> there is no cached copy. Every command below has been run; the ones that reach the point of
> needing the JaCoCo agent fail, and that failure is reproduced verbatim in §4. **There is no
> percentage anywhere in this document, because nobody has measured one.**

---

## 1. How to run it

```
./gradlew jacocoMergedReport
```

That single command is self-contained: it turns on instrumentation, runs the debug unit tests in
all eight modules, and merges the result into one report.

| Output | Path |
|---|---|
| HTML (for humans) | `build/reports/jacoco/merged/html/index.html` |
| XML (for CI / Codecov / SonarQube) | `build/reports/jacoco/merged/jacocoMergedReport.xml` |

Both paths are relative to the **repository root** — the report lives in the root project's build
directory, not in any module's.

### The `-Pcoverage` flag

Instrumentation is off by default and is switched on by either of two things:

* `-Pcoverage` on the command line, or
* the literal task name `jacocoMergedReport` appearing in the command line.

The second is what makes the one-line command above work. It inspects only the task names actually
typed into `gradle.startParameter.taskNames`; if you reach the report some other way — an IDE run
configuration that rewrites the request, or a task of your own that `dependsOn` it — you need the
explicit `-Pcoverage`:

```
./gradlew -Pcoverage <your-task>
```

**Why it is not simply always on.** An instrumented `testDebugUnitTest` resolves
`org.jacoco:org.jacoco.agent` from Maven Central *before running any test*. On a machine that
cannot reach Maven Central that resolution fails and the test task dies — so leaving
instrumentation unconditional would break plain `./gradlew test` for everyone in that situation,
including this one. This was not assumed; it was measured (§4, command 3).

---

## 2. Where the configuration lives, and why there

All of it is in the **root `build.gradle.kts`**, in one block. Nothing was added to any of the
eight module build scripts.

The alternative would have been a convention plugin in `buildSrc` or an included build. That was
rejected because this repo has neither today, and the whole feature amounts to one boolean, one
version pin and one task — introducing a build-logic module that nothing else in the project uses
would be more machinery than the thing it configures. A `subprojects { plugins.withId(...) }` block
keeps it readable in one place and picks up any module added to `settings.gradle.kts` in future
without a further edit.

Four details in that block are worth knowing:

* **The JaCoCo agent's `inclnolocationclasses` flag is rewritten to `true`.** Without it every
  Robolectric test in the project contributes nothing to the report, silently — see the status
  update at the top of this file for the measurement. AGP builds its `-javaagent` argument through
  a `CommandLineArgumentProvider` and hardcodes the flag to `false`, and exposes no DSL for it, so
  the root script wraps each provider and patches the one string on its way out. `excludes=jdk.internal.*`
  goes with it and is not optional: with location-less classes in scope the agent also tries to
  instrument the JDK's synthesized reflection accessors, and the test JVM dies at startup with
  `NoClassDefFoundError: jdk/internal/reflect/GeneratedSerializationConstructorAccessor1`.

* **The `jacoco` plugin is applied to the root project only.** The eight modules do not need it —
  AGP wires JaCoCo into their unit tests itself once `enableUnitTestCoverage` is set. The root
  needs it because that is what gives `JacocoReport` its `jacocoAnt` tool classpath.
* **The JaCoCo version is pinned to `0.8.14` on both sides.** The two defaults in play do not
  agree: printed from this build on 2026-08-30, Gradle 9.4.1's `jacoco` plugin defaults
  `toolVersion` to `0.8.14` (the reporting tool), while AGP 9.0.1 defaults
  `android.testCoverage.jacocoVersion` to `0.8.13` (the agent that writes the `.exec` files).
  Both are pinned to one value so the tool that reads the execution data is the same version as
  the agent that wrote it.
* **The class-output directories are globbed from paths verified on this machine, not from the
  usual recipe.** With AGP 9.0.1 and Gradle's built-in Kotlin compilation, production classes land
  in `build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes` and
  `build/intermediates/javac/debug/compileDebugJavaWithJavac/classes`. Neither is the
  `build/tmp/kotlin-classes/debug` path that almost every published Android-JaCoCo recipe
  hard-codes; that layout predates built-in Kotlin compilation. `tmp/kotlin-classes/debug` is kept
  in the pattern list as a fallback, and the task fails loudly (`doFirst`) if it ends up with zero
  class files or zero execution data.

  Worth being precise about the failure this avoids, because it is *not* a clean zero. That legacy
  directory still exists here and still holds classes — measured on this checkout, 62 of
  `feature-notes`' 168 compiled classes and 7 of `core-domain`'s 12. A recipe pointed only there
  would therefore report a **plausible-looking but badly understated** number rather than an
  obvious 0%, and nothing in the output would say which classes went missing. That is the harder
  failure to notice, and the reason the globs are wide and the `doFirst` guards exist.

---

## 3. Exclusions

### What is excluded

| Pattern | Why |
|---|---|
| `**/R.class`, `**/R$*.class`, `**/BuildConfig.*`, `**/Manifest*.*` | Android build bookkeeping. Nobody writes it, nobody tests it. |
| `**/hilt_aggregated_deps/**`, `**/dagger/hilt/internal/**` | Hilt's cross-module aggregation classes. |
| `**/*_Factory*.*`, `**/*_MembersInjector*.*`, `**/*_HiltModules*.*`, `**/*_GeneratedInjector.*`, `**/*_ComponentTreeDeps.*`, `**/Dagger*.*`, `**/Hilt_*.*` | Hilt/Dagger annotation-processor output. |
| `**/*_Impl*.*` | Room's generated DAO and database bodies. The hand-written half — the abstract `@Dao` / `@Database` declaration — stays in. |
| `**/ComposableSingletons*.*` | The Compose compiler lifts every constant lambda in a file into a synthetic `ComposableSingletons$<File>Kt` class. |
| `**/*$$serializer.*` | Pre-emptive. **No module applies the kotlinx.serialization plugin today, so this pattern currently matches nothing.** It is here so that adding serialization later cannot quietly inflate the denominator. |

These patterns were checked against the real compiled output rather than assumed. After a full
`:app:assembleDebug` on 2026-08-30, they removed **94 of 432** production `.class` files
(≈22%), broken down per module as *kept / total*:

| Module | Kept | Total |
|---|---:|---:|
| `app` | 18 | 39 |
| `core-crypto` | 18 | 24 |
| `core-data` | 52 | 62 |
| `core-domain` | 13 | 13 |
| `core-ui` | 8 | 8 |
| `feature-auth` | 43 | 55 |
| `feature-notes` | 165 | 199 |
| `feature-settings` | 21 | 32 |
| **total** | **338** | **432** |

Every one of the 94 removed files was inspected by name. All 94 are generator output — the full
list is names like `SingleNoteViewModel_Factory`, `NoteDao_Impl`, `Hilt_MainActivity`,
`ComposableSingletons$NotesListScreenKt` and the `hilt_aggregated_deps` tree. **No hand-written
class was caught by any pattern.**

Three caveats on that table:

* It counts `.class` *files*, which is only a rough proxy for the instruction counts JaCoCo
  actually reports.
* It says nothing about how much of the kept code is covered, which remains unmeasured.
* Not every pattern fires today. `R.class`, `R$*.class`, `Manifest*`, `*_ComponentTreeDeps`,
  `Dagger*` and `$$serializer` currently match nothing in the scanned tree — those class files
  either do not exist in this project or live outside the directories the report reads (the
  `DaggerMainApplication_HiltComponents_SingletonC` family, for instance, is written by the Hilt
  Gradle plugin into `build/intermediates/classes/`, which is deliberately not scanned because it
  also contains an ASM-transformed *duplicate* of every class already counted). They are kept as
  cheap insurance against a future build-configuration change, not because they are load-bearing.

### What is deliberately NOT excluded: Compose UI

The obvious next exclusion would be `**/ui/**`, and it is common advice, because Compose UI cannot
be unit-tested in this project at all: `androidx.compose.ui:ui-test-junit4` pulls in
`kotlinx-coroutines-test`, which lives on Maven Central and does not resolve here.

**It is not excluded, and the reason is specific to this codebase rather than a general principle.**
In this project, `ui/` is not a UI package. It contains:

```
app/…/ui/MainActivity.kt
feature-auth/…/ui/AuthViewModel.kt
feature-notes/…/ui/list/NotesListViewModel.kt
feature-notes/…/ui/single/SingleNoteViewModel.kt
feature-notes/…/ui/single/EditorHistory.kt
feature-notes/…/ui/trash/TrashViewModel.kt
feature-settings/…/ui/SettingsViewModel.kt
```

Of the fourteen `.kt` files under a `ui/` directory, seven contain no `@Composable` at all. Those
seven include **all five ViewModels** — which are, by some distance, the highest-risk untested code
in the repository — and `EditorHistory`, the undo/redo stack, which *is* already covered by
`EditorHistoryTest`. A package-level `**/ui/**` exclusion would therefore do the two worst things
at once: delete the biggest known gap from the denominator, and delete tested code from the
numerator.

The honest version of the argument for excluding Compose screens is "measuring something you have
decided not to test is noise". The honest version against is "a gap you have decided not to fix is
still a gap, and a number that hides it stops anyone arguing about it". Given that this project's
ViewModels sit in the same directory as its screens, the second wins on mechanics alone — a
directory pattern cannot separate them here.

If someone later wants a Composables-excluded variant of the number, the right way to get it is to
move the ViewModels out of `ui/` first, not to widen the pattern.

---

## 4. What was actually verified, and what was not

Everything below was run on 2026-08-30 in a git worktree of this repository, on the branch that
introduced this configuration.

| # | Command | Result |
|---|---|---|
| 1 | `./gradlew tasks --all` | **Succeeded.** `jacocoMergedReport` is registered on the root project. |
| 2 | `./gradlew -Pcoverage :app:tasks --all` and `… :core-domain:tasks --all` | **Succeeded.** `createDebugUnitTestCoverageReport` appears in both, confirming AGP sees `enableUnitTestCoverage`. |
| 3 | `./gradlew :core-domain:testDebugUnitTest`, with instrumentation forced on | **FAILED**, and this is the central limitation: `Could not resolve all files for configuration ':core-domain:jacocoAgent'. > Could not resolve org.jacoco:org.jacoco.agent:0.8.14 … Could not GET 'https://repo.maven.apache.org/maven2/org/jacoco/org.jacoco.agent/0.8.14/org.jacoco.agent-0.8.14.pom'. Received status code 403 from server: Forbidden` |
| 4 | `./gradlew jacocoMergedReport --dry-run` | **Succeeded.** All eight `:<module>:testDebugUnitTest` tasks plus `:jacocoMergedReport` are in the graph — the aggregation really does reach every module. |
| 5 | `./gradlew jacocoMergedReport` | **FAILED**, with exactly the 403 from row 3. This is also the proof that the task-name switch works: without instrumentation it would have failed on missing `.exec` data instead. |
| 6 | `./gradlew :app:assembleDebug` | **Succeeded.** |
| 7 | `./gradlew test` | **Succeeded. 181 tests, 0 failures** (counted from the `tests=` and `failures=` attributes of the JUnit XML in `*/build/test-results/testDebugUnitTest/`). `test` runs the debug variant only in this project — no `testReleaseUnitTest` results are produced. |
| 8 | A throwaway Gradle task listing the class-file trees the report will read | **Succeeded** — this is where the 338/432 figures in §3 come from. The task was removed before commit. |
| 9 | `./gradlew --configuration-cache test` | **Succeeded**, "Configuration cache entry stored". The default (coverage-off) path is unaffected by this change. |
| 10 | `./gradlew --configuration-cache jacocoMergedReport --dry-run` | **FAILED** — but on the same 403, while Gradle was serializing `:app:jacocoAgent`. Whether `jacocoMergedReport` is configuration-cache compatible therefore **could not be determined here**; the build never got far enough to find out. This project does not enable the configuration cache today. |

### What remains unverified

* **The report itself.** No HTML page and no XML file has ever been generated. Their contents,
  their exact internal structure, and whether JaCoCo is happy with the class/source pairing are
  all unconfirmed.
* **Any coverage percentage.** Unknown, for every module and for the project.
* **That the `.exec` glob finds the execution data.** The report reads `**/*.exec` and `**/*.ec`
  under each module's build directory, because AGP has moved that output between releases and a
  stale hard-coded path fails silently. No `.exec` file has ever existed here to confirm the glob
  against. If it turns out to be wrong, the task's `doFirst` check will say so explicitly rather
  than reporting 0%.
* **Cross-module class/source alignment in the merged report.** Merging eight modules' class trees
  and source trees into one `JacocoReport` is standard, but it has not been executed once.
* **Configuration-cache compatibility of `jacocoMergedReport`** (row 10 above).

The first person to run this on a machine with normal Maven Central access should expect to spend a
few minutes on it, not zero. If it fails, the two `doFirst` messages in the root
`build.gradle.kts` name the exact variable to fix.

---

## 5. No threshold, on purpose

There is no `JacocoCoverageVerification` task and no `violationRules { }` gate, and adding one now
would be a mistake. Nobody has seen this project's number. A threshold picked before the first
measurement is either low enough to be meaningless or high enough to block every build, and both
outcomes teach people to bypass the gate. Set one once there is a real baseline to hold.

---

## 6. How to read the number when you finally get one

This matters more than the configuration does, because the headline percentage this produces will
be **actively misleading about where the risk is** in this codebase.

Coverage here is not spread thinly and evenly. It is concentrated almost entirely in **pure
functions** — sort ordering, trash retention policy, checklist serialization, folder naming, note
export, search matching, the lockout policy, the passphrase cipher, the editor's undo stack. Those
are well tested — and per `docs/design/test-coverage-review.md`, several of those tests are genuine
regression tests written against bugs that actually shipped.

Meanwhile every **stateful** component has none:

* `SingleNoteViewModel`, `NotesListViewModel`, `AuthViewModel`, `TrashViewModel`,
  `SettingsViewModel` — no unit tests.
* `SecureUnlockManager` — no unit tests. This is the class that decides whether the database key
  is recoverable.
* `RoomNotesRepository`, `DataStoreSettingsRepository` — no unit tests.
* All Compose UI — untestable in this environment at all (§3).

So the two halves of the codebase behave completely differently under measurement, and the single
average across them describes neither:

* **A high number would not mean the app is safe.** The pure functions are small and numerous, so
  they can carry the average on their own while the sequencing, latching and mutex discipline that
  decides whether a note survives an edit remains entirely unexercised.
* **A low number would not mean the testing is bad.** The tests that exist are unusually good for
  their size.
* **The number will move for the wrong reasons.** Adding a handful of small pure helpers raises it.
  Adding the first real `SingleNoteViewModel` test — by far the most valuable test anyone could
  write here — may barely move it, because one test cannot cover 729 lines.

Use the **per-class table in the HTML report**, not the headline figure. The question worth asking
of this report is *"is `SecureUnlockManager` still at zero?"*, not *"did we go up this sprint?"*.

The reason the stateful half is untested is not indifference. Per
**`docs/design/test-coverage-review.md`**, which checked each of them by running the build,
`kotlinx-coroutines-test`, Turbine, MockK, Mockito, Robolectric and Truth all live on Maven Central
and none of them resolve in this environment — so there is currently no way to drive a coroutine
or fake a repository in a unit test here. That constraint, and what can be tested despite it, is
worked through in detail in that document. (Those specific resolution results were verified there,
not re-run for this document.)

> **Note:** at the time of writing, `test-coverage-review.md` is not on `master` — it is arriving
> in a separate pull request from the branch `test-coverage-review`. If the link above is dead,
> that PR has not merged yet.
