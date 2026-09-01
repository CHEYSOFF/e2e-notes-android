package my.cheysoff.desktop.keychain

import my.cheysoff.desktop.platform.HostOs
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Picks the [CredentialStore] for the host OS.
 *
 * The one place in this module that decides which store is in use. Everything else takes a
 * [CredentialStore] and cannot tell the difference — including the case where the answer is
 * [NoCredentialStore], which is the point: the app has to behave correctly when there is no store
 * at all, and the way to guarantee that is for "no store" to be an ordinary implementation rather
 * than a null check at every call site.
 */
object CredentialStores {

    /** File name of the DPAPI blob inside the vault directory. Windows only. */
    private const val DPAPI_BLOB = "remember.dpapi"

    /**
     * The store for [hostOs], keeping any files it needs inside [vaultDirectory].
     *
     * Linux gets [NoCredentialStore] rather than a Secret Service / libsecret client. That is a
     * deliberate omission, not an oversight: the D-Bus Secret Service is present on GNOME and KDE
     * desktops and absent on a headless or minimal one, so the implementation would need a runtime
     * probe and a fallback — and the fallback would be this. Shipping the fallback alone is honest
     * about what has actually been built and costs a Linux user one passphrase per launch.
     */
    fun forHost(hostOs: HostOs, vaultDirectory: Path): CredentialStore = when (hostOs) {
        HostOs.WINDOWS -> DpapiCredentialStore(vaultDirectory.resolve(DPAPI_BLOB))
        HostOs.MACOS -> MacKeychainCredentialStore(ProcessCommands)
        HostOs.OTHER -> NoCredentialStore
    }

    /**
     * Runs `security` as a real subprocess.
     *
     * Never throws, per [MacKeychainCredentialStore.Commands]: a machine without `/usr/bin/security`
     * reports a non-zero exit like any other failure, and the caller ends at the passphrase prompt.
     */
    private object ProcessCommands : MacKeychainCredentialStore.Commands {
        override fun run(
            command: List<String>,
            stdin: String?,
        ): MacKeychainCredentialStore.Result = try {
            val process = ProcessBuilder(command)
                // Merged so a failure message cannot fill the stderr pipe and deadlock the wait
                // below. The output is only ever read on success, where `security` writes nothing
                // to stderr.
                .redirectErrorStream(true)
                .start()
            process.outputStream.use { output ->
                if (stdin != null) output.write(stdin.toByteArray(Charsets.UTF_8))
            }
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            // A bounded wait: `security` can block on an unlock prompt if the login keychain is
            // locked, and a modal dialog behind an app that has not drawn its window yet is
            // indistinguishable from a hang. Timing out lands the user at the passphrase prompt.
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroy()
                MacKeychainCredentialStore.Result(exitCode = -1, stdout = "")
            } else {
                MacKeychainCredentialStore.Result(process.exitValue(), stdout)
            }
        } catch (_: Exception) {
            MacKeychainCredentialStore.Result(exitCode = -1, stdout = "")
        }

        private const val TIMEOUT_SECONDS = 20L
    }
}
