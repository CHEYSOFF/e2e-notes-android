import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // Versionless on purpose; see core-domain/build.gradle.kts for why these two look like this.
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    androidLibrary {
        namespace = "my.cheysoff.core_editor"
        compileSdk = 36
        minSdk = 24
    }
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }

    sourceSets {
        val commonMain by getting {
            dependencies {
                // `api`: the editor's public surface is made of Block.
                api(project(":core-blocks"))
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
            }
        }
        val commonTest by getting { dependencies { implementation(libs.kotlin.test) } }
        val jvmTest by getting {
            dependencies {
                implementation(compose.desktop.uiTestJUnit4)
                implementation(compose.desktop.currentOs)
            }
        }
    }
}
