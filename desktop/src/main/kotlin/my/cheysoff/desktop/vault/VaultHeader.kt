package my.cheysoff.desktop.vault

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import my.cheysoff.core_crypto.PinWrap
import my.cheysoff.core_crypto.sync.ArkWrap
import java.util.Base64

/**
 * `vault.json`: everything the desktop needs to reconstruct its keys from a passphrase, and
 * nothing that could be used without one.
 *
 * ```
 * passphrase --PBKDF2--> K_wrap --AES-GCM--> vaultKey (32 random bytes)
 * vaultKey   --HKDF----> K_arkwrap --AES-GCM--> ARK
 * ARK        --HKDF----> K_content, K_id, accountId
 * ```
 *
 * The middle layer — a random `vaultKey` between the passphrase and the ARK — is the same shape
 * `SecureUnlockManager` uses on the phone, and it is here for the same reason. The phone needs a
 * stable key that both the PIN path and the biometric path can produce; the desktop needs one that
 * both the passphrase path and the OS credential store can produce. Without it, "remember me on
 * this computer" would have to store the passphrase itself, and changing the passphrase would have
 * to re-seal every record instead of re-wrapping 32 bytes.
 *
 * ## What this file leaks to someone who copies it
 *
 * The salt, the IVs, the iteration count, and the fact that a Manana vault exists. Not the number
 * of notes, not the account ID, not the device. Everything that could identify the account is
 * derived from the ARK, and the ARK is inside `ark`.
 *
 * ## Why the two wraps are in one file
 *
 * They are useless apart. A `vaultKey` wrap without the ARK wrap opens a database whose every row
 * is sealed under keys that are gone; an ARK wrap without the `vaultKey` wrap cannot be opened at
 * all. Splitting them would only create a state where half the vault survives a partial restore,
 * which reads as recoverable and is not.
 */
data class VaultHeader(
    /** Format version of this file. Bumped only for a change no older build can read. */
    val version: Int,
    /** PBKDF2 parameters and the wrapped `vaultKey`. Carries its own iteration count. */
    val keyWrap: PinWrap,
    /** The ARK, sealed under `HKDF(vaultKey, "manana/sync/v1/arkwrap")`. */
    val arkWrap: ArkWrap,
    /**
     * This installation's device identifier: the salt `HlcNode.derive` mixes with the ARK.
     *
     * Stored in the clear, and that is correct — it is a salt, not a secret, and the node it
     * produces is a one-way function of it *and* the ARK. It must be **stable for the life of the
     * vault**: the node is what breaks ties between two devices writing in the same millisecond,
     * so a value that changed per launch would make this machine look like a new device on every
     * start and could let two of its own writes tie with each other.
     *
     * It must also be **unique per device**, which is why it is random rather than derived from
     * anything about the machine: two computers that both restored the same vault directory from a
     * backup would otherwise share a node, and two devices with the same node cannot be ordered
     * against each other at all.
     */
    val deviceId: String,
) {
    companion object {

        /** The only version this build writes, and the only one it reads. */
        const val CURRENT_VERSION = 1

        private const val KEY_VERSION = "v"
        private const val KEY_KDF = "kdf"
        private const val KEY_KDF_ALGORITHM = "alg"
        private const val KEY_KDF_ITERATIONS = "iterations"
        private const val KEY_KDF_SALT = "salt"
        private const val KEY_VAULT_KEY = "vaultKey"
        private const val KEY_ARK = "ark"
        private const val KEY_DEVICE_ID = "deviceId"
        private const val KEY_IV = "iv"
        private const val KEY_CIPHERTEXT = "ct"

        /**
         * Written for a human reading the file, and checked on the way back in.
         *
         * `PassphraseCipher` hardcodes PBKDF2-HMAC-SHA256, so this string cannot currently be
         * anything else and the check cannot currently fail. It is recorded anyway because a file
         * whose KDF is implicit is a file that cannot be migrated: the day a second KDF exists,
         * every vault written before today would have to be *assumed* to be this one.
         */
        private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"

        // java.util.Base64 rather than the project's `Base64Url`. Base64Url is encode-only and
        // documented as such -- blinded IDs are never decoded back to bytes anywhere in the
        // protocol -- and this file has to round-trip. Adding a decoder to a module shared with
        // Android for the sake of one desktop-local file would put an unused, untested code path
        // in the app's crypto module.
        private val encoder: Base64.Encoder = Base64.getEncoder()
        private val decoder: Base64.Decoder = Base64.getDecoder()

        private val json = Json { prettyPrint = true }

        /** Renders [header] as the bytes of `vault.json`. */
        fun encode(header: VaultHeader): ByteArray {
            val obj = buildJsonObject {
                put(KEY_VERSION, JsonPrimitive(header.version))
                put(
                    KEY_KDF,
                    buildJsonObject {
                        put(KEY_KDF_ALGORITHM, JsonPrimitive(KDF_ALGORITHM))
                        put(KEY_KDF_ITERATIONS, JsonPrimitive(header.keyWrap.iterations))
                        put(KEY_KDF_SALT, JsonPrimitive(encoder.encodeToString(header.keyWrap.salt)))
                    },
                )
                put(KEY_VAULT_KEY, sealedObject(header.keyWrap.iv, header.keyWrap.ciphertext))
                put(KEY_ARK, sealedObject(header.arkWrap.iv, header.arkWrap.ciphertext))
                put(KEY_DEVICE_ID, JsonPrimitive(header.deviceId))
            }
            return json.encodeToString(JsonObject.serializer(), obj).toByteArray(Charsets.UTF_8)
        }

        /**
         * Parses `vault.json`, or returns null if it is not one this build can use.
         *
         * Null covers a truncated file, a field of the wrong type, a version from a newer build and
         * an unrecognised KDF, all the same way — and the caller must react to null by asking the
         * user for the passphrase against a vault it cannot open, **never** by writing a fresh
         * header. Overwriting an unreadable header is what silently destroys an account: the ARK
         * inside it is the only copy on a device that has not paired.
         */
        fun decode(bytes: ByteArray): VaultHeader? = try {
            val root = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            val version = root.getValue(KEY_VERSION).jsonPrimitive.int
            if (version != CURRENT_VERSION) {
                null
            } else {
                val kdf = root.getValue(KEY_KDF).jsonObject
                if (kdf.getValue(KEY_KDF_ALGORITHM).jsonPrimitive.content != KDF_ALGORITHM) {
                    null
                } else {
                    val vaultKey = root.getValue(KEY_VAULT_KEY).jsonObject
                    val ark = root.getValue(KEY_ARK).jsonObject
                    VaultHeader(
                        version = version,
                        keyWrap = PinWrap(
                            salt = decoder.decode(kdf.getValue(KEY_KDF_SALT).jsonPrimitive.content),
                            iv = decoder.decode(vaultKey.getValue(KEY_IV).jsonPrimitive.content),
                            ciphertext = decoder.decode(
                                vaultKey.getValue(KEY_CIPHERTEXT).jsonPrimitive.content,
                            ),
                            iterations = kdf.getValue(KEY_KDF_ITERATIONS).jsonPrimitive.int,
                        ),
                        arkWrap = ArkWrap(
                            iv = decoder.decode(ark.getValue(KEY_IV).jsonPrimitive.content),
                            ciphertext = decoder.decode(
                                ark.getValue(KEY_CIPHERTEXT).jsonPrimitive.content,
                            ),
                        ),
                        deviceId = root.getValue(KEY_DEVICE_ID).jsonPrimitive.content,
                    )
                }
            }
        } catch (_: Exception) {
            // Deliberately broad. The parse walks a file that may have been truncated by a full
            // disk, edited by hand, or written by a different program entirely, and every library
            // on this path throws a different type for each of those. There is exactly one useful
            // answer -- "this is not a header I can use" -- and no caller can act on which of the
            // dozen exception types produced it.
            null
        }

        private fun sealedObject(iv: ByteArray, ciphertext: ByteArray): JsonObject = buildJsonObject {
            put(KEY_IV, JsonPrimitive(encoder.encodeToString(iv)))
            put(KEY_CIPHERTEXT, JsonPrimitive(encoder.encodeToString(ciphertext)))
        }
    }
}
