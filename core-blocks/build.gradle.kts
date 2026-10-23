import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // Versionless on purpose; see core-domain/build.gradle.kts for why both plugins look like this.
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    androidLibrary {
        namespace = "my.cheysoff.core_blocks"
        compileSdk = 36
        minSdk = 24
    }
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }
    // No mingwX64 canary here: core-domain already enforces that the shared core stays portable.

    sourceSets {
        val commonMain by getting {
            dependencies {
                // `api`: Block exposes Hlc in its public constructor.
                api(project(":core-domain"))
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ksoup.entities.common)
            }
        }
        val commonTest by getting { dependencies { implementation(libs.kotlin.test) } }
        val jvmTest by getting { dependencies { implementation(libs.junit) } }
    }
}
