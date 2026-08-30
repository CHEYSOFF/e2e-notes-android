package my.cheysoff.core_crypto.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The record envelope.
 *
 * Round-tripping is the cheap half. The half that matters is that **every** byte of the envelope
 * and **every** component of the associated data is actually authenticated, so each is tampered
 * with independently below and each must produce a null. A single component silently left out of
 * the AAD is invisible to a round-trip test and is exactly the kind of omission that turns into a
 * server-rollback vulnerability years later.
 *
 * Two things here are protected twice over, and that redundancy hides them from any seal/open
 * test: `blindedId` feeds **both** the per-record key derivation and the associated data, and the
 * version byte is **both** checked structurally by `open` and bound into the associated data.
 * Dropping either from its associated-data half changes nothing a round trip can see. The
 * redundancy is deliberate, so rather than leave those halves unasserted, the last two sections
 * of this file assert `associatedData` and `perRecordKeyBytes` directly.
 */
class RecordEnvelopeTest {

    private val kContent = ByteArray(32) { it.toByte() }
    private val otherKContent = ByteArray(32) { (it + 7).toByte() }

    private val recType = "note"
    private val blindedId = "AAECAwQFBgcICQoLDA"
    private val hlc = "1756500000000:3:pixel7"

    private val payload = "<p>Remember the milk.</p>".toByteArray()

    private fun seal(
        recType: String = this.recType,
        blindedId: String = this.blindedId,
        hlc: String = this.hlc,
        payload: ByteArray = this.payload,
        key: ByteArray = kContent,
    ) = RecordEnvelope.seal(key, recType, blindedId, hlc, payload)

    private fun open(
        envelope: ByteArray,
        recType: String = this.recType,
        blindedId: String = this.blindedId,
        hlc: String = this.hlc,
        key: ByteArray = kContent,
    ) = RecordEnvelope.open(key, recType, blindedId, hlc, envelope)

    /** Offsets of the three tamperable regions of `ver ‖ nonce ‖ ciphertext ‖ tag`. */
    private fun nonceOffset() = 1
    private fun ciphertextOffset() = 1 + SyncProtocol.NONCE_BYTES
    private fun tagOffset(envelope: ByteArray) = envelope.size - SyncProtocol.TAG_BYTES

    private fun ByteArray.withFlippedBit(index: Int): ByteArray =
        copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }

    // ---------------------------------------------------------------------------------------
    // Round trip
    // ---------------------------------------------------------------------------------------

    @Test
    fun `seal then open returns the original payload`() {
        assertArrayEquals(payload, open(seal()))
    }

    @Test
    fun `an empty payload round-trips`() {
        assertEquals(0, open(seal(payload = ByteArray(0)))!!.size)
    }

    @Test
    fun `a payload spanning several buckets round-trips`() {
        val large = ByteArray(3000) { (it % 253).toByte() }

        assertArrayEquals(large, open(seal(payload = large)))
    }

    @Test
    fun `a payload ending in zero bytes round-trips at full length`() {
        val trailing = byteArrayOf(0x41, 0x00, 0x00)

        assertArrayEquals(trailing, open(seal(payload = trailing)))
    }

    // ---------------------------------------------------------------------------------------
    // Structure
    // ---------------------------------------------------------------------------------------

    @Test
    fun `the envelope starts with the version byte and carries a 12-byte nonce`() {
        val envelope = seal()

        assertEquals(SyncProtocol.ENVELOPE_VERSION, envelope[0])
        assertEquals(
            1 + SyncProtocol.NONCE_BYTES + SyncProtocol.PADDING_BUCKET_BYTES + SyncProtocol.TAG_BYTES,
            envelope.size,
        )
    }

    @Test
    fun `envelope length reveals only the bucket, not the payload size`() {
        // A 1-byte note and a 200-byte note must be the same size on the wire.
        val tiny = seal(payload = ByteArray(1))
        val bigger = seal(payload = ByteArray(200))

        assertEquals(tiny.size, bigger.size)
    }

    @Test
    fun `the ciphertext does not contain the plaintext`() {
        val marker = "SECRETMARKER".toByteArray()
        val envelope = seal(payload = marker)

        assertTrue(!String(envelope, Charsets.ISO_8859_1).contains("SECRETMARKER"))
    }

    // ---------------------------------------------------------------------------------------
    // Nonces
    // ---------------------------------------------------------------------------------------

    @Test
    fun `two seals of identical input produce different envelopes`() {
        // If this ever fails, the nonce has become deterministic — which under GCM means key and
        // nonce reuse, i.e. plaintext recovery and forgery. It is the single most important
        // assertion in this file.
        val first = seal()
        val second = seal()

        assertNotEquals(first.toHex(), second.toHex())
    }

    @Test
    fun `nonces do not repeat across many seals of the same record`() {
        val nonces = (1..200).map {
            seal().copyOfRange(nonceOffset(), nonceOffset() + SyncProtocol.NONCE_BYTES).toHex()
        }

        assertEquals(200, nonces.toSet().size)
    }

    @Test
    fun `every byte position of the nonce varies across seals`() {
        // "Nonces are all different" is NOT enough to rule out the dangerous implementation: a
        // counter also produces all-different nonces, and would sail through the two tests above
        // while reintroducing exactly the backup-restore and crash replay hazards that random
        // nonces exist to avoid. What separates a counter from SecureRandom is that a counter
        // leaves ten or eleven of the twelve byte positions constant.
        //
        // Sixty-four draws over 256 values give ~57 distinct values per position on average; the
        // probability that a genuinely random position shows 8 or fewer distinct values is far
        // below any level at which this could flake. A counter shows exactly 1.
        val nonces = (1..64).map { seal().copyOfRange(nonceOffset(), ciphertextOffset()) }

        for (position in 0 until SyncProtocol.NONCE_BYTES) {
            val distinct = nonces.map { it[position] }.toSet().size

            assertTrue("nonce byte $position took only $distinct distinct values", distinct > 8)
        }
    }

    @Test
    fun `every envelope of the same payload still opens to that payload`() {
        // The counterpart to the test above: randomness must not cost correctness.
        repeat(20) {
            assertArrayEquals(payload, open(seal()))
        }
    }

    // ---------------------------------------------------------------------------------------
    // Tampering with the envelope bytes
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a flipped ciphertext bit fails to open`() {
        assertNull(open(seal().withFlippedBit(ciphertextOffset())))
    }

    @Test
    fun `a flipped bit in the middle of the ciphertext fails to open`() {
        val envelope = seal()
        val middle = (ciphertextOffset() + tagOffset(envelope)) / 2

        assertNull(open(envelope.withFlippedBit(middle)))
    }

    @Test
    fun `a flipped tag bit fails to open`() {
        val envelope = seal()

        assertNull(open(envelope.withFlippedBit(tagOffset(envelope))))
    }

    @Test
    fun `a flipped bit in the last tag byte fails to open`() {
        val envelope = seal()

        assertNull(open(envelope.withFlippedBit(envelope.size - 1)))
    }

    @Test
    fun `a flipped nonce bit fails to open`() {
        assertNull(open(seal().withFlippedBit(nonceOffset())))
    }

    @Test
    fun `a flipped bit in the last nonce byte fails to open`() {
        assertNull(open(seal().withFlippedBit(SyncProtocol.NONCE_BYTES)))
    }

    @Test
    fun `an unknown version byte is rejected`() {
        val envelope = seal()
        envelope[0] = 2

        assertNull(open(envelope))
    }

    @Test
    fun `a truncated envelope is rejected`() {
        val envelope = seal()

        assertNull(open(envelope.copyOf(envelope.size - 1)))
        assertNull(open(envelope.copyOf(SyncProtocol.NONCE_BYTES)))
        assertNull(open(ByteArray(0)))
    }

    // ---------------------------------------------------------------------------------------
    // Tampering with the associated data — one component at a time
    // ---------------------------------------------------------------------------------------

    @Test
    fun `opening with a different recType fails`() {
        assertNull(open(seal(), recType = "folder"))
    }

    @Test
    fun `opening with a recType that differs by one character fails`() {
        assertNull(open(seal(), recType = "notf"))
    }

    @Test
    fun `opening with a different hlc fails`() {
        // This is the server-rollback defence: an old envelope served under a new clock, or a new
        // envelope replayed under an old one, cannot be opened.
        assertNull(open(seal(), hlc = "1756500000001:3:pixel7"))
    }

    @Test
    fun `opening with an hlc that differs only in its device component fails`() {
        assertNull(open(seal(), hlc = "1756500000000:3:pixel8"))
    }

    @Test
    fun `an envelope sealed for one blindedId cannot be opened as another`() {
        // Prevents the server from moving a record's content onto a different record.
        assertNull(open(seal(), blindedId = "ZZZZZZZZZZZZZZZZZZ"))
    }

    @Test
    fun `an envelope sealed for one blindedId cannot be opened as one differing by a character`() {
        assertNull(open(seal(), blindedId = blindedId.dropLast(1) + "X"))
    }

    @Test
    fun `an envelope with shifted field boundaries cannot be opened`() {
        // ("note", "AB…") and ("not", "eAB…") are the same bytes under naive concatenation.
        // This envelope must not open under the shifted labels. Note *why* it does not: the
        // shifted `blindedId` also selects a different per-record key, so this assertion would
        // still hold even if the associated data were built naively. The length-prefix property
        // itself is asserted directly in `associatedData is injective …` below.
        val envelope = seal(recType = "note", blindedId = "ABCDEF")

        assertNull(open(envelope, recType = "not", blindedId = "eABCDEF"))
        assertArrayEquals(payload, open(envelope, recType = "note", blindedId = "ABCDEF"))
    }

    // ---------------------------------------------------------------------------------------
    // Associated data, asserted directly
    //
    // seal/open cannot expose these: `blindedId` and the version byte are each protected twice
    // over (key derivation and a structural version check respectively), so dropping either from
    // the associated data changes no observable seal/open behaviour. Asserting on the associated
    // data itself is the only way to show that half is really wired up.
    // ---------------------------------------------------------------------------------------

    private fun aad(
        recType: String = this.recType,
        blindedId: String = this.blindedId,
        hlc: String = this.hlc,
    ) = RecordEnvelope.associatedData(recType, blindedId, hlc).toHex()

    @Test
    fun `associated data begins with the envelope version byte`() {
        assertEquals("01", aad().substring(0, 2))
    }

    @Test
    fun `associated data changes with every one of its four components`() {
        val baseline = aad()

        assertNotEquals(baseline, aad(recType = "folder"))
        assertNotEquals(baseline, aad(blindedId = "somethingElseEntirely"))
        assertNotEquals(baseline, aad(hlc = "1756500000001:3:pixel7"))
    }

    @Test
    fun `associated data is injective across shifted field boundaries`() {
        // The property the 2-byte length prefixes exist for. Naive concatenation would make each
        // of these pairs identical, which would let an envelope authenticate under labels it was
        // never sealed with.
        assertNotEquals(aad(recType = "note", blindedId = "ABC"), aad(recType = "not", blindedId = "eABC"))
        assertNotEquals(aad(blindedId = "ABC", hlc = "12"), aad(blindedId = "ABC1", hlc = "2"))
        assertNotEquals(aad(recType = "a", blindedId = "bc"), aad(recType = "ab", blindedId = "c"))
    }

    @Test
    fun `associated data is deterministic`() {
        assertEquals(aad(), aad())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a field too long to length-prefix is rejected rather than silently truncated`() {
        // 65_536 bytes does not fit the 2-byte length prefix. Writing the low 16 bits anyway would
        // silently produce associated data that two different field triples could share, which is
        // the exact ambiguity the prefixes exist to remove. No real recType, blinded ID or HLC is
        // remotely this long; the check is here so a future caller cannot make one.
        RecordEnvelope.associatedData(recType, blindedId, "h".repeat(0x10000))
    }

    // ---------------------------------------------------------------------------------------
    // Keys
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a different K_content cannot open the envelope`() {
        assertNull(open(seal(), key = otherKContent))
    }

    @Test
    fun `records under the same key use different per-record keys`() {
        // Two records sealed under one K_content must not be interchangeable. Asserting the
        // envelopes differ is not enough — random nonces guarantee that anyway — so this checks
        // that neither opens under the other's label. As with the boundary test above, this
        // passes for two overlapping reasons (different key AND different associated data); the
        // key half is isolated by the next test.
        val first = seal(blindedId = "recordAAAAAAAAAAAAAAAA")
        val second = seal(blindedId = "recordBBBBBBBBBBBBBBBB")

        assertNull(open(first, blindedId = "recordBBBBBBBBBBBBBBBB"))
        assertNull(open(second, blindedId = "recordAAAAAAAAAAAAAAAA"))
    }

    @Test
    fun `the per-record key really depends on the blindedId`() {
        // Asserted directly because seal/open cannot see it: with `blindedId` also bound into the
        // associated data, a version of this code that derived ONE key for every record would
        // still pass every seal/open test in this file while giving each record's key billions of
        // encryptions instead of one — which is the assumption that makes a random 96-bit nonce
        // safe in the first place.
        val a = RecordEnvelope.perRecordKeyBytes(kContent, "recordAAAAAAAAAAAAAAAA").toHex()
        val b = RecordEnvelope.perRecordKeyBytes(kContent, "recordBBBBBBBBBBBBBBBB").toHex()

        assertNotEquals(a, b)
    }

    @Test
    fun `the per-record key is deterministic and 32 bytes`() {
        val first = RecordEnvelope.perRecordKeyBytes(kContent, blindedId)
        val second = RecordEnvelope.perRecordKeyBytes(kContent, blindedId)

        assertEquals(SyncProtocol.DERIVED_KEY_BYTES, first.size)
        assertEquals(first.toHex(), second.toHex())
    }

    @Test
    fun `the per-record key depends on K_content`() {
        assertNotEquals(
            RecordEnvelope.perRecordKeyBytes(kContent, blindedId).toHex(),
            RecordEnvelope.perRecordKeyBytes(otherKContent, blindedId).toHex(),
        )
    }

    @Test
    fun `keys derived from a real ARK seal and open records end to end`() {
        // The whole Phase 1 stack in one test: ARK → K_content/K_id → blinded ID → envelope.
        val ark = AccountRootKey.generateArk()
        val keys = AccountRootKey.derive(ark)
        val id = BlindedRecordId.compute(keys.kId, "note", "3f2504e0-4f89-11d3-9a0c-0305e82c3301")

        val envelope = RecordEnvelope.seal(keys.kContent, "note", id, hlc, payload)

        assertArrayEquals(payload, RecordEnvelope.open(keys.kContent, "note", id, hlc, envelope))
    }

    @Test
    fun `a second device deriving from the same ARK opens the first device's envelope`() {
        // The property that makes pairing work: nothing device-specific is baked into an envelope.
        val ark = AccountRootKey.generateArk()
        val deviceA = AccountRootKey.derive(ark)
        val deviceB = AccountRootKey.derive(ark)

        val envelope = RecordEnvelope.seal(deviceA.kContent, recType, blindedId, hlc, payload)

        assertArrayEquals(
            payload,
            RecordEnvelope.open(deviceB.kContent, recType, blindedId, hlc, envelope),
        )
    }
}
