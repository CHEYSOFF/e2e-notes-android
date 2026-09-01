package my.cheysoff.desktop.platform

/**
 * Which desktop this build is running on.
 *
 * Two things in this module branch on the OS and nothing else does: where the vault directory
 * lives, and which credential store backs "remember me on this computer". Both need the *same*
 * answer, so the detection is done once, here, rather than with a `startsWith("win")` at each site.
 *
 * [OTHER] is not "Linux". It is every OS this module has no specific handling for, and the code
 * that reads it must therefore degrade rather than guess: the vault falls back to the XDG layout,
 * and the credential store falls back to [my.cheysoff.desktop.keychain.NoCredentialStore], which
 * means the user is asked for the passphrase every launch. That is the correct failure: a wrong
 * guess about a credential store is a secret written somewhere unintended.
 */
enum class HostOs {
    WINDOWS,
    MACOS,
    OTHER;

    companion object {
        /**
         * Detects from an `os.name` string.
         *
         * The parameter is not read from [System] inside so that both the vault-location and the
         * credential-store tests can drive every branch on any machine — this project builds only
         * on Windows today, so an untestable OS branch would be an untested one.
         */
        fun from(osName: String): HostOs {
            val name = osName.lowercase()
            return when {
                name.startsWith("windows") -> WINDOWS
                // Apple has shipped both "Mac OS X" and "macOS" in this property across JDK
                // versions, so match the substring both spellings share rather than either one.
                name.contains("mac") -> MACOS
                else -> OTHER
            }
        }

        /** The OS this JVM is running on. */
        fun current(): HostOs = from(System.getProperty("os.name") ?: "")
    }
}
