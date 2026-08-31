package my.cheysoff.core_crypto

import my.cheysoff.core_crypto.sync.Hkdf
import my.cheysoff.core_crypto.sync.SyncProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HlcNode]: the properties that make the node safe to publish to the sync operator, and the one
 * that makes it usable as a clock tie-breaker.
 *
 * This value is the single piece of a record that the operator can read. Every assertion below is
 * about what it must and must not reveal, plus the derivation being deterministic — a node that
 * changed between unlocks would split one device's history into two pseudonyms and, worse, would
 * make the tie-break non-deterministic across a restart.
 */
class HlcNodeTest {

    private fun ark(fill: Byte) = ByteArray(SyncProtocol.ARK_BYTES) { fill }

    private val deviceA = "0123456789abcdef0123456789abcdef"
    private val deviceB = "fedcba9876543210fedcba9876543210"

    @Test
    fun `the same account key and device always give the same node`() {
        // Stability is what lets the node be derived on demand instead of stored, and it is what
        // keeps a device's clocks comparable to its own older ones across a restart.
        assertEquals(HlcNode.derive(ark(1), deviceA), HlcNode.derive(ark(1), deviceA))
    }

    @Test
    fun `two devices on one account get different nodes`() {
        // This is the tie-breaker's whole job: two devices writing in the same millisecond with
        // the same counter must not produce equal clocks for different values, or each replica is
        // free to decide the other's write lost.
        assertNotEquals(HlcNode.derive(ark(1), deviceA), HlcNode.derive(ark(1), deviceB))
    }

    @Test
    fun `one device on two accounts gets unrelated nodes`() {
        // The privacy property. A node that survived a change of account would let an operator
        // hosting both accounts see that one device is behind them — which is exactly what a
        // "random string stored in prefs" (the design's first proposal) would have done.
        assertNotEquals(HlcNode.derive(ark(1), deviceA), HlcNode.derive(ark(2), deviceA))
    }

    @Test
    fun `the node does not contain the device id, in any form`() {
        val node = HlcNode.derive(ark(1), deviceA)
        assertFalse("the device id leaked into the node verbatim", node.contains(deviceA))
        // It is an HKDF output, so no substring of the salt survives; the strongest cheap check is
        // that no run of the device id shows up in it.
        deviceA.windowed(size = 6, step = 1).forEach {
            assertFalse("a fragment of the device id leaked into the node", node.contains(it))
        }
    }

    @Test
    fun `the node is lowercase hex of the documented length`() {
        val node = HlcNode.derive(ark(1), deviceA)
        assertEquals(HlcNode.NODE_BYTES * 2, node.length)
        assertTrue("not lowercase hex: '$node'", node.matches(Regex("[0-9a-f]+")))
        // Hex, so it contains neither the Hlc field separator nor either FieldClocks separator,
        // and the clock's wire form stays a two-indexOf parse.
        assertFalse(node.contains("-"))
        assertFalse(node.contains(";"))
        assertFalse(node.contains("="))
    }

    @Test
    fun `leading zero bytes are not dropped`() {
        // A hex helper written on Long.toHexString would silently shorten some nodes, making two
        // devices' nodes different lengths and the encoding non-injective. Search for an ARK whose
        // node starts with a zero nibble and check the length holds.
        var found = false
        for (i in 0..255) {
            val node = HlcNode.derive(ark(i.toByte()), deviceA)
            if (node.startsWith("0")) {
                assertEquals(HlcNode.NODE_BYTES * 2, node.length)
                found = true
                break
            }
        }
        assertTrue("no sample produced a leading zero nibble; widen the search", found)
    }

    @Test
    fun `the derivation is HKDF over the ARK salted with the device id`() {
        // Pins the construction itself, not just its consequences. If this changes, every existing
        // device's node changes with it — harmless for correctness, but it splits that device's
        // history in two from the operator's point of view, so it should be a deliberate act.
        val expected = Hkdf.derive(
            ikm = ark(3),
            salt = deviceA.toByteArray(Charsets.US_ASCII),
            info = HlcNode.INFO_HLC_NODE.toByteArray(Charsets.US_ASCII),
            length = HlcNode.NODE_BYTES,
        ).joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }

        assertEquals(expected, HlcNode.derive(ark(3), deviceA))
    }

    @Test
    fun `the info string is versioned and namespaced like the rest of the protocol`() {
        assertEquals("manana/sync/v1/hlcnode", HlcNode.INFO_HLC_NODE)
        // Distinct from every info string in SyncProtocol, so the node can never collide with a
        // key derived from the same ARK.
        assertNotEquals(SyncProtocol.INFO_CONTENT, HlcNode.INFO_HLC_NODE)
        assertNotEquals(SyncProtocol.INFO_RECORD_ID, HlcNode.INFO_HLC_NODE)
        assertNotEquals(SyncProtocol.INFO_ACCOUNT, HlcNode.INFO_HLC_NODE)
        assertNotEquals(SyncProtocol.INFO_ARK_WRAP, HlcNode.INFO_HLC_NODE)
    }

    @Test
    fun `the ARK is not modified by the derivation`() {
        val key = ark(7)
        HlcNode.derive(key, deviceA)
        assertTrue("the caller's ARK was zeroed or mutated", key.all { it == 7.toByte() })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an empty device id is rejected`() {
        // An empty salt is replaced by zeroes inside HKDF, so every device on the account would
        // silently share one node and the tie-breaker would stop working.
        HlcNode.derive(ark(1), "")
    }
}
