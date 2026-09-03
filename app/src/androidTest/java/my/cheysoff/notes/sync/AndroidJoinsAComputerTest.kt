package my.cheysoff.notes.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import my.cheysoff.core_pairing.protocol.BundleOutcome
import my.cheysoff.core_pairing.protocol.CollectResult
import my.cheysoff.core_pairing.protocol.DepositResult
import my.cheysoff.core_pairing.protocol.HkdfKeyDerivation
import my.cheysoff.core_pairing.protocol.HttpRendezvousClient
import my.cheysoff.core_pairing.protocol.InviteOutcome
import my.cheysoff.core_pairing.protocol.JoiningDeviceSession
import my.cheysoff.core_pairing.protocol.MonotonicClock
import my.cheysoff.core_pairing.protocol.PairingConfig
import my.cheysoff.core_pairing.protocol.RendezvousSlot
import my.cheysoff.core_pairing.protocol.RendezvousUrl
import my.cheysoff.feature_pairing.identity.DeviceIdentityKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * This device answering an invite shown by a computer that holds the account.
 *
 * ## Why instrumented rather than a JVM test
 *
 * Three of the things this direction rests on only exist on a device:
 *
 *  - the **device key is the AndroidKeyStore's**, minted by [DeviceIdentityKey] and never
 *    exportable. It is that key the computer vouches for, so a pairing that enrolled anything else
 *    would authenticate as nothing afterwards — and a JVM test cannot tell the two apart.
 *  - the request is made under `network_security_config.xml`, which permits cleartext to loopback
 *    and to nothing else. Reaching the host through `adb reverse` rather than `10.0.2.2` keeps the
 *    **shipped rule as the rule under test** instead of suspending it for the occasion.
 *  - the JCA provider doing the P-256 agreement is Android's.
 *
 * ## How it is driven
 *
 * Skipped unless the arguments are present, so an ordinary `connectedAndroidTest` costs nothing.
 * The invite is the QR payload the desktop app is displaying at that moment, which is why it is
 * passed in rather than generated: what is being demonstrated is a real invite from a real account
 * device, and the two-minute TTL means it has to be this one.
 *
 *     adb reverse tcp:8477 tcp:8477
 *     adb shell am instrument -w \
 *       -e invite 'MNP1:...' \
 *       -e class my.cheysoff.notes.sync.AndroidJoinsAComputerTest \
 *       my.cheysoff.notes.test/androidx.test.runner.AndroidJUnitRunner
 *
 * It prints the six digits, which is exactly why the collect that follows is not proof on its own:
 * the person at the laptop compares them and presses the button, and only then does anything get
 * sealed. This test waits for that, and fails if it never comes.
 *
 * What it does **not** exercise is the camera. The invite arrives as an argument rather than as
 * pixels, so the lens and `QrScanner` are the one part of the phone's path this leaves unproven.
 */
@RunWith(AndroidJUnit4::class)
class AndroidJoinsAComputerTest {

    @Test
    fun answersTheInviteWithItsKeystoreKeyAndOpensWhatComesBack() {
        val invite = InstrumentationRegistry.getArguments().getString("invite")
        assumeNotNull(invite)

        // The real Keystore key. `ensureProvisioned` is what the pairing screen calls, so this is
        // the same key a real pairing would offer, and its private half never leaves the Keystore.
        val devicePublicKey = DeviceIdentityKey().ensureProvisioned()
        assertEquals("a SEC1 uncompressed P-256 point", 65, devicePublicKey.size)

        val phone = JoiningDeviceSession(
            keyDerivation = HkdfKeyDerivation,
            clock = MonotonicClock { android.os.SystemClock.elapsedRealtime() },
            devicePublicKey = devicePublicKey,
        )
        val accepted = phone.onScanned(invite!!) as? InviteOutcome.Accepted
            ?: error("this device refused the invite: ${phone.onScanned(invite)}")

        val server = RendezvousUrl.parse(accepted.server.url)
            ?: error("the invite named an address this cannot use: ${accepted.server.url}")
        println("server named in the invite: ${server.base}")
        println("SAS this device shows:     ${accepted.sas}")

        val client = HttpRendezvousClient(server)
        val deposit = client.deposit(phone.sid!!, RendezvousSlot.REPLY, accepted.replyCode)
        assertTrue("the server refused the reply: $deposit", deposit is DepositResult.Deposited)

        println("waiting for the laptop's confirmation...")
        val deadline = System.currentTimeMillis() + WAIT_MILLIS
        var sealCode: String? = null
        while (System.currentTimeMillis() < deadline && sealCode == null) {
            when (val result = client.collect(phone.sid!!, RendezvousSlot.BUNDLE)) {
                is CollectResult.Collected -> sealCode = result.sealCode
                is CollectResult.Pending, is CollectResult.Unreachable -> Thread.sleep(1_000)
                is CollectResult.Unusable -> error("the server answered unusably: ${result.detail}")
            }
        }
        val code = sealCode ?: error("nothing arrived within ${WAIT_MILLIS / 1000}s")

        val opened = phone.onBundle(code)
        assertTrue(
            "the bundle did not open: ${(opened as? BundleOutcome.Rejected)?.failure}",
            opened is BundleOutcome.Opened,
        )
        opened as BundleOutcome.Opened
        assertEquals("an ARK is 32 bytes", 32, opened.bundle.ark.size)

        val config = PairingConfig.decode(opened.bundle.config)
        assertNotNull("the pairing named no server", config)
        assertEquals(server.base, config!!.serverUrl)
        // The proof that the computer vouched for THIS device: the id only exists because the
        // server accepted an `authorize` carrying the key printed below.
        assertNotNull("the computer did not enrol this device", config.deviceId)

        println("accountId:               ${opened.bundle.accountId}")
        println("deviceId assigned:       ${config.deviceId}")
        println("keystore key enrolled:   ${devicePublicKey.joinToString("") { "%02x".format(it) }}")
    }

    private companion object {
        /** Long enough for a person to look at two screens and press a button. */
        const val WAIT_MILLIS = 100_000L
    }
}
