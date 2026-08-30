package my.cheysoff.feature_pairing.ui

sealed interface PairingIntent {

    /** The user picked a side. Starts a session. */
    data class RoleChosen(val role: PairingRole) : PairingIntent

    /**
     * The new device has finished showing its code and is ready to scan the reply.
     *
     * A deliberate manual step rather than a timer or an automatic transition: the other phone has
     * to have actually scanned QR1 first, and only the person holding both phones knows when that
     * happened.
     */
    data object OfferShown : PairingIntent

    /**
     * One QR symbol came off the camera.
     *
     * Fired for every symbol in view, many times a second, including ones that are not pairing
     * codes at all. The ViewModel is responsible for that being cheap.
     */
    data class CodeScanned(val text: String) : PairingIntent

    /**
     * The account device has finished showing QR2 and the other phone has scanned it.
     *
     * Manual for the same reason [OfferShown] is: the account device emitted QR2 and has no way to
     * observe whether the other phone read it. Only the person holding both phones knows.
     */
    data object SealShown : PairingIntent

    /** The user says the two six-digit codes match. This is what commits the pairing. */
    data object SasConfirmed : PairingIntent

    /** The user says they do not match. Everything received is discarded. */
    data object SasRejected : PairingIntent

    /** Throw the session away and go back to the role chooser. */
    data object StartOver : PairingIntent

    /**
     * The result of a camera permission request.
     *
     * [permanentlyDenied] is `shouldShowRequestPermissionRationale == false` *after* a refusal,
     * which is the only reliable way Android exposes "don't ask again". It is computed by the
     * screen, because it needs an Activity.
     */
    data class CameraPermissionChanged(
        val granted: Boolean,
        val permanentlyDenied: Boolean,
    ) : PairingIntent
}
