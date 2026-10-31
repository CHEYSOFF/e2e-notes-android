package my.cheysoff.desktop.ui.attachment

import my.cheysoff.core_domain.attachment.AttachmentLimits
import my.cheysoff.core_domain.attachment.ImportLadder
import java.awt.Color
import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopImageImporterTest {

    @Test
    fun `a large noisy image is downscaled and encoded under the phone's caps`() {
        // Noise is the worst case for JPEG, so this walks down the ladder rather than stopping at
        // the first rung.
        val random = Random(42)
        val source = BufferedImage(4000, 3000, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until source.height) for (x in 0 until source.width) source.setRGB(x, y, random.nextInt())

        val result = DesktopImageImporter.import(source) as DesktopImportResult.Imported
        val a = result.attachment

        assertEquals(AttachmentLimits.MIME_JPEG, a.mimeType)
        assertTrue(a.bytes.size <= AttachmentLimits.MAX_ATTACHMENT_BYTES)
        assertTrue(maxOf(a.width, a.height) <= ImportLadder.MAX_LONG_EDGE)
        assertEquals(4f / 3f, a.width.toFloat() / a.height, 0.01f)
        assertEquals(AttachmentLimits.THUMB_LONG_EDGE, maxOf(a.thumbWidth, a.thumbHeight))
        val decoded = ImageIO.read(ByteArrayInputStream(a.bytes))
        assertEquals(a.width, decoded.width)
        assertEquals(a.height, decoded.height)
    }

    @Test
    fun `a small image keeps its size and transparency becomes white`() {
        val source = BufferedImage(40, 20, BufferedImage.TYPE_INT_ARGB) // fully transparent

        val a = (DesktopImageImporter.import(source) as DesktopImportResult.Imported).attachment

        assertEquals(40, a.width)
        assertEquals(20, a.height)
        val pixel = Color(ImageIO.read(ByteArrayInputStream(a.bytes)).getRGB(20, 10))
        assertTrue("expected near-white, was $pixel", pixel.red > 245 && pixel.green > 245 && pixel.blue > 245)
    }

    @Test
    fun `clipboard text wins over the bitmap rendering Word and browsers put beside it`() {
        val image = BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB)
        assertNull(imageFrom(clip(text = "hello", image = image)))
        assertNotNull(imageFrom(clip(text = null, image = image)))
        assertNotNull(imageFrom(clip(text = "  ", image = image)))
        assertNull(imageFrom(clip(text = "hello", image = null)))
    }

    private fun clip(text: String?, image: Image?) = object : Transferable {
        private val flavors = listOfNotNull(
            DataFlavor.stringFlavor.takeIf { text != null },
            DataFlavor.imageFlavor.takeIf { image != null },
        )

        override fun getTransferDataFlavors() = flavors.toTypedArray()
        override fun isDataFlavorSupported(flavor: DataFlavor) = flavor in flavors
        override fun getTransferData(flavor: DataFlavor): Any = when (flavor) {
            DataFlavor.stringFlavor -> text!!
            else -> image!!
        }
    }
}
