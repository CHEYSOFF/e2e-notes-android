package my.cheysoff.core_pairing.protocol

import my.cheysoff.core_crypto.sync.Hkdf

/**
 * The production [KeyDerivation]: `core-crypto`'s [Hkdf], and nothing else.
 *
 * This is an adapter, not an implementation. It exists because [KeyDerivation] names its output
 * length `outLen` and [Hkdf.derive] names it `length`, and because the pairing module should
 * depend on an interface it owns rather than on a class in another module. Every byte it returns
 * comes from the one HKDF-SHA256 in this codebase — the same object the record envelope and the
 * ARK key hierarchy call.
 *
 * That single-implementation property is the whole point, and it is why the RFC 5869 fake the
 * pairing tests used to bind was deleted rather than kept alongside this: two copies of a protocol
 * primitive each pass their own tests and disagree only on a real pair of devices.
 * `HkdfSeamTest` pins this object's output — through this adapter, in the exact shapes pairing
 * uses — to literal bytes, so a change on either side of the seam fails a named test instead of a
 * pairing.
 *
 * [Hkdf.derive] takes a nullable salt where this interface takes a non-null one; the two agree on
 * what an empty salt means (RFC 5869: HashLen zero bytes), and pairing never passes one anyway —
 * the salt is always the 16-byte `sid`.
 */
object HkdfKeyDerivation : KeyDerivation {

    override fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, outLen: Int): ByteArray =
        Hkdf.derive(ikm = ikm, salt = salt, info = info, length = outLen)
}
