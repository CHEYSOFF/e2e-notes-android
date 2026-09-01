package my.cheysoff.core_crypto.platform

/**
 * The four cryptographic primitives this module cannot express portably, and nothing else.
 *
 * ## Why this file is as small as it is
 *
 * Everything above these four functions -- the key hierarchy, the record envelope, the blinded
 * record ID, the ARK wrap, the device label, the padding, the HLC node -- is now ONE
 * implementation in `commonMain`, shared by every platform. Only the primitives underneath differ.
 *
 * That split is the whole point. This repository has already shipped the alternative once, in the
 * form of two HKDF implementations that had to agree with each other and did not, and the comments
 * in `Hkdf`, `Base64Url` and `core-sync-net/build.gradle.kts` all point back at it. A second
 * implementation of `RecordEnvelope` written against CryptoKit would be that mistake again, one
 * layer up and with a worse failure mode: an iPhone and a Pixel that each round-trip perfectly
 * against themselves and cannot read each other's notes, presenting as data corruption rather than
 * as a crypto mismatch.
 *
 * **If a fifth `expect` appears in this file, stop and argue about it first.** Each one is a place
 * two platforms can drift apart, and the cost of drift here is measured in unreadable notes.
 *
 * ## What is guaranteed of an actual
 *
 * An actual must implement the named standard exactly -- AES-256-GCM per NIST SP 800-38D,
 * HMAC-SHA256 per RFC 2104, PBKDF2-HMAC-SHA256 per RFC 8018 -- with no truncation, no
 * platform-specific framing and no extra encoding. `ProtocolVectorsTest` and the published
 * known-answer vectors beside it are what hold each platform to that; they are the first thing to
 * run on a platform whose actuals are new.
 *
 * ## Not verified on Apple
 *
 * The Apple actuals in `appleMain` have never been compiled, linked or executed -- the Kotlin/Native
 * Apple compilers only run on macOS and this branch was written on Windows. See
 * `docs/BUILDING-IOS.md`.
 */

/**
 * [size] cryptographically secure random bytes.
 *
 * Must come from the OS CSPRNG, must never be seeded from anything derived from the device, and
 * must never be replaced by a counter or a PRNG this app controls. Two callers here are GCM nonces,
 * where a repeat is not a weakening but a total break -- see `RecordEnvelope`'s KDoc for why.
 *
 * An implementation that cannot obtain randomness must throw rather than return low-entropy bytes.
 * A crash is recoverable; a note sealed under a predictable nonce is not.
 */
internal expect fun secureRandomBytes(size: Int): ByteArray

/**
 * HMAC-SHA256 of [message] under [key] -- RFC 2104, full 32-byte tag.
 *
 * [key] may be any length **except zero**, including longer than the 64-byte SHA-256 block, in
 * which case RFC 2104 hashes it first; every mainstream implementation does this, and `HkdfTest`'s
 * RFC 5869 case A.2 (an 80-byte key) pins it.
 *
 * The zero-length exclusion is a platform difference this seam declines to paper over: CommonCrypto
 * accepts an empty HMAC key and the JCA refuses one, because `SecretKeySpec` rejects an empty key
 * array outright. Nothing here needs it -- `Hkdf.extract` is the only caller that could produce
 * one and RFC 5869 has it substitute [HASH_LEN][my.cheysoff.core_crypto.sync.Hkdf.HASH_LEN] zero
 * bytes instead -- so the contract simply excludes it rather than requiring one platform to
 * emulate the other on an input no caller can reach.
 *
 * Called once per HKDF block and once per blinded record ID, so it is on a hot-ish path during a
 * full sync, but never hot enough to justify an incremental streaming variant -- which would be a
 * fifth `expect` and a second way to get the same answer.
 */
internal expect fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray

/**
 * PBKDF2-HMAC-SHA256 -- RFC 8018 -- producing [keyBytes] bytes from [password] and [salt] over
 * [iterations] rounds.
 *
 * ## Why the password is a `CharArray` and not UTF-8 bytes
 *
 * Because the JVM actual must keep producing the bytes it produces **today**, and the conversion
 * from characters to bytes is the JCA provider's, not this code's. Devices in the field already
 * hold PIN wraps made by `SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")`; encoding the
 * password here and handing bytes to the provider would change the derived key for any password
 * whose encoding the provider does not spell the same way, and every one of those users would find
 * their correct PIN rejected. So the `CharArray` is passed straight through on the JVM and the
 * provider keeps its own conversion.
 *
 * The Apple actual has no such history and encodes UTF-8, which is what OpenJDK does. **For an
 * ASCII password the two agree byte for byte, and the only password this app derives from is a
 * numeric PIN**, so the difference is unreachable in the product. It is also unimportant if it were
 * reached: a PIN wrap is written and read on one device and never travels, so this is the one
 * primitive here where cross-platform parity is a nicety rather than a requirement. `PassphraseCipher`
 * says the same thing from its side.
 *
 * An implementation must not cap or reinterpret [iterations]; the caller passes 210,000.
 */
internal expect fun pbkdf2HmacSha256(
    password: CharArray,
    salt: ByteArray,
    iterations: Int,
    keyBytes: Int,
): ByteArray

/**
 * AES-256-GCM encryption: returns `ciphertext ‖ tag`, the tag being the full 16 bytes.
 *
 * The concatenated layout is not an arbitrary choice -- it is what the JCA returns from
 * `Cipher.doFinal`, it is the tail of the record envelope as it is stored and transmitted, and
 * every existing envelope on every existing device is laid out that way. An actual on a platform
 * whose API hands back the tag separately must concatenate in this order.
 *
 * [key] is 32 bytes and [nonce] is 12; both are checked by the caller rather than here, so that a
 * platform actual is a thin adapter and not a second place the protocol's sizes are written down.
 * [aad] is authenticated and not encrypted; null and empty must behave identically, because the
 * JVM's `Cipher` treats a skipped `updateAAD` and an empty one as the same thing and existing
 * ciphertexts depend on it.
 */
internal expect fun aesGcmSeal(
    key: ByteArray,
    nonce: ByteArray,
    aad: ByteArray?,
    plaintext: ByteArray,
): ByteArray

/**
 * AES-256-GCM decryption of `ciphertext ‖ tag`, or **null** if the tag does not verify.
 *
 * ## The tag must actually be verified, and null must actually mean that
 *
 * Returning the plaintext without checking the tag turns an authenticated cipher into a raw stream
 * cipher and silently removes every integrity guarantee this protocol rests on -- a server operator
 * could then flip any bit of any note. This is not a theoretical caution: CommonCrypto's original
 * `CCCryptorGCMFinal` did not compare tags, which is why the deprecated `CCCryptorGCM` one-shot
 * *writes the computed tag out* on decrypt instead of validating it, and why an actual built on it
 * must do the comparison itself in constant time. See the Apple actual and `docs/BUILDING-IOS.md`.
 *
 * Null covers every failure identically and on purpose -- wrong key, altered ciphertext, tampered
 * nonce, mismatched associated data, truncated input. Callers treat all of them as "this record
 * cannot be trusted", and distinguishing them would invite one to be treated as recoverable.
 * An actual must not throw for a bad tag, and must not report a decrypt failure as an empty array.
 */
internal expect fun aesGcmOpen(
    key: ByteArray,
    nonce: ByteArray,
    aad: ByteArray?,
    sealed: ByteArray,
): ByteArray?
