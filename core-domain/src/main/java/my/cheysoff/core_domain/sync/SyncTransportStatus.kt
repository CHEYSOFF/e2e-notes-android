package my.cheysoff.core_domain.sync

/**
 * The two facts about the sync transport that a screen is allowed to ask for, and the one action
 * it is allowed to trigger.
 *
 * ## Why this interface exists at all
 *
 * The settings screen has to answer "is this device paired?" and "does that address answer?".
 * Both answers live behind types the settings module cannot see and should not: the pairing
 * identity key belongs to `:feature-pairing`, and `SyncApi` belongs to `:core-sync-net`. `:app` is
 * the only module that can see both — the same argument `KeystoreDeviceSigner` and
 * `ArkDeviceLabelSealer` make for living there — so `:app` implements this and the settings module
 * depends on nothing but these three types.
 *
 * ## Why there is no "sync now"
 *
 * Because there is no sync. No engine, no merge, no scheduler; those are separate work. The only
 * network call this interface can make is the unauthenticated `GET /healthz` behind [checkServer],
 * which is the use `SyncApi.health` documents for itself: it commits no key material, uploads
 * nothing, and tells the user whether the address they typed is an address that answers.
 */
interface SyncTransportStatus {

    /**
     * True once this device has completed a pairing.
     *
     * Reads the AndroidKeyStore for the device identity key, which pairing provisions at the
     * moment the user confirms the six digits and which nothing else creates. That is a disk-backed
     * platform call, hence `suspend`; callers must not run it on the main thread.
     */
    suspend fun isPaired(): Boolean

    /**
     * One `GET /healthz` against the configured server.
     *
     * Returns [SyncServerCheck.NotConfigured] without touching the network when there is nothing
     * to check — no pairing, no stored URL, or a stored URL that no longer validates.
     */
    suspend fun checkServer(): SyncServerCheck
}

/** The outcome of [SyncTransportStatus.checkServer]. */
sealed interface SyncServerCheck {

    /**
     * The server answered `GET /healthz`.
     *
     * This says the address is reachable and is speaking the protocol this client understands. It
     * says nothing at all about whether this device is enrolled on that server, whether the account
     * exists, or whether anything would sync — `/healthz` is unauthenticated by design.
     */
    data object Reachable : SyncServerCheck

    /**
     * The call was attempted and did not come back with a health response.
     *
     * [message] is the transport's own description, which is written to be safe to show and to log:
     * `SyncException` carries no token, no signature and no account identifier in any message.
     */
    data class Unreachable(val message: String) : SyncServerCheck

    /**
     * Nothing was attempted, because there is nothing to attempt it against.
     *
     * [message] says which piece is missing, in the same words the settings screen shows.
     */
    data class NotConfigured(val message: String) : SyncServerCheck
}
