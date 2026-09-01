plugins {
    // Applied without a version -- AGP puts the Kotlin Gradle plugin on the build classpath itself.
    // See core-domain/build.gradle.kts.
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

/**
 * The iOS app.
 *
 * COMPILED, NEVER RUN. Kotlin/Native cross-compiles Apple klibs from any host, so
 * `./gradlew :ios-app:compileKotlinIosSimulatorArm64` runs on the Windows machine this was written
 * on and the Compose compiler plugin has been over every composable in it. **Linking the framework
 * and launching it both need macOS**, so no screen here has ever been rendered and no tap has ever
 * been handled.
 *
 * Compiling is worth less on a UI module than on a crypto one. It says the types line up; it says
 * nothing about whether a layout is right, whether the keyboard covers the caret, or whether the
 * safe-area insets end up applied once or twice. docs/BUILDING-IOS.md lists what to expect to fix.
 *
 * What *is* evidenced is everything underneath -- :core-domain, :core-crypto-shared, :core-sync-net
 * and :core-store all have real test suites that run here. This module is the thin layer that turns
 * those into an app, and it is deliberately thin for that reason.
 */
kotlin {
    // Two targets, not the four the library modules carry, and both absences were measured rather
    // than assumed.
    //
    // No `macosArm64`: the library modules declare it because it is the only Apple target whose
    // TESTS run without a simulator, which makes it the fastest check on the shared code. This
    // module has no tests and would only gain a desktop-shaped Compose window nobody wants.
    //
    // **No `iosX64`, and this one is a hard constraint rather than a choice.** Compose Multiplatform
    // 1.12.0 publishes no Intel-simulator artifacts: adding the target fails resolution with
    // "Couldn't resolve dependency 'org.jetbrains.compose.runtime:runtime' ... Unresolved platforms:
    // [iosX64]" for runtime, foundation, ui and material3 alike. So **this app can only be run on an
    // Apple-silicon Mac's simulator, or on a device.** The library modules underneath keep their
    // `iosX64` target -- they resolve fine and it costs nothing -- so the shared code is still
    // checked for it. See docs/BUILDING-IOS.md.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "MananaApp"
            // Static, which is what Compose Multiplatform's own templates use and what the Xcode
            // project in `iosApp/` expects. A dynamic framework would need an embed-and-sign build
            // phase and would start slower; nothing here needs one.
            isStatic = true
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":core-domain"))
                implementation(project(":core-crypto-shared"))
                implementation(project(":core-store"))
                implementation(libs.kotlinx.coroutines.core)

                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                // `compose.components.resources` is deliberately absent. The Android app's type is
                // Urbanist, loaded from a font resource, and matching it here would mean shipping
                // the font files into this module and a resource pipeline that cannot be checked
                // on this machine. The screens below use the system face and say so; picking the
                // font back up is a small, visible change for someone with a simulator in front of
                // them, and a source of silent breakage for someone without one.
            }
        }

        // One shared source set for the three iOS targets. `maybeCreate` for the reason
        // :core-crypto-shared documents -- the default hierarchy template may or may not have
        // created it, and `by creating` fails if it did.
        val iosMain = maybeCreate("iosMain").apply { dependsOn(commonMain) }
        listOf("iosArm64", "iosSimulatorArm64").forEach { target ->
            getByName("${target}Main").dependsOn(iosMain)
        }
    }
}
