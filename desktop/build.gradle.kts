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

sourceSets {
    main {
        // The Urbanist faces are read from :core-ui's Android font resources rather than copied.
        // Desktop Compose loads fonts from the classpath, and a second copy of four .ttf files
        // would be a second thing to keep in step the day the family changes (there is a standing
        // TODO in core-ui/theme/Type.kt to swap Urbanist for a Cyrillic-capable family). Pointing
        // at the same directory means that swap lands on both platforms at once.
        resources.srcDir(rootProject.file("core-ui/src/main/res/font"))
    }
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
    // The pairing protocol, shared verbatim with the phone: the P-256 agreement, the wire format,
    // the seal, the SAS, the QR encoder and the rendezvous client. Nothing about pairing is
    // reimplemented in this module -- the desktop is device B of the same handshake two phones
    // run, with an HTTP source in front of the second leg instead of a camera.
    implementation(project(":core-pairing"))
    // The record payload format, the envelope codec and the `SyncTransport` over them -- the same
    // classes the phone uses, not a desktop reading of the same spec. This module used to carry its
    // own copy of `RecordPayload`, `RecordPayloadCodec` and `RecordCodec`, written before the phone
    // had any; the copy is gone. Two implementations of one wire format is a note written on the
    // phone that the laptop reports as unreadable, and the duplicate's own contract test said in as
    // many words that it was scaffolding to be deleted with the fork.
    //
    // `api` on `:core-sync-engine` and `:core-sync-net` inside that module means the engine and the
    // HTTP client arrive with it, which is what the desktop's `SyncStore` and sync controller need.
    implementation(project(":core-sync-codec"))
    implementation(project(":core-sync-engine"))
    implementation(project(":core-sync-net"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    // The core icon set only. Deliberately NOT `compose.materialIconsExtended`, which is orders of
    // magnitude larger for the sake of glyphs this UI does not draw; the one icon it wants that
    // the core set lacks is the pin, and that is drawn by hand in PinGlyph.
    implementation(libs.compose.material.icons.core)
    // 1.2.0, not the 1.1.0 the Android app is pinned to: 1.2.0 is the first release with a
    // published `richeditor-compose-desktop` variant. The two agree on escaping (both emit a
    // literal "." and escape only & < >), so HTML written on either platform round-trips. The
    // version that does NOT agree is the 1.0.0-rc14 this project used to be on, whose
    // entity-escaped bodies are still on disk; DesktopHtmlText reads those.
    implementation(libs.richeditor.compose.desktop)
    // richeditor's own HTML entity codec, declared explicitly because richeditor exposes it at
    // RUNTIME only. Decoding rc14-era bodies with the library that encoded them is what makes the
    // decode a guaranteed inverse rather than a hand-maintained entity table.
    implementation(libs.ksoup.entities)

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

tasks.withType<Test>().configureEach {
    // Gradle does not pass its own -D properties down into the test JVM, so the fixtures that take
    // one -- a vault directory, a server address -- would silently SKIP rather than run, which
    // reads as a clean pass. Forwarded by prefix rather than by name so that adding a fixture does
    // not mean remembering to come back here; only `manana.*` is passed, so nothing else about the
    // build leaks into a test.
    System.getProperties().forEach { key, value ->
        val name = key.toString()
        if (name.startsWith("manana.")) systemProperty(name, value.toString())
    }
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
                // Without this, jpackage files the Start menu shortcut under a group literally
                // named "Unknown" -- it falls back to the vendor field, which it does not read from
                // `vendor` above.
                menuGroup = "Manana"
                // Installs into the user's profile rather than Program Files, so no administrator
                // prompt. jpackage defaults to per-machine, which fails outright with "Error 1925:
                // you do not have sufficient privileges" for anyone not running an elevated
                // installer -- and a personal notes app has no business asking for the whole
                // machine. Everything it writes (the vault, the DPAPI-protected key) is per-user
                // anyway, so a machine-wide install would put one user's binary above another
                // user's data and share nothing useful between them.
                perUserInstall = true
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
