package my.cheysoff.desktop.ui.attachment

import my.cheysoff.core_domain.attachment.AttachmentLimits
import my.cheysoff.core_domain.attachment.ImportLadder
import my.cheysoff.core_domain.attachment.PixelSize
import my.cheysoff.core_domain.model.AttachmentData
import java.awt.Color
import java.awt.Image
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** The outcome of [DesktopImageImporter.import]; the desktop twin of the phone's `ImportResult`. */
sealed interface DesktopImportResult {
    data class Imported(val attachment: AttachmentData) : DesktopImportResult
    data object TooLarge : DesktopImportResult
    data object NotAnImage : DesktopImportResult
}

/**
 * Turns a pasted image into an [AttachmentData] the phone can open: flatten, downscale, encode as
 * JPEG, cap, thumbnail.
 *
 * Every number comes from [ImportLadder] and [AttachmentLimits] in `:core-domain`, the same tables
 * the phone's `AndroidImageImporter` walks, so a photo pasted here is indistinguishable on the wire
 * from one picked on the phone. Only the encoder differs (ImageIO instead of `Bitmap.compress`).
 *
 * Like the phone's importer, the returned attachment's identity, note, anchor, order and timestamps
 * are placeholders the caller must overwrite -- this class never sees which note is asking.
 *
 * Alpha is flattened onto **white**, for the reason the phone's importer gives: a JPEG encoder
 * left to composite a transparent screenshot on its own produces black margins.
 */
object DesktopImageImporter {

    fun import(image: Image): DesktopImportResult {
        val source = try {
            flatten(image)
        } catch (e: OutOfMemoryError) {
            // The same bounded exception the phone's importer makes: an enormous paste is "too
            // large", not a crash of the whole window.
            return DesktopImportResult.TooLarge
        } ?: return DesktopImportResult.NotAnImage

        var cachedEdge = -1
        var scaled: BufferedImage = source
        var step = ImportLadder.STEPS.first()
        while (true) {
            if (step.longEdge != cachedEdge) {
                scaled = scaleToFit(source, step.longEdge)
                cachedEdge = step.longEdge
            }
            val bytes = encodeJpeg(scaled, step.quality)
            if (bytes.size <= AttachmentLimits.MAX_ATTACHMENT_BYTES) {
                val thumb = encodeThumbnail(scaleToFit(source, AttachmentLimits.THUMB_LONG_EDGE))
                return DesktopImportResult.Imported(
                    AttachmentData(
                        id = "",
                        noteId = "",
                        anchor = 0,
                        order = 0,
                        mimeType = AttachmentLimits.MIME_JPEG,
                        width = scaled.width,
                        height = scaled.height,
                        bytes = bytes,
                        thumbWidth = thumb.first.width,
                        thumbHeight = thumb.first.height,
                        thumbBytes = thumb.second,
                        createdAt = 0L,
                        updatedAt = 0L,
                    ),
                )
            }
            step = ImportLadder.next(step) ?: return DesktopImportResult.TooLarge
        }
    }

    /** An opaque RGB copy of [image] on white, or null when it has no pixels to read. */
    private fun flatten(image: Image): BufferedImage? {
        val width = image.getWidth(null)
        val height = image.getHeight(null)
        if (width <= 0 || height <= 0) return null
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.color = Color.WHITE
            g.fillRect(0, 0, width, height)
            g.drawImage(image, 0, 0, null)
        } finally {
            g.dispose()
        }
        return out
    }

    /**
     * [source] fitted inside [longEdge] per [ImportLadder.fit] (never upscaled).
     *
     * Halved in steps rather than in one draw: a single bilinear pass from 4000px to 320px samples
     * so few source pixels that the thumbnail comes out aliased and noisy.
     */
    private fun scaleToFit(source: BufferedImage, longEdge: Int): BufferedImage {
        val target = ImportLadder.fit(source.width, source.height, longEdge)
        var current = source
        while (current.width != target.width || current.height != target.height) {
            val next = PixelSize(
                width = maxOf(target.width, current.width / 2),
                height = maxOf(target.height, current.height / 2),
            )
            val out = BufferedImage(next.width, next.height, BufferedImage.TYPE_INT_RGB)
            val g = out.createGraphics()
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                g.drawImage(current, 0, 0, next.width, next.height, null)
            } finally {
                g.dispose()
            }
            current = out
        }
        return current
    }

    /** The phone's thumbnail rule: drop quality a rung at a time until it fits or hits the floor. */
    private fun encodeThumbnail(thumb: BufferedImage): Pair<PixelSize, ByteArray> {
        var quality = AttachmentLimits.THUMB_QUALITY
        var bytes = encodeJpeg(thumb, quality)
        while (bytes.size > AttachmentLimits.MAX_THUMB_BYTES && quality > AttachmentLimits.MIN_THUMB_QUALITY) {
            quality = (quality - AttachmentLimits.THUMB_QUALITY_STEP).coerceAtLeast(AttachmentLimits.MIN_THUMB_QUALITY)
            bytes = encodeJpeg(thumb, quality)
        }
        return PixelSize(thumb.width, thumb.height) to bytes
    }

    private fun encodeJpeg(image: BufferedImage, quality: Int): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        try {
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                val param = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = quality / 100f
                }
                writer.write(null, IIOImage(image, null, null), param)
            }
        } finally {
            writer.dispose()
        }
        return out.toByteArray()
    }
}
