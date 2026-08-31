package my.cheysoff.core_sync_net.auth

/**
 * Seals and opens a device's human-readable name, so the transport never handles the Account Root
 * Key.
 *
 * ## Why a seam rather than a call to `DeviceLabelCipher`
 *
 * This module already depends on `:core-crypto`, so it *could* call
 * `DeviceLabelCipher.seal(ark, publicKeyB64, label)` directly. Doing so would mean handing the ARK
 * to the transport, and the ARK is the key every note on the account is sealed under: a client that
 * holds one has it in memory for the life of a sync pass, in a class whose other job is to talk to
 * an untrusted server. [DeviceSigner] exists for exactly this reason -- the device's private key
 * never enters this module either -- and this is the same trade for the other key.
 *
 * The implementation is therefore an adapter in `:app`, next to `KeystoreDeviceSigner`, which is
 * where the ARK's storage (`SecureUnlockManager`) can be seen.
 *
 * ## The device label is not a record
 *
 * `SyncApi`'s KDoc says this module does not encrypt or decrypt, and that remains true of records:
 * an envelope arrives sealed and leaves sealed. A device name is not a record -- it is metadata
 * about the connection itself, it is produced and consumed at exactly the two points this client
 * owns (enrolment and `GET /v1/devices`), and pushing it up to the caller would mean every caller
 * remembering to seal against the right public key. Forgetting that is what put a plaintext name on
 * the wire in the first place.
 *
 * ## What an implementation must guarantee
 *
 * **Neither method may ever produce or accept a plaintext label on the wire.** When the key is
 * unavailable -- a locked device, an account with no ARK yet -- [seal] returns null and the device
 * enrols with no label. That is a real loss (an unnamed row in the device list) and it is the
 * correct one: the alternative is sending the name in the clear, which is the thing being fixed.
 */
interface DeviceLabelSealer {

    /**
     * Seals [label] for the device whose public key is [devicePublicKeyB64], or returns null when
     * this device cannot seal right now.
     *
     * [devicePublicKeyB64] is the base64url text that will be sent in the same request, because
     * that is what the seal is bound to and what a reader will have. Note that for a vouched
     * enrolment it is the **joining** device's key, not the voucher's.
     *
     * Implementations must not throw for a label that is too long; cap or drop it and return
     * something the server will accept, because an over-long name must not stop a device enrolling.
     */
    fun seal(devicePublicKeyB64: String, label: String): ByteArray?

    /**
     * Opens a sealed label, or returns null when it does not authenticate under this device's key.
     *
     * Null is the ordinary case a device list must render as "unnamed", not an error.
     */
    fun open(devicePublicKeyB64: String, sealed: ByteArray): String?

    companion object {

        /**
         * A sealer for a client that has no account key at all: it seals nothing and opens nothing.
         *
         * Every device enrolled through such a client is unnamed, and every device in its listing
         * reads as unnamed. It exists for a client built where no account key is reachable -- the
         * "test this server address" button in settings, which only calls `health()`, and a test
         * that is not about labels. It is spelled out at those call sites rather than being a
         * default, so that nobody gets it by omission.
         */
        val NONE: DeviceLabelSealer = object : DeviceLabelSealer {
            override fun seal(devicePublicKeyB64: String, label: String): ByteArray? = null
            override fun open(devicePublicKeyB64: String, sealed: ByteArray): String? = null
        }
    }
}
