package my.cheysoff.core_crypto.platform

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCCryptorGCMOneshotDecrypt
import platform.CoreCrypto.CCCryptorGCMOneshotEncrypt
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.CCKeyDerivationPBKDF
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.CoreCrypto.kCCPBKDF2
import platform.CoreCrypto.kCCPRFHmacAlgSHA256
import platform.CoreCrypto.kCCSuccess
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

/**
 * iOS and macOS: CommonCrypto and Security.framework.
 *
 * ## NOT COMPILED. NOT RUN. NOT VERIFIED.
 *
 * Every line of this file was written on a Windows machine. The Kotlin/Native Apple compilers only
 * run on macOS, so nothing here has been through a compiler, a linker or a test. Treat it as a
 * carefully argued first draft, and treat the first `xcodebuild test` as the moment it becomes
 * evidence. `ProtocolVectorsTest` in `commonTest` is what turns that run into a yes-or-no answer:
 * if it passes, this file agrees with the JVM byte for byte and an iPhone can read a note the phone
 * or the laptop wrote; if it fails, the failing test names the primitive that disagrees.
 *
 * `docs/BUILDING-IOS.md` lists what is most likely to break here and what to do about each.
 *
 * ## Why CommonCrypto and not CryptoKit
 *
 * CryptoKit is the framework Apple points at, and for AES-GCM it is the better API -- `AES.GCM.seal`
 * and `AES.GCM.open` are hard to misuse and `open` throws rather than handing back unverified
 * plaintext. It is also **Swift-only**. Kotlin/Native's interop reaches Objective-C and C, not
 * Swift, so using it would mean an Objective-C-visible Swift shim living in the Xcode project,
 * injected down into this module -- which would make the crypto a dependency the *app* supplies, and
 * a library that cannot decrypt its own storage without the app handing it a cipher is a worse
 * design than the one below. CommonCrypto is C, ships in the OS on every supported version, and
 * needs no shim.
 *
 * If the one-shot GCM symbols below turn out not to be exposed by the `platform.CoreCrypto`
 * bindings on the SDK in use, the fallback is the deprecated `CCCryptorGCM` plus a hand-written
 * constant-time tag comparison. `docs/BUILDING-IOS.md` carries that code, and the reason it is the
 * fallback rather than the default is directly below.
 *
 * ## The GCM footgun this file is written to avoid
 *
 * CommonCrypto's older GCM interface does **not** verify the authentication tag on decrypt. The
 * deprecated one-shot `CCCryptorGCM` takes the tag buffer as an *output* parameter in both
 * directions: on decrypt it writes the tag it computed and returns success regardless of whether
 * that matches the tag that arrived. Code that passes the received tag in and ignores the result
 * has quietly turned AES-GCM into AES-CTR with no integrity at all, and it round-trips perfectly
 * against itself, so no test that only seals and opens will ever notice.
 *
 * [CCCryptorGCMOneshotDecrypt] takes the tag as a `const` **input** and returns a failure status
 * when it does not match, which is why it is what this file calls. If it is ever replaced, whatever
 * replaces it must compare the tags itself, in constant time, and return null on mismatch.
 *
 * Never logs key material.
 */

/**
 * Zero-length arrays cannot be pinned and addressed -- `addressOf(0)` on an empty [ByteArray]
 * throws -- so an empty input becomes a null pointer with a zero length, which is what the C API
 * expects for "no associated data" and "no salt".
 *
 * Inline so the pinning stays inside the caller's frame and the pointer never escapes it.
 */
@OptIn(ExperimentalForeignApi::class)
private inline fun <R> ByteArray.withPointer(block: (CPointer<ByteVar>?) -> R): R =
    if (isEmpty()) block(null) else usePinned { block(it.addressOf(0)) }

@OptIn(ExperimentalForeignApi::class)
internal actual fun secureRandomBytes(size: Int): ByteArray {
    require(size > 0) { "cannot draw $size random bytes" }
    val out = ByteArray(size)
    val status = out.withPointer { pointer ->
        SecRandomCopyBytes(kSecRandomDefault, size.convert(), pointer)
    }
    // Throwing is the correct response and the KDoc on the `expect` says why: a GCM nonce drawn
    // from a failed RNG would be zeros, every note on the device would be sealed under the same
    // (key, nonce) pair for its record, and the break is total rather than partial. A crash here
    // is recoverable; that is not.
    check(status == errSecSuccess) { "SecRandomCopyBytes failed with status $status" }
    return out
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
    val out = ByteArray(SHA256_DIGEST_BYTES)
    key.withPointer { keyPointer ->
        message.withPointer { messagePointer ->
            out.usePinned { outPinned ->
                CCHmac(
                    algorithm = kCCHmacAlgSHA256,
                    key = keyPointer,
                    keyLength = key.size.convert(),
                    data = messagePointer,
                    dataLength = message.size.convert(),
                    macOut = outPinned.addressOf(0),
                )
            }
        }
    }
    // CCHmac returns void: there is no status to check. A zero-length key is legal per RFC 2104
    // and CommonCrypto handles it, which is what RFC 5869 case A.3 exercises through `Hkdf`.
    return out
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun pbkdf2HmacSha256(
    password: CharArray,
    salt: ByteArray,
    iterations: Int,
    keyBytes: Int,
): ByteArray {
    // UTF-8, matching what OpenJDK's PBKDF2 does with a char[]. See the `expect`'s KDoc: the only
    // password this app derives from is a numeric PIN, where every encoding agrees, and a PIN wrap
    // never leaves the device -- so this is the one primitive here whose cross-platform parity is
    // a nicety rather than a requirement.
    val passwordBytes = password.concatToString().encodeToByteArray()
    val out = ByteArray(keyBytes)
    val status = passwordBytes.withPointer { passwordPointer ->
        salt.withPointer { saltPointer ->
            out.usePinned { outPinned ->
                CCKeyDerivationPBKDF(
                    algorithm = kCCPBKDF2,
                    password = passwordPointer,
                    passwordLen = passwordBytes.size.convert(),
                    salt = saltPointer?.reinterpret(),
                    saltLen = salt.size.convert(),
                    prf = kCCPRFHmacAlgSHA256,
                    rounds = iterations.convert(),
                    derivedKey = outPinned.addressOf(0).reinterpret(),
                    derivedKeyLen = keyBytes.convert(),
                )
            }
        }
    }
    // The password is the user's PIN; it does not stay on the heap a moment longer than it must.
    passwordBytes.fill(0)
    check(status == kCCSuccess) { "CCKeyDerivationPBKDF failed with status $status" }
    return out
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun aesGcmSeal(
    key: ByteArray,
    nonce: ByteArray,
    aad: ByteArray?,
    plaintext: ByteArray,
): ByteArray {
    val associated = aad ?: ByteArray(0)
    val ciphertext = ByteArray(plaintext.size)
    val tag = ByteArray(TAG_BYTES)

    val status = key.withPointer { keyPointer ->
        nonce.withPointer { noncePointer ->
            associated.withPointer { aadPointer ->
                plaintext.withPointer { plaintextPointer ->
                    // `ciphertext` is empty whenever the plaintext is, and an empty payload is a
                    // legal thing to seal, so it goes through the same null-pointer path.
                    ciphertext.withPointer { ciphertextPointer ->
                        tag.usePinned { tagPinned ->
                            CCCryptorGCMOneshotEncrypt(
                                alg = kCCAlgorithmAES,
                                key = keyPointer,
                                keyLength = key.size.convert(),
                                iv = noncePointer,
                                ivLen = nonce.size.convert(),
                                aData = aadPointer,
                                aDataLen = associated.size.convert(),
                                dataIn = plaintextPointer,
                                dataInLength = plaintext.size.convert(),
                                dataOut = ciphertextPointer,
                                tagOut = tagPinned.addressOf(0),
                                tagLength = TAG_BYTES.convert(),
                            )
                        }
                    }
                }
            }
        }
    }
    // Unlike the decrypt path, a failure here is not "this input was bad" -- the caller controls
    // every argument and they have already been size-checked. It is a broken assumption, and
    // returning a partially-written buffer would put unencrypted-looking garbage into the store.
    check(status == kCCSuccess) { "CCCryptorGCMOneshotEncrypt failed with status $status" }

    // ciphertext ‖ tag, the layout every existing envelope on every existing device already has.
    // CommonCrypto hands the tag back separately; the JCA does not. This is the whole of the
    // difference between the two platforms' AEAD APIs.
    val out = ByteArray(ciphertext.size + tag.size)
    ciphertext.copyInto(out)
    tag.copyInto(out, destinationOffset = ciphertext.size)
    return out
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun aesGcmOpen(
    key: ByteArray,
    nonce: ByteArray,
    aad: ByteArray?,
    sealed: ByteArray,
): ByteArray? {
    // A blob too short to hold a tag cannot be split, let alone authenticated. The JVM's
    // `Cipher.doFinal` reports this as an AEADBadTagException, which the JVM actual maps to null;
    // matching that here keeps "cannot open" one answer on both platforms.
    if (sealed.size < TAG_BYTES) return null

    val ciphertext = sealed.copyOfRange(0, sealed.size - TAG_BYTES)
    val tag = sealed.copyOfRange(sealed.size - TAG_BYTES, sealed.size)
    val associated = aad ?: ByteArray(0)
    val plaintext = ByteArray(ciphertext.size)

    val status = key.withPointer { keyPointer ->
        nonce.withPointer { noncePointer ->
            associated.withPointer { aadPointer ->
                ciphertext.withPointer { ciphertextPointer ->
                    plaintext.withPointer { plaintextPointer ->
                        tag.withPointer { tagPointer ->
                            // `tagIn`, not `tagOut`. This function compares the tag and reports a
                            // mismatch through its return value; the deprecated `CCCryptorGCM` it
                            // replaces does not. See the file KDoc -- getting this backwards
                            // removes every integrity guarantee in the protocol and no seal/open
                            // round-trip test would notice.
                            CCCryptorGCMOneshotDecrypt(
                                alg = kCCAlgorithmAES,
                                key = keyPointer,
                                keyLength = key.size.convert(),
                                iv = noncePointer,
                                ivLen = nonce.size.convert(),
                                aData = aadPointer,
                                aDataLen = associated.size.convert(),
                                dataIn = ciphertextPointer,
                                dataInLength = ciphertext.size.convert(),
                                dataOut = plaintextPointer,
                                tagIn = tagPointer,
                                tagLength = TAG_BYTES.convert(),
                            )
                        }
                    }
                }
            }
        }
    }

    if (status != kCCSuccess) {
        // Whatever was written into `plaintext` before the tag check failed is unauthenticated
        // output. It must not be returned and it must not be left readable on the heap.
        plaintext.fill(0)
        return null
    }
    return plaintext
}

/** SHA-256 output length. Not read from `SyncProtocol`: that file holds wire constants, this is a hash. */
private const val SHA256_DIGEST_BYTES = 32

/** The full 128-bit GCM tag, never truncated. Matches `SyncProtocol.TAG_BYTES`. */
private const val TAG_BYTES = 16
