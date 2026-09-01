package my.cheysoff.core_crypto.platform

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CValuesRef
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.value
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.CCKeyDerivationPBKDF
import platform.CoreCrypto.CCRandomGenerateBytes
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.CoreCrypto.kCCOptionECBMode
import platform.CoreCrypto.kCCPBKDF2
import platform.CoreCrypto.kCCPRFHmacAlgSHA256
import platform.CoreCrypto.kCCSuccess
import platform.posix.size_tVar

/**
 * iOS and macOS: CommonCrypto.
 *
 * ## What has and has not been verified
 *
 * **This file compiles.** Kotlin/Native can cross-compile Apple *klibs* from any host, so
 * `./gradlew :core-crypto-shared:compileKotlinIosSimulatorArm64` runs on the Windows machine this
 * was written on and does type-check every line below against the real CommonCrypto bindings. That
 * is a much better position than this file started in, and it is how the GCM problem below was
 * found rather than guessed at.
 *
 * **It has never been run.** Linking a framework and executing a test both need macOS, so nothing
 * here has produced a byte. The suites that decide whether it is *correct* —
 * `PlatformCryptoKnownAnswerTest` and `ProtocolVectorsTest`, both `commonTest` — are waiting on a
 * Mac. See `docs/BUILDING-IOS.md`.
 *
 * ## CommonCrypto has no GCM, and that is not a mistake in this file
 *
 * The `platform.CoreCrypto` bindings expose ECB, CBC, CFB, CFB8, CTR, OFB and RC4. There is no
 * `kCCModeGCM`, no `CCCryptorGCM`, and no `CCCryptorGCMOneshotEncrypt`/`Decrypt` — verified by
 * reading the klib's symbol table, not inferred. So [aesGcmSeal] and [aesGcmOpen] are
 * [GaloisCounterMode] — GHASH and the counter arithmetic in portable Kotlin — over CommonCrypto's
 * AES. [GaloisCounterMode]'s KDoc argues why that is the least-bad of the available options and
 * `GaloisCounterModeTest` is what makes it safe: it checks that code against the published GCM
 * vectors and differentially against the JVM's own AES-GCM over hundreds of random inputs, on a
 * machine that has a reference implementation.
 *
 * The upshot is worth stating plainly, because it is the difference between this port being
 * trustworthy and not. The unverified Apple surface is no longer "an AEAD"; it is **four thin
 * calls** — an RNG, an HMAC, a PBKDF2 and an AES-ECB — each of which either matches a published
 * vector on the first run or does not.
 *
 * Never logs key material.
 */

/**
 * A pointer for a native call, or null for an empty array.
 *
 * `refTo(0)` pins the array for the duration of the call it is passed to, which is exactly the
 * lifetime needed here and is why none of these functions has to nest `usePinned` blocks. It throws
 * on an empty array, hence the guard: an empty associated data, an empty salt and an empty
 * plaintext are all legal inputs, and every C function below takes a null pointer with a zero
 * length to mean the same thing.
 */
@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.ref(): CValuesRef<ByteVar>? = if (isEmpty()) null else refTo(0)

/**
 * The same, as `unsigned char *`.
 *
 * CommonCrypto's headers are inconsistent about this -- `CCHmac` and `CCCrypt` take `const void *`,
 * which the bindings render as `CValuesRef<ByteVar>`, while `CCKeyDerivationPBKDF` takes
 * `const uint8_t *` for its salt and `uint8_t *` for its output. `asUByteArray()` is a view over
 * the same storage rather than a copy, so the derived key is written into the caller's array.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalUnsignedTypes::class)
private fun ByteArray.uref(): CValuesRef<UByteVar>? =
    if (isEmpty()) null else asUByteArray().refTo(0)

@OptIn(ExperimentalForeignApi::class)
internal actual fun secureRandomBytes(size: Int): ByteArray {
    require(size > 0) { "cannot draw $size random bytes" }
    val out = ByteArray(size)
    // CommonCrypto's own RNG rather than Security.framework's `SecRandomCopyBytes`. Both read the
    // same kernel CSPRNG; this one is in the framework already imported here and reports failure
    // through the same `kCCSuccess` convention as everything else in this file.
    val status = CCRandomGenerateBytes(out.refTo(0), size.convert())
    // Throwing is correct and the `expect`'s KDoc says why: a GCM nonce drawn from a failed RNG
    // would be zeros, every record on the device would be sealed under the same (key, nonce) pair,
    // and the break is total rather than partial. A crash is recoverable; that is not.
    check(status == kCCSuccess) { "CCRandomGenerateBytes failed with status $status" }
    return out
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
    val out = ByteArray(SHA256_DIGEST_BYTES)
    // CCHmac returns void; there is no status to check. A zero-length key is excluded by the
    // seam's contract, so `key.ref()` is never null in practice -- see the `expect`'s KDoc.
    CCHmac(
        algorithm = kCCHmacAlgSHA256,
        key = key.ref(),
        keyLength = key.size.convert(),
        data = message.ref(),
        dataLength = message.size.convert(),
        macOut = out.refTo(0),
    )
    return out
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun pbkdf2HmacSha256(
    password: CharArray,
    salt: ByteArray,
    iterations: Int,
    keyBytes: Int,
): ByteArray {
    val out = ByteArray(keyBytes)
    // The binding types `const char *password` as a Kotlin `String?`, so the conversion to UTF-8 is
    // Kotlin/Native's rather than this file's -- which means the PIN cannot be zeroed after use the
    // way the JVM side's `PBEKeySpec.clearPassword` zeroes its copy. That is a real difference and
    // it is small: a Kotlin `String` is immutable and the PIN it came from is a handful of digits
    // the user just typed. It is recorded here rather than left to be noticed.
    //
    // `passwordLen` is the length in BYTES, so it is measured after encoding and not from
    // `password.size` -- a non-ASCII PIN would otherwise derive a key from a truncated password.
    val text = password.concatToString()
    val status = CCKeyDerivationPBKDF(
        algorithm = kCCPBKDF2,
        password = text,
        passwordLen = text.encodeToByteArray().size.convert(),
        salt = salt.uref(),
        saltLen = salt.size.convert(),
        prf = kCCPRFHmacAlgSHA256,
        rounds = iterations.convert(),
        derivedKey = out.uref(),
        derivedKeyLen = keyBytes.convert(),
    )
    check(status == kCCSuccess) { "CCKeyDerivationPBKDF failed with status $status" }
    return out
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun aesGcmSeal(
    key: ByteArray,
    nonce: ByteArray,
    aad: ByteArray?,
    plaintext: ByteArray,
): ByteArray = GaloisCounterMode.seal(
    cipher = aesBlockCipher(key),
    nonce = nonce,
    aad = aad ?: ByteArray(0),
    plaintext = plaintext,
)

@OptIn(ExperimentalForeignApi::class)
internal actual fun aesGcmOpen(
    key: ByteArray,
    nonce: ByteArray,
    aad: ByteArray?,
    sealed: ByteArray,
): ByteArray? = GaloisCounterMode.open(
    cipher = aesBlockCipher(key),
    nonce = nonce,
    aad = aad ?: ByteArray(0),
    sealed = sealed,
)

/**
 * AES in ECB over a whole number of blocks — the one primitive [GaloisCounterMode] needs.
 *
 * ECB is safe here and only here: every buffer this encrypts is a counter block or the all-zero
 * block, both of which this code chose and neither of which is user data. It is never applied to a
 * note.
 *
 * `kCCOptionECBMode` **without** `kCCOptionPKCS7Padding`. With padding, CommonCrypto would append a
 * whole extra block and the keystream would be right followed by sixteen wrong bytes — which every
 * vector in `GaloisCounterModeTest` catches, but only once someone runs it.
 */
@OptIn(ExperimentalForeignApi::class)
private fun aesBlockCipher(key: ByteArray) = GaloisCounterMode.BlockCipher { input ->
    require(input.size % AES_BLOCK_BYTES == 0) {
        "ECB input must be a whole number of blocks, was ${input.size}"
    }
    val out = ByteArray(input.size)
    val status = memScoped {
        val moved = alloc<size_tVar>()
        val result = CCCrypt(
            op = kCCEncrypt,
            alg = kCCAlgorithmAES,
            options = kCCOptionECBMode,
            key = key.refTo(0),
            keyLength = key.size.convert(),
            // No IV in ECB. Passing one would be ignored; passing null says so.
            iv = null,
            dataIn = input.refTo(0),
            dataInLength = input.size.convert(),
            dataOut = out.refTo(0),
            dataOutAvailable = out.size.convert(),
            dataOutMoved = moved.ptr,
        )
        // A short write would leave the tail of `out` as zeros, and a zero keystream block is a
        // plaintext block passed straight through. Checked rather than trusted.
        if (result == kCCSuccess && moved.value.toInt() != out.size) {
            error("CCCrypt wrote ${moved.value} of ${out.size} bytes")
        }
        result
    }
    check(status == kCCSuccess) { "CCCrypt failed with status $status" }
    out
}

/** SHA-256 output length. Not from `SyncProtocol`: that file holds wire constants, this is a hash. */
private const val SHA256_DIGEST_BYTES = 32

/** AES's block size, in bytes. The same on every key length. */
private const val AES_BLOCK_BYTES = 16
