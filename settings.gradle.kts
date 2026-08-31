// Google's read-through mirror of Maven Central, listed BEFORE mavenCentral() in both blocks.
//
// repo.maven.apache.org / repo1.maven.org answer 403 Forbidden from this network for every
// artifact. It is the NETWORK that is blocked, not any one tool -- Gradle, curl and Android Studio
// all get the same 403. Sonatype geo-blocks Central from some regions. Google's Maven is a
// different host and is unaffected, which is why androidx and AGP always resolved while anything
// published only to Central never did.
//
// This mirror is Google-hosted and serves the same artifacts under the same coordinates. It
// unblocks kotlinx-coroutines-test (and therefore all ViewModel and Compose UI testing), the
// JaCoCo agent behind coverage reporting, Espresso's transitive dependencies, and the Unified Test
// Platform artifacts that made connectedAndroidTest unusable.
//
// mavenCentral() is KEPT as the next entry rather than replaced: Gradle tries repositories in
// order and falls through on a miss, so the mirror is simply asked first, and nothing here needs
// changing if the block lifts or the mirror goes away.
pluginManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2")
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2")
        mavenCentral()
    }
}

rootProject.name = "notes"
include(":app")
include(":feature-auth")
include(":core-ui")
include(":feature-notes")
include(":core-crypto")
// The portable half of :core-crypto -- the sync primitives, the record envelope, the passphrase
// wrap -- split out so the desktop app can use them. :core-crypto keeps everything that needs the
// AndroidKeyStore, EncryptedSharedPreferences or BiometricPrompt. Package names are unchanged
// across the split, so no import anywhere in the app moved.
include(":core-crypto-shared")
include(":core-domain")
include(":core-data")
include(":feature-settings")
include(":feature-pairing")
// The network transport for E2E sync (Phase 3). It is the only module that declares INTERNET; see
// core-sync-net/src/main/AndroidManifest.xml for what that permission is for and when it is used.
include(":core-sync-net")
// The sync coordinator: the push/pull pass loop that drives `Merge`. Pure `commonMain` -- no
// Android, no Room, no HTTP -- so that the N-replica convergence harness can drive the real engine
// on the JVM in milliseconds. See core-sync-engine/build.gradle.kts.
include(":core-sync-engine")

// The Compose Desktop app. Plain Kotlin/JVM -- it is the same binary on Windows, macOS and
// Linux, and jpackage builds whichever installer the host OS supports.
include(":desktop")
