plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

android {
    namespace = "my.cheysoff.core_crypto"
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
            isIncludeAndroidResources = true
            all {
                // Robolectric fetches its android-all jars at TEST RUNTIME, from Maven Central by
                // default — which answers 403 on this network. Point it at the same Google-hosted
                // read-through mirror settings.gradle.kts uses for Gradle's own resolution.
                it.systemProperty(
                    "robolectric.dependency.repo.url",
                    "https://maven-central.storage-download.googleapis.com/maven2",
                )
            }
        }
    }
}

dependencies {
    // `api`, not `implementation`: the sync primitives moved to this module keep their original
    // package names, so every existing consumer of :core-crypto still imports
    // `my.cheysoff.core_crypto.sync.*` and needs them on its compile classpath. Making the split
    // invisible to consumers is the whole point -- nothing else in the app changed.
    api(project(":core-crypto-shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.security.crypto)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.junit)
    // androidx.test:runner rather than espresso-core. Every screen in this app is Compose, so
    // Espresso's View matchers have nothing to act on, and `Espresso` appears nowhere in the repo;
    // the runner named in testInstrumentationRunner above is the only part of that dependency tree
    // androidTest actually uses.
    androidTestImplementation(libs.androidx.test.runner)
}
