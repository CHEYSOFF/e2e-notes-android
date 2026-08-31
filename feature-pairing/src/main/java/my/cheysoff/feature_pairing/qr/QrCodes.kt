package my.cheysoff.feature_pairing.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * QR encoding and decoding, over `com.google.zxing:core` only.
 *
 * `core` is pure Java with no Android dependency whatsoever — no `Bitmap`, no `Activity`, no
 * resources — which is why both halves of this file are ordinary JVM code that unit tests can
 * exercise directly, including a full encode-to-decode round trip through a synthesised camera
 * frame. That is deliberate and it is the reason `zxing-android-embedded` is not used: it would
 * bring a whole capture Activity and its manifest, and it would move this logic somewhere no unit
 * test can reach.
 *
 * ML Kit's barcode scanner is likewise not used. It is roughly 2.5 MB and a Google Play Services
 * dependency, in an application whose entire premise is that it talks to nobody.
 */
object QrCodes {

    /**
     * Error correction level for the codes this app *renders*.
     *
     * M (~15% recoverable) rather than L or Q. L makes the symbol smallest but is the level that
     * struggles when the "paper" is a phone screen photographed by another phone — moiré from the
     * pixel grid, glare, and a rolling shutter all eat modules. Q/H would be more robust still, but
     * they grow the symbol enough at these payload sizes to force a denser module grid on a small
     * screen, which loses more than the extra redundancy gains.
     */
    private val ERROR_CORRECTION = ErrorCorrectionLevel.M

    /**
     * Levels [encode] falls back through when the preferred one produces a symbol that this app's
     * own reader cannot decode. Measured, not theoretical: about **0.6% of pairing payloads**
     * (6 in 969, and 8 in 887 on a second run) encode at level M into a symbol that zxing then
     * fails to read back — at every scale from 3 to 8, under both HybridBinarizer and
     * GlobalHistogramBinarizer, with and without extra quiet space around the code. It is a
     * property of the symbol, not of how it is photographed, so more frames never rescue it: the
     * QR on screen is simply unreadable and the user waits forever and gives up.
     *
     * Every one of those payloads encodes cleanly at L, Q and H (8/8 in the sample), so trying
     * another level is a complete fix rather than a mitigation. M stays first because it is the
     * right trade-off for a screen photographed by another screen; the rest are ordered by how
     * little they cost after that.
     */
    private val ERROR_CORRECTION_FALLBACKS = listOf(
        ErrorCorrectionLevel.Q,
        ErrorCorrectionLevel.L,
        ErrorCorrectionLevel.H,
    )

    /**
     * Quiet-zone width in modules. The QR specification requires 4; zxing's writer defaults to 4
     * as well, and it is spelled out here because the renderer needs to know the matrix already
     * contains it and must not add its own margin on top.
     */
    private const val QUIET_ZONE_MODULES = 4

    /**
     * A rendered QR code as a square grid of modules.
     *
     * `true` is a dark module. [size] is the side length in modules and already includes the quiet
     * zone, so a renderer scales this to pixels and draws nothing extra.
     */
    class QrMatrix(val size: Int, private val dark: BooleanArray) {
        init {
            require(dark.size == size * size) { "matrix is not $size x $size" }
        }

        operator fun get(x: Int, y: Int): Boolean = dark[y * size + x]
    }

    /**
     * Encode [text] as a QR symbol.
     *
     * The symbol version (and therefore [QrMatrix.size]) is chosen by zxing from the payload
     * length; nothing here pins it, because a pinned version would either waste modules on a short
     * payload or fail outright on a long one, and QR2's length varies with the account id and the
     * config blob inside the seal.
     */
    fun encode(text: String): QrMatrix {
        // Asking for 0 x 0 tells QRCodeWriter "no minimum size" -- it then returns the natural
        // module grid for the chosen version rather than an integer-scaled-up copy of it. Scaling
        // is the renderer's job, and doing it here would throw away the ability to draw crisp
        // modules at whatever size the screen actually offers.
        for (level in listOf(ERROR_CORRECTION) + ERROR_CORRECTION_FALLBACKS) {
            val candidate = encodeAt(text, level)
            // Read our own symbol back before showing it to a camera. See
            // ERROR_CORRECTION_FALLBACKS: a small fraction of payloads produce a symbol zxing
            // cannot decode at any scale, and this is the only place that can notice, because by
            // the time it reaches the screen there is nothing left to vary.
            if (VERIFY_SCALES.all { decodeMatrix(candidate, it) == text }) return candidate
        }
        // Every level failed. Astronomically unlikely on the measured rates, and still better to
        // fail loudly here than to render a code the user will hold a phone at until they give up.
        error("could not encode a QR symbol that reads back, at any error-correction level")
    }

    private fun encodeAt(text: String, level: ErrorCorrectionLevel): QrMatrix {
        val hints = mapOf<EncodeHintType, Any>(
            EncodeHintType.ERROR_CORRECTION to level,
            EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
            EncodeHintType.CHARACTER_SET to "ISO-8859-1",
        )
        val matrix: BitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val size = matrix.width
        check(matrix.height == size) { "a QR symbol is square; got ${matrix.width}x${matrix.height}" }
        val dark = BooleanArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                dark[y * size + x] = matrix.get(x, y)
            }
        }
        return QrMatrix(size, dark)
    }

    /**
     * Render [matrix] the way the screen does and read it back through the same decoder the camera
     * analyser uses, so the check exercises the real path rather than zxing's internal state.
     *
     * Rendered at [scale] pixels per module, never 1:1 — a one-pixel-per-module image defeats
     * zxing's detector for almost every symbol, so a 1:1 check would turn a 0.6% failure into a
     * total one. That was tried; it rejected nearly everything.
     */
    private fun decodeMatrix(matrix: QrMatrix, scale: Int): String? {
        val size = matrix.size
        val width = size * scale
        val plane = ByteArray(width * width)
        for (y in 0 until width) {
            for (x in 0 until width) {
                plane[y * width + x] = if (matrix[x / scale, y / scale]) 0x00 else 0xFF.toByte()
            }
        }
        return decodeLuminance(plane, width, width, width)
    }

    /**
     * Pixels per module the self-check renders at. **All** of them must read back, because a
     * symbol that decodes at one scale can genuinely fail at another — measured: payloads that
     * read fine at 3 px/module failed at 4 and above. A camera never gives one fixed scale, so
     * checking a single one would ship codes that die at the distance the user happens to hold
     * the phone. Three renders per pairing code, once, is a price worth paying for that.
     */
    private val VERIFY_SCALES = intArrayOf(3, 4, 6)

    /**
     * Try to read a QR code out of one camera frame's **luminance plane**.
     *
     * The Y plane of a YUV_420_888 `ImageProxy` is already an 8-bit greyscale image, which is
     * exactly what zxing's binarizer wants — so nothing here converts a colour space, allocates a
     * `Bitmap`, or copies more than the plane itself. On a device whose analyser emits a padded
     * plane, [rowStride] is larger than [width]; `PlanarYUVLuminanceSource` handles that natively
     * and it must not be "fixed" by repacking the array.
     *
     * @param yPlane the luminance plane, at least `rowStride * height` bytes.
     * @param rowStride bytes per row in [yPlane], which is `>= width`.
     * @param crop the sub-rectangle to search, in pixels, or null for the whole frame. Passing the
     *   on-screen viewfinder rectangle both speeds the search up and stops the scanner from
     *   silently reading a code the user cannot see.
     * @return the decoded text, or null if this frame contains no readable QR code. A frame without
     *   a code is the *normal* case — most frames are motion blur — so this returns null rather
     *   than throwing.
     */
    fun decodeLuminance(
        yPlane: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int = width,
        crop: Crop? = null,
    ): String? {
        if (width <= 0 || height <= 0) return null
        if (rowStride < width) return null
        if (yPlane.size < rowStride * height) return null

        val area = crop ?: Crop(0, 0, width, height)
        if (area.width <= 0 || area.height <= 0) return null
        if (area.left < 0 || area.top < 0) return null
        if (area.left + area.width > width || area.top + area.height > height) return null

        val source: LuminanceSource = PlanarYUVLuminanceSource(
            yPlane,
            rowStride,
            height,
            area.left,
            area.top,
            area.width,
            area.height,
            /* reverseHorizontal = */ false,
        )
        return readQr(source)
    }

    /** A pixel rectangle inside a camera frame. */
    data class Crop(val left: Int, val top: Int, val width: Int, val height: Int)

    /**
     * Run the QR reader over one luminance source, twice if necessary.
     *
     * The second attempt inverts the image. Inverted (light-on-dark) QR codes are exactly what a
     * phone photographing *this app* produces — every pairing screen in Mañana is white modules on
     * a black card, because a black app that suddenly shows a white sheet is both jarring and, at
     * night, blinding. zxing's `QRCodeReader` does not try inversion on its own.
     *
     * A `QRCodeReader` is created per call rather than shared: it is stateful across `reset()` and
     * this is called from the camera's analyser executor.
     */
    private fun readQr(source: LuminanceSource): String? {
        val hints = mapOf<DecodeHintType, Any>(
            // Do the slow, thorough pass. Frames arrive continuously and a missed code just means
            // waiting for the next one, but the user is holding two phones up to each other and
            // every extra second of that is felt.
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        )
        attempt(source, hints)?.let { return it }
        return attempt(source.invert(), hints)
    }

    private fun attempt(source: LuminanceSource, hints: Map<DecodeHintType, Any>): String? = try {
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints)?.text
    } catch (e: NotFoundException) {
        // No code in this frame. The overwhelmingly common outcome, and not an error.
        null
    } catch (e: ReaderException) {
        // Found something QR-shaped and could not read it: checksum or format error, i.e. a
        // half-occluded or motion-blurred symbol. Also normal; the next frame gets another go.
        null
    } catch (e: ArrayIndexOutOfBoundsException) {
        // zxing's binarizer has historically thrown this on pathological frames rather than
        // returning NotFoundException. Swallowed for the same reason: it is a frame, not a fault.
        null
    }
}
