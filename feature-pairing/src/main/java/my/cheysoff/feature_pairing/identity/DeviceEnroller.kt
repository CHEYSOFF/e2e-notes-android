package my.cheysoff.feature_pairing.identity

import my.cheysoff.core_pairing.protocol.RendezvousUrl

/**
 * Enrols another device on this account, at the server that device named.
 *
 * ## Who may vouch, and for what
 *
 * `POST /v1/devices/authorize` is signed by an **already-enrolled** device: the server looks the
 * voucher up, refuses it if it is revoked, and verifies the signature against its stored key. So the
 * joining device cannot enrol itself, and the account device has to do it on its behalf. That is the
 * whole reason this seam exists.
 *
 * The rule an implementation must not weaken is on the *other* argument. [joiningDeviceKey] is the
 * key that came out of QR1 — read by a camera a person aimed at a screen they were looking at — and
 * it is the only key an implementation may pass to the server. Fetching the key from the rendezvous
 * instead would work identically and would hand the choice of which key gets enrolled to whoever
 * answers the request, which is precisely what vouching exists to prevent.
 *
 * ## Why it is an interface, in this module
 *
 * `:feature-pairing` cannot see `:core-sync-net` and should not: pairing is a protocol between two
 * devices in a room, and giving it an HTTP client would put the account key and a server connection
 * in one class. The implementation is an adapter in `:app`, next to `KeystoreDeviceSigner` and
 * `ArkDeviceLabelSealer`, for the reason both of those give — `:app` is the module that can see the
 * key's owner and the transport at once.
 *
 * ## It is never called on the phone-to-phone path
 *
 * That flow reaches no server and therefore has nothing to enrol on. `PairingViewModel` calls this
 * only when QR1 carried a server address, and `pairingWithAnotherPhoneNeverTouchesTheNetwork` is the
 * test that holds the line.
 */
fun interface DeviceEnroller {

    /**
     * Vouch for [joiningDeviceKey] on [server], and return the id the server assigned it.
     *
     * Suspends: it is one or two HTTP round trips. Never throws — a failure is
     * [EnrolmentResult.Refused] with a sentence the screen can show, because a pairing that cannot
     * enrol is still a pairing that can hand over the ARK, and taking the process down would be a
     * worse answer than saying so.
     *
     * @param joiningDeviceKey SEC1 uncompressed P-256, 65 bytes, exactly as QR1 carried it.
     * @param label the joining device's name. Sealed against the joining device's own key before it
     *   is sent; see `DeviceLabelSealer`.
     */
    suspend fun enrol(
        server: RendezvousUrl,
        joiningDeviceKey: ByteArray,
        label: String,
    ): EnrolmentResult
}

/** What [DeviceEnroller.enrol] did. */
sealed interface EnrolmentResult {

    /** The server enrolled the device. [deviceId] is what it will authenticate as. */
    class Enrolled(val deviceId: String) : EnrolmentResult

    /**
     * It did not. [message] is a sentence for the user.
     *
     * Not fatal to the pairing. The ARK can still cross and the config still names the server, so
     * the new device ends up with the account and no session — which the pairing screen says out
     * loud rather than discovering silently at the first sync.
     */
    class Refused(val message: String) : EnrolmentResult
}
