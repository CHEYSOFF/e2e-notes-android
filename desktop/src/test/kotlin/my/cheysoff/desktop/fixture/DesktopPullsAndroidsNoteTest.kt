package my.cheysoff.desktop.fixture

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import my.cheysoff.core_crypto.sync.AccountRootKey
import my.cheysoff.core_crypto.sync.Base64Url
import my.cheysoff.core_sync_codec.RecordCodec
import my.cheysoff.core_sync_engine.SyncOutcome
import my.cheysoff.core_sync_net.DeviceCredentials
import my.cheysoff.core_sync_net.http.ServerEndpoint
import my.cheysoff.desktop.store.RecordNotesRepository
import my.cheysoff.desktop.store.RecordStore
import my.cheysoff.desktop.sync.DesktopSyncService
import my.cheysoff.desktop.vault.DeviceKeyPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File
import java.nio.file.Paths
import java.security.SecureRandom

/**
 * The desktop half of an Android-to-desktop crossing: this laptop pulls, from a real server, a note
 * that the Android app wrote and pushed.
 *
 * `AndroidPushesToServerTest` is the other half, and a shared ARK is what makes the two halves one
 * account. Neither half can prove the thing on its own — that is the point. Two JVM stores agreeing
 * shows the engine is consistent with itself; only a record written by Room, sealed by Android's
 * codec and opened by the desktop's shows the two platforms agree.
 *
 * Two steps, because the two processes have to share key material and cannot talk:
 *
 *  1. `generatesAnAccountForTheAndroidHalf` mints the ARK and this laptop's device key and writes
 *     them to `-Dmanana.generateHandoff`. The orchestrator reads the hex out and passes it to the
 *     instrumented test, which claims the account and vouches for this key.
 *  2. `pullsTheNoteAndroidWrote` reads that file from `-Dmanana.handoff`, plus the note id and
 *     device id the Android half produced, runs a pass, and asserts the note is on this laptop's
 *     disk.
 *
 * The two steps take DIFFERENT properties on purpose. Both are selected by class name, so a single
 * property would re-run the generator during step 2 and replace the ARK underneath it -- the pull
 * then asks a server that has never heard of the account, and the failure reads as a sync bug
 * rather than as a clobbered file. It cost half an hour once; the generator now also refuses to
 * overwrite.
 *
 * Both skip without their properties, so an ordinary test run costs nothing.
 *
 * Observed, on the emulator against a real server:
 *
 *     ANDROID_PUSHED noteId=8b37b208-... laptopDeviceId=r1Fh19K8Dgmo3NJq-UN_4w
 *     pull pass: SyncOutcome$Completed
 *     CROSSED title=Written on Android
 */
class DesktopPullsAndroidsNoteTest {

    @Test
    fun generatesAnAccountForTheAndroidHalf() {
        // Gated on its OWN property, not on `manana.handoff`, and refuses to overwrite. Both halves
        // of this fixture are selected by class name, so running the pull step re-ran this one and
        // replaced the ARK underneath it -- the pull then asked a server that had never heard of
        // the account, and the failure looked like a sync bug rather than a clobbered file.
        val handoff = System.getProperty("manana.generateHandoff")
        assumeNotNull(handoff)
        require(!File(handoff!!).exists()) {
            "refusing to overwrite an existing handoff at " + handoff +
                "; delete it if you really mean to start a new account"
        }

        val ark = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val key = DeviceKeyPair.generate()
        File(handoff).writeText(
            buildString {
                appendLine(ark.toHex())
                appendLine(key.publicKeySec1.toHex())
                appendLine(key.privateKeyPkcs8.toHex())
            }
        )
        // Read by the orchestrator, which cannot parse a file it has no schema for as cheaply as it
        // can read one line of stdout.
        println("HANDOFF ark=" + ark.toHex() + " pub=" + key.publicKeySec1.toHex())
    }

    @Test
    fun pullsTheNoteAndroidWrote() = runBlocking {
        val handoff = System.getProperty("manana.handoff")
        val serverUrl = System.getProperty("manana.syncServer")
        val noteId = System.getProperty("manana.noteId")
        val deviceId = System.getProperty("manana.deviceId")
        val vaultDir = System.getProperty("manana.pullVault")
        assumeNotNull(handoff)
        assumeNotNull(serverUrl)
        assumeNotNull(noteId)
        assumeNotNull(deviceId)
        assumeNotNull(vaultDir)

        val lines = File(handoff!!).readLines().filter { it.isNotBlank() }
        val ark = lines[0].hexToBytes()
        val deviceKey = DeviceKeyPair(lines[2].hexToBytes(), lines[1].hexToBytes())

        val keys = AccountRootKey.derive(ark)
        val accountId = Base64Url.encode(keys.accountId)
        val codec = RecordCodec(keys)

        val directory = Paths.get(vaultDir!!).also { it.toFile().mkdirs() }
        val store = RecordStore.open(directory.resolve("records.db"))
        val repository = RecordNotesRepository.load(store, codec, node = "laptop")

        val service = DesktopSyncService(
            endpoint = ServerEndpoint(serverUrl!!),
            deviceKey = deviceKey,
            credentials = DeviceCredentials(accountId, deviceId!!),
            codec = codec,
            store = store,
            arkProvider = { ark.copyOf() },
            clockObserver = repository.clockObserver,
        )

        val outcome = service.syncOnce()
        println("pull pass: " + outcome)
        assertTrue("the laptop did not finish its pass: " + outcome, outcome is SyncOutcome.Completed)

        // Re-read from disk rather than from the snapshot the pass ran against: what is being
        // claimed is that the note is STORED on this laptop, not that it passed through it.
        val arrived = RecordNotesRepository
            .load(store, codec, node = "laptop")
            .getNoteById(noteId!!)
            .first()

        assertEquals("Written on Android", arrived?.title)
        assertEquals("typed on the phone, read on the laptop", arrived?.content)
        println("CROSSED title=" + arrived?.title)
    }

    private companion object {

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        fun String.hexToBytes(): ByteArray =
            ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
