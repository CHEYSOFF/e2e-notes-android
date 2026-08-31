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
    namespace = "my.cheysoff.feature_settings"
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
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core-ui"))
    implementation(project(":core-domain"))
    // The biometric toggle reads and clears the biometric wrap directly (SecureUnlockManager) and
    // asks the device whether biometrics are usable at all (AuthRepository).
    implementation(project(":core-crypto"))
    // ...and turns biometric unlock ON through feature-auth's BiometricEnroller, the same path the
    // post-PIN-setup enrollment uses. This is the only reason a feature module depends on another
    // feature module: duplicating the enrollment sequence is the alternative, and it is worse.
    implementation(project(":feature-auth"))
    // For ServerEndpoint, and for that alone. The sync-server field validates by constructing the
    // exact object the transport would be built from, so the address this screen accepts and the
    // address the transport accepts cannot drift apart -- see SyncServerUrl.kt. Nothing here
    // touches SyncApi or opens a connection; the screen's one network action goes through
    // :core-domain's SyncTransportStatus, which :app implements.
    implementation(project(":core-sync-net"))

    implementation(libs.androidx.core.ktx)
    // For FragmentActivity, which the biometric prompt must be hosted by.
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    // androidx.test:runner rather than espresso-core. Every screen in this app is Compose, so
    // Espresso's View matchers have nothing to act on, and `Espresso` appears nowhere in the repo;
    // the runner named in testInstrumentationRunner above is the only part of that dependency tree
    // androidTest actually uses. Compose UI tests, when they get written, use ui-test-junit4 below.
    androidTestImplementation(libs.androidx.test.runner)

    // --- Test-only, added so ViewModel and Compose UI tests become possible ---------------------
    // kotlinx-coroutines-test is the gate: it supplies the test dispatcher without which no
    // ViewModel can be unit-tested (Dispatchers.Main throws "Module with the Main dispatcher had
    // failed to initialize"), AND it is a transitive dependency of ui-test-junit4, so it blocks
    // Compose UI tests too. It resolves from Maven Central only.
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
