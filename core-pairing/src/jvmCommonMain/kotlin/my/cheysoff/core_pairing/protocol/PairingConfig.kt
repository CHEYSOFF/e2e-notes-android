package my.cheysoff.core_pairing.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What the account device tells the joining device about the account's server, sealed inside
 * [AccountBundle.config].
 *
 * ```
 *   {"server":"https://notes.example/","deviceId":"<16 bytes base64url>"}
 * ```
 *
 * ## Why these two values travel here and not in QR1
 *
 * QR1's `url` is a **hint**: it is what the joining device typed on its own screen, it crosses in
 * the clear, and nothing has been agreed when the account device reads it. The copy here is
 * different in one decisive way — it is inside the AES-256-GCM seal, under a key derived from an
 * ECDH whose private halves never left either device, so the joining device knows the account
 * device chose it.
 *
 * `deviceId` has no other way to travel at all. It is minted by the server when the account device
 * vouches (`POST /v1/devices/authorize`), it is returned only to the voucher, and the joining device
 * cannot ask for it: every endpoint that would tell it needs a session, and opening a session needs
 * the id. So the pairing seal is the one channel that exists, and carrying it is what turns "the
 * desktop holds the ARK" into "the desktop can sync".
 *
 * ## Absent is a state, not an error
 *
 * A phone-to-phone pairing seals an empty config, because that flow involves no server. [decode]
 * answers null for it, and a caller must read null as "this pairing said nothing about a server",
 * never as a failure — a phone that paired offline has an account and no sync configuration, which
 * is exactly correct.
 *
 * ## The format is JSON and both ends parse it here
 *
 * `AccountBundle.config` has always been documented as opaque to the pairing module, and it stays
 * opaque to the *protocol* — nothing in the handshake reads it. What this object adds is that the
 * two apps agree on its shape in one place rather than each writing its own `"server"` key, which
 * is the same argument the record payload codec makes at greater length.
 */
object PairingConfig {

    private const val KEY_SERVER = "server"
    private const val KEY_DEVICE_ID = "deviceId"

    private val json = Json

    /**
     * The config blob for a pairing that enrolled the joining device.
     *
     * @param serverUrl the account's sync server, as [RendezvousUrl.base] normalises it.
     * @param deviceId the id the server assigned to the joining device, or empty when the account
     *   device could not enrol it. An empty id is written as an absent key rather than as `""`, so
     *   that "no enrolment" has one spelling on the wire.
     */
    fun encode(serverUrl: String, deviceId: String): String {
        val obj = buildJsonObject {
            put(KEY_SERVER, JsonPrimitive(serverUrl))
            if (deviceId.isNotEmpty()) put(KEY_DEVICE_ID, JsonPrimitive(deviceId))
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    /**
     * Parse a config blob, or return null if there is nothing usable in it.
     *
     * Null covers an empty string (the phone-to-phone case), a blob that is not JSON, and one that
     * carries no `server`. It is deliberately lenient about **extra** keys, which is the opposite of
     * the record payload codec's rule and is right for the opposite reason: this value is never
     * re-serialised and sent onwards, so an unknown key cannot be silently dropped from anybody's
     * data. A newer build adding a field must not make an older build refuse to pair.
     */
    fun decode(config: String): PairedServer? {
        if (config.isEmpty()) return null
        val root = try {
            json.parseToJsonElement(config).jsonObject
        } catch (e: Exception) {
            // The bytes are authenticated -- they came out of the seal -- so this is a version
            // disagreement or a bug, not an attack. Either way there is one useful answer.
            return null
        }
        val server = try {
            root[KEY_SERVER]?.jsonPrimitive?.content
        } catch (e: IllegalArgumentException) {
            null
        } ?: return null
        if (server.isEmpty()) return null
        val deviceId = try {
            root[KEY_DEVICE_ID]?.jsonPrimitive?.content
        } catch (e: IllegalArgumentException) {
            null
        }
        return PairedServer(serverUrl = server, deviceId = deviceId?.takeIf { it.isNotEmpty() })
    }
}

/**
 * The server configuration a pairing handed over.
 *
 * [deviceId] is null when the account device named a server but could not enrol this device on it —
 * the account device was offline, or it is not itself enrolled. The pairing still succeeded and the
 * ARK is still valid; what is missing is the ability to open a session, and the honest thing for a
 * caller to do with a null is to say so rather than to sync and find out.
 */
class PairedServer(val serverUrl: String, val deviceId: String?)
