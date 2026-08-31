package my.cheysoff.core_sync_net.http

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import my.cheysoff.core_sync_net.SyncException
import my.cheysoff.core_sync_net.wire.Base64Codec
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Android and the JVM: Ktor over OkHttp.
 *
 * ## Why OkHttp is still underneath
 *
 * `docs/design/e2e-sync-phase3-plan.md` §10 decision D1 chose OkHttp **for the pinning**, and that
 * reasoning is unchanged. The pairing QR carries `spkiPinSha256`: the SHA-256 of the server's DER
 * `SubjectPublicKeyInfo`. [CertificatePinner] pins on exactly that value, in exactly that form. The
 * alternative -- a hand-written `X509TrustManager` that delegates to the platform's and then
 * compares an SPKI digest -- is forty lines of security code whose failure mode is "accepts
 * everything" and which nothing in this repository could test.
 *
 * ## `preconfigured`, not `config { }`
 *
 * The [OkHttpClient] is **built here**, eagerly, and handed to Ktor whole. Ktor's OkHttp engine also
 * accepts a `config { }` block, and it would be the shorter spelling, but that block is applied
 * lazily when the engine first needs a client -- so a malformed pin pattern would be discovered on
 * the first request rather than at construction, which is where `ServerEndpointTest` looks for it.
 * Building the client here also makes the claim "Android's network stack did not change" literal
 * rather than approximate: these are the same calls, in the same order, that the pre-Ktor transport
 * made.
 *
 * Ktor takes this client as its prototype and calls `newBuilder()` on it per request; with no
 * `HttpTimeout` plugin installed it adds no timeouts of its own, so the three set here are the ones
 * that apply.
 */
internal actual fun createSyncEngine(endpoint: ServerEndpoint): HttpClientEngine {
    val builder = OkHttpClient.Builder()
        .connectTimeout(SyncTimeouts.CONNECT_MILLIS, TimeUnit.MILLISECONDS)
        .readTimeout(SyncTimeouts.READ_MILLIS, TimeUnit.MILLISECONDS)
        .writeTimeout(SyncTimeouts.WRITE_MILLIS, TimeUnit.MILLISECONDS)
        // Off at the engine as well as in Ktor's config, and knowingly redundant today: Ktor's
        // `followRedirects = false` is what actually stops a redirect being followed, and flipping
        // these two back on was measured to fail no test. They are here against a Ktor default
        // changing under an upgrade, which is a cheap thing to insure and an expensive thing to
        // discover -- a redirect followed by a pinned client is a walk to a host the pin does not
        // cover.
        .followRedirects(false)
        .followSslRedirects(false)
        // See KtorHttpTransport's KDoc: `Backoff` is the only retry policy this module has, and a
        // second one underneath it makes the delay schedule unpredictable.
        .retryOnConnectionFailure(false)

    endpoint.spkiPinSha256?.let { pin ->
        builder.certificatePinner(
            CertificatePinner.Builder()
                .add(endpoint.host, okHttpPin(pin))
                .build()
        )
    }
    return OkHttp.create { preconfigured = builder.build() }
}

/**
 * OkHttp reports a pin mismatch as [SSLPeerUnverifiedException], whose message names the pins and
 * the certificate chain.
 *
 * The cause chain is walked rather than the top exception being tested, because the exception a
 * caller sees now comes back through Ktor's engine and Ktor is free to wrap it. A test for the
 * outermost type alone would keep passing on a Ktor upgrade that added a wrapper, and it would
 * degrade a pin failure into "check your connection" -- which is the one connection failure that
 * must never be presented that way.
 *
 * The `SSLPeerUnverifiedException` message itself is deliberately not propagated: it is long, it is
 * confusing to a user, and it is not this client's to interpret. The cause is kept for a bug report.
 */
internal actual fun classifyTransportFailure(cause: Throwable): SyncException {
    var current: Throwable? = cause
    // Bounded rather than "walk to the end": a self-referential cause chain is rare but it is a
    // hang, and a transport failure is not the place to discover that.
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        if (current is SSLPeerUnverifiedException) {
            return SyncException.PinMismatch(
                "the sync server's certificate did not match the pinned key",
                cause,
            )
        }
        current = current.cause
        depth++
    }
    return SyncException.Network("could not reach the sync server", cause)
}

private const val MAX_CAUSE_DEPTH = 16

/**
 * The 32 raw bytes of `ServerHint.spkiPinSha256`, in the form [CertificatePinner] wants.
 *
 * OkHttp's format is `"sha256/"` followed by **standard**, padded base64 of the digest -- not the
 * base64url the rest of this protocol uses. Getting that wrong does not fail loudly at the point of
 * the mistake; `CertificatePinner.Builder.add` accepts any well-formed base64, so the wrong alphabet
 * produces a pin that simply never matches and a client that cannot talk to its own server. Kept as
 * a named function so it has a test.
 */
fun okHttpPin(spkiPinSha256: ByteArray): String {
    require(spkiPinSha256.size == ServerEndpoint.SPKI_PIN_SIZE_BYTES) {
        "an SPKI pin is a 32-byte SHA-256 digest"
    }
    return "sha256/" + Base64Codec.encodeStandard(spkiPinSha256)
}
