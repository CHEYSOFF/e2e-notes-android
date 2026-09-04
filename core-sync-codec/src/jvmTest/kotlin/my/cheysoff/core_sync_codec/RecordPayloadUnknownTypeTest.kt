package my.cheysoff.core_sync_codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A record type this build does not know is not a damaged record.
 *
 * The distinction is the whole point: a damaged record is evidence something is wrong and the
 * engine must stop; a record written for a later build is evidence of nothing at all, and stopping
 * on it freezes the cursor and then halts a device whose only problem is that it has not been
 * updated yet.
 */
class RecordPayloadUnknownTypeTest {

    /** A payload identical in shape to a note's, but naming a type this build has never heard of. */
    private fun bytesWithRecType(wireKey: String): ByteArray =
        """
        {"v":1,"serializer":1,"recType":"$wireKey","uuid":"u1",
         "hlc":"1-0-node","fields":{"title":"","content":"","checklist":"","pinned":"0","favorite":"0","folderId":null,"updatedAt":"0","isDeleted":"0"},"clocks":{},"del":false}
        """.trimIndent().encodeToByteArray()

    @Test
    fun `an unknown record type decodes as UnknownType, not Malformed`() {
        // See UNIMPLEMENTED_TEST_RECORD_TYPE's own doc for why this must not be a plausible
        // feature name: "sketch" and then "attachment" both were, and both went on to become real.
        val result = RecordPayloadCodec.decode(bytesWithRecType(UNIMPLEMENTED_TEST_RECORD_TYPE))

        assertTrue("expected UnknownType, got $result", result is PayloadResult.UnknownType)
        assertEquals(UNIMPLEMENTED_TEST_RECORD_TYPE, (result as PayloadResult.UnknownType).wireKey)
    }

    @Test
    fun `a genuinely damaged payload is still Malformed`() {
        // A known type whose required keys are missing: this build should have been able to read
        // it and could not, which is a different fact and must keep its old, louder handling.
        val damaged = """{"v":1,"serializer":1,"recType":"note"}""".encodeToByteArray()

        assertTrue(RecordPayloadCodec.decode(damaged) is PayloadResult.Malformed)
    }
}
