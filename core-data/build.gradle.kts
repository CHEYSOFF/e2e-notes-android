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
}

dependencies {
    implementation(project(":core-domain"))
    implementation(project(":core-crypto"))

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
    // Migration4to5Test drives Room directly; no UI, so no Espresso (which also drags in
    // hamcrest-integration and javawriter for nothing).
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
