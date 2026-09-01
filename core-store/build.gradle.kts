import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // Applied without a version, for the reason core-domain/build.gradle.kts spells out: AGP puts
    // the Kotlin Gradle plugin on the build classpath itself, so naming a version here fails.
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.sqldelight)
}

kotlin {
    // No Android target, and that is deliberate rather than an omission. Android already has a
    // `NotesRepository`: `RoomNotesRepository`, over a SQLCipher-encrypted Room database, with
    // three migration tests behind it. This module is the record-shaped store the desktop and
    // Apple builds need, and adding an Android target would put a second implementation of the
    // same interface on the same platform -- the thing the brief for this work says not to do.
    //
    // Whether Android should eventually MOVE to this store is a real question and a good one: a
    // record-shaped store makes at-rest security equal on-the-wire security and makes the device a
    // sync replica by construction, which is a stronger position than "an encrypted database that
    // also syncs". It is not this branch's question.
    //
    // The JVM target IS a product target -- it is what a Compose Desktop build would persist notes
    // with -- and it is also what makes this module testable on a machine with no Apple toolchain.
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }

    // The Apple targets. Never compiled here.
    iosArm64()
    iosSimulatorArm64()
    iosX64()
    macosArm64()

    // No `mingwX64()` canary, and unlike :core-crypto-shared this one is not even possible: the
    // module depends on :core-crypto-shared, which has no mingw actuals, and on SQLDelight's
    // drivers, which are published for Apple and the JVM but not for mingw.
    //
    // What replaces it is better than a canary anyway, and it is the reason this module is built
    // the way it is. SQLDelight's JVM driver is a real SQLite, so `RecordStore` and
    // `RecordNotesRepository` -- every line of logic in this module -- are exercised against a real
    // database by `jvmTest`, on this machine. What is left unverified is `RecordDriver.apple.kt`:
    // five lines that hand back a `NativeSqliteDriver`. That is as small as the untested surface
    // of an iOS store gets.

    sourceSets {
        val commonMain by getting {
            dependencies {
                // `Note`, `Folder`, `NotesRepository`, `SyncRecord`, `Hlc`, `Merge`'s field rules.
                api(project(":core-domain"))
                // `RecordEnvelope`, `BlindedRecordId`, `AccountKeys`.
                implementation(project(":core-crypto-shared"))
                // `RecordPayload` -- the sealed payload format. Deliberately NOT re-implemented
                // here: a store that wrote a different payload than the transport sends would be a
                // device that could not read back what it had synced.
                implementation(project(":core-sync-net"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.sqldelight.runtime)
                implementation(libs.sqldelight.coroutines)
            }
        }
        val commonTest by getting { dependencies { implementation(kotlin("test")) } }

        val jvmMain by getting {
            dependencies {
                // The JVM SQLite driver, over sqlite-jdbc.
                //
                // It is here rather than in `jvmTest` for a reason that is easy to get backwards.
                // The `expect` in `RecordDriver.kt` needs an actual on every target the module
                // has, so a JVM target with no `jvmMain` driver does not compile; and a test-only
                // driver would mean the JVM tests exercised a database the JVM product could not
                // open, which is the shape of a test that proves less than it looks like it does.
                //
                // The JVM target is therefore a real one, and it is what a Compose Desktop build
                // of this app would persist notes with. That it also lets the whole module be
                // tested against a real SQLite on a machine that cannot build for Apple is the
                // reason it was worth having on this branch.
                implementation(libs.sqldelight.driver.jvm)
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(libs.junit)
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        // Wired with explicit `dependsOn` for the same reason :core-crypto-shared documents, and
        // `maybeCreate` so it keeps working if the default hierarchy template is applied.
        val appleMain = maybeCreate("appleMain").apply {
            dependsOn(commonMain)
            dependencies { implementation(libs.sqldelight.driver.native) }
        }
        listOf("iosArm64", "iosSimulatorArm64", "iosX64", "macosArm64").forEach { target ->
            getByName("${target}Main").dependsOn(appleMain)
        }
    }
}

sqldelight {
    databases {
        create("RecordDatabase") {
            packageName.set("my.cheysoff.core_store.db")
            // Schema version 1, and there is no migration directory yet because there is no
            // shipped version to migrate from. When there is: `.sqm` files beside the `.sq` one,
            // and `verifyMigrations` turned on -- SQLDelight can check them at build time, which
            // is a stronger position than the Room migrations in :core-data, whose tests need a
            // device.
            verifyMigrations.set(true)
        }
    }
}
