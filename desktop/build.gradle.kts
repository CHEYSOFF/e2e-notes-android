import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // Applied without a version: AGP puts the Kotlin Gradle plugin on the build classpath itself
    // (root buildscript block), so asking for one fails with "already on the classpath with an
    // unknown version" -- the same rule the multiplatform modules document.
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
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
    implementation(project(":core-domain"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    // The core icon set only. Deliberately NOT `compose.materialIconsExtended`, which is orders
    // of magnitude larger for the sake of glyphs this UI does not draw; the one icon it wants
    // that the core set lacks is the pin, and that is drawn by hand in PinGlyph.
    implementation(libs.compose.material.icons.core)
    implementation(libs.kotlinx.coroutines.core)
    // 1.2.0, not the 1.1.0 the Android app is pinned to: 1.2.0 is the first release with a
    // published `richeditor-compose-desktop` variant. The two agree on escaping (both emit
    // literal "." and only escape & < >), so HTML written on either platform round-trips; the
    // version that does NOT agree is the 1.0.0-rc14 this project used to be on, whose
    // entity-escaped bodies are still on disk. See DesktopHtmlText for how those are read back.
    implementation(libs.richeditor.compose.desktop)
    implementation(libs.ksoup.entities)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

compose.desktop {
    application {
        mainClass = "my.cheysoff.desktop.ui.DesktopAppKt"
        // `./gradlew :desktop:run -PemptyLibrary` starts with no notes, which is how the empty
        // state is looked at (and screenshotted) without deleting the sample library by hand.
        if (project.hasProperty("emptyLibrary")) jvmArgs += "-Dmanana.emptyLibrary=true"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Manana"
            packageVersion = "1.0.0"
        }
    }
}
