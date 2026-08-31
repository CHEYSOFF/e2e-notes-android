import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // Applied without a version: AGP puts the Kotlin Gradle plugin -- which is where
    // `org.jetbrains.kotlin.jvm` lives -- on the build classpath itself (see the root buildscript
    // block), and asking for a version here fails with "already on the classpath with an unknown
    // version". The two plugins below ship in their own artifacts and so do carry versions.
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    // 17 rather than the 11 every other module uses. Nothing here runs on Android, and this module
    // ships its own JVM inside the installer (jpackage bundles a jlink image), so the floor is the
    // JDK that builds it rather than the oldest device anyone might own.
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // The repository interface the whole app is written against, plus the merge engine's record
    // model. This module implements `NotesRepository`; it does not define a desktop-shaped
    // variant of it, because the UI is coded against exactly this type.
    implementation(project(":core-domain"))
    // PassphraseCipher, RecordEnvelope, AccountRootKey, BlindedRecordId, ArkCipher. No crypto is
    // written in this module; every primitive comes from here so that a desktop record and a
    // phone record are the same bytes.
    implementation(project(":core-crypto-shared"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)

    // The record store. Plain SQLite over JDBC -- not SQLCipher: on desktop the confidentiality of
    // a row is the record envelope's job, not the file's. See RecordStore's KDoc.
    implementation(libs.sqlite.jdbc)

    // Windows DPAPI (Crypt32) for the "remember me on this computer" convenience layer.
    // `jna-platform` is what carries the Crypt32 binding; `jna` alone is only the loader.
    implementation(libs.jna)
    implementation(libs.jna.platform)

    // Used through its JsonElement API only -- no `@Serializable` classes and therefore no
    // serialization compiler plugin. See RecordPayload for why the payload is built key by key.
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

compose.desktop {
    application {
        mainClass = "my.cheysoff.desktop.MainKt"

        nativeDistributions {
            // All three are configured, and only one of them can be BUILT on any given machine:
            // jpackage produces the installer of the host OS and nothing else. macOS is a
            // first-class target for this app -- it is the same JVM binary as Windows -- so the
            // `macOS` block below is filled in properly even though `packageDmg` can only run on a
            // Mac. Leaving it out would mean the first person with a Mac has to invent a bundle ID,
            // and a bundle ID that changes after a release is a different application to the OS.
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)

            packageName = "Manana"
            // Not the app's marketing version: jpackage requires MAJOR.MINOR.PATCH with a non-zero
            // major on every platform, and rejects the qualifiers a git describe would produce.
            packageVersion = "1.0.0"
            description = "Manana - end-to-end encrypted notes"
            vendor = "CHEYSOFF"
            copyright = "Copyright (c) CHEYSOFF"

            windows {
                menu = true
                shortcut = true
                // **Never change this UUID.** Windows Installer uses it to decide whether an .msi
                // upgrades the installed product or installs a second copy beside it. A new value
                // ships an app that cannot be upgraded, only uninstalled and reinstalled -- and
                // the two copies would each have their own Start-menu entry pointing at the same
                // vault directory.
                upgradeUuid = "0e4a2f5c-9b1d-4f8e-a3c7-6d2b8f4e1a90"
            }

            macOS {
                // The macOS identity. Like `upgradeUuid` above this is a permanent identifier:
                // it keys the app's Keychain items, its sandbox container and its notarization
                // record, so changing it later orphans everything a previous version stored --
                // including the Keychain entry MacKeychainCredentialStore writes.
                bundleID = "my.cheysoff.manana"
                packageName = "Manana"
                dockName = "Manana"
                // Signing and notarization are deliberately NOT configured. They need a Developer
                // ID certificate in a Mac's keychain, and a half-configured signing block fails
                // the build on every machine that lacks one rather than producing an unsigned
                // build. An unsigned .dmg installs behind a Gatekeeper warning, which is the right
                // default until there is a certificate to name here.
            }

            linux {
                packageName = "manana"
                menuGroup = "Office"
                appCategory = "Office"
            }
        }
    }
}
