package my.cheysoff.core_crypto.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bucket padding.
 *
 * Two independent things are being checked and they fail in opposite directions:
 *
 *  - **Round-trip exactness.** A padding scheme that loses a byte corrupts notes. The interesting
 *    inputs are the boundaries — empty, one below a bucket, exactly a bucket, one above — and
 *    content that itself ends in zero bytes, which is what a naive "strip trailing zeros"
 *    implementation destroys silently.
 *  - **Size leakage.** A padding scheme that rounds to the wrong thing still round-trips
 *    perfectly while leaking the note size it was supposed to hide, so the size assertions here
 *    matter as much as the equality ones.
 */
class RecordPaddingTest {

    private val bucket = SyncProtocol.PADDING_BUCKET_BYTES

    /** The largest payload that still fits one bucket alongside the 4-byte length prefix. */
    private val largestSingleBucketPayload = bucket - 4

    private fun content(size: Int): ByteArray = ByteArray(size) { (it % 251).toByte() }

    private fun assertRoundTrips(original: ByteArray) {
        val recovered = RecordPadding.unpad(RecordPadding.pad(original))

        assertArrayEquals(original, recovered)
    }

    // ---------------------------------------------------------------------------------------
    // Round-trip exactness
    // ---------------------------------------------------------------------------------------

    @Test
    fun `empty content round-trips to an empty array`() {
        val recovered = RecordPadding.unpad(RecordPadding.pad(ByteArray(0)))

        assertEquals(0, recovered!!.size)
    }

    @Test
    fun `content exactly filling a bucket round-trips`() {
        assertRoundTrips(content(largestSingleBucketPayload))
    }

    @Test
    fun `content one byte over a bucket round-trips`() {
        assertRoundTrips(content(largestSingleBucketPayload + 1))
    }

    @Test
    fun `content one byte under a bucket round-trips`() {
        assertRoundTrips(content(largestSingleBucketPayload - 1))
    }

    @Test
    fun `content exactly two buckets round-trips`() {
        assertRoundTrips(content(2 * bucket - 4))
    }

    @Test
    fun `a single byte round-trips`() {
        assertRoundTrips(byteArrayOf(0x41))
    }

    @Test
    fun `content whose own trailing bytes are zero round-trips`() {
        // The case a "strip trailing zeros" scheme silently truncates. UTF-16 text, a serialized
        // integer field and any binary payload can all end in 0x00.
        assertRoundTrips(byteArrayOf(0x41, 0x00, 0x00, 0x00))
    }

    @Test
    fun `content that is entirely zero bytes round-trips at full length`() {
        assertRoundTrips(ByteArray(100))
    }

    @Test
    fun `a realistic note body round-trips byte for byte`() {
        val note = "<p>Milk, eggs, and a <b>long</b> think about Tuesday.</p>".toByteArray()

        assertRoundTrips(note)
    }

    @Test
    fun `pad does not modify its input`() {
        val original = content(50)
        val copy = original.copyOf()

        RecordPadding.pad(original)

        assertArrayEquals(copy, original)
    }

    // ---------------------------------------------------------------------------------------
    // Size leakage
    // ---------------------------------------------------------------------------------------

    @Test
    fun `padded size is always a positive multiple of the bucket size`() {
        for (size in listOf(0, 1, 7, 100, 251, 252, 253, 511, 512, 1000, 5000)) {
            val padded = RecordPadding.pad(content(size))

            assertEquals("size $size", 0, padded.size % bucket)
            assertTrue("size $size", padded.size >= bucket)
        }
    }

    @Test
    fun `every payload in the first bucket pads to the same length`() {
        // The privacy claim itself: a shopping list and a diary entry of different lengths must be
        // indistinguishable by ciphertext size as long as they fall in the same bucket.
        val shortest = RecordPadding.pad(ByteArray(0)).size
        val longest = RecordPadding.pad(content(largestSingleBucketPayload)).size

        assertEquals(bucket, shortest)
        assertEquals(bucket, longest)
    }

    @Test
    fun `crossing a bucket boundary costs exactly one more bucket`() {
        val inside = RecordPadding.pad(content(largestSingleBucketPayload)).size
        val over = RecordPadding.pad(content(largestSingleBucketPayload + 1)).size

        assertEquals(bucket, inside)
        assertEquals(2 * bucket, over)
    }

    @Test
    fun `the length prefix is counted so a full-bucket payload does not fit one bucket`() {
        // A pad() that forgot to count its own 4-byte header would return one bucket here and then
        // overflow. Asserting the size is what catches it, since the round trip would still work.
        assertEquals(2 * bucket, RecordPadding.pad(content(bucket)).size)
    }

    // ---------------------------------------------------------------------------------------
    // Malformed input
    // ---------------------------------------------------------------------------------------

    @Test
    fun `unpad returns null for a block shorter than the length prefix`() {
        assertNull(RecordPadding.unpad(ByteArray(3)))
    }

    @Test
    fun `unpad returns null when the declared length exceeds the block`() {
        val padded = RecordPadding.pad(content(10))
        padded[3] = 0xFF.toByte()

        assertNull(RecordPadding.unpad(padded))
    }

    @Test
    fun `unpad returns null for a negative declared length`() {
        val padded = RecordPadding.pad(content(10))
        padded[0] = 0xFF.toByte()

        assertNull(RecordPadding.unpad(padded))
    }

    @Test
    fun `unpad accepts a block whose declared length exactly fills it`() {
        val exact = ByteArray(SyncProtocol.PADDING_BUCKET_BYTES)
        val payloadLength = exact.size - 4
        exact[2] = (payloadLength ushr 8).toByte()
        exact[3] = payloadLength.toByte()

        assertEquals(payloadLength, RecordPadding.unpad(exact)!!.size)
    }
}
