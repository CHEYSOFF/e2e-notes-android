package my.cheysoff.feature_notes.ui.attachment

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.cheysoff.core_domain.attachment.AttachmentLimits
import my.cheysoff.core_domain.attachment.EncodeStep
import my.cheysoff.core_domain.attachment.ImportLadder
import my.cheysoff.core_domain.attachment.PixelSize
import my.cheysoff.core_domain.model.AttachmentData

/**
 * The outcome of [ImageImporter.import].
 *
 * [Imported] carries a fully encoded [AttachmentData] -- but only the parts a decode can know.
 * [AttachmentData.id], [AttachmentData.noteId], [AttachmentData.anchor], [AttachmentData.order],
 * [AttachmentData.createdAt] and [AttachmentData.updatedAt] are placeholders the caller MUST
 * overwrite before saving: this class never sees which note is asking or where the image belongs,
 * on purpose, so it stays testable (its pure half, anyway -- see [ImportLadder]) without a note to
 * hand it.
 *
 * [TooLarge] and [NotAnImage] are the two ways an import can fail, reported separately and
 * deliberately: "the ladder ran out of rungs" and "this was never a decodable image" call for
 * different words in front of the user, and a single generic failure message is worse than none
 * (`docs/design/image-attachments.md` §7). Neither carries any part of a partial [AttachmentData] --
 * there is nothing here for a caller to accidentally save half of.
 */
sealed interface ImportResult {
    data class Imported(val attachment: AttachmentData) : ImportResult
    data object TooLarge : ImportResult
    data object NotAnImage : ImportResult
}

/**
 * Turns a picked photo into a stored [AttachmentData]: decode, downscale, flatten, encode, cap,
 * thumbnail. An interface (with [AndroidImageImporter] the only real implementation) purely so a
 * ViewModel test can substitute a fake -- [ImageDecoder] is real-device-only and cannot run under a
 * plain JVM unit test, which is also why nothing in this file is covered by one; see
 * `ImageImportOutcomeTest` for what *is* tested without a device, and Task 8's instrumented pass for
 * the encode path itself.
 */
interface ImageImporter {
    suspend fun import(uri: Uri): ImportResult
}

/**
 * Decodes a picked photo, downscales and re-encodes it under [AttachmentLimits.MAX_ATTACHMENT_BYTES]
 * by walking [ImportLadder] one rung at a time, and builds its thumbnail. [ImportLadder] and
 * [AttachmentLimits] decide every number and every step; this class only encodes.
 *
 * `ImageDecoder`, never `BitmapFactory`: it applies EXIF orientation on its own, so a sideways photo
 * -- the single most common import bug there is -- cannot happen here by construction.
 * `setAllocator(ALLOCATOR_SOFTWARE)` is required so the decoded bitmap can be drawn onto and
 * compressed; the hardware allocator's bitmaps support neither.
 *
 * Alpha is flattened onto **white** explicitly, by drawing the decoded bitmap onto an opaque
 * `ARGB_8888` bitmap pre-filled white: `Bitmap.compress(JPEG, ...)` composites a transparent source
 * onto black on its own, which would turn a screenshot's transparent margins into black bars.
 *
 * Everything here runs on [Dispatchers.IO] -- decoding reads through a `ContentResolver`, and
 * encoding is CPU work neither belongs on the main thread.
 */
class AndroidImageImporter @Inject constructor(
    @ApplicationContext context: Context,
) : ImageImporter {

    private val contentResolver: ContentResolver = context.contentResolver

    override suspend fun import(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        var cachedLongEdge = -1
        var cachedBitmap: Bitmap? = null
        var cachedSize: PixelSize? = null
        try {
            var step: EncodeStep? = ImportLadder.STEPS.first()
            while (step != null) {
                val rung = step
                if (rung.longEdge != cachedLongEdge) {
                    cachedBitmap?.recycle()
                    val decoded = decodeFittedAndFlattened(uri, rung.longEdge)
                    cachedBitmap = decoded.first
                    cachedSize = decoded.second
                    cachedLongEdge = rung.longEdge
                }
                val bitmap = cachedBitmap
                val size = cachedSize
                if (bitmap == null || size == null) return@withContext ImportResult.NotAnImage

                val bytes = compress(bitmap, rung.quality)
                if (bytes.size <= AttachmentLimits.MAX_ATTACHMENT_BYTES) {
                    val thumb = buildThumbnail(uri)
                    return@withContext ImportResult.Imported(
                        AttachmentData(
                            id = UUID.randomUUID().toString(),
                            noteId = "",
                            anchor = 0,
                            order = 0,
                            mimeType = AttachmentLimits.MIME_JPEG,
                            width = size.width,
                            height = size.height,
                            bytes = bytes,
                            thumbWidth = thumb.size.width,
                            thumbHeight = thumb.size.height,
                            thumbBytes = thumb.bytes,
                            createdAt = 0L,
                            updatedAt = 0L,
                        )
                    )
                }
                step = ImportLadder.next(rung)
            }
            ImportResult.TooLarge
        } catch (e: IOException) {
            // Covers android.graphics.ImageDecoder.DecodeException (an IOException subtype): a
            // source that will not open, or bytes that never were an image.
            ImportResult.NotAnImage
        } catch (e: SecurityException) {
            // A revoked/expired content-URI grant -- the source will not open either way.
            ImportResult.NotAnImage
        } finally {
            cachedBitmap?.recycle()
        }
    }

    /**
     * The thumbnail encode. Never fails the import: a large thumbnail is a slow rail, not a broken
     * attachment, so quality is dropped one rung at a time (mirroring [ImportLadder]'s own
     * quality-first shape) until [AttachmentLimits.MAX_THUMB_BYTES] is met or the quality floor is
     * reached -- at which point the best attempt so far is used regardless of size.
     */
    private fun buildThumbnail(uri: Uri): ThumbResult {
        val (bitmap, size) = decodeFittedAndFlattened(uri, AttachmentLimits.THUMB_LONG_EDGE)
        try {
            var quality = AttachmentLimits.THUMB_QUALITY
            var bytes = compress(bitmap, quality)
            while (bytes.size > AttachmentLimits.MAX_THUMB_BYTES && quality > MIN_THUMB_QUALITY) {
                quality = (quality - THUMB_QUALITY_STEP).coerceAtLeast(MIN_THUMB_QUALITY)
                bytes = compress(bitmap, quality)
            }
            return ThumbResult(size, bytes)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Decodes [uri] fitted inside [longEdge] (per [ImportLadder.fit], read from the header
     * `onHeaderDecoded` reports) and flattened onto an opaque white bitmap. A fresh
     * [ImageDecoder.Source] every call: a `Source` built over a `ContentResolver` `Uri` may be
     * decoded more than once, but recreating it here keeps every call site's lifetime obvious rather
     * than relying on that.
     */
    private fun decodeFittedAndFlattened(uri: Uri, longEdge: Int): Pair<Bitmap, PixelSize> {
        val source = ImageDecoder.createSource(contentResolver, uri)
        var target = PixelSize(0, 0)
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            target = ImportLadder.fit(info.size.width, info.size.height, longEdge)
            decoder.setTargetSize(target.width, target.height)
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
        }
        val flattened = Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
        Canvas(flattened).apply {
            drawColor(Color.WHITE)
            drawBitmap(decoded, 0f, 0f, null)
        }
        decoded.recycle()
        return flattened to target
    }

    private fun compress(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    private data class ThumbResult(val size: PixelSize, val bytes: ByteArray)

    private companion object {
        const val THUMB_QUALITY_STEP = 10
        const val MIN_THUMB_QUALITY = 10
    }
}
