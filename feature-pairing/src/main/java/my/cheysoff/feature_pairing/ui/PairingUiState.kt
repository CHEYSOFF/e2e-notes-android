package my.cheysoff.feature_pairing.ui

import androidx.compose.runtime.Immutable
import my.cheysoff.feature_pairing.protocol.PairingFailure

/**
 * Which half of the exchange this device is playing.
 *
 * The user picks it, and the pick is not a preference — it decides which device's account key
 * survives. Getting it backwards would seal the *new* device's (non-existent) key onto the one
 * holding the notes, so the chooser names the two roles by what the user can see on the phone in
 * their hand rather than by "A" and "B".
 */
enum class PairingRole {
    /** This device already has the notes. It scans first, then shows the sealed reply. */
    HasMyNotes,

    /** This is the new device. It shows its code first, then scans the reply. */
    NewDevice,
}

/**
 * The camera permission, as the screen needs to reason about it.
 *
 * [PermanentlyDenied] is separated from [Denied] because the two need different words and
 * different buttons: one can be asked again, the other can only be fixed in system settings, and
 * an app that keeps showing "Allow camera" to someone who chose "Don't allow" twice is an app that
 * looks broken.
 */
enum class CameraPermission { Unknown, Granted, Denied, PermanentlyDenied }

/**
 * Where the pairing has got to.
 *
 * Both roles pass through [Confirming]: the six-digit SAS is the last step for each of them, and
 * neither device commits anything until the user says the two match.
 */
@Immutable
sealed interface PairingStage {

    /** Nothing started. The role chooser. */
    data object ChoosingRole : PairingStage

    /**
     * New device, step 1: showing QR1 while the other phone scans it.
     *
     * [code] is the payload; the screen renders it. [secondsRemaining] counts the session's TTL
     * down and reaching zero moves to [Failed] with [PairingFailure.EXPIRED].
     */
    @Immutable
    data class ShowingOffer(val code: String, val secondsRemaining: Int) : PairingStage

    /** New device, step 2: camera up, waiting for the other phone's QR2. */
    @Immutable
    data class ScanningSeal(val secondsRemaining: Int, val lastHint: ScanHint?) : PairingStage

    /** Account device, step 1: camera up, waiting for the new phone's QR1. */
    @Immutable
    data class ScanningOffer(val lastHint: ScanHint?) : PairingStage

    /**
     * Account device, step 2: showing QR2 alongside the SAS.
     *
     * The SAS is shown here rather than only after the other device confirms, because the whole
     * point is that the user compares two screens that are both already displaying it.
     */
    @Immutable
    data class ShowingSeal(
        val code: String,
        val sas: String,
        val secondsRemaining: Int,
    ) : PairingStage

    /** Both roles: compare the six digits and say whether they match. */
    @Immutable
    data class Confirming(val sas: String, val role: PairingRole) : PairingStage

    /** Done. Nothing further to do on this screen. */
    @Immutable
    data class Finished(val role: PairingRole) : PairingStage

    /**
     * Stopped. Recoverable only by starting over.
     *
     * [failure] is the protocol's reason, or null when the protocol was happy and a *person*
     * stopped it — the SAS comparison failing is the only such case, and conflating it with a GCM
     * tag failure would make both harder to read in a bug report.
     */
    @Immutable
    data class Failed(val failure: PairingFailure?, val message: String) : PairingStage
}

/**
 * A one-line note about the last thing the camera saw that was not the code we wanted.
 *
 * Deliberately coarse. The scanner is fed every symbol in view, so a "that is not a pairing code"
 * for each of them would flicker constantly; only the cases a user can act on are surfaced.
 */
enum class ScanHint {
    /** A pairing code from a different app version. */
    DifferentVersion,

    /** A valid pairing code, but the other step's — the phones are out of sequence. */
    WrongStep,

    /** A pairing code from another session: a stale screen, or somebody else's pairing. */
    OtherSession,
}

/**
 * Everything [PairingScreen] draws.
 *
 * [available] is the sync-key-hierarchy gate — see `PairingKeyMaterial.isBound`. It is true in
 * every shipped build now that the hierarchy is bound; it is kept as the backstop that would show
 * an honest message rather than a flow that cannot finish.
 */
@Immutable
data class PairingScreenState(
    val available: Boolean = false,
    val cameraPermission: CameraPermission = CameraPermission.Unknown,
    val stage: PairingStage = PairingStage.ChoosingRole,
    /**
     * True when this device holds an account key, i.e. when [PairingRole.HasMyNotes] is a role it
     * can actually play. A device with no ARK has nothing to seal.
     */
    val canShareAccount: Boolean = false,
)
