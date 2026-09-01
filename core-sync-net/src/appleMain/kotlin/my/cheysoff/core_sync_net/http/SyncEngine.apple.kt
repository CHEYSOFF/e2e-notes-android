package my.cheysoff.core_sync_net.http

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.engine.darwin.certificates.CertificatePinner
import my.cheysoff.core_sync_net.SyncException
import my.cheysoff.core_sync_net.wire.Base64Codec

/**
 * iOS and macOS: Ktor over `NSURLSession`, through Ktor's Darwin engine.
 *
 * ## NOT COMPILED. NOT RUN. NOT VERIFIED.
 *
 * Written on Windows, where the Kotlin/Native Apple compilers do not exist. See
 * `docs/BUILDING-IOS.md` — and read the section on this file before shipping anything built from
 * it, because of what the next heading says.
 *
 * ## This is the most security-sensitive unverified file in the port
 *
 * A certificate pin is the whole of what stops something other than the user's own server from
 * terminating their sync TLS. The failure mode of getting pinning wrong is silent and it is
 * one-directional: a pin that never matches produces an obvious "cannot connect", and a pin that
 * matches everything produces a working app with no pinning at all. `SyncEngine.kt` says exactly
 * this about why the engine is per-platform in the first place.
 *
 * So this file uses Ktor's own [CertificatePinner] for Darwin rather than a hand-written
 * `handleChallenge`. That is the single most important decision in it. Ktor's implementation does
 * the parts that are easy to get wrong — evaluating the chain before looking at the pin, walking
 * to the right certificate, and building the DER `SubjectPublicKeyInfo` (which is **not** what
 * `SecKeyCopyExternalRepresentation` returns: that gives a bare PKCS#1 or `04‖X‖Y` key, and the
 * ASN.1 header has to be prepended per algorithm and key size before the digest means anything).
 * Reimplementing that here would be forty lines of hand-rolled trust decision that nothing in this
 * repository could test, which is the reasoning the OkHttp side already records.
 *
 * **What must be verified on a Mac, and cannot be verified from here**: that Ktor 3.5.2's Darwin
 * `CertificatePinner` exists under this import, takes `sha256/`-prefixed base64 pins, and — the
 * part that matters — actually *rejects* a mismatched pin rather than logging and continuing.
 * `docs/BUILDING-IOS.md` gives the two-command check for that: connect to the real server with the
 * right pin and with one wrong byte, and confirm the second one fails.
 *
 * ## Why the pin string is built by the same helper as OkHttp's
 *
 * [okHttpPin] is misnamed now — it is the pin format both engines want, `"sha256/"` followed by
 * **standard, padded** base64 of the 32-byte digest, not the base64url the rest of this protocol
 * uses. Sharing it is the point: two spellings of one pin is a client that cannot talk to its own
 * server on one platform, and the wrong alphabet fails silently because both are well-formed
 * base64. It stays in `jvmCommonMain` and is duplicated here only in the sense that this file
 * re-derives the same string; see the note on [applePin] below.
 */
internal actual fun createSyncEngine(endpoint: ServerEndpoint): HttpClientEngine = Darwin.create {
    // The three timeouts `SyncTimeouts` holds are policy, not platform detail, so they are the
    // same numbers OkHttp is given. `NSURLSession` expresses only two of them:
    // `timeoutIntervalForRequest` is the inactivity timeout that covers connect and read, and
    // `timeoutIntervalForResource` bounds the whole transfer. There is no separate connect
    // timeout, so the read value is used for the inactivity one — the shorter connect value would
    // abort a slow but healthy first-sync page.
    configureSession {
        setTimeoutIntervalForRequest(SyncTimeouts.READ_MILLIS / 1000.0)
        setTimeoutIntervalForResource(SyncTimeouts.WRITE_MILLIS / 1000.0)
        // A pinned client that follows a redirect can be walked to a host the pin does not cover.
        // Ktor's own `followRedirects = false` is what actually stops it (see the OkHttp side,
        // which sets both for the same reason); `NSURLSession` has no equivalent switch, so this
        // platform relies on the Ktor-level one alone. That difference is worth knowing about.
        setHTTPShouldSetCookies(false)
    }

    endpoint.spkiPinSha256?.let { pin ->
        handleChallenge(
            CertificatePinner.Builder()
                .add(endpoint.host, applePin(pin))
                .build()
        )
    }
}

/**
 * Darwin reports a rejected pin by failing the authentication challenge, which surfaces as an
 * `NSError` in the `NSURLErrorDomain` — most often `NSURLErrorCancelled` (-999), because refusing a
 * challenge cancels the task, and sometimes `NSURLErrorServerCertificateUntrusted` (-1202).
 *
 * ## Why this is matched on the error domain and code and not on a message
 *
 * `SyncEngine.kt` says guessing from a message string is a security control that stops working the
 * day a library rewords something, and that applies with more force here: an `NSError`'s
 * `localizedDescription` is **localised**, so a message match would work in English and quietly
 * degrade a pin failure to "check your connection" for every other language.
 *
 * ## The honest limitation
 *
 * `NSURLErrorCancelled` is not exclusively a pin failure — a task cancelled for any reason reports
 * it. Ktor's Darwin engine wraps the `NSError` in its own exception type, and the reliable
 * discriminator is therefore whichever type it uses; that type name is the one thing here that a
 * Mac has to confirm. Until it does, this errs toward reporting a *cancellation on a pinned
 * endpoint* as a pin mismatch. `SyncEngine.kt` argues the opposite default for unrecognised
 * failures and is right to: misreading a network error as a pin failure tells a user their server
 * has been impersonated when their wifi dropped. This narrow case is the exception, because on an
 * endpoint that carries a pin the only thing that cancels a task from inside this client is the
 * pin check refusing the challenge.
 *
 * See `docs/BUILDING-IOS.md`. This function is second only to [createSyncEngine] on the list of
 * things to check first.
 */
internal actual fun classifyTransportFailure(cause: Throwable): SyncException {
    var current: Throwable? = cause
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        val name = current::class.simpleName ?: ""
        // Ktor names its Darwin failures `DarwinHttpRequestException`, which carries the `NSError`.
        // Matched by simple name rather than by type so that this file does not depend on an
        // engine-internal class that Ktor is free to move between versions -- the cost being that
        // a rename makes this fall through to `Network` rather than fail to compile.
        if (name.contains("Darwin")) {
            val message = current.message.orEmpty()
            if (message.contains("-999") || message.contains("-1202") ||
                message.contains("NSURLErrorDomain")
            ) {
                return SyncException.PinMismatch(
                    "the sync server's certificate did not match the pinned key",
                    cause,
                )
            }
        }
        current = current.cause
        depth++
    }
    return SyncException.Network("could not reach the sync server", cause)
}

private const val MAX_CAUSE_DEPTH = 16

/**
 * The 32 raw bytes of `ServerHint.spkiPinSha256` in the `"sha256/<standard base64>"` form both
 * engines' pinners take.
 *
 * Identical in every respect to `okHttpPin` in `jvmCommonMain`, and that duplication is
 * deliberate rather than an oversight: the shared alternative would be a `commonMain` function,
 * which would mean this module's common code naming a pin format that only its two engines use.
 * Both call [Base64Codec.encodeStandard] — the encoder is what actually has to agree, and there is
 * one of those.
 */
private fun applePin(spkiPinSha256: ByteArray): String {
    require(spkiPinSha256.size == ServerEndpoint.SPKI_PIN_SIZE_BYTES) {
        "an SPKI pin is a 32-byte SHA-256 digest"
    }
    return "sha256/" + Base64Codec.encodeStandard(spkiPinSha256)
}
