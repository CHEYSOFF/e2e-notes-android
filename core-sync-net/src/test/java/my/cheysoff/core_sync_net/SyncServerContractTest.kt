package my.cheysoff.core_sync_net

import kotlinx.coroutines.runBlocking
import my.cheysoff.core_sync_net.http.OkHttpTransport
import my.cheysoff.core_sync_net.http.ServerEndpoint
import my.cheysoff.core_sync_net.wire.Base64Codec
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * The client, driven against the **real** sync server from `server/`, over real HTTP.
 *
 * ## Why this test is the one that matters
 *
 * Every other test in this module is written against a fake transport, and a fake transport can
 * only ever agree with whatever this client believes. If the client and the server disagree about
 * the byte layout of a signed message, the SEC1 point encoding, the base64url variant or a JSON
 * field name, both suites stay green and the first user to press sync gets `401 bad_signature` with
 * no explanation. This project has already shipped exactly that failure once, with two HKDF
 * implementations that each passed their own tests.
 *
 * So this starts the server -- the actual `manana-sync-server` build, on a real port, with a real
 * SQLite store -- and drives a full lifecycle through it: claim, session, push, pull, conflict,
 * history, vouch for a second device, revoke it.
 *
 * ## How to run it
 *
 * ```
 * ./gradlew :core-sync-net:testDebugUnitTest -PsyncContract
 * ```
 *
 * Without `-PsyncContract` every test here is **skipped**, and a plain `./gradlew test` therefore
 * does not need a JDK 17 toolchain, a second Gradle build or a free TCP port. That is the trade:
 * the contract test is opt-in so that the everyday suite stays hermetic, and the opt-in is one flag
 * so that nobody has to reconstruct how to use it.
 *
 * On the first run it builds the server itself (`server/gradlew installDist`), which takes a couple
 * of minutes. After that it reuses `server/build/install/manana-sync-server`. To pre-build:
 *
 * ```
 * cd server && ./gradlew installDist
 * ```
 *
 * ## Why it launches `java` rather than `gradlew run`
 *
 * `gradlew run` starts a Gradle daemon which starts the server as a *child* process. Killing the
 * Gradle process at the end of the test leaves the server running and the port held, which turns
 * one flaky run into every subsequent run failing. Launching the JVM directly from the install
 * image means the [Process] this test holds **is** the server, so `destroy()` ends it.
 *
 * ## Test order
 *
 * The methods run in name order and share one server and one account, because the protocol is
 * sequential: there is no way to test a conflict without first having pushed, and no way to push
 * without having enrolled. Each name is prefixed with its step number so the order is visible
 * rather than incidental.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SyncServerContractTest {

    // ------------------------------------------------------------------------------------------
    // 1. Enrolment
    // ------------------------------------------------------------------------------------------

    @Test
    fun `step 01 - a claim over real HTTP enrols this device`() = runBlocking {
        val health = clientA.health()
        assertEquals("ok", health.status)

        val outcome = clientA.claimAccount(accountId, "Contract test device A")
        val claimed = outcome as? ClaimOutcome.Claimed
            ?: error("the first claim on a fresh account must succeed, got $outcome")
        deviceIdA = claimed.deviceId
        assertTrue(deviceIdA.isNotEmpty())
    }

    /**
     * The TOFU race, as a normal branch. Two devices paired moments apart both try to claim; the
     * loser must not treat `409 account_exists` as a failure. Plan §10, decision D2.
     */
    @Test
    fun `step 02 - a second claim on the same account is AlreadyClaimed, not an error`() =
        runBlocking {
            assertTrue(clientA.claimAccount(accountId, "second try") is ClaimOutcome.AlreadyClaimed)
        }

    /**
     * The session handshake end to end. Nothing in this test signs a challenge explicitly -- the
     * client does it, with [my.cheysoff.core_sync_net.auth.SignedMessage]'s bytes, and the server
     * verifies with its own independent implementation of the same specification. A single wrong
     * byte in either makes this fail.
     */
    @Test
    fun `step 03 - the session handshake yields a token the server accepts`() = runBlocking {
        val devices = clientA.listDevices(credentialsA())

        assertEquals(1, devices.size)
        assertEquals(deviceIdA, devices.single().deviceId)
        assertTrue("the calling device must be marked self", devices.single().isSelf)
        assertFalse(devices.single().isRevoked)
        assertArrayEquals(
            "the server must hold exactly the key this device signs with",
            signerA.publicKeySec1(),
            devices.single().publicKey,
        )
    }

    // ------------------------------------------------------------------------------------------
    // 2. Push and pull
    // ------------------------------------------------------------------------------------------

    @Test
    fun `step 04 - a batch push is accepted and returns a sequence number per item`() = runBlocking {
        val outcome = clientA.pushRecords(
            credentialsA(),
            listOf(
                PushItem(RECORD_ONE, "note", "1000-0-nodeA", baseSeq = 0, envelope = ENVELOPE_ONE),
                PushItem(RECORD_TWO, "folder", "1001-0-nodeA", baseSeq = 0, envelope = ENVELOPE_TWO),
            ),
        )

        assertFalse("nothing existed yet, so nothing can conflict", outcome.hasConflicts)
        val seqs = outcome.results.map { (it as PushResult.Accepted).seq }
        assertEquals(2, seqs.size)
        assertTrue("sequence numbers are allocated in order", seqs[0] < seqs[1])
        seqOne = seqs[0]
        seqTwo = seqs[1]
        assertEquals(seqTwo, outcome.accountSeq)
    }

    /**
     * The envelope is opaque, and this proves it in the only way that counts: the bytes that come
     * back are the bytes that went out. [ENVELOPE_ONE] is deliberately not valid ciphertext and
     * deliberately contains bytes that a careless text round trip would mangle.
     */
    @Test
    fun `step 05 - a pull returns the pushed envelopes byte-for-byte, in seq order`() = runBlocking {
        val page = clientA.changesSince(credentialsA(), Cursor.START)

        assertEquals(2, page.records.size)
        assertEquals(RECORD_ONE, page.records[0].blindedId)
        assertEquals(RECORD_TWO, page.records[1].blindedId)
        assertArrayEquals(ENVELOPE_ONE, page.records[0].envelope)
        assertArrayEquals(ENVELOPE_TWO, page.records[1].envelope)
        assertEquals("note", page.records[0].recType)
        assertEquals("1000-0-nodeA", page.records[0].hlc)
        assertEquals(seqTwo, page.nextCursor.seq)
        assertFalse(page.hasMore)
    }

    /**
     * The cursor is the server's `seq` and nothing else. Pulling from the cursor the previous page
     * ended at must return nothing -- which is only true if the number came from `seq`. A cursor
     * taken from `receivedAt` would be a millisecond timestamp far larger than any sequence number,
     * and the server would answer `409 cursor_ahead_of_server` instead.
     */
    @Test
    fun `step 06 - pulling from the returned cursor yields an empty page`() = runBlocking {
        val page = clientA.changesSince(credentialsA(), Cursor.ofSeq(seqTwo))

        assertTrue(page.records.isEmpty())
        assertEquals(seqTwo, page.nextCursor.seq)
    }

    @Test
    fun `step 07 - a limit smaller than the account pages the pull`() = runBlocking {
        val first = clientA.changesSince(credentialsA(), Cursor.START, limit = 1)

        assertEquals(1, first.records.size)
        assertTrue("a full page means there is more", first.hasMore)
        assertEquals(seqOne, first.nextCursor.seq)

        val second = clientA.changesSince(credentialsA(), first.nextCursor, limit = 1)
        assertEquals(listOf(RECORD_TWO), second.records.map { it.blindedId })
    }

    // ------------------------------------------------------------------------------------------
    // 3. Conflict
    // ------------------------------------------------------------------------------------------

    /**
     * The `409` the merge engine is built on. One item's `baseSeq` is stale and must come back as a
     * conflict carrying the blocking version inline; the other item's is correct and **must still
     * be applied**, because records are independent and refusing the whole batch would resend work
     * that was never in conflict.
     */
    @Test
    fun `step 08 - a stale baseSeq conflicts inline while the rest of the batch applies`() =
        runBlocking {
            val outcome = clientA.pushRecords(
                credentialsA(),
                listOf(
                    // Correct base: this record is still at seqOne.
                    PushItem(RECORD_ONE, "note", "2000-0-nodeA", baseSeq = seqOne, envelope = ENVELOPE_THREE),
                    // Stale base: this record moved to seqTwo, and 0 asserts it does not exist.
                    PushItem(RECORD_TWO, "folder", "2001-0-nodeA", baseSeq = 0, envelope = ENVELOPE_TWO),
                ),
            )

            assertTrue(outcome.hasConflicts)

            val applied = outcome.results[0] as PushResult.Accepted
            assertTrue("the non-conflicting item must be applied", applied.seq > seqTwo)
            seqOne = applied.seq

            val conflict = outcome.results[1] as PushResult.Conflict
            val blocking = conflict.current
                ?: error("the conflicting version must arrive inline, with no second round trip")
            assertEquals(seqTwo, blocking.seq)
            assertArrayEquals(
                "the inline version is the one already on the server",
                ENVELOPE_TWO,
                blocking.envelope,
            )
        }

    @Test
    fun `step 09 - history returns the superseded version of a record`() = runBlocking {
        val versions = clientA.history(credentialsA(), RECORD_ONE)

        assertEquals("both versions of this record are retained", 2, versions.size)
        assertEquals("newest first", seqOne, versions[0].seq)
        assertArrayEquals(ENVELOPE_THREE, versions[0].envelope)
        assertArrayEquals(ENVELOPE_ONE, versions[1].envelope)
    }

    /** F7. The server refuses a cursor beyond its high-water mark rather than answering "empty". */
    @Test
    fun `step 10 - a cursor ahead of the server is refused rather than answered`() = runBlocking {
        try {
            clientA.changesSince(credentialsA(), Cursor.ofSeq(seqOne + 10_000))
            fail("expected CursorAheadOfServer")
        } catch (e: SyncException.CursorAheadOfServer) {
            assertEquals(seqOne + 10_000, e.requested)
        }
    }

    // ------------------------------------------------------------------------------------------
    // 4. A second device, and revocation
    // ------------------------------------------------------------------------------------------

    /**
     * Vouched enrolment: device A signs `("authorize", accountId, B's key, ts)` with the key the
     * server stored for it at claim time. This is the call that proves the whole identity chain --
     * the SEC1 encoding of B's key, A's DER signature, and the canonical message the server rebuilds
     * from the strings in the body.
     */
    @Test
    fun `step 11 - an enrolled device can vouch for a second device`() = runBlocking {
        val enrolled = clientA.authorizeDevice(
            accountId = accountId,
            voucherDeviceId = deviceIdA,
            newPublicKey = signerB.publicKeySec1(),
            deviceLabel = "Contract test device B",
        )
        deviceIdB = enrolled.deviceId

        val devices = clientA.listDevices(credentialsA())
        assertEquals(2, devices.size)
        assertArrayEquals(
            signerB.publicKeySec1(),
            devices.single { it.deviceId == deviceIdB }.publicKey,
        )
    }

    @Test
    fun `step 12 - the second device opens its own session and sees the same records`() =
        runBlocking {
            val page = clientB.changesSince(credentialsB(), Cursor.START)

            assertEquals(setOf(RECORD_ONE, RECORD_TWO), page.records.map { it.blindedId }.toSet())
            assertTrue(
                "device B must be the self entry in its own listing",
                clientB.listDevices(credentialsB()).single { it.isSelf }.deviceId == deviceIdB,
            )
        }

    /**
     * Revocation kills the device's live sessions in the same transaction, so B's cached token stops
     * working at once. The client then discards it, re-handshakes -- and the challenge endpoint
     * refuses a revoked device, which is what turns into [SyncException.DeviceRevoked] here.
     */
    @Test
    fun `step 13 - a revoked device cannot use its cached token or obtain a new one`() =
        runBlocking {
            clientA.revokeDevice(credentialsA(), deviceIdB)

            try {
                clientB.listDevices(credentialsB())
                fail("a revoked device must not be able to read")
            } catch (e: SyncException) {
                assertTrue("expected a revocation, got $e", e is SyncException.DeviceRevoked)
            }

            val listed = clientA.listDevices(credentialsA())
            assertTrue(listed.single { it.deviceId == deviceIdB }.isRevoked)
        }

    // ------------------------------------------------------------------------------------------
    // Fixture
    // ------------------------------------------------------------------------------------------

    private fun credentialsA() = DeviceCredentials(accountId, deviceIdA)
    private fun credentialsB() = DeviceCredentials(accountId, deviceIdB)

    companion object {

        /**
         * A 16-byte account ID, which is the shape `HKDF(ARK, "manana/sync/v1/account")` produces.
         *
         * Randomised per run so that re-running against a server whose store survived (a `.db` file
         * rather than `:memory:`) still starts from an unclaimed account.
         */
        private val accountId = Base64Codec.encodeUrl(
            ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        )

        private val signerA = TestDeviceSigner()
        private val signerB = TestDeviceSigner()

        private lateinit var clientA: SyncApi
        private lateinit var clientB: SyncApi

        private lateinit var deviceIdA: String
        private lateinit var deviceIdB: String
        private var seqOne: Long = 0
        private var seqTwo: Long = 0

        private const val RECORD_ONE = "contract-record-one"
        private const val RECORD_TWO = "contract-record-two"

        /**
         * Deliberately **not** valid ciphertext.
         *
         * The server does not parse envelopes and must not start; storing a byte string that could
         * never be a seal is how "the server does not parse envelopes" is asserted from this side.
         * The bytes include `0x00`, `0xFF` and a run that is invalid UTF-8, so any accidental text
         * round trip anywhere between here and the SQLite blob shows up as a mismatch.
         */
        private val ENVELOPE_ONE = byteArrayOf(0, -1, -128, 127, 1, 2, 3, -2, -3)
        private val ENVELOPE_TWO = ByteArray(300) { (it * 31).toByte() }
        private val ENVELOPE_THREE = byteArrayOf(-1, -1, -1, 0, 0, 0, 42)

        private var server: Process? = null
        private val serverOutput = StringBuilder()

        @BeforeClass
        @JvmStatic
        fun startServer() {
            assumeTrue(
                "The sync-server contract test is opt-in. Run it with: " +
                    "./gradlew :core-sync-net:testDebugUnitTest -PsyncContract",
                System.getProperty("manana.sync.contract") == "true",
            )

            val repositoryRoot = File(
                requireNotNull(System.getProperty("manana.repo.root")) {
                    "manana.repo.root is not set; see core-sync-net/build.gradle.kts"
                }
            )
            val serverDir = File(repositoryRoot, "server")
            check(serverDir.isDirectory) { "the standalone server build is missing: $serverDir" }

            val libraries = installServer(serverDir)
            val port = freePort()
            server = launchServer(libraries, port)
            val endpoint = ServerEndpoint("http://127.0.0.1:$port")
            clientA = SyncHttpClient(
                endpoint = endpoint,
                transport = OkHttpTransport.create(endpoint),
                signer = signerA,
            )
            clientB = SyncHttpClient(
                endpoint = endpoint,
                transport = OkHttpTransport.create(endpoint),
                signer = signerB,
            )
            awaitHealthy(clientA)
        }

        @AfterClass
        @JvmStatic
        fun stopServer() {
            val process = server ?: return
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
            server = null
        }

        /**
         * Builds the standalone server if its install image is not already there, and returns the
         * directory holding its jars.
         *
         * `installDist` rather than `run`: it produces a plain directory of jars this test can put
         * on a classpath, with no Gradle process left holding the server as a child.
         */
        private fun installServer(serverDir: File): File {
            val libraries = File(serverDir, "build/install/manana-sync-server/lib")
            if (libraries.isDirectory && libraries.listFiles { f -> f.extension == "jar" }?.isNotEmpty() == true) {
                return libraries
            }
            val wrapper = if (isWindows()) "gradlew.bat" else "./gradlew"
            val build = ProcessBuilder(wrapper, "installDist", "--console=plain")
                .directory(serverDir)
                .redirectErrorStream(true)
                .start()
            val log = build.inputStream.bufferedReader().readText()
            check(build.waitFor(15, TimeUnit.MINUTES)) { "building the sync server timed out" }
            check(build.exitValue() == 0) { "building the sync server failed:\n$log" }
            check(libraries.isDirectory) { "installDist produced no lib directory at $libraries" }
            return libraries
        }

        private fun launchServer(libraries: File, port: Int): Process {
            val java = File(File(System.getProperty("java.home"), "bin"), if (isWindows()) "java.exe" else "java")
            val process = ProcessBuilder(
                java.absolutePath,
                "-cp",
                File(libraries, "*").path,
                "manana.sync.server.MainKt",
            ).apply {
                environment()["MANANA_HOST"] = "127.0.0.1"
                environment()["MANANA_PORT"] = port.toString()
                // In memory, so a run leaves nothing behind and a re-run starts clean.
                environment()["MANANA_DB"] = ":memory:"
                redirectErrorStream(true)
            }.start()

            // Drained on a daemon thread. A server whose stdout nobody reads eventually blocks on a
            // full pipe buffer, and the failure looks like a hung test rather than a full pipe.
            Thread {
                process.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(serverOutput) { serverOutput.appendLine(line) }
                }
            }.apply { isDaemon = true }.start()

            return process
        }

        /** Polls `GET /healthz` until the server answers, or fails with whatever it printed. */
        private fun awaitHealthy(client: SyncApi) {
            val deadline = System.currentTimeMillis() + 60_000
            var lastFailure: Exception? = null
            while (System.currentTimeMillis() < deadline) {
                if (server?.isAlive != true) break
                try {
                    runBlocking { client.health() }
                    return
                } catch (e: SyncException) {
                    lastFailure = e
                    Thread.sleep(200)
                }
            }
            val printed = synchronized(serverOutput) { serverOutput.toString() }
            throw IllegalStateException(
                "the sync server did not become healthy. Its output was:\n$printed",
                lastFailure,
            )
        }

        /**
         * A port the OS says is free.
         *
         * There is a race between closing this socket and the server binding it. It is accepted:
         * the alternative is a fixed port, which collides with a previous run that has not finished
         * dying, and that failure is far more common than losing this race.
         */
        private fun freePort(): Int = ServerSocket(0).use { it.localPort }

        private fun isWindows(): Boolean =
            System.getProperty("os.name").orEmpty().lowercase().contains("win")
    }
}
