// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
    }
}
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.dagger.hilt) apply false
    alias(libs.plugins.ksp) apply false
    // Applied to the root project only. The root project builds nothing of its own; it hosts the
    // whole-project `jacocoMergedReport` task below, and applying the plugin here is what gives
    // that task a `jacocoClasspath` convention (the `jacocoAnt` configuration) to run with.
    // The eight real modules do NOT need this plugin: AGP wires JaCoCo into their unit tests by
    // itself once `enableUnitTestCoverage` is set.
    jacoco
}

// ---------------------------------------------------------------------------------------------
// Code coverage
// ---------------------------------------------------------------------------------------------
// See docs/design/code-coverage.md for how to run this, what is excluded, and — importantly — how
// to read the number that comes out.
//
// Everything coverage-related lives in this one file on purpose. The eight module build scripts
// are otherwise near-identical boilerplate already, and the project has no buildSrc directory, no
// included build and no convention plugins; introducing one just to set a single boolean and
// register one report task would add a build-logic module that nothing else in the repo uses.
// A `subprojects { plugins.withId(...) }` block keeps the whole feature in one readable place, and
// any new module added to settings.gradle.kts picks the instrumentation up automatically as long
// as it applies the Android application or library plugin (which all eight current modules do).
// ---------------------------------------------------------------------------------------------

// Pinned rather than left to defaults, because the two defaults in play here do not agree.
// Measured on this machine on 2026-08-30 by printing them from the build:
//   - Gradle 9.4.1's `jacoco` plugin defaults `toolVersion` to 0.8.14 (this drives the *report*).
//   - AGP 9.0.1 defaults `android.testCoverage.jacocoVersion` to 0.8.13 (this drives the *agent*
//     that instruments the unit tests and writes the .exec files).
// Leaving that mismatch in place means reporting 0.8.13 execution data with a 0.8.14 tool. Pinning
// both sides to one value removes the question entirely.
val jacocoToolVersion = "0.8.14"

jacoco {
    toolVersion = jacocoToolVersion
}

// Instrumenting the unit tests is opt-in, for one concrete reason: an instrumented
// `testDebugUnitTest` resolves `org.jacoco:org.jacoco.agent` from Maven Central, and on a machine
// that cannot reach Maven Central the test task fails before a single test runs. That was verified
// here on 2026-08-30 — with coverage forced on, `./gradlew :core-domain:testDebugUnitTest` failed
// with "Could not resolve org.jacoco:org.jacoco.agent:0.8.14 … Received status code 403". Making
// instrumentation unconditional would therefore break plain `./gradlew test` for anyone in that
// situation, so the switch below defaults to off.
//
// It flips on in either of two ways:
//   1. `-Pcoverage` anywhere on the command line — the explicit, always-works escape hatch.
//   2. `jacocoMergedReport` being one of the task names actually typed on the command line, so
//      that `./gradlew jacocoMergedReport` is a single self-contained command. This inspects only
//      the literal task names in the start parameters; a build that reaches the report task some
//      other way (an IDE run configuration that rewrites the request, or `dependsOn` from a task
//      of your own) will NOT trip it, and needs `-Pcoverage`.
val coverageRequestedByProperty = providers.gradleProperty("coverage").isPresent
val coverageRequestedByTaskName = gradle.startParameter.taskNames.any {
    it.substringAfterLast(':') == "jacocoMergedReport"
}
val coverageEnabled = coverageRequestedByProperty || coverageRequestedByTaskName

subprojects {
    // `plugins.withId` fires when (and only when) the module applies the plugin itself, so this
    // does not depend on the order the module build scripts are evaluated in, and it silently does
    // nothing for a module that is neither an Android app nor an Android library.
    // ApplicationExtension and LibraryExtension are used instead of their shared CommonExtension
    // supertype because CommonExtension is generic over six type parameters whose arity has changed
    // between AGP releases; these two interfaces are not generic.
    plugins.withId("com.android.application") {
        extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
            testCoverage.jacocoVersion = jacocoToolVersion
            // configureEach rather than named("debug") so that nothing here assumes the debug build
            // type already exists at the moment the plugin is applied.
            buildTypes.configureEach {
                if (name == "debug") enableUnitTestCoverage = coverageEnabled
            }
        }
    }
    plugins.withId("com.android.library") {
        extensions.configure<com.android.build.api.dsl.LibraryExtension> {
            testCoverage.jacocoVersion = jacocoToolVersion
            buildTypes.configureEach {
                if (name == "debug") enableUnitTestCoverage = coverageEnabled
            }
        }
    }

    // Make the JaCoCo agent see classes Robolectric loaded.
    //
    // Robolectric does not run tests against the classes on the test classpath. It reads their
    // bytes itself, rewrites them (that is how a call into the Android framework reaches a shadow),
    // and defines them in its own SandboxClassLoader with a ProtectionDomain that has no code
    // source. The JaCoCo agent skips such classes by default — `inclnolocationclasses` is false —
    // because on a normal JVM "no location" usually means a runtime-synthesized proxy, not real
    // code. The result is not an error: the tests run, pass, and simply contribute nothing.
    //
    // Measured here on 2026-08-30: with the flag off, `SecureUnlockManagerTest` (37 passing tests)
    // and `RoomNotesRepositoryTest` (28 passing tests) left SecureUnlockManager at 0/134 lines and
    // RoomNotesRepository at 0/53. Turning it on is what makes those tests count.
    //
    // `jdk.internal.*` is excluded because those classes are loaded by the bootstrap loader, which
    // also reports no location, and instrumenting them fails with IllegalAccessError on JDK 9+.
    // It is the exclusion the JaCoCo docs pair with this option.
    //
    // findByType rather than configure: the extension is contributed by AGP when unit-test
    // coverage is on, so on a normal (`coverageEnabled == false`) build there is nothing here and
    // this must do nothing rather than fail.
    if (coverageEnabled) {
        tasks.withType<Test>().configureEach {
            doFirst {
                // AGP builds the -javaagent argument itself, through a CommandLineArgumentProvider
                // rather than the Gradle `jacoco` task extension, and hardcodes
                // `inclnolocationclasses=false` into it. There is no DSL for that option, so each
                // provider is wrapped in one that rewrites the flag on its way out. Wrapping rather
                // than replacing keeps every other argument — and every provider's task inputs —
                // exactly as AGP built them.
                val originals = jvmArgumentProviders.toList()
                jvmArgumentProviders.clear()
                originals.forEach { original ->
                    jvmArgumentProviders.add(
                        CommandLineArgumentProvider {
                            original.asArguments().map { arg ->
                                if (arg.startsWith("-javaagent:") && arg.contains("jacocoagent.jar")) {
                                    // `excludes` is not optional alongside the flag above. Once
                                    // location-less classes are in scope, the agent also tries to
                                    // instrument the accessor classes the JDK synthesizes for
                                    // reflection, and the test JVM dies at startup with
                                    // NoClassDefFoundError:
                                    // jdk/internal/reflect/GeneratedSerializationConstructorAccessor1.
                                    // Observed here before this exclusion was added.
                                    arg.replace(
                                        "inclnolocationclasses=false",
                                        "inclnolocationclasses=true,excludes=jdk.internal.*",
                                    )
                                } else {
                                    arg
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

// Class files that exist only because a code generator put them there, plus the Android build's
// own bookkeeping classes. Measured on 2026-08-30 after a full `:app:assembleDebug`, these
// patterns remove 94 of the 432 production .class files in the tree scanned below (~22%) — all 94
// of them generator output, none of them hand-written. That is code no human wrote and no test
// should be expected to cover, and leaving it in is the single biggest reason raw Android coverage
// numbers are meaningless.
//
// The patterns are Ant-style and are matched against paths *relative to each module's build
// directory*, which is why every one of them starts with `**/`.
//
// What is deliberately NOT excluded: `**/ui/**`. See docs/design/code-coverage.md §Exclusions —
// in this project that directory holds all five ViewModels and `EditorHistory`, so excluding it
// would remove both the least-tested and some of the best-tested code in the repo.
val coverageExclusions = listOf(
    // --- Android build plumbing ---------------------------------------------------------------
    // Of these four, only BuildConfig currently matches anything. AGP 9 packages the R classes into
    // build/intermediates/compile_r_class_jar/debug/generateDebugRFile/R.jar rather than emitting
    // loose .class files into the directories scanned below, and no module here generates a
    // Manifest class. The other three are kept as cheap insurance, not because they are needed.
    "**/R.class",
    "**/R\$*.class",
    "**/BuildConfig.*",
    "**/Manifest*.*",

    // --- Hilt / Dagger ------------------------------------------------------------------------
    // Hilt annotation-processor output. All of these except `Dagger*` and `*_ComponentTreeDeps`
    // were observed firing on this project on 2026-08-30. `Dagger*` matched nothing because the
    // DaggerXxx_HiltComponents classes are written by the Hilt Gradle plugin's own
    // `hiltJavaCompileDebug` task into build/intermediates/classes/, a tree that
    // `debugClassPatterns` below deliberately does not scan; `*_ComponentTreeDeps` matched nothing
    // because this build does not currently produce that class. Both are kept as insurance.
    "**/hilt_aggregated_deps/**",
    "**/dagger/hilt/internal/**",
    "**/*_Factory*.*",
    "**/*_MembersInjector*.*",
    "**/*_HiltModules*.*",
    "**/*_GeneratedInjector.*",
    "**/*_ComponentTreeDeps.*",
    "**/Dagger*.*",
    "**/Hilt_*.*",

    // --- Room ---------------------------------------------------------------------------------
    // Room writes the real DAO and database bodies into `<Name>_Impl`. Those are the generated
    // SQL-binding methods; the hand-written half is the abstract @Dao / @Database declaration.
    "**/*_Impl*.*",

    // --- Compose ------------------------------------------------------------------------------
    // The Compose compiler lifts every constant lambda in a file into a synthetic
    // `ComposableSingletons$<FileName>Kt` class. Confirmed present in this project, e.g.
    // ComposableSingletons$NotesListScreenKt in :feature-notes.
    "**/ComposableSingletons*.*",

    // --- kotlinx.serialization ------------------------------------------------------------------
    // No module in this project applies the kotlinx.serialization plugin today, so this pattern
    // currently matches nothing. It is here so that adding serialization later cannot quietly
    // inflate the denominator with generated `$$serializer` classes before anyone notices.
    "**/*\$\$serializer.*",
)

// Directories AGP/Kotlin actually compile production classes into. Both entries were confirmed by
// listing the build directories of :core-domain and :feature-notes on 2026-08-30 with AGP 9.0.1
// and Gradle 9.4.1's built-in Kotlin support:
//   intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes      (Kotlin)
//   intermediates/javac/debug/compileDebugJavaWithJavac/classes          (Java, incl. Hilt output)
// Note that neither is the `build/tmp/kotlin-classes/debug` path that most published JaCoCo
// recipes hard-code — that layout predates AGP's built-in Kotlin compilation.
//
// That legacy path is deliberately NOT listed as a fallback, which was tried and does not work.
// It still exists in this project and still holds .class files, but they are STALE output from an
// earlier compilation: for MainApplication$onCreate$1 the two copies are 2011 and 3138 bytes,
// i.e. genuinely different bytecode for the same class name. Feeding JaCoCo both makes it fail
// the whole report with `IOException: Error while analyzing MainApplication$onCreate$1.class`,
// not merge them or prefer one. A module compiling through the standalone Kotlin Android plugin
// would need this path added back — and the duplicate resolved — rather than simply listed.
//
// The `debug` segment is what keeps unit-test classes out: those land under `debugUnitTest`.
//
// `build/intermediates/classes/` is deliberately absent. It holds two things that would corrupt
// the report: `transformDebugClassesWithAsm/dirs/`, which is a post-processed *copy* of classes
// already counted above and would double-count every one of them, and `hiltJavaCompileDebug/`,
// which is pure Hilt component output.
val debugClassPatterns = listOf(
    "intermediates/built_in_kotlinc/debug/*/classes/**",
    "intermediates/javac/debug/*/classes/**",
)

tasks.register<JacocoReport>("jacocoMergedReport") {
    group = "verification"
    description =
        "Runs every module's debug unit tests with coverage instrumentation and merges the result " +
            "into one HTML + XML report at build/reports/jacoco/merged/."

    // Depend on the test tasks rather than on each module's per-module
    // `createDebugUnitTestCoverageReport`: the only thing this task needs from them is the .exec
    // execution data, which `testDebugUnitTest` is what actually writes. Running the eight
    // per-module reports as well would just be eight extra HTML trees nobody reads.
    dependsOn(subprojects.map { "${it.path}:testDebugUnitTest" })

    val moduleClassDirs = subprojects.map { sub ->
        sub.fileTree(sub.layout.buildDirectory) {
            include(debugClassPatterns)
            exclude(coverageExclusions)
        }
    }
    val moduleSourceDirs = subprojects.flatMap { sub ->
        // The project keeps its Kotlin under src/main/java. src/main/kotlin is listed too so a
        // module that follows the other convention is not silently reported without sources.
        listOf(sub.file("src/main/java"), sub.file("src/main/kotlin"))
    }
    // Globbed rather than hard-coded because AGP has moved its unit-test coverage output more than
    // once across releases, and a stale hard-coded path fails SILENTLY rather than erroring. Note
    // the miss is usually partial, not total: the pre-built-in-kotlinc directory still exists in
    // this project and still holds some classes, so a stale path understates coverage instead of
    // reporting an obvious zero. The `doFirst` check below can only catch the total case.
    val moduleExecutionData = subprojects.map { sub ->
        sub.fileTree(sub.layout.buildDirectory) { include("**/*.exec", "**/*.ec") }
    }

    classDirectories.setFrom(moduleClassDirs)
    sourceDirectories.setFrom(moduleSourceDirs)
    executionData.setFrom(moduleExecutionData)

    reports {
        // XML is the format every CI and coverage service reads; HTML is the one a human reads.
        // CSV is off because nothing consumes it here.
        xml.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/merged/jacocoMergedReport.xml"))
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/merged/html"))
        csv.required.set(false)
    }

    doFirst {
        // Both of these are silent failure modes rather than errors if left unchecked, and both are
        // what a future AGP directory-layout change would look like from here. They catch only the
        // TOTAL miss; a layout change that still matched one of the globs would report a partial
        // number that these guards cannot distinguish from a real one.
        if (classDirectories.isEmpty) {
            error(
                "jacocoMergedReport found no production class files. The compiled-output layout " +
                    "has probably moved; update `debugClassPatterns` in the root build.gradle.kts."
            )
        }
        if (executionData.isEmpty) {
            error(
                "jacocoMergedReport found no JaCoCo execution data (.exec). Either the unit tests " +
                    "did not run instrumented — pass -Pcoverage — or AGP's coverage output path " +
                    "has moved; see `moduleExecutionData` in the root build.gradle.kts."
            )
        }
    }
}

// Deliberately absent: any `JacocoCoverageVerification` task or `violationRules { }` gate.
// Nobody has seen this project's number yet. A threshold picked before the first measurement is
// either set low enough to be meaningless or high enough to block every build, and both outcomes
// teach the team to bypass it. Add one once there is a real baseline to hold the line at.
