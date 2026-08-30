plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

android {
    namespace = "my.cheysoff.feature_pairing"
    compileSdk = 36

    defaultConfig {
        // Higher than the 24 the other library modules declare, and deliberately equal to the
        // app's own floor. Nothing in the protocol needs 31 -- P-256 JCA and AndroidKeyStore EC
        // are both far older -- but there is no reason for this module to claim support for a
        // configuration the application it ships in cannot produce, and CameraX's current release
        // line is the part with a real floor.
        minSdk = 31

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
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core-ui"))
    // The sync key hierarchy. This module needs exactly one thing from it -- HKDF-SHA256 -- and
    // takes it rather than carrying a second copy; see PairingSeamModule.
    implementation(project(":core-crypto"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // For androidx.lifecycle.compose.LocalLifecycleOwner: the Compose-UI one CameraX binding used
    // to read is deprecated and moved here.
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // QR. `com.google.zxing:core` is pure Java with no Android dependency at all -- it is used
    // here both to WRITE (QrEncoder -> ImageBitmap) and to READ (QrFrameDecoder over a camera
    // frame). zxing-android-embedded is deliberately not used: it ships a whole capture Activity
    // and its own manifest, which is more surface than a Compose app needs.
    implementation(libs.zxing.core)

    // Camera. camera-camera2 is the CameraX backend, camera-lifecycle binds the use cases to a
    // LifecycleOwner, camera-view supplies PreviewView. ML Kit is deliberately not used for
    // barcode detection: +2.5 MB and a Google Play Services dependency in an app whose premise is
    // talking to nobody -- zxing already decodes the frames.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
