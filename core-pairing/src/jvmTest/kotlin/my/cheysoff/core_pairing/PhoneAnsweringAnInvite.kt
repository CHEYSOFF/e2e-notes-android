package my.cheysoff.core_pairing

import my.cheysoff.core_pairing.protocol.BundleOutcome
import my.cheysoff.core_pairing.protocol.CollectResult
import my.cheysoff.core_pairing.protocol.DepositResult
import my.cheysoff.core_pairing.protocol.HkdfKeyDerivation
import my.cheysoff.core_pairing.protocol.HttpRendezvousClient
import my.cheysoff.core_pairing.protocol.InviteOutcome
import my.cheysoff.core_pairing.protocol.JoiningDeviceSession
import my.cheysoff.core_pairing.protocol.MonotonicClock
import my.cheysoff.core_pairing.protocol.P256
import my.cheysoff.core_pairing.protocol.PairingConfig
import my.cheysoff.core_pairing.protocol.RendezvousSlot
import my.cheysoff.core_pairing.protocol.RendezvousUrl
import my.cheysoff.core_pairing.qr.QrCodes
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File
import java.security.interfaces.ECPublicKey
import javax.imageio.ImageIO

/**
 * A developer fixture, not a test of anything: **the phone half of an invite, reading a real
 * screenshot of the real desktop app.**
 *
 * The mirror of [PhoneReadingAScreenshot], for the direction where the computer holds the account.
 * It exists for the same reason: handing an invite string from one object to another in the same
 * JVM proves the protocol and proves nothing about the app. This reads the pixels the desktop
 * actually drew, through the same [QrCodes.decodeLuminance] a phone's camera analyser calls, then
 * does everything a phone would — agree the secret, print the six digits, deposit its reply to the
 * real server, wait for the person at the laptop to confirm, collect the bundle and open it. What
 * it skips is the lens.
 *
 * ```
 * ./gradlew :core-pairing:jvmTest --tests '*PhoneAnsweringAnInvite*' \
 *     -Dmanana.qrScreenshot=C:\path\to\shot.png
 * ```
 *
 * It prints the SAS and the ARK, which is exactly why it is a fixture and not a test: the digits on
 * the desktop's own screen are the thing being checked, and only a person looking at both can check
 * them. The server address is not passed in — it comes out of the invite, which is the point.
 */
class PhoneAnsweringAnInvite {

    @Test
    fun readTheScreenAnswerAndCollect() {
        val shot = System.getProperty("manana.qrScreenshot")
        assumeNotNull(shot)

        val invite = decodeQr(File(shot)) ?: error("no QR code found in $shot")
        println("read off the screen: $invite")

        // A stand-in for the AndroidKeyStore key. On a real phone this is non-exportable; here it
        // only has to be a real point, because the computer runs it through an on-curve check
        // before it will vouch for it.
        val deviceKey = P256.generateKeyPair()
        val devicePublicKey = P256.encodePublicKey(deviceKey.public as ECPublicKey)

        val phone = JoiningDeviceSession(
            keyDerivation = HkdfKeyDerivation,
            clock = MonotonicClock { System.nanoTime() / 1_000_000 },
            devicePublicKey = devicePublicKey,
        )
        val accepted = phone.onScanned(invite) as? InviteOutcome.Accepted
            ?: error("the phone refused what it read: ${phone.onScanned(invite)}")

        val server = RendezvousUrl.parse(accepted.server.url)
            ?: error("the invite named an address this cannot use: ${accepted.server.url}")
        println("server named in the code: ${server.base}")
        println("SAS the phone shows:      ${accepted.sas}")

        val client = HttpRendezvousClient(server)
        val deposit = client.deposit(phone.sid!!, RendezvousSlot.REPLY, accepted.replyCode)
        println("reply deposit: ${deposit::class.simpleName}")
        check(deposit is DepositResult.Deposited) { "the server refused the reply: $deposit" }

        println("waiting for the laptop's confirmation...")
        val deadline = System.currentTimeMillis() + WAIT_MILLIS
        var sealCode: String? = null
        while (System.currentTimeMillis() < deadline && sealCode == null) {
            when (val result = client.collect(phone.sid!!, RendezvousSlot.BUNDLE)) {
                is CollectResult.Collected -> sealCode = result.sealCode
                is CollectResult.Pending -> Thread.sleep(1_000)
                is CollectResult.Unreachable -> Thread.sleep(1_000)
                is CollectResult.Unusable -> error("the server answered unusably: ${result.detail}")
            }
        }
        val code = sealCode ?: error("nothing arrived within ${WAIT_MILLIS / 1000}s")

        val opened = phone.onBundle(code)
        check(opened is BundleOutcome.Opened) {
            "the bundle did not open: ${(opened as BundleOutcome.Rejected).failure}"
        }
        println("ARK the phone received:   ${opened.bundle.ark.toHex()}")
        println("accountId:                ${opened.bundle.accountId}")
        val config = PairingConfig.decode(opened.bundle.config)
        println("server from the seal:     ${config?.serverUrl}")
        println("deviceId the laptop got:  ${config?.deviceId}")
        println("this phone's device key:  ${devicePublicKey.toHex()}")
        check(config?.deviceId != null) { "the laptop did not enrol this device" }
    }

    private fun decodeQr(file: File): String? {
        val image = ImageIO.read(file) ?: error("could not read $file as an image")
        val width = image.width
        val height = image.height
        val luminance = ByteArray(width * height)
        var index = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val rgb = image.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                luminance[index++] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
            }
        }
        return QrCodes.decodeLuminance(luminance, width, height)
    }

    private companion object {
        /** Long enough for a person to look at two screens and press a button. */
        const val WAIT_MILLIS = 100_000L
    }
}
