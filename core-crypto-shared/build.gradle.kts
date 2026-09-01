import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // Applied without versions: AGP puts both plugins on the build classpath itself, and asking
    // for a version fails with "already on the classpath with an unknown version". See
    // core-domain/build.gradle.kts for the AGP 9 rule about `com.android.library`.
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    androidLibrary {
        namespace = "my.cheysoff.core_crypto.shared"
        compileSdk = 36
        minSdk = 24
    }
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }

    // The Apple targets.
    //
    // `iosArm64` is a device, `iosSimulatorArm64` is the simulator on an Apple-silicon Mac,
    // `iosX64` is the simulator on an Intel Mac, and `macosArm64` is there for one specific
    // reason: it is the only Apple target whose tests run WITHOUT a simulator, so
    // `./gradlew :core-crypto-shared:macosArm64Test` is the shortest path from a fresh clone to a
    // yes-or-no answer on whether the Apple crypto agrees with the JVM. Run that first. See
    // docs/BUILDING-IOS.md.
    //
    // NONE of these have ever been compiled. The Kotlin/Native Apple compilers only run on macOS
    // and this module was written on Windows, where every one of these targets is disabled by the
    // Kotlin Gradle plugin (see `kotlin.native.ignoreDisabledTargets` in gradle.properties). What
    // has been verified here is the JVM and Android halves, which is a statement about the shared
    // code and not about `appleMain`.
    iosArm64()
    iosSimulatorArm64()
    iosX64()
    macosArm64()

    // NOTE: still no `mingwX64()` canary here, unlike :core-domain, but the reason has changed and
    // the old one no longer applies.
    //
    // It used to be that the crypto in this module WAS the JVM: `javax.crypto.Cipher`, `Mac`,
    // `SecretKeyFactory`, `java.security.SecureRandom`, sitting in `jvmCommonMain`, JVM-bound by
    // construction. That is no longer true. Every class in this module now lives in `commonMain`
    // and is written against the four `expect` functions in `my.cheysoff.core_crypto.platform`;
    // `jvmCommonMain` holds the JCA actuals and nothing else.
    //
    // So a canary is now *possible* -- and it would need mingw actuals, which would mean either a
    // Windows CNG binding that ships in no product or a hand-rolled AES-GCM, and the second of
    // those is a third implementation of the primitive whose whole problem is that
    // implementations must agree. The check a canary would give is instead given, more directly,
    // by `ProtocolVectorsTest`: it is `commonTest`, so it compiles and runs on every target the
    // module has, and it checks the answers rather than only the compilation.
    //
    // What is genuinely NOT checked on this machine is whether `appleMain` compiles at all. Only a
    // Mac can answer that.
    sourceSets {
        val commonMain by getting
        val commonTest by getting {
            dependencies {
                // `kotlin.test` rather than JUnit, because this source set compiles for Apple
                // targets too and JUnit does not. The existing JVM-only suites stay on JUnit; see
                // the `jvmTest` block below.
                implementation(kotlin("test"))
            }
        }

        val jvmCommonMain by creating { dependsOn(commonMain) }
        val androidMain by getting { dependsOn(jvmCommonMain) }
        val jvmMain by getting { dependsOn(jvmCommonMain) }

        // Wired with explicit `dependsOn` rather than through `applyDefaultHierarchyTemplate`.
        //
        // The template's `withAndroidTarget()` matches the OLD `androidTarget()`, not the
        // `androidLibrary` target that AGP 9's multiplatform plugin creates, so the template
        // silently links nothing: `compileAndroidMain` reports NO-SOURCE and the Android variant
        // ships an empty jar. That failure is quiet -- the module builds, its JVM target has every
        // class, and only a consumer of the Android variant discovers the classes are missing.
        //
        // The cost is a Gradle warning that the default template was not applied, which is
        // accurate and harmless: this module declares every source-set edge itself, here.
        //
        // `maybeCreate` rather than `by creating` for `appleMain` so that this keeps working if a
        // future Kotlin version does apply the template after all -- in which case `appleMain`
        // already exists and creating it again would fail the build.
        val appleMain = maybeCreate("appleMain").apply { dependsOn(commonMain) }
        val appleTest = maybeCreate("appleTest").apply { dependsOn(commonTest) }
        listOf("iosArm64", "iosSimulatorArm64", "iosX64", "macosArm64").forEach { target ->
            getByName("${target}Main").dependsOn(appleMain)
            getByName("${target}Test").dependsOn(appleTest)
        }

        val jvmTest by getting { dependencies { implementation(libs.junit) } }
    }
}
