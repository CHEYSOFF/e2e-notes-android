import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // Applied without a version: AGP puts the Kotlin Gradle plugin on the build classpath itself
    // (see the root buildscript block), so asking for a version here fails with "already on the
    // classpath with an unknown version".
    id("org.jetbrains.kotlin.multiplatform")
    // NOT `com.android.library`. Since AGP 9 that plugin refuses to co-exist with the Kotlin
    // multiplatform plugin and says so at configuration time; this is its multiplatform
    // counterpart, and it carries the Android options inside the `kotlin` block below rather than
    // in a top-level `android { }`.
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    androidLibrary {
        namespace = "my.cheysoff.core_domain"
        compileSdk = 36
        minSdk = 24
    }
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }

    // A Kotlin/Native target that ships in no product.
    //
    // The Apple targets can only be compiled on macOS, so on this machine nothing would otherwise
    // stop JVM-only code from drifting back into `commonMain` -- and it would not be noticed until
    // someone opened Xcode. `mingwX64` is the one Kotlin/Native target that DOES build on Windows,
    // so it stands in as a canary: if this module still compiles for it, the shared core is
    // genuinely free of the JVM and an Apple target has a real chance of building. It proves
    // portability, not that iOS works.
    //
    // Delete it the day a Mac is in CI; until then it is the only thing enforcing the property
    // this module's whole layout depends on.
    mingwX64()

    sourceSets {
        val commonMain by getting {
            // `api`, not `implementation`: Flow appears in this module's public repository
            // signatures, so every consumer needs it on their compile classpath. It used to
            // arrive transitively through androidx.core.ktx, which this module no longer takes --
            // those three AndroidX dependencies were unused Android Studio template leftovers.
            dependencies {
                api(libs.kotlinx.coroutines.core)
                // `kotlin.synchronized` exists only on the JVM. HlcGenerator guards its mutable
                // pair with a lock because Room's write coroutines are not thread-confined, so the
                // lock has to survive the move to common code rather than be dropped.
                implementation(libs.atomicfu)
            }
        }
        val jvmTest by getting { dependencies { implementation(libs.junit) } }
    }
}
