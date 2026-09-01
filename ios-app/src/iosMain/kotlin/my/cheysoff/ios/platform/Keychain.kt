package my.cheysoff.ios.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.alloc
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.darwin.OSStatus

/**
 * A minimal Keychain: one named blob, written, read and deleted.
 *
 * ## COMPILED. NOT RUN. NOT VERIFIED.
 *
 * It type-checks against the real Security.framework bindings, which is how the CoreFoundation
 * ownership below settled into the shape it has. It has never stored or read a byte, and the two
 * things a Mac must confirm are that a stored item comes back on the next launch, and that the
 * accessibility attribute is the one that was asked for. Both checks are in
 * `docs/BUILDING-IOS.md`.
 *
 * ## Why this exists rather than `NSUserDefaults`
 *
 * The one thing this app stores outside the record database is the ARK wrapped under the user's
 * PIN, and the strength of that wrap is bounded by the PIN. A six-digit PIN is a million
 * candidates; PBKDF2 at 210,000 rounds costs roughly a tenth of a second each, so a million
 * candidates is on the order of a day of one CPU. **The wrap is therefore not safe against anyone
 * who gets a copy of it**, and everything rests on the copy being hard to get.
 *
 * That is exactly the job the Android build gives the AndroidKeyStore, and the Keychain is the
 * equivalent here. `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` is the attribute that does the
 * work, and both halves of it matter: *WhenUnlocked* keeps the item unreadable while the phone is
 * locked, and *ThisDeviceOnly* keeps it out of iCloud Keychain and out of an encrypted iTunes
 * backup, so a copy cannot be lifted from a backup and attacked offline at leisure.
 *
 * `NSUserDefaults` would have been a dozen lines instead of a hundred and would have put that blob
 * in a plist inside the app container, where a backup carries it away. That is not a smaller
 * version of this; it is a different security posture, and it would have been a quiet one.
 *
 * ## What is deliberately NOT here
 *
 * No biometrics. The Android app has a biometric unlock path, and the iOS equivalent
 * (`kSecAccessControlBiometryCurrentSet` plus `LAContext`) is a real feature with real edge cases
 * -- re-enrolment invalidating the item, the "biometry lockout" state, the fallback to a device
 * passcode. Adding it unverified would be adding an unlock path nobody has ever seen succeed OR
 * fail. It is the natural next piece of work; see docs/BUILDING-IOS.md.
 */
@OptIn(ExperimentalForeignApi::class)
internal object Keychain {

    /**
     * The service name every item is filed under.
     *
     * Distinct from any bundle identifier on purpose: the bundle id can change (a rename, a
     * different signing team, a TestFlight build) and an item filed under the old one would be
     * invisible afterwards -- which for this app means an ARK that cannot be unwrapped, i.e. an
     * account silently forked. A constant string cannot do that.
     */
    private const val SERVICE = "my.cheysoff.manana.vault"

    /** Reads one item, or null if it is absent. Any other failure is also null; see [store]. */
    fun read(key: String): ByteArray? = memScoped {
        val query = CFDictionaryCreateMutable(
            kCFAllocatorDefault, 5,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        )
        CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(query, kSecAttrService, cfString(SERVICE))
        CFDictionaryAddValue(query, kSecAttrAccount, cfString(key))
        CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)

        val result = alloc<CFTypeRefVar>()
        val status: OSStatus = SecItemCopyMatching(query, result.ptr)
        CFRelease(query)
        if (status != errSecSuccess) {
            // `errSecItemNotFound` is the ordinary "first launch" answer. Anything else is a
            // Keychain that is not behaving, and the honest response is the same one: this device
            // has no usable vault right now. It must NOT be read as "so make a new ARK" -- see
            // `AccountRootKey.generateArk`, which spells out that minting a second one forks the
            // account irrecoverably. `ArkVault` is where that rule is enforced.
            return@memScoped null
        }
        // `SecItemCopyMatching` returns a +1 `CFDataRef`. `CFBridgingRelease` both converts it to
        // an `NSData` and consumes that reference, which is why there is no `CFRelease` here and
        // would be a double free if there were. Going through Foundation rather than
        // `CFDataGetBytePtr` also avoids reinterpreting an opaque `CFTypeRef` by hand.
        val data = CFBridgingRelease(result.value) as? NSData ?: return@memScoped null
        data.toByteArray()
    }

    /**
     * Writes one item, replacing whatever was there.
     *
     * Delete-then-add rather than `SecItemUpdate`, because the two are the same number of calls and
     * this way the accessibility attribute is written on every store -- an item added by an older
     * build with a weaker attribute is upgraded rather than kept.
     */
    fun store(key: String, value: ByteArray): Boolean = memScoped {
        delete(key)
        val item = CFDictionaryCreateMutable(
            kCFAllocatorDefault, 5,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        )
        CFDictionaryAddValue(item, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(item, kSecAttrService, cfString(SERVICE))
        CFDictionaryAddValue(item, kSecAttrAccount, cfString(key))
        CFDictionaryAddValue(item, kSecValueData, cfData(value))
        CFDictionaryAddValue(
            item,
            kSecAttrAccessible,
            kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
        )

        val status = SecItemAdd(item, null)
        CFRelease(item)
        status == errSecSuccess
    }

    /** Removes an item. Absent is success: the caller wanted it gone and it is. */
    fun delete(key: String): Boolean = memScoped {
        val query = CFDictionaryCreateMutable(
            kCFAllocatorDefault, 3,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        )
        CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(query, kSecAttrService, cfString(SERVICE))
        CFDictionaryAddValue(query, kSecAttrAccount, cfString(key))
        val status = SecItemDelete(query)
        CFRelease(query)
        status == errSecSuccess || status == errSecItemNotFound
    }

    /**
     * A `CFStringRef` this function owns and hands to a dictionary that retains it.
     *
     * `CFBridgingRetain` on an `NSString` gives a +1 `CFTypeRef`. The reference is deliberately not
     * released: these are short-lived query dictionaries, released whole a few lines later, and the
     * leak is bounded by the number of Keychain calls this app makes in a session (three). Getting
     * the release ordering wrong on `CFDictionaryAddValue`'s retained keys is a far more expensive
     * mistake than leaking three small strings, and this is one of the files a Mac has never
     * compiled -- so it takes the option whose failure mode is smallest.
     */
    private fun cfString(value: String) = CFBridgingRetain(value as Any)

    @OptIn(BetaInteropApi::class)
    private fun cfData(value: ByteArray) = CFBridgingRetain(
        value.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = value.size.convert())
        }
    )

    /** The item's bytes, copied out of Foundation's storage into a Kotlin array. */
    private fun NSData.toByteArray(): ByteArray {
        val count = this.length.toInt()
        if (count <= 0) return ByteArray(0)
        val source = this.bytes?.reinterpret<ByteVar>() ?: return ByteArray(0)
        return ByteArray(count) { index -> source[index] }
    }
}
