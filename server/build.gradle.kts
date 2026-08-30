plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    application
}

group = "my.cheysoff.manana"
version = "1.0.0"

kotlin {
    jvmToolchain(17)
}

// Versions are inline rather than in a version catalog: this build has six dependencies and one
// module, and a catalog would be a second file to keep in sync for no gain. The root build's
// catalog is deliberately not shared -- see settings.gradle.kts for why the two builds are
// separate.
dependencies {
    implementation("io.ktor:ktor-server-core:3.5.2")
    implementation("io.ktor:ktor-server-cio:3.5.2")
    implementation("io.ktor:ktor-server-status-pages:3.5.2")
    // kotlinx-serialization directly rather than through Ktor's ContentNegotiation plugin. Request
    // bodies are read as bounded byte arrays and decoded by hand so that the size cap, the strict
    // "unknown field is a 400" decoding, and the exact error body are all decisions this code makes
    // rather than ones a plugin makes on its behalf.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    // The JDBC driver bundles the SQLite native library for every platform it supports, so there
    // is nothing to install on the host.
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    // Ktor logs through SLF4J and prints a warning to stderr with no provider on the classpath.
    // slf4j-simple is the smallest binding that silences it. It is configured to WARN in
    // src/main/resources/simplelogger.properties so that Ktor's own INFO chatter never competes
    // with -- or contradicts -- the audit line RequestLog emits. See RequestLog's KDoc.
    implementation("org.slf4j:slf4j-simple:2.0.16")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:3.5.2")
}

application {
    mainClass.set("manana.sync.server.MainKt")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
