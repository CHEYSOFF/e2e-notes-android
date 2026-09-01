package my.cheysoff.desktop.fixture

import my.cheysoff.desktop.keychain.NoCredentialStore
import my.cheysoff.desktop.vault.DesktopVault
import my.cheysoff.desktop.vault.UnlockResult
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.nio.file.Paths
import java.security.MessageDigest

/**
 * A developer fixture: opens a vault on disk and prints a **fingerprint** of the account key it
 * holds, so that a pairing done by hand through the real UI can be checked against what the phone
 * actually sent.
 *
 * ```
 * ./gradlew :desktop:test --tests '*VaultArkFingerprint*' \
 *     -Dmanana.inspectVault=C:\path\to\vault -Dmanana.inspectPassphrase='…'
 * ```
 *
 * ## Why a digest and not the key
 *
 * `DemoVaultProvisioner` prints its own passphrase, which is fine — it minted a throwaway vault
 * seconds earlier. This one points at a vault that may be real, and a tool whose output is an
 * Account Root Key is a tool that will eventually be pointed at one and its output pasted somewhere.
 * SHA-256 of the ARK settles "is this the same key?" exactly as well and settles nothing else.
 */
class VaultArkFingerprint {

    @Test
    fun printTheFingerprint() {
        val directory = System.getProperty("manana.inspectVault")
        val passphrase = System.getProperty("manana.inspectPassphrase")
        assumeNotNull(directory)
        assumeNotNull(passphrase)

        val vault = DesktopVault(
            directory = Paths.get(directory).toAbsolutePath(),
            credentialStore = NoCredentialStore,
        )
        when (val result = vault.unlock(passphrase.toCharArray())) {
            is UnlockResult.Unlocked -> result.session.use {
                println("vault:       $directory")
                println("ark sha-256: ${sha256Hex(it.ark)}")
                println("hlc node:    ${it.hlcNode}")
            }

            else -> error("could not open the vault at $directory: $result")
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
