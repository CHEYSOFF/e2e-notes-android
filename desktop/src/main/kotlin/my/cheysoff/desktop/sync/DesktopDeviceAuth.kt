package my.cheysoff.desktop.sync

import my.cheysoff.core_crypto.sync.DeviceLabelCipher
import my.cheysoff.core_sync_net.auth.DeviceLabelSealer
import my.cheysoff.core_sync_net.auth.DeviceSigner
import my.cheysoff.desktop.vault.DeviceKeyPair

/**
 * This computer's identity to the sync server: it signs session challenges with the device key that
 * the phone vouched for during pairing.
 *
 * The key itself lives in the vault, wrapped, and is only in memory while the vault is open — the
 * same lifetime as the ARK. It is a *signing* key and not the ARK: someone who steals it can act as
 * this device against the server, which is bad, but they still cannot read a note, because every
 * record on the wire is sealed under keys derived from the ARK. That difference is why it is
 * acceptable for this key to exist on a general-purpose machine at all.
 */
class DesktopDeviceSigner(private val deviceKey: DeviceKeyPair) : DeviceSigner {

    override fun publicKeySec1(): ByteArray = deviceKey.publicKeySec1

    override fun sign(message: ByteArray): ByteArray = deviceKey.sign(message)
}

/**
 * Seals and opens device labels under the account root key.
 *
 * The Android app has its own adapter for the same interface, and the two are not a duplication:
 * the cryptography is [DeviceLabelCipher], shared, and each platform only differs in where it gets
 * the ARK from — the Keystore-backed unlock manager there, an open vault here.
 *
 * @param arkProvider hands back a **copy** of the ARK, which this class zeroes after every use. A
 *   provider that returns the live array would have it wiped underneath its owner.
 */
class VaultDeviceLabelSealer(private val arkProvider: () -> ByteArray?) : DeviceLabelSealer {

    override fun seal(devicePublicKeyB64: String, label: String): ByteArray? {
        // Capped rather than rejected: `DeviceLabelCipher.seal` refuses an over-long label instead
        // of cutting one, and an unfortunate computer name must not be able to stop this device
        // enrolling.
        val trimmed = DeviceLabelCipher.trimToSealableLength(label)
        if (trimmed.isEmpty()) return null
        val ark = arkProvider() ?: return null
        return try {
            DeviceLabelCipher.seal(ark, devicePublicKeyB64, trimmed)
        } finally {
            ark.fill(0)
        }
    }

    override fun open(devicePublicKeyB64: String, sealed: ByteArray): String? {
        val ark = arkProvider() ?: return null
        return try {
            DeviceLabelCipher.open(ark, devicePublicKeyB64, sealed)
        } finally {
            ark.fill(0)
        }
    }
}
