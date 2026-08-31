package my.cheysoff.notes.sync

import my.cheysoff.core_sync_net.http.ServerEndpoint
import my.cheysoff.feature_settings.model.SyncServerUrlCheck
import my.cheysoff.feature_settings.model.checkSyncServerUrl

/**
 * The decision "can this device talk to a sync server, and if so, where?" — with no I/O in it.
 *
 * Separated from [SyncTransportProvider] so the decision itself is a pure function over two facts
 * and can be unit-tested (`SyncEndpointPlanTest`) without a Keystore, a DataStore or a socket. The
 * provider around it does the reading and the client construction and makes no decisions of its
 * own.
 */
sealed interface SyncEndpointPlan {

    /** Everything needed to build a client is present. */
    data class Usable(val endpoint: ServerEndpoint) : SyncEndpointPlan

    /**
     * Something is missing. [reason] is what a caller branches on; [message] is a sentence a
     * screen can show.
     */
    data class Unusable(val reason: SyncNotConfigured, val message: String) : SyncEndpointPlan
}

/**
 * Why there is no transport.
 *
 * All three are ordinary, expected states of a correct install, not errors — an app whose premise
 * is talking to nobody spends most of its life in [NOT_PAIRED].
 */
enum class SyncNotConfigured {

    /** No pairing has completed, so this device is not on an account. */
    NOT_PAIRED,

    /** Paired, but the user has not set a server address. */
    NO_SERVER_URL,

    /**
     * A server address is stored and does not validate.
     *
     * Reachable in practice by a preferences file written by a build with a different rule, or
     * edited on a rooted device. The settings screen refuses to store an invalid address, so this
     * is a guard rather than the common path — and it is a distinct reason because "you never set
     * one" and "the one you set is no longer acceptable" send a user to different places.
     */
    UNUSABLE_SERVER_URL,
}

/**
 * Decide, from the two persisted facts, what the transport can be.
 *
 * @param paired whether the device identity key exists — pairing provisions it and nothing else
 *   creates it.
 * @param storedUrl the persisted server base URL, or null.
 *
 * Pairing is checked **first and independently of the URL**, because a server address on an
 * unpaired device is not a usable configuration: there is no account ID to name, no ARK to seal a
 * device label with and no identity key to sign a session challenge. Reporting "ready" for it
 * would be the silent no-op this wiring exists to avoid.
 */
fun planSyncEndpoint(paired: Boolean, storedUrl: String?): SyncEndpointPlan = when {
    !paired -> SyncEndpointPlan.Unusable(
        SyncNotConfigured.NOT_PAIRED,
        "This device hasn't been paired, so it isn't on an account yet.",
    )

    storedUrl.isNullOrBlank() -> SyncEndpointPlan.Unusable(
        SyncNotConfigured.NO_SERVER_URL,
        "No sync server address has been set.",
    )

    else -> when (val check = checkSyncServerUrl(storedUrl)) {
        // Re-validated on the way out of storage rather than trusted. The settings screen is the
        // only writer today, but a preferences file is not a trusted input and the cost of being
        // wrong here is a client pointed somewhere the current rule would refuse.
        is SyncServerUrlCheck.Ok -> SyncEndpointPlan.Usable(
            // No certificate pin. `ServerEndpoint` accepts one and `ServerHint` on the pairing QR
            // carries one, but nothing in the app can produce one today: PairingViewModel builds
            // its session with the default `ServerHint.NONE`, so a device advertises no server and
            // no pin, and the hint an account device does receive
            // (`PairingSession.receivedServerHint`) is never persisted or read outside a test.
            // Wiring a pin through would mean carrying it in `AccountBundle.config` and storing it
            // at pairing commit -- work inside `:feature-pairing`, not here. Passing null is
            // therefore the honest state: the platform CA set is trusted, and the app does not
            // claim a pin it is not enforcing.
            ServerEndpoint(check.normalized)
        )

        is SyncServerUrlCheck.Rejected -> SyncEndpointPlan.Unusable(
            SyncNotConfigured.UNUSABLE_SERVER_URL,
            check.message,
        )
    }
}
