plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.dagger.hilt)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

// Required by @Database(exportSchema = true): Room writes the generated schema JSON here, so
// schema drift shows up in review diffs and FUTURE migrations become testable. Not testable yet:
// MigrationTestHelper needs the STARTING version's schema, and export only began at v5, so the
// earliest coverable step is 5 -> 6. Wiring it up will also need room-testing on the androidTest
// classpath and schemas/ registered as an androidTest asset dir; neither is set up.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "my.cheysoff.core_data"
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
    implementation(project(":core-domain"))
    implementation(project(":core-crypto"))
    // `SyncStore` and the types it exchanges. `api`, not `implementation`: `RoomSyncStore` IS a
    // `SyncStore`, so anything that constructs one -- the app's sync wiring -- needs the interface
    // on its compile classpath too.
    api(project(":core-sync-engine"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    
    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    
    // SQLCipher
    implementation(libs.sqlcipher)
    implementation(libs.androidx.sqlite)

    // DataStore (app settings)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    // RoomNotesRepositoryTest runs Room on the JVM under Robolectric (which ships its own SQLite),
    // so the repository's coverage lands in `jacocoMergedReport` — that report merges only the
    // `testDebugUnitTest` execution data, so an instrumented test of the same code would score 0.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.junit)
    // Every repository method is a suspend function or a Flow; runTest drives both.
    testImplementation(libs.kotlinx.coroutines.test)
    // Migration4to5Test drives Room directly; no UI, so no Espresso (which also drags in
    // hamcrest-integration and javawriter for nothing).
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
