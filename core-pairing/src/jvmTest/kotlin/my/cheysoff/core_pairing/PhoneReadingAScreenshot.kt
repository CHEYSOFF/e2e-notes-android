package my.cheysoff.core_pairing

import my.cheysoff.core_pairing.protocol.AccountBundle
import my.cheysoff.core_pairing.protocol.AccountDeviceSession
import my.cheysoff.core_pairing.protocol.DepositResult
import my.cheysoff.core_pairing.protocol.HkdfKeyDerivation
import my.cheysoff.core_pairing.protocol.HttpRendezvousClient
import my.cheysoff.core_pairing.protocol.MonotonicClock
import my.cheysoff.core_pairing.protocol.OfferOutcome
import my.cheysoff.core_pairing.protocol.RendezvousUrl
import my.cheysoff.core_pairing.qr.QrCodes
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File
import java.security.SecureRandom
import javax.imageio.ImageIO

/**
 * A developer fixture, not a test of anything: **the phone half of a pairing, reading a real
 * screenshot of the real desktop app.**
 *
 * It exists because the alternative evidence — handing the offer string from one object to another
 * in the same JVM — proves the protocol and proves nothing about the app. This reads the pixels the
 * desktop actually drew, through the same [QrCodes.decodeLuminance] a phone's camera analyser calls,
 * and then does everything a phone would: ECDH against the point it found, seal a real ARK, and POST
 * the result to the real server. What it skips is the lens.
 *
 * ```
 * ./gradlew :core-pairing:jvmTest --tests '*PhoneReadingAScreenshot*' \
 *     -Dmanana.qrScreenshot=C:\path\to\shot.png -Dmanana.pairingServer=http://127.0.0.1:8477
 * ```
 *
 * It prints the ARK and the SAS, which is exactly why it is a fixture and not a test: the SAS on the
 * desktop's own screen a second later is the thing being checked, and only a person looking at both
 * can check it.
 */
class PhoneReadingAScreenshot {

    @Test
    fun readTheScreenAndSend() {
        val shot = System.getProperty("manana.qrScreenshot")
        val address = System.getProperty("manana.pairingServer")
        assumeNotNull(shot)
        assumeNotNull(address)

        val offer = decodeQr(File(shot)) ?: error("no QR code found in $shot")
        println("read off the screen: $offer")

        val server = RendezvousUrl.parse(address) ?: error("bad server address: $address")
        val ark = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val phone = AccountDeviceSession(
            keyDerivation = HkdfKeyDerivation,
            clock = MonotonicClock { System.nanoTime() / 1_000_000 },
            bundle = AccountBundle(ark, "screenshot-demo", "{}"),
        )
        val accepted = phone.onScanned(offer) as? OfferOutcome.Accepted
            ?: error("the phone refused what it read: ${phone.onScanned(offer)}")

        println("server named in the code: ${phone.receivedServerHint?.url}")
        println("ARK the phone is sharing: ${ark.toHex()}")
        println("SAS the phone shows:      ${accepted.sas}")

        val result = HttpRendezvousClient(server).deposit(phone.receivedSid!!, accepted.sealCode)
        println("deposit: ${result::class.simpleName}")
        check(result is DepositResult.Deposited) { "the server refused the deposit: $result" }
    }

    /**
     * Pull the QR payload out of a PNG through the production decoder.
     *
     * The image is reduced to an 8-bit luminance plane first, which is the exact shape a
     * `YUV_420_888` camera frame's Y plane has — so this is the code path a phone runs, not a
     * convenience path added for a screenshot. `decodeLuminance` also runs an inverted second pass,
     * which is what lets it read Mañana's own light-on-dark codes.
     */
    private fun decodeQr(file: File): String? {
        val image = ImageIO.read(file) ?: error("could not read $file as an image")
        val width = image.width
        val height = image.height
        val plane = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val rgb = image.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                // Rec. 601 luma, the same weighting a camera's Y plane carries.
                plane[y * width + x] = ((299 * r + 587 * g + 114 * b) / 1000).toByte()
            }
        }
        return QrCodes.decodeLuminance(plane, width, height)
    }
}
