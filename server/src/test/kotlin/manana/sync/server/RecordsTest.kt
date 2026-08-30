package manana.sync.server

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `POST /v1/records`, `GET /v1/changes`, `GET /v1/records/{id}/history`. */
class RecordsTest {

    private val envelopeA = "sealed-A".toByteArray()
    private val envelopeB = "sealed-B".toByteArray()

    @Test
    fun aPushedRecordComesBackOnTheNextPull() = serverTest { harness ->
        val me = enrol(harness)
        val id = blindedId(1)

        val push: UpsertResponse = push(me.token, upsertItem(id, envelopeA, baseSeq = 0)).decode()
        assertEquals("ok", push.results.single().status)
        assertEquals(1L, push.results.single().seq)

        val pull: ChangesResponse = client.getAuth("/v1/changes?since=0", me.token).decode()
        val record = pull.records.single()
        assertEquals(id, record.blindedId)
        assertEquals(1L, record.seq)
        assertContentEquals(envelopeA, B64.decodeOrNull(record.envelope))
        assertEquals(1L, pull.nextCursor)
    }

    @Test
    fun aCursorAtTheHeadReturnsNothing() = serverTest { harness ->
        val me = enrol(harness)
        push(me.token, upsertItem(blindedId(1), envelopeA, 0))
        val pull: ChangesResponse = client.getAuth("/v1/changes?since=1", me.token).decode()
        assertTrue(pull.records.isEmpty())
        assertEquals(1L, pull.nextCursor)
        assertEquals(false, pull.hasMore)
    }

    /**
     * The compare-and-set. A second writer whose `baseSeq` is stale is refused, and the version
     * that blocked it comes back inline so the client can merge without another round trip.
     */
    @Test
    fun aStaleBaseSeqIsRejectedWithTheConflictingEnvelopeInline() = serverTest { harness ->
        val me = enrol(harness)
        val id = blindedId(1)
        push(me.token, upsertItem(id, envelopeA, 0))

        val response = push(me.token, upsertItem(id, envelopeB, baseSeq = 0))
        assertEquals(409, response.status.value)

        val body: UpsertResponse = response.decode()
        val result = body.results.single()
        assertEquals("conflict", result.status)
        assertNull(result.seq)
        val current = assertNotNull(result.current)
        assertEquals(1L, current.seq)
        assertContentEquals(envelopeA, B64.decodeOrNull(current.envelope))

        // And nothing was written: the head is still the first version.
        val pull: ChangesResponse = client.getAuth("/v1/changes?since=0", me.token).decode()
        assertContentEquals(envelopeA, B64.decodeOrNull(pull.records.single().envelope))
    }

    @Test
    fun aCorrectBaseSeqSupersedesThePreviousVersion() = serverTest { harness ->
        val me = enrol(harness)
        val id = blindedId(1)
        push(me.token, upsertItem(id, envelopeA, 0))

        val second: UpsertResponse = push(me.token, upsertItem(id, envelopeB, baseSeq = 1)).decode()
        assertEquals("ok", second.results.single().status)
        assertEquals(2L, second.results.single().seq)

        val pull: ChangesResponse = client.getAuth("/v1/changes?since=0", me.token).decode()
        assertEquals(1, pull.records.size)
        assertContentEquals(envelopeB, B64.decodeOrNull(pull.records.single().envelope))
    }

    /** `baseSeq = 0` asserts "this record does not exist"; it must not overwrite one that does. */
    @Test
    fun baseSeqZeroAgainstAnExistingRecordIsAConflict() = serverTest { harness ->
        val me = enrol(harness)
        val id = blindedId(1)
        push(me.token, upsertItem(id, envelopeA, 0))
        val response = push(me.token, upsertItem(id, envelopeB, 0))
        assertEquals(409, response.status.value)
    }

    /** A `baseSeq` from the future is as wrong as a stale one and must not be trusted. */
    @Test
    fun aBaseSeqAheadOfTheHeadIsAConflict() = serverTest { harness ->
        val me = enrol(harness)
        val id = blindedId(1)
        push(me.token, upsertItem(id, envelopeA, 0))
        val response = push(me.token, upsertItem(id, envelopeB, baseSeq = 99))
        assertEquals(409, response.status.value)
    }

    /**
     * A batch is partly applicable: records are independent, so one conflict must not throw away
     * the work of the items that were fine.
     */
    @Test
    fun aBatchAppliesTheItemsThatDidNotConflict() = serverTest { harness ->
        val me = enrol(harness)
        val stale = blindedId(1)
        val fresh = blindedId(2)
        push(me.token, upsertItem(stale, envelopeA, 0))

        val response = push(
            me.token,
            upsertItem(stale, envelopeB, baseSeq = 0),
            upsertItem(fresh, envelopeB, baseSeq = 0),
        )
        assertEquals(409, response.status.value)
        val body: UpsertResponse = response.decode()
        assertEquals("conflict", body.results.single { it.blindedId == stale }.status)
        assertEquals("ok", body.results.single { it.blindedId == fresh }.status)

        val pull: ChangesResponse = client.getAuth("/v1/changes?since=0", me.token).decode()
        assertEquals(2, pull.records.size)
    }

    @Test
    fun theSameRecordTwiceInOneBatchIsRejected() = serverTest { harness ->
        val me = enrol(harness)
        val id = blindedId(1)
        val response = push(me.token, upsertItem(id, envelopeA, 0), upsertItem(id, envelopeB, 1))
        assertEquals(400, response.status.value)
        assertEquals("duplicate_record_in_batch", response.errorCode())
    }

    /**
     * There is no delete endpoint, and there must not be one: a deletion reaches the server as an
     * ordinary upsert whose tombstone flag is inside the sealed payload. This test asserts the
     * absence -- `DELETE /v1/records/{id}` is not routed at all -- and then performs a deletion the
     * way the protocol actually does it.
     */
    @Test
    fun thereIsNoDeleteEndpointAndDeletesAreOrdinaryUpserts() = serverTest { harness ->
        val me = enrol(harness)
        val id = blindedId(1)
        push(me.token, upsertItem(id, envelopeA, 0))

        assertEquals(404, client.deleteAuth("/v1/records/$id", me.token).status.value)

        // The client's "delete": a new sealed version whose plaintext says `del: true`. To the
        // server this is indistinguishable from any other edit, which is the whole point.
        val tombstone = "sealed-tombstone".toByteArray()
        val response = push(me.token, upsertItem(id, tombstone, baseSeq = 1))
        assertEquals(200, response.status.value)

        val pull: ChangesResponse = client.getAuth("/v1/changes?since=0", me.token).decode()
        assertEquals(1, pull.records.size)
        assertContentEquals(tombstone, B64.decodeOrNull(pull.records.single().envelope))
    }

    @Test
    fun historyReturnsRecentVersionsNewestFirst() = serverTest { harness ->
        val me = enrol(harness)
        val id = blindedId(1)
        push(me.token, upsertItem(id, "v1".toByteArray(), 0))
        push(me.token, upsertItem(id, "v2".toByteArray(), 1))
        push(me.token, upsertItem(id, "v3".toByteArray(), 2))

        val history: HistoryResponse = client.getAuth("/v1/records/$id/history", me.token).decode()
        assertEquals(listOf(3L, 2L, 1L), history.versions.map { it.seq })
        assertContentEquals("v3".toByteArray(), B64.decodeOrNull(history.versions.first().envelope))
    }

    @Test
    fun historyIsCappedAtTheConfiguredDepth() = serverTest(testConfig(historyDepth = 2)) { harness ->
        val me = enrol(harness)
        val id = blindedId(1)
        push(me.token, upsertItem(id, "v1".toByteArray(), 0))
        push(me.token, upsertItem(id, "v2".toByteArray(), 1))
        push(me.token, upsertItem(id, "v3".toByteArray(), 2))

        val history: HistoryResponse = client.getAuth("/v1/records/$id/history", me.token).decode()
        assertEquals(listOf(3L, 2L), history.versions.map { it.seq })
    }

    @Test
    fun historyForAnUnknownRecordIs404() = serverTest { harness ->
        val me = enrol(harness)
        val response = client.getAuth("/v1/records/${blindedId(7)}/history", me.token)
        assertEquals(404, response.status.value)
        assertEquals("unknown_record", response.errorCode())
    }

    @Test
    fun changesArePagedAndTheCursorAdvances() = serverTest { harness ->
        val me = enrol(harness)
        repeat(5) { push(me.token, upsertItem(blindedId(it), envelopeA, 0)) }

        val first: ChangesResponse = client.getAuth("/v1/changes?since=0&limit=2", me.token).decode()
        assertEquals(2, first.records.size)
        assertTrue(first.hasMore)

        val second: ChangesResponse =
            client.getAuth("/v1/changes?since=${first.nextCursor}&limit=2", me.token).decode()
        assertEquals(2, second.records.size)

        val third: ChangesResponse =
            client.getAuth("/v1/changes?since=${second.nextCursor}&limit=2", me.token).decode()
        assertEquals(1, third.records.size)
        assertEquals(false, third.hasMore)

        val seen = (first.records + second.records + third.records).map { it.blindedId }
        assertEquals(5, seen.toSet().size)
    }

    /** A pull returns each record's head once, not every version it has ever had. */
    @Test
    fun aPullReturnsOnlyTheHeadVersionOfEachRecord() = serverTest { harness ->
        val me = enrol(harness)
        val id = blindedId(1)
        push(me.token, upsertItem(id, "v1".toByteArray(), 0))
        push(me.token, upsertItem(id, "v2".toByteArray(), 1))

        val pull: ChangesResponse = client.getAuth("/v1/changes?since=0", me.token).decode()
        assertEquals(1, pull.records.size)
        assertEquals(2L, pull.records.single().seq)
    }

    /** Records are per-account. Two accounts on one server never see each other's blobs. */
    @Test
    fun oneAccountNeverSeesAnothersRecords() = serverTest { harness ->
        val alice = enrol(harness)
        val bob = enrol(harness)
        push(alice.token, upsertItem(blindedId(1), envelopeA, 0))

        val bobsPull: ChangesResponse = client.getAuth("/v1/changes?since=0", bob.token).decode()
        assertTrue(bobsPull.records.isEmpty())
        assertEquals(
            404,
            client.getAuth("/v1/records/${blindedId(1)}/history", bob.token).status.value,
        )
    }

    /**
     * The same blinded ID on two accounts is two unrelated records. It cannot happen in practice --
     * `K_id` differs per account, so the labels differ -- but the server must not be the thing that
     * makes it safe by accident.
     */
    @Test
    fun theSameBlindedIdOnTwoAccountsIsTwoRecords() = serverTest { harness ->
        val alice = enrol(harness)
        val bob = enrol(harness)
        val id = blindedId(1)
        push(alice.token, upsertItem(id, envelopeA, 0))
        assertEquals(200, push(bob.token, upsertItem(id, envelopeB, baseSeq = 0)).status.value)

        val alicesPull: ChangesResponse = client.getAuth("/v1/changes?since=0", alice.token).decode()
        assertContentEquals(envelopeA, B64.decodeOrNull(alicesPull.records.single().envelope))
    }

    @Test
    fun recordEndpointsRequireABearerToken() = serverTest { harness ->
        val me = enrol(harness)
        assertEquals(401, client.getAuth("/v1/changes?since=0", null).status.value)
        assertEquals(401, client.getAuth("/v1/records/${blindedId(1)}/history", null).status.value)
        assertEquals(
            401,
            client.postJson(
                "/v1/records",
                JSON_LENIENT.encodeToString(UpsertRequest(listOf(upsertItem(blindedId(1), envelopeA, 0)))),
            ).status.value,
        )
        // Sanity: the same calls do work with a token, so the 401s above are about auth.
        assertEquals(200, client.getAuth("/v1/changes?since=0", me.token).status.value)
    }
}
