package my.cheysoff.core_sync_net

import my.cheysoff.core_crypto.sync.DeviceLabelCipher
import my.cheysoff.core_sync_net.auth.DeviceLabelSealer
import my.cheysoff.core_sync_net.auth.DeviceSigner
import my.cheysoff.core_sync_net.http.Delayer
import my.cheysoff.core_sync_net.http.HttpRequest
import my.cheysoff.core_sync_net.http.HttpResponse
import my.cheysoff.core_sync_net.http.HttpTransport
import my.cheysoff.core_sync_net.http.Jitter
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * A scripted [HttpTransport].
 *
 * The point of the seam. Everything the client decides -- what to sign, when to re-handshake, how
 * long to wait after a `429`, whether a `409` is data or a failure -- is decided above this, so all
 * of it is reachable from a test that never opens a socket and never starts a server.
 *
 * Responses are queued and served in order. A request that arrives with the queue empty is a test
 * bug rather than a network condition, so it fails loudly instead of returning a default: a client
 * that made one more request than the test expected is exactly the kind of thing this fake exists
 * to catch.
 */
class FakeHttpTransport : HttpTransport {

    /** Every request the client made, in order. Assert against this, not against a mock. */
    val requests = mutableListOf<HttpRequest>()

    private val queued = ArrayDeque<HttpResponse>()

    fun enqueue(
        status: Int,
        body: String = "",
        headers: Map<String, String> = emptyMap(),
    ) {
        queued.addLast(HttpResponse(status, headers, body.toByteArray(Charsets.UTF_8)))
    }

    fun enqueue(status: Int, body: ByteArray, headers: Map<String, String> = emptyMap()) {
        queued.addLast(HttpResponse(status, headers, body))
    }

    /** The bodies of every request, as text. */
    fun bodies(): List<String> = requests.map { it.body?.decodeToString().orEmpty() }

    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests += request
        check(queued.isNotEmpty()) {
            "the client made an unexpected request: ${request.method} ${request.url}"
        }
        return queued.removeFirst()
    }
}

/**
 * A [Delayer] that records what it was asked to wait for and returns immediately.
 *
 * The interesting property of the back-off is *how long the client decided to wait*, not that the
 * thread actually slept. Sleeping for real would make the `429` tests the slowest in the suite and
 * would test the JVM's scheduler rather than this module's policy.
 */
class RecordingDelayer : Delayer {
    val waits = mutableListOf<Long>()
    override suspend fun delay(millis: Long) {
        waits += millis
    }
}

/** Jitter that always adds the same amount, so a back-off test can assert an exact total. */
class FixedJitter(private val extra: Long) : Jitter {
    override fun extraMillis(baseMillis: Long): Long = extra
}

/**
 * A real P-256 signer, on a plain JVM key pair.
 *
 * Not a stub that returns constant bytes: the signature algorithm, the DER encoding and the SEC1
 * public-key encoding are all things the server checks, so a fake that skipped them would make the
 * tests agree with a client that cannot enrol. This is the same `SHA256withECDSA` over the same
 * curve that `DeviceIdentityKey` performs inside the AndroidKeyStore -- the only difference is
 * where the private key lives, which is exactly what the [DeviceSigner] seam abstracts and the only
 * part of it a JVM test cannot exercise.
 */
class TestDeviceSigner(private val keyPair: KeyPair = generateP256()) : DeviceSigner {

    /** How many times a signature was requested. Used to prove a re-handshake actually re-signed. */
    var signCount: Int = 0
        private set

    override fun publicKeySec1(): ByteArray = encodeSec1(keyPair.public as ECPublicKey)

    override fun sign(message: ByteArray): ByteArray {
        signCount++
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(message)
            sign()
        }
    }

    companion object {

        fun generateP256(): KeyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }

        /**
         * SEC1 uncompressed: `0x04 ‖ X(32) ‖ Y(32)`.
         *
         * The fixed 32-byte fields are the whole subtlety. `BigInteger.toByteArray()` emits a
         * leading zero byte whenever the high bit is set and emits fewer than 32 bytes whenever the
         * coordinate is small, so roughly one key in two is the wrong length if the result is used
         * directly -- and the server answers that with `invalid_public_key` for a key that is
         * perfectly valid.
         */
        fun encodeSec1(key: ECPublicKey): ByteArray {
            val out = ByteArray(65)
            out[0] = 0x04
            writeFixed(key.w.affineX, out, 1)
            writeFixed(key.w.affineY, out, 33)
            return out
        }

        private fun writeFixed(value: BigInteger, out: ByteArray, offset: Int) {
            val bytes = value.toByteArray()
            // Drop a sign byte if there is one, and left-pad if the coordinate is short.
            val start = if (bytes.size > 32) bytes.size - 32 else 0
            val length = bytes.size - start
            require(length <= 32) { "coordinate does not fit in 32 bytes" }
            System.arraycopy(bytes, start, out, offset + (32 - length), length)
        }
    }
}

/**
 * A [DeviceLabelSealer] over the real [DeviceLabelCipher], under a fixed test ARK.
 *
 * Deliberately the production cipher rather than a stub that returns the label back. What has to be
 * proved is that what crosses the wire is base64url of a 157-byte AES-GCM seal, that the server
 * accepts it as `sealedLabel` and stores it byte for byte, and that it opens again against the
 * public-key string the server re-encoded — none of which a stub would exercise, and all of which
 * `SyncServerContractTest` checks against the real server.
 *
 * The production implementation is `:app`'s `ArkDeviceLabelSealer`, which is this plus fetching the
 * ARK from `SecureUnlockManager` and zeroing it afterwards. That fetch is the only part that needs
 * Android, and it is the only part not covered here.
 */
class ArkLabelSealer(private val ark: ByteArray) : DeviceLabelSealer {

    override fun seal(devicePublicKeyB64: String, label: String): ByteArray =
        DeviceLabelCipher.seal(ark, devicePublicKeyB64, label)

    override fun open(devicePublicKeyB64: String, sealed: ByteArray): String? =
        DeviceLabelCipher.open(ark, devicePublicKeyB64, sealed)
}
