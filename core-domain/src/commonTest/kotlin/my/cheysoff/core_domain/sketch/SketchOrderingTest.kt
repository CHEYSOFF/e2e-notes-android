package my.cheysoff.core_domain.sketch

import my.cheysoff.core_domain.model.SketchData
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The one rule both the phone and the desktop render a note's sketches by: anchor first, id
 * (`uuid`) breaking ties. Runs on both `jvmTest` and `mingwX64Test` -- see [sortSketches]'s own KDoc
 * for why this used to be two copies, pinned only by mirrored tests on each platform, and is now
 * one function both call.
 */
class SketchOrderingTest {

    private fun sketch(id: String, anchor: Int = 0, order: Int = 0) = SketchData(
        id = id,
        noteId = "n1",
        anchor = anchor,
        order = order,
        strokes = "1|10x10",
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    @Test
    fun `orders by anchor first`() {
        val sketches = listOf(sketch("a", anchor = 2), sketch("b", anchor = 0), sketch("c", anchor = 1))

        assertEquals(listOf("b", "c", "a"), sortSketches(sketches).map { it.id })
    }

    @Test
    fun `ties break by id -- not by order or insertion position`() {
        // Same anchor, `order` deliberately disagreeing with the desired id order, insertion order
        // deliberately reversed too -- only an explicit id tie-break can produce "a, b, c" here.
        val sketches = listOf(
            sketch("c", anchor = 0, order = 0),
            sketch("b", anchor = 0, order = 5),
            sketch("a", anchor = 0, order = 9),
        )

        assertEquals(listOf("a", "b", "c"), sortSketches(sketches).map { it.id })
    }
}
