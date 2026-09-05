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
 * thumbnail. An interface (with [AndroidImageImporter] the only real implementation) so a
 * ViewModel test can substitute a fake -- [ImageDecoder] is real-device-only and cannot run under a
 * plain JVM unit test.
 *
 * [import] takes a `String`, not an `android.net.Uri`, even though the only real implementation
 * needs a `Uri` internally. This keeps [ImportResult], [ImageImporter] and every caller of
 * `import` (`SingleNoteViewModel`, `SingleNoteIntent.ImportAttachment`) platform-free -- worth more
 * here than usual, since `core-domain` is already Kotlin Multiplatform and the desktop is a live
 * target for this codebase, and a `String` is a shape that can cross that boundary while
 * `android.net.Uri` is not. There is exactly one producer today: the photo-picker callback in
 * `SingleNoteScreen.kt`, which converts the platform `Uri` it receives with `.toString()` immediately
 * and never holds onto the `Uri` itself. `Uri.parse(uri.toString())` round-trips a content URI
 * losslessly, and the picker's temporary read grant is keyed on the URI's content rather than
 * object identity, so re-parsing here does not lose access to the picked image. The cost is type
 * safety -- a `String` parameter accepts any string, where a `Uri` parameter declares provenance --
 * paid deliberately for the platform-independence above.
 */
interface ImageImporter {
    suspend fun import(uri: String): ImportResult
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

    override suspend fun import(uri: String): ImportResult = withContext(Dispatchers.IO) {
        val contentUri = Uri.parse(uri)
        var cachedLongEdge = -1
        var cachedBitmap: Bitmap? = null
        var cachedSize: PixelSize? = null
        try {
            var step: EncodeStep? = ImportLadder.STEPS.first()
            while (step != null) {
                val rung = step
                if (rung.longEdge != cachedLongEdge) {
                    cachedBitmap?.recycle()
                    val decoded = decodeFittedAndFlattened(contentUri, rung.longEdge)
                    cachedBitmap = decoded.first
                    cachedSize = decoded.second
                    cachedLongEdge = rung.longEdge
                }
                val bitmap = cachedBitmap
                val size = cachedSize
                if (bitmap == null || size == null) return@withContext ImportResult.NotAnImage

                val bytes = compress(bitmap, rung.quality)
                if (bytes.size <= AttachmentLimits.MAX_ATTACHMENT_BYTES) {
                    // The main encode already fit under the cap by this point -- a failure past
                    // this line must never discard it. buildThumbnail opens a second, independent
                    // decode of the same source, and a grant revoked between the two decodes (or an
                    // OOM on a pathological source -- see the outer catch's own KDoc) would
                    // otherwise turn a photo that already succeeded into a reported failure. Falling
                    // back to a thumbnail scaled down from the bitmap already in hand keeps the
                    // import honest about what actually went wrong: nothing.
                    val thumb = try {
                        buildThumbnail(contentUri)
                    } catch (e: IOException) {
                        thumbnailFromBitmap(bitmap)
                    } catch (e: SecurityException) {
                        thumbnailFromBitmap(bitmap)
                    } catch (e: OutOfMemoryError) {
                        thumbnailFromBitmap(bitmap)
                    }
                    return@withContext ImportResult.Imported(
                        AttachmentData(
                            id = UUID.randomUUID().toString(),
                            noteId = "",
                            anchor = 0,
                            order = 0,
                            mimeType = AttachmentLimits.MIME_JPEG,
                            // Read off the bitmap actually produced, not the ImportLadder.fit()
                            // target requested of the decoder. They agree today because
                            // setTargetSize is honoured, but reading the bitmap costs nothing and
                            // cannot drift if that ever stops being true.
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
        } catch (e: OutOfMemoryError) {
            // Catching Error is normally wrong, and is right here for a specific, bounded reason:
            // ImageDecoder.setTargetSize only bounds peak allocation for codecs that can decode
            // scaled (libjpeg can, so an ordinary JPEG photo is safe). PNG and WebP decode at full
            // resolution first and downsample afterwards -- and PickVisualMedia(ImageOnly) admits
            // both -- so a 12000x8000 PNG screenshot allocates roughly 384 MB of ARGB_8888 before a
            // single pixel is scaled, and decodeFittedAndFlattened allocates a second full-size copy
            // on top of that for the white flatten. Left uncaught, that OOM propagates out of this
            // withContext, out of the ViewModel's launch, and into the default uncaught-exception
            // handler -- a process kill on the one code path whose entire error design exists to
            // produce a message instead. This handler runs on a background dispatcher with nothing
            // else of this import in flight, and `finally` below has already released what it can,
            // so mapping to a result here is a bounded, deliberate exception to "never catch Error".
            // TooLarge, not NotAnImage: "too large to attach" is truthful for an image too big to
            // decode; "isn't a photo" would be actively misleading.
            ImportResult.TooLarge
        } finally {
            cachedBitmap?.recycle()
        }
    }

    /**
     * The thumbnail encode, decoding [uri] a second time at [AttachmentLimits.THUMB_LONG_EDGE]
     * rather than downscaling the caller's already-fitted bitmap, so a thumbnail is never worse
     * than the sharpest crop `ImageDecoder` itself can produce at that size. On failure the caller
     * falls back to [thumbnailFromBitmap] instead of calling this again.
     */
    private fun buildThumbnail(uri: Uri): ThumbResult {
        val (bitmap, size) = decodeFittedAndFlattened(uri, AttachmentLimits.THUMB_LONG_EDGE)
        try {
            return encodeThumbnail(bitmap, size)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * The thumbnail encode's fallback: scales [source] (the main encode's own bitmap, already
     * decoded, flattened and opaque) down to thumbnail size in memory, touching neither
     * `ContentResolver` nor `ImageDecoder`. Used only when [buildThumbnail]'s own, independent
     * decode of the source fails after the main encode has already succeeded -- see the call site's
     * KDoc. Never upscales, matching [ImportLadder.fit]'s own rule.
     */
    private fun thumbnailFromBitmap(source: Bitmap): ThumbResult {
        val target = ImportLadder.fit(source.width, source.height, AttachmentLimits.THUMB_LONG_EDGE)
        val scaled = if (target.width == source.width && target.height == source.height) {
            source
        } else {
            Bitmap.createScaledBitmap(source, target.width, target.height, true)
        }
        try {
            return encodeThumbnail(scaled, PixelSize(scaled.width, scaled.height))
        } finally {
            if (scaled !== source) scaled.recycle()
        }
    }

    /**
     * Compresses [bitmap] as the thumbnail, dropping quality one rung at a time (mirroring
     * [ImportLadder]'s own quality-first shape, with its own rung size and floor in
     * [AttachmentLimits]) until [AttachmentLimits.MAX_THUMB_BYTES] is met or the floor is reached --
     * at which point the best attempt so far is used regardless of size. Never fails: a large
     * thumbnail is a slow rail, not a broken attachment.
     */
    private fun encodeThumbnail(bitmap: Bitmap, size: PixelSize): ThumbResult {
        var quality = AttachmentLimits.THUMB_QUALITY
        var bytes = compress(bitmap, quality)
        while (bytes.size > AttachmentLimits.MAX_THUMB_BYTES && quality > AttachmentLimits.MIN_THUMB_QUALITY) {
            quality = (quality - AttachmentLimits.THUMB_QUALITY_STEP).coerceAtLeast(AttachmentLimits.MIN_THUMB_QUALITY)
            bytes = compress(bitmap, quality)
        }
        return ThumbResult(size, bytes)
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
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val target = ImportLadder.fit(info.size.width, info.size.height, longEdge)
            decoder.setTargetSize(target.width, target.height)
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
        }
        val flattened = Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
        Canvas(flattened).apply {
            drawColor(Color.WHITE)
            drawBitmap(decoded, 0f, 0f, null)
        }
        decoded.recycle()
        return flattened to PixelSize(flattened.width, flattened.height)
    }

    private fun compress(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    private data class ThumbResult(val size: PixelSize, val bytes: ByteArray)
}
