package my.cheysoff.core_domain.sync

import kotlin.test.Test
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
}
