package my.cheysoff.core_domain.model

/**
 * Identity of the running build, as shown in the settings screen's About section.
 *
 * The values come from the application module's generated `BuildConfig`, which only the `:app`
 * module can see; this data class is what carries them across the module boundary (provided by
 * `AppInfoModule` in `:app` and injected wherever they are displayed). Feature modules must not
 * reach for a `BuildConfig` of their own — a library module's would describe the library, not
 * the app.
 */
data class AppInfo(
    /** `versionName` as declared in the app module, e.g. "1.0". */
    val versionName: String,
    /** `versionCode` as declared in the app module, e.g. 1. */
    val versionCode: Int,
)
