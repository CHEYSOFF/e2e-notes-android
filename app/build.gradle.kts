plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.dagger.hilt)
    alias(libs.plugins.ksp)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

android {
    namespace = "my.cheysoff.notes"
    compileSdk = 36

    defaultConfig {
        applicationId = "my.cheysoff.notes"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        // AGP does not generate BuildConfig unless asked. The settings screen's About section
        // shows the app's version, which is read from BuildConfig by AppInfoModule.
        buildConfig = true
    }
    // Preserve the prior android:extractNativeLibs="true" behaviour (AGP 9 forbids the manifest
    // attribute and routes it through the build script instead).
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(project(":feature-auth"))
    implementation(project(":feature-notes"))
    implementation(project(":feature-settings"))
    implementation(project(":feature-pairing"))
    implementation(project(":core-ui"))
    implementation(project(":core-domain"))
    implementation(project(":core-data"))
    implementation(project(":core-crypto"))
    // The sync transport. This is what brings the INTERNET permission into the merged manifest --
    // the declaration itself lives in core-sync-net/src/main/AndroidManifest.xml, next to the
    // network code, and that file explains what it is for.
    implementation(project(":core-sync-net"))
    // The pull/push pass loop, and the payload codec that turns a `SyncRecord` into the sealed
    // blob the server stores. `:app` is where the two meet the HTTP client and the account keys.
    implementation(project(":core-sync-engine"))
    implementation(project(":core-sync-codec"))

    implementation(libs.androidx.core.ktx)
    // The sync server address is a preference, stored the same way every other preference in this
    // app is. It is here rather than in :core-data because the only code that reads it is the sync
    // transport wiring next door in my/cheysoff/notes/sync -- see DataStoreSyncSettingsRepository.
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.process)
    ksp(libs.hilt.compiler)
    testImplementation(libs.junit)
    // `runTest`, for the sync transport adapter's tests. Its suspending surface is the whole of
    // what it is: a version of these that blocked would be testing the dispatcher.
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    // androidx.test:runner rather than espresso-core. Every screen in this app is Compose, so
    // Espresso's View matchers have nothing to act on, and `Espresso` appears nowhere in the repo;
    // the runner named in testInstrumentationRunner above is the only part of that dependency tree
    // androidTest actually uses. Compose UI tests, when they get written, use ui-test-junit4 below.
    androidTestImplementation(libs.androidx.test.runner)
    // Room reaches :app only through :core-data's `implementation`, so it is absent from the
    // androidTest classpath. AndroidPushesToServerTest opens a real database to write the note it
    // pushes, which is the whole point of it being an instrumented test.
    androidTestImplementation(libs.androidx.room.runtime)
    androidTestImplementation(libs.androidx.room.ktx)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}