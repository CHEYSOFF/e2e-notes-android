package my.cheysoff.desktop.vault

import my.cheysoff.desktop.platform.HostOs
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Where the vault directory lives on each desktop.
 *
 * The directory holds two files and both are per-user state rather than documents: `vault.json`
 * (the wrapped keys) and `records.db` (the sealed records). So it goes in the OS's application-data
 * location, not the home directory root and not `Documents` — a user who syncs `Documents` to a
 * cloud drive would otherwise be uploading the vault, and while everything in it is encrypted, the
 * DPAPI blob beside it is machine-bound and would arrive on the other machine as an unusable file
 * that looks like a corrupted one.
 *
 * Every input is a parameter with a default rather than a direct [System] read, because this
 * project builds only on Windows: the macOS and Linux branches would otherwise be code nobody can
 * execute, and this file's whole job is to be right on three platforms at once.
 */
object VaultLocation {

    /** The directory name on Windows and macOS, where application-data folders are capitalised. */
    private const val DISPLAY_NAME = "Manana"

    /** The directory name on Linux, where they are not. */
    private const val LOWERCASE_NAME = "manana"

    /**
     * The default vault directory for [hostOs].
     *
     * - **Windows**: `%LOCALAPPDATA%\Manana`. LOCALAPPDATA rather than APPDATA on purpose — APPDATA
     *   is the *roaming* profile, which a domain login copies between machines, and the DPAPI blob
     *   in this directory cannot be decrypted on another machine.
     * - **macOS**: `~/Library/Application Support/Manana`.
     * - **anything else**: `$XDG_DATA_HOME/manana`, or `~/.local/share/manana` when XDG_DATA_HOME
     *   is unset or relative (the spec says a relative value must be ignored).
     */
    fun defaultDirectory(
        hostOs: HostOs = HostOs.current(),
        env: (String) -> String? = System::getenv,
        userHome: String = System.getProperty("user.home") ?: ".",
    ): Path = when (hostOs) {
        HostOs.WINDOWS -> {
            val localAppData = env("LOCALAPPDATA")
            if (localAppData.isNullOrBlank()) {
                // A Windows session without LOCALAPPDATA is not a normal one, but a null here
                // would crash at startup before the user could be told anything. The home
                // directory is always defined and is the same profile the variable points into.
                Paths.get(userHome, "AppData", "Local", DISPLAY_NAME)
            } else {
                Paths.get(localAppData, DISPLAY_NAME)
            }
        }

        HostOs.MACOS -> Paths.get(userHome, "Library", "Application Support", DISPLAY_NAME)

        HostOs.OTHER -> {
            val xdgDataHome = env("XDG_DATA_HOME")
            // "Starts with /" rather than `Paths.get(it).isAbsolute`, deliberately. XDG_DATA_HOME
            // is a POSIX-only variable and the spec defines absolute in POSIX terms, so this is
            // the right test on the systems that set it -- and it is also the only one that can be
            // exercised, since `Paths.get("/home/x")` reports itself as relative on Windows, where
            // this project builds.
            val xdg = xdgDataHome?.takeIf { it.isNotBlank() && it.startsWith("/") }
            if (xdg == null) {
                Paths.get(userHome, ".local", "share", LOWERCASE_NAME)
            } else {
                Paths.get(xdg, LOWERCASE_NAME)
            }
        }
    }
}
