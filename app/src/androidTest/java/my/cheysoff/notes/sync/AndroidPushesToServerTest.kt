package my.cheysoff.notes.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import my.cheysoff.core_crypto.sync.AccountRootKey
import my.cheysoff.core_crypto.sync.Base64Url
import my.cheysoff.core_crypto.sync.DeviceLabelCipher
import my.cheysoff.core_data.data.RoomNotesRepository
import my.cheysoff.core_data.data.local.NoteDatabase
import my.cheysoff.core_data.data.sync.SyncStoreFactory
import my.cheysoff.core_data.data.sync.SyncClock
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_sync_codec.EnvelopeSyncTransport
import my.cheysoff.core_sync_codec.RecordCodec
import my.cheysoff.core_sync_engine.ClockObserver
import my.cheysoff.core_sync_engine.SyncEngine
import my.cheysoff.core_sync_engine.SyncOutcome
import my.cheysoff.core_sync_net.ClaimOutcome
import my.cheysoff.core_sync_net.DeviceCredentials
import my.cheysoff.core_sync_net.SyncHttpClient
import my.cheysoff.core_sync_net.auth.DeviceLabelSealer
import my.cheysoff.core_sync_net.auth.DeviceSigner
import my.cheysoff.core_sync_net.http.ServerEndpoint
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/**
 * The Android half of an Android-to-desktop crossing: this device claims an account, vouches for a
 * laptop key, writes a note through the app's own repository, and pushes it to a real server.
 *
 * ## Why instrumented rather than a JVM test
 *
 * Everything deciding what bytes reach the server is Android's here: RoomNotesRepository stamps the
 * clocks, RoomSyncStore decides what is dirty, RecordCodec seals. JVM tests exercise those same
 * classes — but the claim being made is that a note written BY THE ANDROID APP is readable by the
 * desktop app, and that is worth making on the platform whose database, JCA provider and
 * record-writing path are the ones in question.
 *
 * ## How it is driven
 *
 * Skipped unless the orchestration arguments are present, so an ordinary connectedAndroidTest costs
 * nothing. It is one half of a pair: DesktopPullsAndroidsNoteTest is the other, and a shared ARK is
 * what makes the two one account.
 *
 *     adb reverse tcp:8479 tcp:8479
 *     adb shell am instrument -w \
 *       -e ark HEX -e desktopPub HEX -e server http://127.0.0.1:8479 \
 *       -e class my.cheysoff.notes.sync.AndroidPushesToServerTest \
 *       my.cheysoff.notes.test/androidx.test.runner.AndroidJUnitRunner
 *
 * The reverse tunnel rather than 10.0.2.2, on purpose: network_security_config.xml permits cleartext
 * to loopback and nothing else, and the tunnel puts the host's server on the device's own
 * 127.0.0.1. The rule the app ships with is therefore the rule under test, rather than being
 * suspended for the occasion.
 */
@RunWith(AndroidJUnit4::class)
class AndroidPushesToServerTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun claimsVouchesWritesAndPushes() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val arkHex = args.getString("ark")
        val desktopPubHex = args.getString("desktopPub")
        val serverUrl = args.getString("server")
        assumeNotNull(arkHex)
        assumeNotNull(desktopPubHex)
        assumeNotNull(serverUrl)

        val ark = hexToBytes(arkHex!!)
        val keys = AccountRootKey.derive(ark)
        val accountId = Base64Url.encode(keys.accountId)
        val endpoint = ServerEndpoint(serverUrl!!)

        // A plain JCA key rather than the Keystore-backed one the app ships. What is under test is
        // the bytes of a record; the Keystore changes where a signature comes from, not what is
        // signed and not what is sealed.
        val deviceKey = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

        val api = SyncHttpClient.create(
            endpoint = endpoint,
            signer = JcaDeviceSigner(deviceKey),
            labelSealer = ArkLabelSealer(ark),
        )

        val claim = api.claimAccount(accountId, "android-emulator")
        assertTrue("the server refused the claim: " + claim, claim is ClaimOutcome.Claimed)
        val myDeviceId = (claim as ClaimOutcome.Claimed).deviceId

        // Vouching for the laptop, which cannot enrol itself. That is the property being exercised
        // rather than worked around.
        val laptop = api.authorizeDevice(
            accountId = accountId,
            voucherDeviceId = myDeviceId,
            newPublicKey = hexToBytes(desktopPubHex!!),
            deviceLabel = "laptop",
        )

        val dbName = "android-to-desktop-" + UUID.randomUUID()
        ctx.deleteDatabase(dbName)
        val db = Room.databaseBuilder(ctx, NoteDatabase::class.java, dbName)
            .addMigrations(*NoteDatabase.ALL_MIGRATIONS)
            .build()
        val clock = SyncClock(node = { "androiddevice" })
        val repository = RoomNotesRepository(db.noteDao, db.folderDao, db.sketchDao, db, clock)

        val noteId = UUID.randomUUID().toString()
        repository.saveNote(
            Note(
                id = noteId,
                title = "Written on Android",
                content = "typed on the phone, read on the laptop",
            )
        )

        val codec = RecordCodec(keys)
        // Built through the factory the app itself uses, rather than by hand: `createdAtOf` lives
        // there, and a second way of assembling the store is a second thing that can disagree.
        // `clock` is the same SyncClock the engine below observes remote clocks into -- sharing it
        // with the factory is exactly the wiring `DefaultSyncController` gives production, and this
        // test pushes a real record to a real server, so a store whose minted clocks were invisible
        // to the generator is exactly the hazard the parameter exists to prevent.
        val factory = SyncStoreFactory(db, clock)
        val syncStore = factory.create(accountId)
        val engine = SyncEngine(
            store = syncStore,
            transport = EnvelopeSyncTransport(
                api = api,
                credentials = DeviceCredentials(accountId, myDeviceId),
                codec = codec,
                createdAtOf = factory::createdAtOf,
            ),
            clock = ClockObserver { seen -> clock.observe(seen) },
        )

        val outcome = engine.runPass()
        assertTrue("the push pass did not complete: " + outcome, outcome is SyncOutcome.Completed)

        // Handed to the orchestrator, which pulls this file and feeds it to the desktop half.
        File(ctx.filesDir, HANDOFF).writeText(noteId + "\n" + laptop.deviceId + "\n")
        println("ANDROID_PUSHED noteId=" + noteId + " laptopDeviceId=" + laptop.deviceId)
        db.close()
    }

    private class JcaDeviceSigner(private val pair: KeyPair) : DeviceSigner {

        override fun publicKeySec1(): ByteArray {
            // SEC1 uncompressed, 0x04 then X and Y. ECPublicKey.encoded is X.509, which the server
            // refuses as invalid_public_key, so the point is written out by hand.
            val point = (pair.public as ECPublicKey).w
            val out = ByteArray(65)
            out[0] = 0x04
            writeFixed(point.affineX.toByteArray(), out, 1)
            writeFixed(point.affineY.toByteArray(), out, 33)
            return out
        }

        override fun sign(message: ByteArray): ByteArray =
            Signature.getInstance("SHA256withECDSA").run {
                initSign(pair.private)
                update(message)
                sign()
            }

        /** Drops BigInteger's sign byte and left-pads into a fixed 32-byte slot. */
        private fun writeFixed(value: ByteArray, target: ByteArray, at: Int) {
            val src = if (value.size > 32) value.copyOfRange(value.size - 32, value.size) else value
            src.copyInto(target, at + (32 - src.size))
        }
    }

    private class ArkLabelSealer(private val ark: ByteArray) : DeviceLabelSealer {

        override fun seal(devicePublicKeyB64: String, label: String): ByteArray =
            DeviceLabelCipher.seal(
                ark,
                devicePublicKeyB64,
                DeviceLabelCipher.trimToSealableLength(label),
            )

        override fun open(devicePublicKeyB64: String, sealed: ByteArray): String? =
            DeviceLabelCipher.open(ark, devicePublicKeyB64, sealed)
    }

    private companion object {

        const val HANDOFF = "android-to-desktop.txt"

        fun hexToBytes(hex: String): ByteArray =
            ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
