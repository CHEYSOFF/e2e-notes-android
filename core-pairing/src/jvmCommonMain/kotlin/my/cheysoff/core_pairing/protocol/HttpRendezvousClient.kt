package my.cheysoff.core_pairing.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The one production [RendezvousClient]: `HttpURLConnection`, and nothing else.
 *
 * ## Why `HttpURLConnection`
 *
 * It is the only HTTP client that exists unmodified on both sides of this exchange. `java.net.http`
 * is JDK 11+ and is **not on Android at any API level**; OkHttp and Ktor are both real
 * dependencies that the desktop module does not otherwise carry. Two implementations of one
 * two-call protocol is the shape of mistake this module exists to avoid, so the client is written
 * once against the API both platforms have had since forever.
 *
 * ## What it deliberately does not do
 *
 * **No retry.** A failed request comes back as `Unreachable` and the caller decides. The collect
 * loop retries because polling *is* retrying; the deposit does not, because a deposit that may or
 * may not have landed must be reported rather than repeated — the server refuses a second one, and
 * a client that silently retried would turn a slow network into "pairing failed".
 *
 * **No redirects.** `setInstanceFollowRedirects(false)`. A redirect on this route can only move the
 * sealed bundle somewhere the user never saw and never approved, and the honest answer to a server
 * that wants to redirect a pairing deposit is to stop.
 *
 * **No certificate pinning, and no attempt at it.** Pinning belongs with the sync transport, which
 * has a configured server and a pin that arrives authenticated. Here the address is an
 * unauthenticated hint the user has just been asked to confirm, and the confidentiality of the blob
 * does not rest on the transport at all — see [RendezvousProtocol].
 */
class HttpRendezvousClient(
    private val server: RendezvousUrl,
    /** Connect and read timeouts. Short: a poll that hangs is a poll that is not polling. */
    private val timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : RendezvousClient {

    override fun deposit(sid: ByteArray, sealCode: String): DepositResult {
        val blob = RendezvousProtocol.toBlob(sealCode)
        val body = JsonObject(mapOf(RendezvousProtocol.FIELD_SEALED to JsonPrimitive(blob)))
            .toString().toByteArray(Charsets.UTF_8)

        val response = try {
            request(sid, method = "POST", body = body)
        } catch (e: IOException) {
            return DepositResult.Unreachable(e.message ?: e::class.java.simpleName)
        }

        return when (response.status) {
            HttpURLConnection.HTTP_CREATED, HttpURLConnection.HTTP_OK ->
                DepositResult.Deposited(expiresAt = readExpiry(response.body))

            HttpURLConnection.HTTP_CONFLICT -> DepositResult.AlreadyDeposited

            else -> DepositResult.Refused(describe(response))
        }
    }

    override fun collect(sid: ByteArray): CollectResult {
        val response = try {
            request(sid, method = "GET", body = null)
        } catch (e: IOException) {
            return CollectResult.Unreachable(e.message ?: e::class.java.simpleName)
        }

        return when (response.status) {
            HttpURLConnection.HTTP_OK -> {
                val blob = readSealed(response.body)
                    ?: return CollectResult.Unusable("the server returned no sealed bundle")
                // Checked here rather than after the prefix goes back on, so an over-long or
                // non-base64 body is refused before anything downstream allocates it. The frame
                // itself is then validated by the ordinary decoder, which is the point of
                // `fromBlob`: this leg skips no guard the scanned leg runs.
                if (!RendezvousProtocol.isPlausibleBlob(blob)) {
                    return CollectResult.Unusable("the server returned an implausible sealed bundle")
                }
                CollectResult.Collected(RendezvousProtocol.fromBlob(blob))
            }

            // The ordinary answer for all but the last poll: the phone has not sent it yet. It is
            // also the answer after a successful collect, since the server is single-use -- which
            // this caller never sees, because it stops on the first success.
            HttpURLConnection.HTTP_NOT_FOUND -> CollectResult.Pending

            // Backing off is the loop's job, not this method's. Reported as unreachable rather than
            // unusable so the poller keeps its schedule instead of aborting the pairing.
            HTTP_TOO_MANY_REQUESTS -> CollectResult.Unreachable("the server is rate limiting")

            else -> CollectResult.Unusable(describe(response))
        }
    }

    // -------------------------------------------------------------------------------------------

    private class Response(val status: Int, val body: String)

    private fun request(sid: ByteArray, method: String, body: ByteArray?): Response {
        val url = URL(server.base + RendezvousProtocol.PATH_PREFIX + RendezvousProtocol.encodeSid(sid))
        val connection = open(url)
        return try {
            connection.requestMethod = method
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                // Streaming mode with a known length, so the request is not buffered whole and a
                // Content-Length is sent -- which is what lets the server refuse an oversized body
                // without reading it.
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            // `errorStream` is where a 4xx/5xx body lands; `inputStream` throws for those.
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            Response(status, stream.readBounded(MAX_RESPONSE_BYTES))
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Reads at most [max] bytes and discards the rest.
     *
     * A bound rather than `readBytes()`: the response comes from a host named in a QR code, and an
     * unbounded read of an unbounded body is how a poll loop becomes an out-of-memory. The cap is
     * far above any legal response, so truncation shows up as a parse failure rather than as
     * silently accepted half-JSON.
     */
    private fun InputStream?.readBounded(max: Int): String {
        if (this == null) return ""
        return use { stream ->
            val buffer = ByteArray(max)
            var filled = 0
            while (filled < max) {
                val read = stream.read(buffer, filled, max - filled)
                if (read <= 0) break
                filled += read
            }
            String(buffer, 0, filled, Charsets.UTF_8)
        }
    }

    private fun readSealed(body: String): String? =
        field(body, RendezvousProtocol.FIELD_SEALED)?.contentOrNull

    private fun readExpiry(body: String): Long =
        field(body, RendezvousProtocol.FIELD_EXPIRES_AT)?.longOrNull ?: 0L

    private fun field(body: String, name: String): JsonPrimitive? = try {
        JSON.parseToJsonElement(body).jsonObject[name]?.jsonPrimitive
    } catch (e: Exception) {
        // Any shape that is not the object this expects. Never thrown outward: an unparseable body
        // is a server that is not this one, and the caller's `Unusable` says so more usefully than
        // a serialization exception would.
        null
    }

    /**
     * A short reason for the UI.
     *
     * The server's own `message` is preferred when there is one — it is written for a person — and
     * the status code is the fallback. Nothing from the body is trusted for anything but display,
     * and it is bounded by [MAX_RESPONSE_BYTES] before it gets here.
     */
    private fun describe(response: Response): String =
        field(response.body, "message")?.contentOrNull?.take(MAX_MESSAGE_CHARS)
            ?: "the server answered ${response.status}"

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 10_000

        /** Not in `HttpURLConnection`, which predates RFC 6585. */
        const val HTTP_TOO_MANY_REQUESTS = 429

        /** Comfortably above a legal response, which is one base64url blob plus braces. */
        const val MAX_RESPONSE_BYTES = 64 * 1024

        const val MAX_MESSAGE_CHARS = 200

        val JSON = Json { ignoreUnknownKeys = true }
    }
}
