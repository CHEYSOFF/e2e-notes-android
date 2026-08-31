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

    // NOTE: no `mingwX64()` canary here, unlike :core-domain, and the absence is deliberate.
    //
    // The crypto in this module is built on the JCA -- `javax.crypto.Cipher`, `Mac`,
    // `SecretKeyFactory`, `java.security.SecureRandom` -- so it is JVM-bound by construction, not
    // by neglect. A canary target would only be satisfiable by stubbing the crypto out, which
    // would make it prove nothing.
    //
    // `commonMain` holds the two pieces that are NOT crypto: `Base64Url` and `SyncProtocol`. They
    // are there because :core-sync-net's transport is common code and uses both -- see their own
    // KDoc. They are pure Kotlin, so the canary argument above does not apply to them either way.
    //
    // An Apple target needs real actuals (CryptoKit/CommonCrypto) behind an `expect` seam, and
    // those must produce byte-identical output to these or two devices cannot read each other's
    // notes. That is a piece of work with its own verification, not a source-set rearrangement,
    // and it is why the code here sits in `jvmCommonMain` rather than pretending to be common.
    // Wired with explicit `dependsOn` rather than through `applyDefaultHierarchyTemplate`.
    //
    // The template's `withAndroidTarget()` matches the OLD `androidTarget()`, not the
    // `androidLibrary` target that AGP 9's multiplatform plugin creates, so the template silently
    // links nothing: `compileAndroidMain` reports NO-SOURCE and the Android variant ships an empty
    // jar. That failure is quiet -- the module builds, its JVM target has every class, and only a
    // consumer of the Android variant discovers the classes are missing.
    //
    // The cost is a Gradle warning that the default template was not applied, which is accurate
    // and harmless here: this module declares its two source-set edges itself, immediately below.
    sourceSets {
        val commonMain by getting
        val jvmCommonMain by creating { dependsOn(commonMain) }
        val androidMain by getting { dependsOn(jvmCommonMain) }
        val jvmMain by getting { dependsOn(jvmCommonMain) }
        val jvmTest by getting { dependencies { implementation(libs.junit) } }
    }
}
