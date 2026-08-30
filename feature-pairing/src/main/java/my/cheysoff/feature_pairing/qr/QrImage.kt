package my.cheysoff.feature_pairing.qr

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color

/**
 * Turn a [QrCodes.QrMatrix] into a Compose [ImageBitmap], one pixel per module.
 *
 * One pixel per module, not one pixel per screen pixel: the bitmap is drawn scaled up with
 * `FilterQuality.None`, so a 33x33 symbol becomes 33x33 hard-edged blocks at whatever size the
 * layout gives it. Rendering at screen resolution instead would mean allocating a bitmap of a few
 * hundred kilobytes and resampling it anyway, and any smoothing at all softens module edges, which
 * is precisely what a camera on the other side of the exchange has to resolve.
 *
 * [dark] and [light] are the module colours. The default is deliberately inverted relative to a
 * printed QR — light modules on a dark ground — because every screen in this app is black and a
 * sudden white sheet is both jarring and, at night, blinding. `QrCodes.decodeLuminance` runs an
 * inverted second pass for exactly this reason, so a Mañana code is readable by this app; it is
 * also readable by most general-purpose scanners, which try inversion too.
 */
@Composable
fun rememberQrImageBitmap(
    matrix: QrCodes.QrMatrix,
    dark: Color = Color.Black,
    light: Color = Color.White,
): ImageBitmap = remember(matrix, dark, light) { qrImageBitmap(matrix, dark, light) }

/** Non-composable form, so the conversion is testable and reusable outside composition. */
fun qrImageBitmap(
    matrix: QrCodes.QrMatrix,
    dark: Color = Color.Black,
    light: Color = Color.White,
): ImageBitmap {
    val size = matrix.size
    val darkArgb = dark.toArgb()
    val lightArgb = light.toArgb()
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            // `matrix[x, y] == true` is a DARK module in QR terms. Which colour that maps to on
            // screen is the caller's choice -- see the inversion note above -- so this reads
            // "dark module gets the `dark` colour", and the *defaults* are what invert it.
            pixels[y * size + x] = if (matrix[x, y]) darkArgb else lightArgb
        }
    }
    // ARGB_8888 rather than RGB_565: a QR code is two colours, so the format costs memory and buys
    // nothing visually -- but at 33x33 pixels the whole bitmap is about 4 KB either way, and
    // 8888 is the format Compose can hand to the GPU without a conversion.
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
    return bitmap.asImageBitmap()
}
