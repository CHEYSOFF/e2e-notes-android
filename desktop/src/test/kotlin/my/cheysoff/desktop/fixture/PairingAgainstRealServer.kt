package my.cheysoff.desktop.fixture

import my.cheysoff.desktop.vault.DeviceKeyPair
import my.cheysoff.core_pairing.protocol.AccountBundle
import my.cheysoff.core_pairing.protocol.AccountDeviceSession
import my.cheysoff.core_pairing.protocol.CollectResult
import my.cheysoff.core_pairing.protocol.DepositResult
import my.cheysoff.core_pairing.protocol.HkdfKeyDerivation
import my.cheysoff.core_pairing.protocol.HttpRendezvousClient
import my.cheysoff.core_pairing.protocol.MonotonicClock
import my.cheysoff.core_pairing.protocol.NewDeviceRendezvous
import my.cheysoff.core_pairing.protocol.OfferOutcome
import my.cheysoff.core_pairing.protocol.PollOutcome
import my.cheysoff.core_pairing.protocol.RendezvousSlot
import my.cheysoff.core_pairing.protocol.RendezvousUrl
import my.cheysoff.desktop.keychain.NoCredentialStore
import my.cheysoff.desktop.vault.AccountOrigin
import my.cheysoff.desktop.vault.DesktopVault
import my.cheysoff.desktop.vault.SetupResult
import my.cheysoff.desktop.vault.UnlockResult
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.nio.file.Files
import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * An **end-to-end pairing against a real, running server**, over real HTTP, ending in a real vault.
 *
 * This is the evidence that the unit suites cannot give. Everything in it is production code:
 *
 *  - the new device is a real [NewDeviceRendezvous] behind a real [HttpRendezvousClient] — the same
 *    two objects the desktop's pairing screen builds;
 *  - the phone is a real [AccountDeviceSession] with a real 32-byte ARK, doing a real P-256 ECDH
 *    against the point it read out of the real QR1 payload, and sealing with real AES-256-GCM;
 *  - the server is the one in `server/`, started separately, storing and returning the blob;
 *  - the vault is a real [DesktopVault], set up through the `AccountOrigin.PAIRED` seam and then
 *    **reopened from disk** to prove the ARK that came off the wire is the one the passphrase
 *    unwraps.
 *
 * The only thing simulated is the person: nobody points a camera, so the offer payload is handed to
 * the phone session directly instead of through a lens. Every byte either side of that is real.
 *
 * Opt-in, like `DemoVaultProvisioner`, because it needs a server:
 *
 * ```
 * cd server && ./gradlew installDist && MANANA_DB=:memory: build/install/manana-sync-server/bin/manana-sync-server
 * ./gradlew :desktop:test --tests '*PairingAgainstRealServer*' -Dmanana.pairingServer=http://127.0.0.1:8080
 * ```
 */
class PairingAgainstRealServer {

    @Test
    fun aRealArkTravelsThroughARealServerIntoARealVault() {
        val address = System.getProperty("manana.pairingServer")
        assumeNotNull(address)
        val server = RendezvousUrl.parse(address)
            ?: error("manana.pairingServer is not a usable address: $address")

        // -- the laptop ------------------------------------------------------------------------
        // A real device key, because QR1 now carries its public half and the phone vouches for
        // exactly that key. Generating one here rather than passing a stub keeps the fixture
        // exercising the same bytes the enrolment path will sign over.
        val deviceKey = DeviceKeyPair.generate()
        val desktop = NewDeviceRendezvous(
            client = HttpRendezvousClient(server),
            keyDerivation = HkdfKeyDerivation,
            clock = MonotonicClock { System.nanoTime() / 1_000_000 },
            server = server,
            devicePublicKey = deviceKey.publicKeySec1,
        )
        println("QR1: ${desktop.offerCode}")

        // Nothing has been sent yet, so the drop is empty and polling says so. Asserted rather than
        // assumed: a `Paired` here would mean the laptop collected a blob nobody put there.
        assertTrue("the drop should be empty before the send", desktop.poll() is PollOutcome.Waiting)

        // -- the phone -------------------------------------------------------------------------
        val ark = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val phone = AccountDeviceSession(
            keyDerivation = HkdfKeyDerivation,
            clock = MonotonicClock { System.nanoTime() / 1_000_000 },
            ark = ark,
            accountId = "e2e-account",
        )
        val accepted = phone.onScanned(desktop.offerCode) as? OfferOutcome.Accepted
            ?: error("the phone refused the laptop's QR1")

        // QR1 carried the address, which is what the phone would show the user before sending.
        assertEquals(server.base, phone.receivedServerHint?.url)

        val deposit = HttpRendezvousClient(server)
            // Sealing is a separate step from accepting: a device must not be able to hand over
            // the account root key before its user has had a chance to compare the SAS digits.
            .deposit(phone.receivedSid!!, RendezvousSlot.BUNDLE, phone.seal("""{"server":"$address"}""")!!)
        println("deposit: $deposit")
        assertTrue("the server refused the deposit: $deposit", deposit is DepositResult.Deposited)

        // -- the laptop collects ---------------------------------------------------------------
        val outcome = desktop.poll()
        assertTrue("the laptop did not open the bundle: $outcome", outcome is PollOutcome.Paired)
        outcome as PollOutcome.Paired

        assertArrayEquals("the ARK changed in transit", ark, outcome.bundle.ark)
        assertEquals("e2e-account", outcome.bundle.accountId)
        // The check a person performs. Both sides derived it independently; the laptop could only
        // produce this number by having opened the seal.
        assertEquals(accepted.sas, outcome.sas)
        println("SAS on both sides: ${outcome.sas}")

        // Single use: the server has nothing left, so a second collect finds nothing. (It is a new
        // session because the first one closed itself on success -- which is also asserted.)
        val second = HttpRendezvousClient(server).collect(phone.receivedSid!!, RendezvousSlot.BUNDLE)
        assertTrue(
            "the server kept the blob after it was collected: $second",
            second is CollectResult.Pending,
        )

        // -- into a real vault -----------------------------------------------------------------
        val directory = Files.createTempDirectory("manana-e2e")
        val vault = DesktopVault(directory = directory, credentialStore = NoCredentialStore)
        val created = vault.setUp(
            PASSPHRASE.toCharArray(),
            AccountOrigin.PAIRED,
            outcome.bundle.ark,
        )
        assertTrue("the vault refused the paired ARK: $created", created is SetupResult.Created)
        (created as SetupResult.Created).session.close()

        // Reopened from disk, because "setUp returned a session" only proves it was in memory.
        val reopened = vault.unlock(PASSPHRASE.toCharArray())
        assertTrue("the paired vault did not reopen: $reopened", reopened is UnlockResult.Unlocked)
        (reopened as UnlockResult.Unlocked).session.use {
            assertArrayEquals("the vault holds a different ARK than the phone", ark, it.ark)
        }
        println("vault at $directory holds the phone's ARK")
    }

    private companion object {
        const val PASSPHRASE = "end-to-end-passphrase"
    }
}
