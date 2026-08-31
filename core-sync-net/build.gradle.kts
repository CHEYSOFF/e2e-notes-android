plugins {
    alias(libs.plugins.android.library)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

android {
    namespace = "my.cheysoff.core_sync_net"
    compileSdk = 36

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests {
            // Everything in this module is plain JVM code -- the transport seam exists precisely so
            // that the client can be tested without Android, without a network and without a
            // server. No Robolectric, and deliberately no `returnDefaultValues`: if a test ever
            // reaches an Android framework class it should fail loudly rather than silently read a
            // zero.
            all {
                // The contract test in SyncServerContractTest starts the real server from
                // `server/` and drives it over real HTTP. It is opt-in because it needs a JDK 17+
                // toolchain and one `server/gradlew installDist`, neither of which a plain
                // `./gradlew test` should require. Run it with:
                //
                //     ./gradlew :core-sync-net:testDebugUnitTest -PsyncContract
                //
                // See the test's own KDoc for what it covers and why it exists.
                it.systemProperty(
                    "manana.sync.contract",
                    if (project.hasProperty("syncContract")) "true" else "false",
                )
                // The contract test needs to find `server/` and the repository root. Gradle sets
                // the test JVM's working directory to the module directory, which is one level
                // below the root, but that is an implementation detail of the Gradle test task
                // rather than a contract -- so the root is passed explicitly instead of derived.
                it.systemProperty("manana.repo.root", rootDir.absolutePath)
            }
        }
    }
}

dependencies {
    // For `Base64Url.encode` and the protocol's size constants. This module deliberately does NOT
    // re-implement either: a second base64url encoder or a second `ACCOUNT_ID_BYTES` is exactly the
    // "two implementations that disagree" failure this project has already shipped once.
    implementation(project(":core-crypto"))

    // The only new third-party dependency. See OkHttpTransport's KDoc for why OkHttp and not
    // HttpURLConnection: `CertificatePinner` is a direct match for the `spkiPinSha256` the pairing
    // QR already carries, and writing a trust manager by hand to get the same property is the kind
    // of code that is wrong in a way nobody notices.
    implementation(libs.okhttp)

    // `suspend` alone needs only the stdlib; `delay` (the 429 back-off) and `Dispatchers.IO` (the
    // blocking OkHttp call) do not.
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
}
