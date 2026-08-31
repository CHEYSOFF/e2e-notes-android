package my.cheysoff.core_sync_net.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.cheysoff.core_sync_net.SyncException
import my.cheysoff.core_sync_net.wire.Base64Codec
import okhttp3.CertificatePinner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * The one place in this application that opens a socket.
 *
 * ## Why OkHttp
 *
 * `docs/design/e2e-sync-phase3-plan.md` §10 decision D1 weighed Ktor client, OkHttp and
 * `HttpURLConnection` and recommended OkHttp **for the pinning**, and that is the reason here too.
 * The pairing QR carries `spkiPinSha256`: the SHA-256 of the server's DER `SubjectPublicKeyInfo`.
 * OkHttp's [CertificatePinner] pins on exactly that value, in exactly that form. The alternative --
 * a hand-written `X509TrustManager` that delegates to the platform's and then compares an SPKI
 * digest -- is forty lines of security code whose failure mode is "accepts everything" and which
 * nothing in this repository could test.
 *
 * It is this module's only third-party dependency, and it is a transport. No analytics, no crash
 * reporting, nothing else rides alongside the `INTERNET` permission this module declares.
 *
 * ## What is switched off, and why
 *
 * - **Redirects.** `followRedirects` and `followSslRedirects` are both false. A pinned client that
 *   follows a redirect can be walked to a host the pin does not cover, and the sync protocol has no
 *   endpoint that legitimately redirects. A `3xx` therefore arrives at the client as a `3xx`, which
 *   it treats as a protocol error.
 * - **Retry on connection failure.** `retryOnConnectionFailure` is false. Retrying is a policy this
 *   module makes deliberately and visibly, in `Backoff`, driven by the server's `Retry-After`.
 *   Two independent retry layers produce a delay schedule nobody can predict, which is how a
 *   back-off that was meant to protect a small VPS turns into the thing that overwhelms it.
 * - **Cookies, cache.** Neither is configured, so OkHttp's defaults apply: no cookie jar and no
 *   cache. A cached `GET /v1/changes` would return a stale page and advance a cursor past records
 *   that were never seen.
 */
class OkHttpTransport private constructor(
    private val client: OkHttpClient,
    private val maxResponseBytes: Int,
) : HttpTransport {

    override suspend fun execute(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val body = request.body?.toRequestBody(JSON_MEDIA_TYPE)
        val builder = Request.Builder().url(request.url)
        when (request.method) {
            HttpMethod.GET -> builder.get()
            HttpMethod.POST -> builder.post(body ?: ByteArray(0).toRequestBody(JSON_MEDIA_TYPE))
            HttpMethod.DELETE -> if (body == null) builder.delete() else builder.delete(body)
        }
        for ((name, value) in request.headers) builder.header(name, value)

        try {
            client.newCall(builder.build()).execute().use { response ->
                val source = response.body?.source()
                val bytes = if (source == null) {
                    ByteArray(0)
                } else {
                    // Read at most one byte more than the cap. `request` on a BufferedSource fills
                    // the buffer up to that many bytes and returns whether it succeeded, so an
                    // oversized body is detected without the rest of it ever being read -- the
                    // client-side mirror of the server's own `readBounded`.
                    val over = source.request(maxResponseBytes.toLong() + 1)
                    if (over && source.buffer.size > maxResponseBytes) {
                        throw SyncException.ResponseTooLarge(maxResponseBytes)
                    }
                    source.readByteArray()
                }
                if (bytes.size > maxResponseBytes) throw SyncException.ResponseTooLarge(maxResponseBytes)
                HttpResponse(
                    status = response.code,
                    headers = response.headers.names().associateWith { response.headers[it].orEmpty() },
                    body = bytes,
                )
            }
        } catch (e: SSLPeerUnverifiedException) {
            // OkHttp reports a pin mismatch as this exception, whose message names the pins and the
            // certificate chain. That message is not propagated: it is long, it is confusing to a
            // user, and it is not this client's to interpret. The cause is kept for a bug report.
            throw SyncException.PinMismatch(
                "the sync server's certificate did not match the pinned key",
                e,
            )
        } catch (e: IOException) {
            throw SyncException.Network("could not reach the sync server", e)
        }
    }

    companion object {

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * Ten seconds to connect, thirty to read.
         *
         * A pull page is 200 records of up to 256 KiB each, so a large first sync over a slow link
         * is a genuinely long read; thirty seconds is generous for it and still short enough that a
         * black-holed connection does not park a sync pass for minutes.
         */
        private const val CONNECT_TIMEOUT_SECONDS = 10L
        private const val READ_TIMEOUT_SECONDS = 30L
        private const val WRITE_TIMEOUT_SECONDS = 30L

        /**
         * The largest response body this client will hold in memory.
         *
         * Sized from the protocol rather than picked: the biggest legitimate response is a full
         * page of `GET /v1/changes`, which is `limit` records of at most `MANANA_MAX_ENVELOPE_BYTES`
         * (256 KiB) each, plus base64 expansion. The client asks for a page of
         * `SyncHttpClient.DEFAULT_CHANGES_LIMIT` (32) records, so the worst legitimate case is on
         * the order of 32 × 256 KiB × 4/3 ≈ 11 MiB. 16 MiB leaves headroom for JSON overhead
         * without being an amount of memory an Android process can absorb by accident.
         *
         * Note this is a cap on **one response**, not on a sync pass. Pulling a large account still
         * works; it takes more pages.
         */
        const val DEFAULT_MAX_RESPONSE_BYTES = 16 * 1024 * 1024

        /**
         * Builds a transport for one [endpoint], enforcing its certificate pin if it has one.
         *
         * The pin is scoped to the endpoint's host, which is what makes it meaningful: a pin that
         * applied to every host would be enforced against whichever host a redirect reached, and
         * redirects are off for the same reason.
         */
        fun create(
            endpoint: ServerEndpoint,
            maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
        ): OkHttpTransport {
            val builder = OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)

            endpoint.spkiPinSha256?.let { pin ->
                builder.certificatePinner(
                    CertificatePinner.Builder()
                        .add(endpoint.host, okHttpPin(pin))
                        .build()
                )
            }
            return OkHttpTransport(builder.build(), maxResponseBytes)
        }

        /**
         * The 32 raw bytes of `ServerHint.spkiPinSha256`, in the form [CertificatePinner] wants.
         *
         * OkHttp's format is `"sha256/"` followed by **standard**, padded base64 of the digest --
         * not the base64url the rest of this protocol uses. Getting that wrong does not fail
         * loudly at the point of the mistake; `CertificatePinner.Builder.add` accepts any
         * well-formed base64, so the wrong alphabet produces a pin that simply never matches and a
         * client that cannot talk to its own server. Kept as a named function so it has a test.
         */
        fun okHttpPin(spkiPinSha256: ByteArray): String {
            require(spkiPinSha256.size == ServerEndpoint.SPKI_PIN_SIZE_BYTES) {
                "an SPKI pin is a 32-byte SHA-256 digest"
            }
            return "sha256/" + Base64Codec.encodeStandard(spkiPinSha256)
        }
    }
}
