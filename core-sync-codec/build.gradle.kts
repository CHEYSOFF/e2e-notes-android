import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // Versionless, for the reason core-domain/build.gradle.kts spells out: AGP puts the Kotlin
    // Gradle plugin on the build classpath itself.
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    androidLibrary {
        namespace = "my.cheysoff.core_sync_codec"
        compileSdk = 36
        minSdk = 24
    }
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }

    // No `mingwX64()` canary, for :core-crypto-shared's reason rather than by neglect: `RecordCodec`
    // is built on `RecordEnvelope` and `BlindedRecordId`, which are JCA and therefore JVM-bound.
    // The half that is portable -- the payload format itself -- is in `commonMain` below and is
    // compiled for every target this module has.
    sourceSets {
        val commonMain by getting {
            dependencies {
                // `api`, not `implementation`: `SyncRecord`, `Hlc` and `RecordType` appear in this
                // module's public signatures, so every consumer needs them on its compile classpath.
                api(project(":core-domain"))
                // The payload is JSON. `kotlinx-serialization-json` is taken for its runtime only
                // -- there is no `@Serializable` here and the compiler plugin is NOT applied, for
                // the reasons `RecordPayloadCodec` gives: building the object key by key is what
                // fixes the key order and makes every unknown key a decode failure.
                implementation(libs.kotlinx.serialization.json)
            }
        }
        val jvmCommonMain by creating {
            dependsOn(commonMain)
            dependencies {
                // `RecordEnvelope`, `BlindedRecordId`, `AccountKeys` -- all of them JCA-backed and
                // therefore in that module's `jvmCommonMain`, which is why this source set exists.
                implementation(project(":core-crypto-shared"))

                // `EnvelopeSyncTransport` is the codec pointed at a server: it implements the
                // engine's `SyncTransport` over the net module's `SyncApi`. Both types are in its
                // constructor and its return types, so both are `api` rather than `implementation`.
                //
                // This is the one place in the build where the engine and the client meet, and it
                // is deliberately NOT either app: an Android application module cannot lend a class
                // to a JVM desktop one, so the alternative to this dependency is two readings of
                // what a 409 means and which failure is fatal.
                api(project(":core-sync-engine"))
                api(project(":core-sync-net"))
            }
        }
        val androidMain by getting { dependsOn(jvmCommonMain) }
        val jvmMain by getting { dependsOn(jvmCommonMain) }
        val jvmTest by getting {
            dependencies {
                implementation(libs.junit)
                implementation(libs.kotlinx.coroutines.test)
                implementation(project(":core-crypto-shared"))
            }
        }
    }
}
