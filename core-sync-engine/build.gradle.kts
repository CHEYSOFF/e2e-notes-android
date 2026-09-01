import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // Applied without a version, for the reason core-domain/build.gradle.kts spells out: AGP puts
    // the Kotlin Gradle plugin on the build classpath itself, so naming a version here fails with
    // "already on the classpath with an unknown version".
    id("org.jetbrains.kotlin.multiplatform")
    // NOT `com.android.library`; since AGP 9 that plugin refuses to co-exist with the Kotlin
    // multiplatform plugin. The Android options live inside the `kotlin` block below.
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    androidLibrary {
        namespace = "my.cheysoff.core_sync_engine"
        compileSdk = 36
        minSdk = 24
    }
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }

    // The same canary target :core-domain carries, for the same reason: iOS can only be compiled on
    // a Mac, so `mingwX64` is the one Kotlin/Native target that builds on this machine and it is
    // therefore the only thing stopping JVM-only code from drifting into `commonMain`. This module
    // is the sync coordinator and it has no business touching a platform API at all, so losing the
    // check here would be losing it where it matters most.
    mingwX64()

    // The Apple targets, for the reasons :core-domain's build script sets out at length. They
    // compile here and have never been linked or run. This module is the sync coordinator and
    // touches no platform API at all, so it is the one with the least to go wrong on a new backend
    // and the most to lose if the mingw canary above is ever dropped.
    iosArm64()
    iosSimulatorArm64()
    iosX64()
    macosArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                // `Merge`, `SyncRecord`, `Hlc` and the rest of the merge core. This module is the
                // loop around them and adds no merge rules of its own.
                api(project(":core-domain"))
                // For `Mutex` (one pass at a time) and `suspend`. No `Dispatchers` and no `delay`:
                // the engine never waits, it reports how long the caller should wait instead.
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(libs.junit)
                // `runBlocking`, to drive the suspending pass from a plain JUnit test.
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}
