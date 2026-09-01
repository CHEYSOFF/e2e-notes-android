package my.cheysoff.core_pairing

import my.cheysoff.core_pairing.protocol.AccountBundle
import my.cheysoff.core_pairing.protocol.AccountDeviceSession
import my.cheysoff.core_pairing.protocol.HkdfKeyDerivation
import my.cheysoff.core_pairing.protocol.NewDeviceSession
import my.cheysoff.core_pairing.protocol.OfferOutcome
import my.cheysoff.core_pairing.protocol.SealOutcome
import my.cheysoff.core_pairing.qr.QrCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QR encoding and decoding, round-tripped through a **synthesised camera frame**.
 *
 * This is as close to the real path as a unit test can get without a camera: the payload is
 * encoded to a module grid exactly as the screen renders it, painted into an 8-bit luminance plane
 * exactly like the Y plane of a `YUV_420_888` `ImageProxy`, and handed to the same
 * [QrCodes.decodeLuminance] the analyser calls. Only CameraX itself — binding use cases, copying
 * the buffer out of an `ImageProxy` — is left untested, and that code is deliberately kept to
 * nothing but those two steps.
 *
 * What this cannot test is optics: focus, glare, moiré between two pixel grids, motion blur. Those
 * need two real phones.
 */
class QrCodesTest {

    @Test
    fun encodesAndReadsBackASimplePayload() {
        val text = "MNP1:AbCdEf-_0123456789"
        assertEquals(text, decodeThrough(QrCodes.encode(text)))
    }

    /**
     * Every code this app renders must read back — not merely most of them.
     *
     * Regression test for a real defect, not a hypothetical: at a fixed error-correction level
     * about **0.6% of pairing payloads** encoded into a symbol that zxing could not decode. It was
     * a property of the symbol, so it reproduced at every scale from 3 to 8, under both
     * HybridBinarizer and GlobalHistogramBinarizer, and with any amount of quiet space around the
     * code. More camera frames could never have rescued it: the QR on screen was simply
     * unreadable, and the user would hold two phones together until they gave up.
     *
     * That is why [QrCodes.encode] now reads its own symbol back and falls through
     * error-correction levels until one does. Two hundred fresh sessions is enough that the old
     * behaviour fails here essentially every run; pinning a single known-bad payload would be
     * tidier but the payload carries a random ephemeral key, so there is no such constant to pin.
     */
    @Test
    fun everyGeneratedOfferCodeReadsBack() {
        repeat(200) {
            val code = NewDeviceSession(HkdfKeyDerivation, FakeClock()).offerCode
            assertEquals(code, decodeThrough(QrCodes.encode(code)))
        }
    }

    /** A real QR1, through a real frame. */
    @Test
    fun readsBackARealOfferCode() {
        val code = NewDeviceSession(HkdfKeyDerivation, FakeClock()).offerCode
        assertEquals(code, decodeThrough(QrCodes.encode(code)))
    }

    /** A real QR2 — the larger of the two payloads, and the one carrying the seal. */
    @Test
    fun readsBackARealSealCode() {
        val clock = FakeClock()
        val newDevice = NewDeviceSession(HkdfKeyDerivation, clock)
        val accountDevice = AccountDeviceSession(
            HkdfKeyDerivation, clock,
            AccountBundle(ByteArray(32) { it.toByte() }, "acct-abcdefgh", """{"url":"https://a.example/"}"""),
        )
        val accepted = accountDevice.onScanned(newDevice.offerCode) as OfferOutcome.Accepted

        val recovered = decodeThrough(QrCodes.encode(accepted.sealCode))
        assertEquals(accepted.sealCode, recovered)
        // ...and the recovered string is still a working pairing code, not merely an equal one.
        assertTrue(newDevice.onScanned(recovered!!) is SealOutcome.Paired)
    }

    /**
     * The app renders **light modules on a dark ground**, which is an inverted QR code.
     * `decodeLuminance` runs a second, inverted pass for exactly this reason.
     */
    @Test
    fun readsBackAnInvertedCode() {
        val code = NewDeviceSession(HkdfKeyDerivation, FakeClock()).offerCode
        assertEquals(code, decodeThrough(QrCodes.encode(code), inverted = true))
    }

    /**
     * A padded plane, where `rowStride > width`.
     *
     * Real analyser frames often are padded, and repacking the buffer to remove the padding is the
     * "fix" that quietly breaks decoding. `PlanarYUVLuminanceSource` takes the stride natively.
     */
    @Test
    fun readsBackFromAPaddedLuminancePlane() {
        val code = NewDeviceSession(HkdfKeyDerivation, FakeClock()).offerCode
        val matrix = QrCodes.encode(code)
        val frame = luminanceFrame(matrix, scale = 4, extraStride = 37)
        assertEquals(
            code,
            QrCodes.decodeLuminance(frame.plane, frame.width, frame.height, frame.rowStride),
        )
    }

    /** A crop that contains the symbol works; the search is limited to the rectangle given. */
    @Test
    fun readsBackFromACrop() {
        val code = NewDeviceSession(HkdfKeyDerivation, FakeClock()).offerCode
        val matrix = QrCodes.encode(code)
        val frame = luminanceFrame(matrix, scale = 4)
        assertEquals(
            code,
            QrCodes.decodeLuminance(
                frame.plane, frame.width, frame.height, frame.rowStride,
                crop = QrCodes.Crop(0, 0, frame.width, frame.height),
            ),
        )
    }

    /** A frame with no code in it is the normal case and must return null, not throw. */
    @Test
    fun returnsNullForAFrameWithNoCode() {
        val blank = ByteArray(320 * 240) { 0xFF.toByte() }
        assertNull(QrCodes.decodeLuminance(blank, 320, 240))

        val noise = ByteArray(320 * 240).also { java.util.Random(7).nextBytes(it) }
        assertNull(QrCodes.decodeLuminance(noise, 320, 240))
    }

    /** Nonsense geometry is refused rather than allowed to index off the end of the array. */
    @Test
    fun refusesImpossibleGeometryWithoutThrowing() {
        val plane = ByteArray(100)
        assertNull(QrCodes.decodeLuminance(plane, 0, 10))
        assertNull(QrCodes.decodeLuminance(plane, 10, 0))
        assertNull(QrCodes.decodeLuminance(plane, 10, 10, rowStride = 5))
        assertNull(QrCodes.decodeLuminance(plane, 100, 100))
        assertNull(QrCodes.decodeLuminance(plane, 10, 10, crop = QrCodes.Crop(-1, 0, 5, 5)))
        assertNull(QrCodes.decodeLuminance(plane, 10, 10, crop = QrCodes.Crop(0, 0, 20, 5)))
        assertNull(QrCodes.decodeLuminance(plane, 10, 10, crop = QrCodes.Crop(0, 0, 0, 5)))
    }

    /** The matrix is square, includes its quiet zone, and grows with the payload. */
    @Test
    fun matrixIsSquareAndIncludesTheQuietZone() {
        val small = QrCodes.encode("MNP1:short")
        val large = QrCodes.encode(NewDeviceSession(HkdfKeyDerivation, FakeClock()).offerCode)

        assertTrue(large.size > small.size)
        // A 4-module quiet zone on each side means the outermost ring is always light.
        for (i in 0 until small.size) {
            assertEquals("row 0 col $i must be quiet zone", false, small[i, 0])
            assertEquals("row ${small.size - 1} col $i", false, small[i, small.size - 1])
            assertEquals("col 0 row $i", false, small[0, i])
            assertEquals("col ${small.size - 1} row $i", false, small[small.size - 1, i])
        }
        // The finder pattern's top-left corner sits just inside the 4-module quiet zone.
        assertTrue(small[4, 4])
    }

    // -- helpers ------------------------------------------------------------------------------

    private class Frame(val plane: ByteArray, val width: Int, val height: Int, val rowStride: Int)

    /**
     * Paint a module grid into an 8-bit luminance plane, the way a camera would see it on a screen.
     *
     * [inverted] paints light modules dark, which is what a photograph of this app's own pairing
     * screen looks like.
     */
    private fun luminanceFrame(
        matrix: QrCodes.QrMatrix,
        scale: Int,
        extraStride: Int = 0,
        inverted: Boolean = false,
    ): Frame {
        val width = matrix.size * scale
        val height = matrix.size * scale
        val rowStride = width + extraStride
        val plane = ByteArray(rowStride * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val dark = matrix[x / scale, y / scale] != inverted
                plane[y * rowStride + x] = if (dark) 0x00 else 0xFF.toByte()
            }
        }
        return Frame(plane, width, height, rowStride)
    }

    private fun decodeThrough(matrix: QrCodes.QrMatrix, inverted: Boolean = false): String? {
        val frame = luminanceFrame(matrix, scale = 4, inverted = inverted)
        val text = QrCodes.decodeLuminance(frame.plane, frame.width, frame.height, frame.rowStride)
        assertNotNull("the frame should have decoded", text)
        return text
    }
}
