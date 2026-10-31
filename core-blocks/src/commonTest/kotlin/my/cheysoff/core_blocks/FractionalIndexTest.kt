package my.cheysoff.core_blocks

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FractionalIndexTest {

    @Test
    fun `between two open ends yields a non empty key`() {
        val key = FractionalIndex.between(null, null)
        assertTrue(key.isNotEmpty())
        assertFalse(key.endsWith('0'))
    }

    @Test
    fun `a thousand random insertions keep keys strictly sorted`() {
        val rnd = Random(42)
        val keys = mutableListOf<String>()
        repeat(1000) {
            val i = rnd.nextInt(keys.size + 1) // insert before keys[i]
            val key = FractionalIndex.between(keys.getOrNull(i - 1), keys.getOrNull(i))
            assertFalse(key.endsWith('0'), "key $key ends in 0")
            keys.add(i, key)
        }
        for (i in 1 until keys.size) {
            assertTrue(keys[i - 1] < keys[i], "${keys[i - 1]} !< ${keys[i]}")
        }
    }

    @Test
    fun `repeated insertion at one end stays ordered`() {
        var first = FractionalIndex.between(null, null)
        repeat(200) {
            val next = FractionalIndex.between(null, first)
            assertTrue(next < first)
            first = next
        }
        var last = first
        repeat(200) {
            val next = FractionalIndex.between(last, null)
            assertTrue(next > last)
            last = next
        }
    }

    @Test
    fun `between keys with a shared prefix lies strictly between`() {
        val mid = FractionalIndex.between("a", "a01")
        assertTrue("a" < mid && mid < "a01", mid)
        assertEquals(true, FractionalIndex.between("a1", "a2") .let { "a1" < it && it < "a2" })
    }

    @Test
    fun `out of order or non canonical bounds are rejected`() {
        assertFailsWith<IllegalArgumentException> { FractionalIndex.between("b", "a") }
        assertFailsWith<IllegalArgumentException> { FractionalIndex.between("a", "a") }
        // "a0" is the same fraction as "a": no key can sit strictly between them.
        assertFailsWith<IllegalArgumentException> { FractionalIndex.between("a", "a0") }
        assertFailsWith<IllegalArgumentException> { FractionalIndex.between("a-", null) }
    }
}
