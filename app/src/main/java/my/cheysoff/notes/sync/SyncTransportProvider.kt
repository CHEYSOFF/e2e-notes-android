package my.cheysoff.notes.sync

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import my.cheysoff.core_domain.repository.SyncSettingsRepository
import my.cheysoff.core_sync_net.SyncApi
import my.cheysoff.core_sync_net.SyncHttpClient
import my.cheysoff.core_sync_net.auth.DeviceLabelSealer
import my.cheysoff.core_sync_net.auth.DeviceSigner
import my.cheysoff.core_sync_net.http.ServerEndpoint
import my.cheysoff.feature_pairing.identity.DeviceIdentity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A [SyncApi] for the configured server, or a stated reason there is none.
 *
 * ## Why the graph hands out this and not a bare `SyncApi`
 *
 * A `@Provides SyncApi` has to return one. There are only three things it could do when no server
 * is configured, and all three are worse than this type:
 *
 *  - **Throw from the provider.** Every injection site becomes a crash site, including ones that
 *    only wanted to ask whether sync is set up.
 *  - **Return a no-op `SyncApi`.** Calls would return empty change lists and successful-looking
 *    pushes. A sync engine reading "the server has no records" from an unconfigured device is the
 *    silent data-loss path this whole design is trying not to build.
 *  - **Return a `SyncApi` that throws on every call.** Closest to honest, and still not available:
 *    `SyncException` is a `sealed class` in `:core-sync-net`, so there is no way to add a
 *    "not configured" case to it from here, and reusing `Network` or `Protocol` would mean
 *    reporting a configuration state as a connection failure.
 *
 * So the absence is in the return type, where a caller cannot skip past it. [SyncTransport.Ready]
 * carries a real client built with `SyncHttpClient.create`; [SyncTransport.NotConfigured] carries
 * why. Nothing opens a socket to find out which.
 *
 * ## Nothing here performs I/O over the network
 *
 * Building a client is object construction — OkHttp connects lazily, on the first call. So
 * [current] reads a preference, touches the Keystore, and returns; a device that is paired and has
 * a URL still sends no packet until something calls a method on the returned [SyncApi]. Today the
 * only caller that does is the settings screen's explicit "Check server" action.
 */
interface SyncTransportProvider {

    /**
     * The current transport, re-derived from what is stored right now.
     *
     * Suspends because both inputs are disk-backed: a DataStore read and an AndroidKeyStore
     * lookup. Cheap on the second and later calls for an unchanged configuration — see the
     * caching note on the implementation.
     */
    suspend fun current(): SyncTransport
}

/** What [SyncTransportProvider.current] can answer. */
sealed interface SyncTransport {

    /**
     * A usable client for [endpoint].
     *
     * "Usable" means configured, not reachable. Nothing has been sent to this server and it may
     * not exist.
     */
    data class Ready(val api: SyncApi, val endpoint: ServerEndpoint) : SyncTransport

    /** There is no transport, and [message] says which piece is missing. */
    data class NotConfigured(val reason: SyncNotConfigured, val message: String) : SyncTransport
}

/**
 * Reads the two stored facts, applies [planSyncEndpoint], and builds a client when it may.
 *
 * The client is cached against the endpoint's `baseUrl`, so repeated calls for an unchanged
 * setting reuse one `SyncHttpClient` — and with it one OkHttp connection pool, which is the whole
 * reason to bother — while a changed setting builds a new one. The cache is guarded by a [Mutex]
 * rather than left to `@Volatile`: two coroutines racing here would otherwise each construct a
 * client and one would be dropped along with its pool.
 */
@Singleton
class DefaultSyncTransportProvider @Inject constructor(
    private val syncSettings: SyncSettingsRepository,
    private val deviceIdentity: DeviceIdentity,
    private val signer: DeviceSigner,
    private val labelSealer: DeviceLabelSealer,
) : SyncTransportProvider {

    private val lock = Mutex()
    private var cachedBaseUrl: String? = null
    private var cachedApi: SyncApi? = null

    override suspend fun current(): SyncTransport {
        // `first()` rather than a stored snapshot: the setting can change while the app is running
        // (the settings screen is one back-press away) and a transport built against a stale URL
        // would post to the previous server.
        val storedUrl = syncSettings.serverUrl.first()
        val paired = deviceIdentity.isProvisioned()

        return when (val plan = planSyncEndpoint(paired = paired, storedUrl = storedUrl)) {
            is SyncEndpointPlan.Unusable ->
                SyncTransport.NotConfigured(plan.reason, plan.message)

            is SyncEndpointPlan.Usable ->
                SyncTransport.Ready(clientFor(plan.endpoint), plan.endpoint)
        }
    }

    private suspend fun clientFor(endpoint: ServerEndpoint): SyncApi = lock.withLock {
        val existing = cachedApi
        if (existing != null && cachedBaseUrl == endpoint.baseUrl) return@withLock existing
        // No TransportLog is passed, so the transport's default (TransportLog.NONE) applies and
        // nothing about a request reaches logcat. Route templates would be safe to log; deciding
        // to log them at all is not this class's decision to make on the user's behalf.
        val created = SyncHttpClient.create(
            endpoint = endpoint,
            signer = signer,
            labelSealer = labelSealer,
        )
        cachedBaseUrl = endpoint.baseUrl
        cachedApi = created
        created
    }
}
