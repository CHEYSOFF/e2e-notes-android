import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // Applied without versions: AGP puts both plugins on the build classpath itself, and asking
    // for a version fails with "already on the classpath with an unknown version".
    // `com.android.library` is not an option -- since AGP 9 it refuses to co-exist with the Kotlin
    // multiplatform plugin. See core-domain/build.gradle.kts.
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    androidLibrary {
        namespace = "my.cheysoff.core_pairing"
        compileSdk = 36
        // 24 rather than :feature-pairing's 31. Nothing here needs 31 -- P-256 through plain JCA
        // has been on the platform since API 23, and zxing is pure Java. The 31 floor belongs to
        // CameraX, which is in :feature-pairing and stays there.
        minSdk = 24
    }
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }

    // Everything here is in `jvmCommonMain`, not `commonMain`, and for the same reason
    // :core-crypto-shared gives: the protocol is built on the JCA -- `KeyAgreement`,
    // `KeyPairGenerator`, `Cipher`, `SecureRandom` -- so it is JVM-bound by construction rather
    // than by neglect. Putting it in `commonMain` behind an `expect` seam would need real Apple
    // actuals producing byte-identical output, which is a piece of work with its own verification
    // and not a source-set rearrangement.
    //
    // Wired with explicit `dependsOn` rather than through `applyDefaultHierarchyTemplate`: the
    // template's `withAndroidTarget()` matches the OLD `androidTarget()`, not AGP 9's
    // `androidLibrary`, so it links NOTHING and `compileAndroidMain` silently reports NO-SOURCE --
    // the same trap :core-crypto-shared and :core-sync-net both document.
    sourceSets {
        val commonMain by getting
        val jvmCommonMain by creating {
            dependsOn(commonMain)
            dependencies {
                // HKDF-SHA256, and the ARK size constant. This module deliberately does NOT carry
                // its own: the RFC 5869 copy the pairing tests once bound was deleted rather than
                // kept alongside it, because two implementations of one protocol primitive each
                // pass their own tests and disagree only on two real devices. See KeyDerivation.
                implementation(project(":core-crypto-shared"))

                // `com.google.zxing:core` is pure Java with no Android dependency at all -- no
                // Bitmap, no Activity, no resources -- which is why the encoder and decoder can
                // live in a module the desktop app also consumes. Turning a matrix into something
                // drawable is per-platform and stays out: :feature-pairing makes an
                // `android.graphics.Bitmap`, :desktop draws the modules with a Compose Canvas.
                implementation(libs.zxing.core)

                // Used through its JsonElement API only -- the rendezvous bodies are two objects
                // with one string field each -- so there is no `@Serializable` class here and
                // therefore no serialization compiler plugin. :desktop makes the same choice for
                // the same reason; see RecordPayload there.
                implementation(libs.kotlinx.serialization.json)
            }
        }
        val androidMain by getting { dependsOn(jvmCommonMain) }
        val jvmMain by getting { dependsOn(jvmCommonMain) }

        val jvmTest by getting {
            dependencies {
                implementation(libs.junit)
                // zxing is `implementation` above, so it is not on the test compile classpath by
                // inheritance. PhoneReadingAScreenshot needs it -- it reads a real screenshot of the
                // desktop app through the production decoder -- so it is named here too.
                implementation(libs.zxing.core)
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    // Gradle does not pass its own -D properties down into the test JVM, so PhoneReadingAScreenshot
    // -- the fixture that reads a QR code off a screenshot of the running desktop app and plays the
    // phone's half against a real server -- would silently skip instead of running. Forwarded
    // explicitly rather than set unconditionally, so an ordinary test run still skips it. The same
    // arrangement :desktop uses for DemoVaultProvisioner.
    System.getProperty("manana.qrScreenshot")?.let { systemProperty("manana.qrScreenshot", it) }
    System.getProperty("manana.pairingServer")?.let { systemProperty("manana.pairingServer", it) }
}
