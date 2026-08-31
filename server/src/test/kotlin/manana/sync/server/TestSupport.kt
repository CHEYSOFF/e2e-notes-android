package manana.sync.server

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Collections

/** A clock a test can move. Nothing in the server calls `System.currentTimeMillis()` directly. */
class MutableClock(var now: Long = 1_770_000_000_000L) : Clock {
    override fun nowMillis(): Long = now
}

/**
 * A test stand-in for a paired Android device: one P-256 key pair, the same SEC1 uncompressed
 * encoding `feature-pairing/.../P256.encodePublicKey` produces, and the same `SHA256withECDSA`
 * signatures `DeviceIdentityKey.sign` produces.
 *
 * That correspondence is the point. These tests exercise the server against the byte formats the
 * real client will emit; if the encoding here and the encoding there ever disagree, the disagreement
 * is a protocol bug and this class is where it should be visible.
 */
class TestDevice(name: String = "test-device") {
    val keyPair: KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    val publicKeyB64: String = B64.encode(sec1Encode(keyPair.public as ECPublicKey))

    /**
     * Stands in for `core-crypto/.../sync/DeviceLabelCipher` output: a fixed-length blob that
     * differs per device name and contains none of it. The server module cannot depend on the
     * Android module, so this is a stand-in rather than the real cipher -- but it has the two
     * properties the server is entitled to assume, which are that the blob is opaque and that its
     * length says nothing about the name.
     */
    val sealedLabel: String = B64.encode(
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(name.toByteArray())
            .let { digest -> ByteArray(157) { digest[it % digest.size] } }
    )

    fun sign(message: ByteArray): String {
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(message)
            sign()
        }
        return B64.encode(signature)
    }

    companion object {
        fun sec1Encode(key: ECPublicKey): ByteArray {
            val out = ByteArray(65)
            out[0] = 0x04
            fun pad(value: java.math.BigInteger, offset: Int) {
                val bytes = value.toByteArray()
                val start = if (bytes.size > 32) bytes.size - 32 else 0
                val length = bytes.size - start
                System.arraycopy(bytes, start, out, offset + 32 - length, length)
            }
            pad(key.w.affineX, 1)
            pad(key.w.affineY, 33)
            return out
        }
    }
}

/** A test's view of one running server: its dependencies, plus the log lines it emitted. */
class Harness(config: ServerConfig) {
    val clock = MutableClock()
    val logLines: MutableList<String> = Collections.synchronizedList(ArrayList())
    val config: ServerConfig = config
    val store: SyncStore = SyncStore.open(config.databasePath, clock, config.historyDepth)
    val deps = ServerDeps(
        store = store,
        config = config,
        clock = clock,
        log = RequestLog(sink = { logLines.add(it) }, debugEnabled = false),
        rateLimiter = RateLimiter(config.rateLimitPerMinute, config.rateLimitBurst, clock),
    )
}

/**
 * The default test configuration: an in-memory database and a rate limit high enough that it never
 * fires by accident. `RateLimitTest` supplies its own tiny budget instead of relying on this one.
 */
fun testConfig(
    databasePath: String = ":memory:",
    maxRequestBytes: Int = 4 * 1024 * 1024,
    maxEnvelopeBytes: Int = 256 * 1024,
    maxBatchItems: Int = 64,
    historyDepth: Int = 10,
    rateLimitPerMinute: Int = 100_000,
    rateLimitBurst: Int = 100_000,
    signatureWindowMillis: Long = 5 * 60 * 1000,
    sessionTtlMillis: Long = 24 * 60 * 60 * 1000,
) = ServerConfig(
    databasePath = databasePath,
    maxRequestBytes = maxRequestBytes,
    maxEnvelopeBytes = maxEnvelopeBytes,
    maxBatchItems = maxBatchItems,
    historyDepth = historyDepth,
    rateLimitPerMinute = rateLimitPerMinute,
    rateLimitBurst = rateLimitBurst,
    signatureWindowMillis = signatureWindowMillis,
    sessionTtlMillis = sessionTtlMillis,
)

/** Boots a server for one test. */
fun serverTest(
    config: ServerConfig = testConfig(),
    block: suspend ApplicationTestBuilder.(Harness) -> Unit,
) = testApplication {
    val harness = Harness(config)
    application { syncModule(harness.deps) }
    try {
        block(harness)
    } finally {
        harness.store.close()
    }
}

val JSON_LENIENT = Json { ignoreUnknownKeys = true }

/** A fresh, well-formed `accountId`: 16 random bytes, base64url -- the shape HKDF produces. */
fun randomAccountId(): String {
    val bytes = ByteArray(16)
    SecureRandom().nextBytes(bytes)
    return B64.encode(bytes)
}

// -------------------------------------------------------------------------------------------
// HTTP helpers
// -------------------------------------------------------------------------------------------

suspend fun HttpClient.postJson(path: String, body: String, token: String? = null): HttpResponse =
    post(path) {
        contentType(ContentType.Application.Json)
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        setBody(body)
    }

suspend fun HttpClient.getAuth(path: String, token: String?): HttpResponse =
    get(path) { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }

suspend fun HttpClient.deleteAuth(path: String, token: String?): HttpResponse =
    delete(path) { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }

suspend fun HttpResponse.json(): JsonObject =
    JSON_LENIENT.decodeFromString(JsonObject.serializer(), bodyAsText())

suspend inline fun <reified T> HttpResponse.decode(): T =
    JSON_LENIENT.decodeFromString(bodyAsText())

/** The machine-readable `error` code of a failure response. */
suspend fun HttpResponse.errorCode(): String = decode<ErrorBody>().error

// -------------------------------------------------------------------------------------------
// Protocol helpers -- the client half of the flows, written the way a client must write them
// -------------------------------------------------------------------------------------------

/** Claims an account with [device] as its first, vouching device. */
suspend fun ApplicationTestBuilder.claimAccount(
    harness: Harness,
    accountId: String,
    device: TestDevice,
): ClaimResponse {
    val ts = harness.clock.now
    val signature = device.sign(SignedMessage.claim(accountId, device.publicKeyB64, ts))
    val body = JSON_LENIENT.encodeToString(
        ClaimRequest(accountId, device.publicKeyB64, device.sealedLabel, ts, signature)
    )
    val response = client.postJson("/v1/account", body)
    check(response.status.value == 201) { "claim failed: ${response.status} ${response.bodyAsText()}" }
    return response.decode()
}

/** Vouches for [joining] using [voucher], returning the new device's ID. */
suspend fun ApplicationTestBuilder.authorizeDevice(
    harness: Harness,
    accountId: String,
    voucherDeviceId: String,
    voucher: TestDevice,
    joining: TestDevice,
): HttpResponse {
    val ts = harness.clock.now
    val signature = voucher.sign(SignedMessage.authorize(accountId, joining.publicKeyB64, ts))
    val body = JSON_LENIENT.encodeToString(
        AuthorizeRequest(
            accountId, joining.publicKeyB64, joining.sealedLabel, ts, voucherDeviceId, signature,
        )
    )
    return client.postJson("/v1/devices/authorize", body)
}

/** The full challenge/response handshake. Returns the bearer token. */
suspend fun ApplicationTestBuilder.openSession(
    accountId: String,
    deviceId: String,
    device: TestDevice,
): String {
    val challengeResponse = client.postJson(
        "/v1/session/challenge",
        JSON_LENIENT.encodeToString(ChallengeRequest(accountId, deviceId)),
    )
    check(challengeResponse.status.value == 200) {
        "challenge failed: ${challengeResponse.status} ${challengeResponse.bodyAsText()}"
    }
    val challenge: ChallengeResponse = challengeResponse.decode()
    val signature = device.sign(SignedMessage.session(accountId, deviceId, challenge.challenge))
    val sessionResponse = client.postJson(
        "/v1/session",
        JSON_LENIENT.encodeToString(
            SessionRequest(accountId, deviceId, challenge.challenge, signature)
        ),
    )
    check(sessionResponse.status.value == 200) {
        "session failed: ${sessionResponse.status} ${sessionResponse.bodyAsText()}"
    }
    return sessionResponse.decode<SessionResponse>().token
}

/** Claim an account and open a session on it in one step -- the setup most tests need. */
class Enrolled(
    val accountId: String,
    val deviceId: String,
    val device: TestDevice,
    val token: String,
)

suspend fun ApplicationTestBuilder.enrol(harness: Harness): Enrolled {
    val accountId = randomAccountId()
    val device = TestDevice("first")
    val claim = claimAccount(harness, accountId, device)
    val token = openSession(accountId, claim.deviceId, device)
    return Enrolled(accountId, claim.deviceId, device, token)
}

/**
 * One `POST /v1/records` item, with an envelope built from arbitrary bytes.
 *
 * Three fields, because three is all the wire has: the record's type and its clock live inside the
 * envelope now. See `MetadataTest`.
 */
fun upsertItem(
    blindedId: String,
    envelope: ByteArray,
    baseSeq: Long,
) = UpsertRequestItem(blindedId, baseSeq, B64.encode(envelope))

suspend fun ApplicationTestBuilder.push(
    token: String,
    vararg items: UpsertRequestItem,
): HttpResponse = client.postJson(
    "/v1/records",
    JSON_LENIENT.encodeToString(UpsertRequest(items.toList())),
    token,
)

/** A blinded-ID-shaped string: 22 base64url characters, as `BlindedRecordId` emits. */
fun blindedId(seed: Int): String {
    val bytes = ByteArray(16)
    java.nio.ByteBuffer.wrap(bytes).putInt(seed)
    return B64.encode(bytes)
}
