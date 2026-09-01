import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // Applied without versions: AGP puts both plugins on the build classpath itself, and asking
    // for a version fails with "already on the classpath with an unknown version". `com.android.library`
    // is not an option -- since AGP 9 it refuses to co-exist with the Kotlin multiplatform plugin.
    // See core-domain/build.gradle.kts.
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    androidLibrary {
        namespace = "my.cheysoff.core_sync_net"
        compileSdk = 36
        minSdk = 24
    }
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }

    // The Apple targets. NONE of them has ever been compiled -- the Kotlin/Native Apple compilers
    // only run on macOS -- and this module is where that matters most, because `appleMain` here is
    // not a thin adapter: it carries the certificate pin. See `SyncEngine.apple.kt`, and read
    // docs/BUILDING-IOS.md before trusting a build of it.
    iosArm64()
    iosSimulatorArm64()
    iosX64()
    macosArm64()

    // No `mingwX64()` canary, unlike :core-domain, and for a concrete reason rather than by
    // omission: `commonMain` here needs an HTTP engine, and Ktor ships none for mingw. A canary
    // target would have to be satisfied by an engine that ships in no product, which would prove
    // that a stub compiles rather than that the transport is portable. What the transport actually
    // rests on -- one client, one set of protocol decisions, an engine underneath -- is checked by
    // `commonMain` compiling for two targets whose engines are wired independently.
    //
    // Wired with explicit `dependsOn` rather than through `applyDefaultHierarchyTemplate`, which is
    // the same trap :core-crypto-shared documents: the template's `withAndroidTarget()` matches the
    // OLD `androidTarget()`, not AGP 9's `androidLibrary`, so it links NOTHING here.
    // `compileAndroidMain` then reports NO-SOURCE, the JVM target still has every class, and only a
    // consumer of the Android variant discovers the jar is empty.
    sourceSets {
        val commonMain by getting {
            dependencies {
                // For `Base64Url.encode` and the protocol's size constants. This module deliberately
                // does NOT re-implement either: a second base64url encoder or a second
                // `ACCOUNT_ID_BYTES` is exactly the "two implementations that disagree" failure this
                // project has already shipped once.
                implementation(project(":core-crypto-shared"))

                // The multiplatform half of the HTTP client. The ENGINE is per-target and lives in
                // the source sets below; nothing in `commonMain` names one.
                implementation(libs.ktor.client.core)

                // `suspend` alone needs only the stdlib; `delay` (the 429 back-off) and `Mutex` (the
                // session token) do not.
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        // Android and the JVM share one engine -- OkHttp -- and therefore share the code that
        // configures it, including the certificate pinner. Splitting that between `androidMain` and
        // `jvmMain` would be two copies of the one piece of code where a mistake means an unpinned
        // connection through a pinned-looking client.
        //
        // The Apple half sits beside this one rather than under it -- see `appleMain` below.
        val jvmCommonMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(libs.ktor.client.okhttp)
                // Named directly, not taken transitively from the engine: `CertificatePinner` and
                // `SSLPeerUnverifiedException` are OkHttp types this module's own code compiles
                // against, and a compile dependency that arrives by accident disappears by accident.
                implementation(libs.okhttp)
            }
        }
        val androidMain by getting { dependsOn(jvmCommonMain) }
        val jvmMain by getting { dependsOn(jvmCommonMain) }

        // The Apple half: the same two actuals over Ktor's Darwin engine, which is
        // `NSURLSession`. It is a sibling of `jvmCommonMain` rather than a child of it -- there is
        // nothing the OkHttp code and the `NSURLSession` code could usefully share, since the
        // whole reason those two functions are `expect` is that pinning has no portable spelling.
        //
        // `maybeCreate` rather than `by creating` so this keeps working if a future Kotlin version
        // does apply the default hierarchy template to this module, in which case `appleMain`
        // already exists and creating it again would fail the build.
        val appleMain = maybeCreate("appleMain").apply {
            dependsOn(commonMain)
            dependencies {
                // The engine. Declared here and nowhere else: `commonMain` names no engine, which
                // is the property that keeps this "one client, swappable engines" rather than two
                // clients.
                implementation(libs.ktor.client.darwin)
            }
        }
        listOf("iosArm64", "iosSimulatorArm64", "iosX64", "macosArm64").forEach { target ->
            getByName("${target}Main").dependsOn(appleMain)
        }

        val jvmTest by getting {
            dependencies {
                implementation(libs.junit)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

// Everything in this module is plain Kotlin -- the transport seam exists precisely so that the
// client can be tested without Android, without a network and without a server. The suite therefore
// lives in `jvmTest` and runs as `:core-sync-net:jvmTest`; the root build script's
// `plugins.withId("org.jetbrains.kotlin.multiplatform")` block is what keeps a bare `./gradlew test`
// running it.
tasks.withType<Test>().configureEach {
    // The contract test in SyncServerContractTest starts the real server from `server/` and drives
    // it over real HTTP. It is opt-in because it needs a JDK 17+ toolchain and one
    // `server/gradlew installDist`, neither of which a plain `./gradlew test` should require. Run it
    // with:
    //
    //     ./gradlew :core-sync-net:jvmTest -PsyncContract
    //
    // See the test's own KDoc for what it covers and why it exists.
    systemProperty(
        "manana.sync.contract",
        if (project.hasProperty("syncContract")) "true" else "false",
    )
    // The contract test needs to find `server/` and the repository root, and so does
    // WireFieldNamesAreInOnePlaceTest. Gradle sets the test JVM's working directory to the module
    // directory, which is one level below the root, but that is an implementation detail of the
    // Gradle test task rather than a contract -- so the root is passed explicitly instead of
    // derived.
    systemProperty("manana.repo.root", rootDir.absolutePath)
}
