package my.cheysoff.core_domain.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random
import java.util.UUID

/**
 * Pins [NameUuid] against `java.util.UUID.nameUUIDFromBytes`, the implementation it replaced.
 *
 * This is the test that makes moving `ConflictCopies` off the JDK a provably behaviour-preserving
 * change rather than an intended one. A conflict copy's id is part of the sync contract -- two
 * devices resolving one conflict must name the copy identically or the account grows a duplicate
 * forever -- so an id that shifted during the move to common code would be a silent, permanent,
 * account-wide defect that no other test in this repo would notice.
 *
 * It runs on the JVM only, because the JDK is the reference being matched. That is the point: the
 * common implementation is what ships everywhere, and here it is held against the one the Android
 * app used in production.
 */
class NameUuidParityTest {

    @Test
    fun matchesTheJdkOverRandomInputs() {
        val random = Random(20260901L)
        repeat(5_000) {
            val bytes = ByteArray(random.nextInt(300)).also(random::nextBytes)
            assertEquals(
                UUID.nameUUIDFromBytes(bytes).toString(),
                NameUuid.v3(bytes),
            )
        }
    }

    @Test
    fun matchesTheJdkAtEveryPaddingBoundary() {
        // MD5 pads to a multiple of 64 bytes and needs 8 of them for the length, so the block
        // count changes at 55/56 and again at 119/120. An off-by-one in the padding arithmetic
        // survives random testing surprisingly well and dies here.
        for (length in intArrayOf(0, 1, 54, 55, 56, 57, 63, 64, 65, 118, 119, 120, 121, 127, 128)) {
            val bytes = ByteArray(length) { (it * 7 + 1).toByte() }
            assertEquals(
                "length $length",
                UUID.nameUUIDFromBytes(bytes).toString(),
                NameUuid.v3(bytes),
            )
        }
    }

    @Test
    fun theConflictCopyIdItselfIsUnchanged() {
        // The real call site, held against the exact expression ConflictCopies used before the
        // move. If this fails, every conflict copy in the account is renamed.
        val clock = Hlc(ms = 1_756_000_000_000L, counter = 3, node = "devicenode")
        val sourceUuid = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"
        val name = "${ConflictCopies.ID_NAMESPACE}|$sourceUuid|$clock"
        assertEquals(
            UUID.nameUUIDFromBytes(name.toByteArray(Charsets.UTF_8)).toString(),
            ConflictCopies.idFor(sourceUuid, clock),
        )
    }

    @Test
    fun theNamespaceIsPinned() {
        // The namespace string is an input to every conflict-copy id. Changing it silently renames
        // every copy in every account, and the parity test above would not notice because it feeds
        // the constant to both sides. Pinned as a literal so that editing it is a deliberate act.
        assertEquals("manana/sync/v1/conflict-copy", ConflictCopies.ID_NAMESPACE)
    }
}
