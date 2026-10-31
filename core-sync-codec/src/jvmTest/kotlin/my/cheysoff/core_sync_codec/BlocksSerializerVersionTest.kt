package my.cheysoff.core_sync_codec

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import my.cheysoff.core_domain.sync.FieldClocks
import my.cheysoff.core_domain.sync.FieldValue
import my.cheysoff.core_domain.sync.Hlc
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_domain.sync.SyncRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A BLOCKS note goes out with serializer 2 so that a build which only knows serializer 1 refuses
 * it ([PayloadResult.UnsupportedVersion]) instead of parsing block JSON as HTML and re-saving it
 * mangled. Every other record must stay byte-identical to what it was before BLOCKS existed;
 * `RecordPayloadWireFormatTest` pins that for the HTML note, and this pins the PLAIN one too.
 */
class BlocksSerializerVersionTest {

    private val clock = Hlc(1_756_612_345_678L, 3, "a1b2c3d4")

    private fun note(format: String?, deleted: Boolean = false) = SyncRecord(
        type = RecordType.NOTE,
        uuid = "note-1",
        rowClock = clock,
        fieldClocks = emptyMap(),
        fields = mapOf(
            FieldClocks.TITLE to FieldValue.of("T"),
            FieldClocks.CONTENT to FieldValue.of("body", format),
            FieldClocks.CHECKLIST to FieldValue.of(""),
            FieldClocks.PINNED to FieldValue.of("0"),
            FieldClocks.FAVORITE to FieldValue.of("0"),
            FieldClocks.FOLDER to FieldValue.of(null),
            FieldClocks.UPDATED_AT to FieldValue.of("200"),
            FieldClocks.DELETED to FieldValue.of(if (deleted) "1" else "0", if (deleted) "300" else null),
        ),
    )

    private fun encoded(format: String?, deleted: Boolean = false): String =
        RecordPayloadCodec.encode(SyncRecords.toPayload(note(format, deleted), createdAt = 100L)).decodeToString()

    private fun serializerOf(json: String): Int =
        Json.parseToJsonElement(json).jsonObject.getValue("serializer").jsonPrimitive.int

    @Test
    fun `blocks note is written with serializer 2`() {
        val json = encoded("blocks")
        assertEquals(2, serializerOf(json))
        assertEquals(2, RecordPayloadCodec.BLOCKS_SERIALIZER_VERSION)
        assertTrue(json.endsWith(""""serializer":2}"""))
        val decoded = RecordPayloadCodec.decode(json.encodeToByteArray())
        assertTrue(decoded is PayloadResult.Ok)
    }

    @Test
    fun `a soft-deleted blocks note is still serializer 2`() {
        assertEquals(2, serializerOf(encoded("blocks", deleted = true)))
    }

    @Test
    fun `html note is still written with serializer 1`() {
        val json = encoded("html")
        assertEquals(1, serializerOf(json))
        assertTrue(json.endsWith(""""serializer":1}"""))
        // The byte-for-byte pin of an html note is RecordPayloadWireFormatTest.GOLDEN.
        assertEquals(1, serializerOf(encoded("plain")))
        assertEquals(1, serializerOf(encoded(null)))
    }

    @Test
    fun `a build that only knows serializer 1 refuses it`() {
        val serializer = serializerOf(encoded("blocks"))
        // Today's check in decode(), verbatim: `version != 1 || serializer != 1` -> UnsupportedVersion.
        val oldBuildRefuses = serializer != 1
        assertTrue(oldBuildRefuses)
        assertEquals(2, serializer)
    }

    @Test
    fun `mismatched serializer and format is malformed`() {
        val blocksAt1 = encoded("blocks").replace(""""serializer":2""", """"serializer":1""")
        assertTrue(RecordPayloadCodec.decode(blocksAt1.encodeToByteArray()) is PayloadResult.Malformed)

        val htmlAt2 = encoded("html").replace(""""serializer":1""", """"serializer":2""")
        assertTrue(RecordPayloadCodec.decode(htmlAt2.encodeToByteArray()) is PayloadResult.Malformed)
    }

    @Test
    fun `serializer 3 is still unsupported`() {
        val at3 = encoded("blocks").replace(""""serializer":2""", """"serializer":3""")
        assertTrue(RecordPayloadCodec.decode(at3.encodeToByteArray()) is PayloadResult.UnsupportedVersion)
    }
}
