package my.cheysoff.core_domain.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordSizeTest {

    private fun record(vararg fields: Pair<String, FieldValue>) = SyncRecord(
        type = RecordType.NOTE,
        uuid = "u",
        rowClock = Hlc(1L, 0, "n"),
        fieldClocks = emptyMap(),
        fields = fields.toMap(),
    )

    @Test
    fun `the estimate grows with the payload`() {
        val small = record(FieldClocks.TITLE to FieldValue.of("hi"))
        val large = record(FieldClocks.TITLE to FieldValue.of("x".repeat(10_000)))
        assertTrue(RecordSize.estimateBytes(large) > RecordSize.estimateBytes(small) + 9_000)
    }

    @Test
    fun `a null part costs nothing beyond its key`() {
        val withNull = record(FieldClocks.TITLE to FieldValue(listOf(null)))
        val withEmpty = record(FieldClocks.TITLE to FieldValue.of(""))
        assertTrue(RecordSize.estimateBytes(withNull) == RecordSize.estimateBytes(withEmpty))
    }

    @Test
    fun `the estimate is never below the sum of the value lengths`() {
        val text = "y".repeat(5_000)
        val one = record(FieldClocks.TITLE to FieldValue.of(text))
        assertTrue(RecordSize.estimateBytes(one) >= text.length)
    }

    /** Isolates the cost of one value's encoding by subtracting the same record with it empty. */
    private fun encodedCost(text: String): Int {
        val withValue = record(FieldClocks.TITLE to FieldValue.of(text))
        val empty = record(FieldClocks.TITLE to FieldValue.of(""))
        return RecordSize.estimateBytes(withValue) - RecordSize.estimateBytes(empty)
    }

    @Test
    fun `a Cyrillic value costs two bytes per character`() {
        val text = "привет"
        assertEquals(text.length * 2, encodedCost(text))
    }

    @Test
    fun `a CJK value costs three bytes per character`() {
        val text = "日本語"
        assertEquals(text.length * 3, encodedCost(text))
    }

    @Test
    fun `an emoji costs four bytes`() {
        val text = "😀" // U+1F600, a surrogate pair -- one emoji, two UTF-16 code units
        assertEquals(4, encodedCost(text))
    }

    @Test
    fun `an ASCII value still costs one byte per character`() {
        val text = "hello"
        assertEquals(text.length, encodedCost(text))
    }
}
