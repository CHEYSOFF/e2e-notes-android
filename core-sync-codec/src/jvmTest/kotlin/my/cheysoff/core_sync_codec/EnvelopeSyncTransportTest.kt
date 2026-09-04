package my.cheysoff.core_sync_codec

import kotlinx.coroutines.test.runTest
import my.cheysoff.core_crypto.sync.AccountKeys
import my.cheysoff.core_crypto.sync.AccountRootKey
import my.cheysoff.core_crypto.sync.RecordEnvelope
import my.cheysoff.core_domain.sync.FieldClocks
import my.cheysoff.core_domain.sync.FieldValue
import my.cheysoff.core_domain.sync.Hlc
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_domain.sync.SyncRecord
import my.cheysoff.core_sync_engine.IncomingRecord
import my.cheysoff.core_sync_engine.PushAck
import my.cheysoff.core_sync_engine.PushRequest
import my.cheysoff.core_sync_engine.RecordFault
import my.cheysoff.core_sync_engine.SyncTransportException
import my.cheysoff.core_sync_engine.TransportFault
import my.cheysoff.core_sync_net.ChangesPage
import my.cheysoff.core_sync_net.ClaimOutcome
import my.cheysoff.core_sync_net.Cursor
import my.cheysoff.core_sync_net.DeviceCredentials
import my.cheysoff.core_sync_net.EnrolledDevice
import my.cheysoff.core_sync_net.PushItem
import my.cheysoff.core_sync_net.PushOutcome
import my.cheysoff.core_sync_net.PushResult
import my.cheysoff.core_sync_net.RemoteDevice
import my.cheysoff.core_sync_net.RemoteRecord
import my.cheysoff.core_sync_net.ServerHealth
import my.cheysoff.core_sync_net.SyncApi
import my.cheysoff.core_sync_net.SyncException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seam between the sync engine and the network: real envelopes, a fake server.
 *
 * ## What is actually at risk here
 *
 * Two things, and both are silent when wrong.
 *
 * **A record that will not open must reach the engine as a fault, not as an absence.** The engine's
 * whole F1 response — count it, skip it, and *do not advance the cursor past it* — depends on the
 * faulted record being in the stream with its `seq`. A transport that dropped it instead would let
 * the cursor sail past, and the record would never be offered again: a note silently missing from
 * this device forever.
 *
 * **Every failure must map to a fault the engine recognises.** A fault it does not recognise must
 * never be mistaken for an empty page, because an empty page from a server that actually refused
 * the request reads as "the account has nothing", and that is one merge away from a mass delete.
 * `SyncHttpClient` also validates its arguments with `require`, so an `IllegalArgumentException`
 * can arrive here where a `SyncException` was expected; that has to be translated too or the
 * engine's promise that a pass never throws stops being true one layer up.
 */
class EnvelopeSyncTransportTest {

    private val keys: AccountKeys = AccountRootKey.derive(AccountRootKey.generateArk())
    private val codec = RecordCodec(keys)
    private val credentials = DeviceCredentials("acct", "device")

    private fun record(uuid: String = "n1", content: String = "milk") = SyncRecord(
        type = RecordType.NOTE,
        uuid = uuid,
        rowClock = Hlc(1_000, 0, "nodeA"),
        fieldClocks = emptyMap(),
        fields = mapOf(
            FieldClocks.TITLE to FieldValue.of("Groceries"),
            FieldClocks.CONTENT to FieldValue.of(content, "plain"),
            FieldClocks.CHECKLIST to FieldValue.of(""),
            FieldClocks.PINNED to FieldValue.of("0"),
            FieldClocks.FAVORITE to FieldValue.of("0"),
            FieldClocks.FOLDER to FieldValue.of(null),
            FieldClocks.UPDATED_AT to FieldValue.of("100"),
            FieldClocks.DELETED to FieldValue.of("0", null),
        ),
    )

    private fun transport(api: SyncApi) =
        EnvelopeSyncTransport(api, credentials, codec, createdAtOf = { _, _ -> 50L })

    private fun sealed(record: SyncRecord, createdAt: Long = 50L) =
        codec.seal(SyncRecords.toPayload(record, createdAt))

    // -- the pull side ---------------------------------------------------------------------------

    @Test
    fun `a sealed record comes back as the record that was sealed`() = runTest {
        val original = record()
        val blob = sealed(original)
        val api = FakeApi(page = listOf(RemoteRecord(blob.blindedId, 7L, blob.envelope)))

        val page = transport(api).changesSince(0L, 32)

        val opened = page.records.single() as IncomingRecord.Opened
        assertEquals(7L, opened.seq)
        assertEquals(original, opened.record)
    }

    @Test
    fun `a record sealed under another account is faulted, not dropped`() = runTest {
        val foreign = RecordCodec(AccountRootKey.derive(AccountRootKey.generateArk()))
        val blob = foreign.seal(SyncRecords.toPayload(record(), 50L))
        val api = FakeApi(page = listOf(RemoteRecord(blob.blindedId, 7L, blob.envelope)))

        val faulted = transport(api).changesSince(0L, 32).records.single() as IncomingRecord.Faulted
        assertEquals(RecordFault.UNREADABLE, faulted.fault)
        assertEquals("the seq must survive, or the cursor cannot stop before it", 7L, faulted.seq)
    }

    /**
     * A record whose payload is authentic but does not hash to the id it arrived under. A server
     * cannot produce this without `K_id`, so it is a client bug, and the engine halts on the first
     * one rather than repairing it.
     */
    @Test
    fun `a mislabelled record is reported as mislabelled`() = runTest {
        val wrongLabel = codec.blindedIdOf(RecordType.NOTE.wireKey, "n1")
        val envelope = RecordEnvelope.seal(
            keys.kContent,
            wrongLabel,
            RecordPayloadCodec.encode(SyncRecords.toPayload(record(uuid = "n2"), 50L)),
        )
        val api = FakeApi(page = listOf(RemoteRecord(wrongLabel, 7L, envelope)))

        val faulted = transport(api).changesSince(0L, 32).records.single() as IncomingRecord.Faulted
        assertEquals(RecordFault.MISLABELLED, faulted.fault)
    }

    @Test
    fun `a payload from a newer build is reported as a version fault`() = runTest {
        val blindedId = codec.blindedIdOf(RecordType.NOTE.wireKey, "n1")
        val text = RecordPayloadCodec.encode(SyncRecords.toPayload(record(), 50L))
            .decodeToString().replaceFirst("\"v\":1", "\"v\":9")
        val api = FakeApi(
            page = listOf(
                RemoteRecord(blindedId, 7L, RecordEnvelope.seal(keys.kContent, blindedId, text.encodeToByteArray()))
            )
        )

        val faulted = transport(api).changesSince(0L, 32).records.single() as IncomingRecord.Faulted
        assertEquals(RecordFault.UNSUPPORTED_PAYLOAD_VERSION, faulted.fault)
    }

    /**
     * A payload that decrypts and then will not parse is authentic damage, and the engine's
     * UNREADABLE response is the right one: count it, skip it, do not advance past it. It is not
     * MISLABELLED — nothing about the record's identity is wrong — and reporting it as a version
     * refusal would halt the whole engine over one damaged row.
     */
    @Test
    fun `a malformed but authentic payload is unreadable rather than a halt`() = runTest {
        val blindedId = codec.blindedIdOf(RecordType.NOTE.wireKey, "n1")
        val api = FakeApi(
            page = listOf(
                RemoteRecord(
                    blindedId, 7L,
                    RecordEnvelope.seal(keys.kContent, blindedId, "not json".encodeToByteArray()),
                )
            )
        )

        val faulted = transport(api).changesSince(0L, 32).records.single() as IncomingRecord.Faulted
        assertEquals(RecordFault.UNREADABLE, faulted.fault)
    }

    /**
     * A sealed record naming a type this build does not implement.
     *
     * Sealed by hand rather than through `RecordPayloadCodec.encode`, which cannot express an
     * unknown type -- the only way to produce these bytes is to write them the way a later build
     * would.
     */
    private fun sealedRecordWithRecType(wireKey: String): RemoteRecord {
        val json = """
            {"v":1,"serializer":1,"recType":"$wireKey","uuid":"u1",
             "hlc":"1-0-node","fields":{},"clocks":{},"del":false}
        """.trimIndent().encodeToByteArray()
        val blindedId = codec.blindedIdOf(wireKey, "u1")
        return RemoteRecord(blindedId, 1L, RecordEnvelope.seal(keys.kContent, blindedId, json))
    }

    /** The transport under test, serving exactly [records] from one page. */
    private fun transportOver(vararg records: RemoteRecord): EnvelopeSyncTransport =
        transport(FakeApi(page = records.toList()))

    @Test
    fun `a record of an unknown type arrives as UNKNOWN_TYPE, not UNREADABLE`() = runTest {
        // UNREADABLE means "I should have been able to read this and could not", and the engine
        // reacts by refusing to page past it. A type from a later build earns neither reaction.
        // "attachment" rather than "sketch": the latter is now RecordType.SKETCH's real wire key.
        val page = transportOver(sealedRecordWithRecType("attachment")).changesSince(0, 32)

        val faulted = page.records.single() as IncomingRecord.Faulted
        assertEquals(RecordFault.UNKNOWN_TYPE, faulted.fault)
    }

    // -- the push side ---------------------------------------------------------------------------

    @Test
    fun `an accepted push is matched back to the row it came from`() = runTest {
        val api = FakeApi()
        val response = transport(api).push(
            listOf(PushRequest(RecordType.NOTE, "n1", baseSeq = 3L, record = record()))
        )

        val ack = response.results.single() as PushAck.Accepted
        assertEquals(RecordType.NOTE, ack.type)
        assertEquals("n1", ack.uuid)
        assertEquals(3L, api.pushed.single().baseSeq)
    }

    /**
     * The identity map back from a blinded id has to survive a response in a different order from
     * the request, because the transport contract allows one. An engine that read acks positionally
     * would acknowledge one row with another row's seq — a lost update that looks like nothing.
     */
    @Test
    fun `a reordered response still acknowledges the right rows`() = runTest {
        val api = FakeApi(reverseResults = true)
        val response = transport(api).push(
            listOf(
                PushRequest(RecordType.NOTE, "n1", 0L, record("n1", "one")),
                PushRequest(RecordType.NOTE, "n2", 0L, record("n2", "two")),
            )
        )

        val bySeq = response.results.filterIsInstance<PushAck.Accepted>().associate { it.uuid to it.seq }
        assertEquals(mapOf("n1" to 1L, "n2" to 2L), bySeq)
    }

    @Test
    fun `a conflict hands the blocking version back as a record`() = runTest {
        val blocking = record(content = "theirs")
        val blob = sealed(blocking)
        val api = FakeApi(conflictWith = RemoteRecord(blob.blindedId, 9L, blob.envelope))

        val response = transport(api).push(
            listOf(PushRequest(RecordType.NOTE, "n1", 3L, record(content = "mine")))
        )

        val ack = response.results.single() as PushAck.Conflicted
        assertEquals(blocking, ack.current)
        assertEquals(9L, ack.currentSeq)
    }

    /**
     * A conflict whose inline version will not open leaves the row dirty with no record attached,
     * which the engine answers by letting the next pull fetch the blocking version the ordinary
     * way. Faulting here would halt on a record there is a perfectly good second route to.
     */
    @Test
    fun `a conflict with an unreadable version is reported with no record rather than as a fault`() = runTest {
        val foreign = RecordCodec(AccountRootKey.derive(AccountRootKey.generateArk()))
        val blob = foreign.seal(SyncRecords.toPayload(record(), 50L))
        val api = FakeApi(conflictWith = RemoteRecord(blob.blindedId, 9L, blob.envelope))

        val ack = transport(api).push(
            listOf(PushRequest(RecordType.NOTE, "n1", 3L, record()))
        ).results.single() as PushAck.Conflicted

        assertNull(ack.current)
        assertEquals("no record means no version to build on", 0L, ack.currentSeq)
    }

    // -- failures --------------------------------------------------------------------------------

    @Test
    fun `every SyncException maps to the fault the engine acts on`() = runTest {
        val cases = listOf(
            SyncException.Network("down", null) to TransportFault.NETWORK,
            SyncException.PinMismatch("pin", null) to TransportFault.PROTOCOL,
            SyncException.RateLimited(2_000L) to TransportFault.RATE_LIMITED,
            SyncException.Unauthorized("nope") to TransportFault.UNAUTHORIZED,
            SyncException.DeviceRevoked to TransportFault.DEVICE_REVOKED,
            SyncException.CursorAheadOfServer(5L) to TransportFault.CURSOR_AHEAD_OF_SERVER,
            SyncException.Protocol("odd") to TransportFault.PROTOCOL,
            SyncException.Server(500, "boom", "boom") to TransportFault.PROTOCOL,
        )

        cases.forEach { (thrown, expected) ->
            val failure = runCatching { transport(FakeApi(fail = thrown)).changesSince(0L, 32) }
                .exceptionOrNull()
            assertTrue("$thrown produced $failure", failure is SyncTransportException)
            assertEquals("$thrown", expected, (failure as SyncTransportException).fault)
        }
    }

    /** A `429` carries the server's own delay through, without the engine's jitter added twice. */
    @Test
    fun `a rate limit carries its delay`() = runTest {
        val failure = runCatching { transport(FakeApi(fail = SyncException.RateLimited(2_000L))).changesSince(0L, 32) }
            .exceptionOrNull() as SyncTransportException
        assertEquals(2_000L, failure.retryAfterMillis)
    }

    /**
     * `SyncHttpClient` rejects a caller error with `require`, i.e. an `IllegalArgumentException`.
     * Letting that escape would take down the coroutine running the pass rather than ending the
     * pass, and the engine's "never throws" promise would stop being true one layer up.
     */
    @Test
    fun `a caller error from the client becomes a transport fault rather than escaping`() = runTest {
        val failure = runCatching {
            transport(FakeApi(fail = IllegalArgumentException("a batch is 1..64 items"))).changesSince(0L, 32)
        }.exceptionOrNull()

        assertTrue("was $failure", failure is SyncTransportException)
        assertEquals(TransportFault.PROTOCOL, (failure as SyncTransportException).fault)
    }

    // -- the fake --------------------------------------------------------------------------------

    /**
     * A `SyncApi` that answers from constructor arguments.
     *
     * Only the three methods this transport calls do anything. The rest throw, so a transport that
     * quietly started calling one would fail loudly here rather than in production against a real
     * account.
     */
    private class FakeApi(
        private val page: List<RemoteRecord> = emptyList(),
        private val hasMore: Boolean = false,
        private val conflictWith: RemoteRecord? = null,
        private val reverseResults: Boolean = false,
        private val fail: Throwable? = null,
    ) : SyncApi {

        val pushed = mutableListOf<PushItem>()

        override suspend fun health(): ServerHealth = error("not called by the transport")

        override suspend fun claimAccount(accountId: String, deviceLabel: String): ClaimOutcome =
            error("not called by the transport")

        override suspend fun authorizeDevice(
            accountId: String,
            voucherDeviceId: String,
            newPublicKey: ByteArray,
            deviceLabel: String,
        ): EnrolledDevice = error("not called by the transport")

        override suspend fun listDevices(credentials: DeviceCredentials): List<RemoteDevice> =
            error("not called by the transport")

        override suspend fun revokeDevice(credentials: DeviceCredentials, deviceId: String): Unit =
            error("not called by the transport")

        override suspend fun history(
            credentials: DeviceCredentials,
            blindedId: String,
            limit: Int?,
        ): List<RemoteRecord> = error("not called by the transport")

        override suspend fun changesSince(
            credentials: DeviceCredentials,
            since: Cursor,
            limit: Int?,
        ): ChangesPage {
            fail?.let { throw it }
            return ChangesPage(
                records = page,
                nextCursor = page.lastOrNull()?.let { Cursor.ofSeq(it.seq) } ?: since,
                hasMore = hasMore,
            )
        }

        override suspend fun pushRecords(
            credentials: DeviceCredentials,
            items: List<PushItem>,
        ): PushOutcome {
            fail?.let { throw it }
            pushed += items
            val results = items.mapIndexed { index, item ->
                if (conflictWith != null) PushResult.Conflict(item.blindedId, conflictWith)
                else PushResult.Accepted(item.blindedId, (index + 1).toLong())
            }
            return PushOutcome(
                results = if (reverseResults) results.reversed() else results,
                accountSeq = results.size.toLong(),
            )
        }
    }
}
