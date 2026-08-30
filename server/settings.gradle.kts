// This build is deliberately STANDALONE. `server/` is not listed in the repository root's
// `settings.gradle.kts` and must never be: the Android application must build identically whether
// or not this directory exists. Nothing here is an Android module, nothing here is on the app's
// classpath, and no task in this build is reachable from `./gradlew` at the repository root.
//
// Repositories mirror the root build's arrangement and for the same reason: repo.maven.apache.org
// answers 403 Forbidden from this network, so Google's read-through mirror of Maven Central is
// listed first and `mavenCentral()` is kept as a fall-through for anywhere the block does not
// apply. Every dependency in `build.gradle.kts` was checked to resolve through the mirror.
pluginManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2")
        mavenCentral()
    }
}

rootProject.name = "manana-sync-server"
