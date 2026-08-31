package my.cheysoff.desktop.keychain

import java.util.Base64

/**
 * "Remember me" on macOS, via a generic-password item in the login Keychain.
 *
 * ## Untested on real hardware, and that is stated here rather than discovered later
 *
 * This project builds and runs on Windows only. Everything below is written against the documented
 * behaviour of `security(1)` and is exercised by unit tests through [Commands], which drive the
 * argument construction, the base64 round trip and every failure path. Those tests prove this class
 * builds the right command and reacts correctly to each answer; they prove **nothing** about
 * macOS. The first run on a Mac is a real test, not a formality.
 *
 * The failure mode if it is wrong is bounded by the same invariant as every other store: a `security`
 * that errors, is missing, or returns something unexpected makes [remember] false and [recall] null,
 * and the user is asked for their passphrase. No path here can leave a vault unopenable.
 *
 * ## Why the CLI rather than Security.framework through JNA
 *
 * `SecItemAdd`/`SecItemCopyMatching` take nested CFDictionary structures whose keys are CFString
 * constants read out of a framework binary. Mapping that through JNA is a few hundred lines that
 * cannot be run, let alone debugged, on this machine — the same reason it would be the wrong thing
 * to write is the reason it would be the wrong thing to trust. `security(1)` ships with every macOS
 * install and its interface is a documented command line.
 *
 * ## Why the secret goes in on stdin
 *
 * `security add-generic-password -w <secret>` puts the secret in the process's argument vector,
 * where any local process can read it from `ps` for as long as the call runs. `security -i` reads
 * the same command from standard input instead, so the secret reaches it through a pipe. That is
 * not a large difference — a local attacker who can watch `ps` has other options — but it is free.
 */
class MacKeychainCredentialStore(
    private val commands: Commands,
    /**
     * The Keychain item's account name. Defaults to the OS user; a parameter so a test can pin it,
     * and so a future multi-vault build can key items per vault directory rather than per user.
     */
    private val account: String = System.getProperty("user.name") ?: "manana",
) : CredentialStore {

    /** Runs an external command. The seam that makes this class testable off a Mac. */
    fun interface Commands {
        /**
         * Runs [command], writes [stdin] to it if non-null, and returns the exit code with
         * whatever it printed. Must not throw: a missing binary is [Result] with a non-zero code.
         */
        fun run(command: List<String>, stdin: String?): Result
    }

    /** What a command did. [stdout] is trimmed of the trailing newline `security` always emits. */
    data class Result(val exitCode: Int, val stdout: String)

    override val isAvailable: Boolean = true

    override val description: String = "macOS Keychain"

    override fun isRemembered(): Boolean = recall() != null

    override fun remember(secret: ByteArray): Boolean {
        // The Keychain stores text. base64 rather than raw bytes because the vault key is 32
        // uniformly random bytes, which are not valid UTF-8 far more often than not, and a
        // round trip through a text field would silently replace the invalid ones.
        val encoded = Base64.getEncoder().encodeToString(secret)
        // -U updates an existing item instead of failing with errSecDuplicateItem, which is what
        // makes "remember" idempotent rather than working only on a machine that has never done it.
        // Only the values are quoted; `security -i` parses the verb and the flags itself. Quoting
        // them too would make it look for a subcommand literally named `"add-generic-password"`.
        val line = "add-generic-password -U -a ${quote(account)} -s ${quote(SERVICE)} -w ${quote(encoded)}"
        return commands.run(listOf(SECURITY, "-i"), line + "\n").exitCode == 0
    }

    override fun recall(): ByteArray? {
        val result = commands.run(
            listOf(SECURITY, "find-generic-password", "-a", account, "-s", SERVICE, "-w"),
            null,
        )
        if (result.exitCode != 0) return null
        return try {
            Base64.getDecoder().decode(result.stdout.trim())
        } catch (_: IllegalArgumentException) {
            // Something else wrote an item under this service name, or the item was edited by hand
            // in Keychain Access. Not ours; treat it as absent rather than as a corrupt secret.
            null
        }
    }

    override fun forget() {
        // The exit code is ignored: the only failure this can report is "no such item", which is
        // the state `forget` is trying to reach.
        commands.run(listOf(SECURITY, "delete-generic-password", "-a", account, "-s", SERVICE), null)
    }

    /**
     * Quotes one argument for `security -i`, which splits its input line itself.
     *
     * Only the interactive path needs this; every other call passes its arguments as a real argv
     * that no shell ever sees. The account name is the one value here that is not a constant, and
     * a macOS user name can contain a space.
     */
    private fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private companion object {
        const val SECURITY = "/usr/bin/security"

        /**
         * The Keychain service name. It is what the user sees in Keychain Access, and it is the
         * key the item is filed under — changing it orphans every existing item, costing one extra
         * passphrase prompt per Mac.
         */
        const val SERVICE = "Manana vault key"
    }
}
