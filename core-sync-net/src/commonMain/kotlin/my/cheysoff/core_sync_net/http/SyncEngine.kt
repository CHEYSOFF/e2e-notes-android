package my.cheysoff.core_sync_net.http

import io.ktor.client.engine.HttpClientEngine
import my.cheysoff.core_sync_net.SyncException

/**
 * The per-platform half of the transport: everything about an HTTP client that a platform cannot
 * express portably.
 *
 * There are exactly two such things, and the shortness of this file is the claim being made. If a
 * third `expect` ever appears here, the "one client, swappable engines" story has started to become
 * "two clients", and that is worth stopping to argue about rather than adding.
 */

/**
 * The HTTP engine for one [endpoint], with that endpoint's certificate pin installed.
 *
 * Pinning is the reason this is per-platform rather than a Ktor plugin. A pin is a statement about
 * the certificate chain, and every platform exposes the chain differently: OkHttp has
 * `CertificatePinner`, which takes the SHA-256 of the DER `SubjectPublicKeyInfo` -- exactly the 32
 * bytes `ServerHint.spkiPinSha256` carries -- while Darwin needs `NSURLSession`'s authentication
 * challenge. Ktor has no common abstraction over either, and writing one here would be a
 * hand-rolled trust decision whose failure mode is "accepts everything".
 *
 * The pin is scoped to [ServerEndpoint.host]. An implementation must also switch redirects off:
 * a pinned client that follows a redirect can be walked to a host the pin does not cover.
 */
internal expect fun createSyncEngine(endpoint: ServerEndpoint): HttpClientEngine

/**
 * Decides what a failure from the engine means.
 *
 * The one distinction that matters is [SyncException.PinMismatch] against
 * [SyncException.Network][my.cheysoff.core_sync_net.SyncException.Network], and it can only be made
 * per-platform because it is made by recognising the platform's own exception type -- OkHttp reports
 * a pin failure as `javax.net.ssl.SSLPeerUnverifiedException`, and Darwin reports it as an
 * `NSError`. Guessing from a message string instead would be a security control that stops working
 * the day a library reworded something.
 *
 * Everything that is not recognised is [SyncException.Network]. That default is the safe direction:
 * a network error is retried on a later pass, while a pin mismatch is a hard stop, so misreading a
 * pin failure as a network error costs a wasted retry and misreading it the other way would tell a
 * user their server has been impersonated when their wifi dropped.
 */
internal expect fun classifyTransportFailure(cause: Throwable): SyncException

/**
 * How long the transport waits, in milliseconds.
 *
 * Here rather than in each engine so that two platforms cannot drift apart on a number that is a
 * policy decision, not a platform detail. Applied by [createSyncEngine], because Ktor's own
 * `HttpTimeout` plugin re-derives engine timeouts from its three fields and this module would
 * rather keep the OkHttp calls it already had than re-check that derivation on every Ktor upgrade.
 */
internal object SyncTimeouts {

    /**
     * Ten seconds to connect, thirty to read.
     *
     * A pull page is 200 records of up to 256 KiB each, so a large first sync over a slow link is a
     * genuinely long read; thirty seconds is generous for it and still short enough that a
     * black-holed connection does not park a sync pass for minutes.
     */
    const val CONNECT_MILLIS = 10_000L
    const val READ_MILLIS = 30_000L
    const val WRITE_MILLIS = 30_000L
}
