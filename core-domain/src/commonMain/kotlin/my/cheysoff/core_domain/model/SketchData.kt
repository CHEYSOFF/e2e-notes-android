package my.cheysoff.core_domain.model

/**
 * One drawing, as a record: its identity, where it belongs, and its geometry as stored text.
 *
 * [strokes] is deliberately the ENCODED string rather than a parsed `Sketch`. This type crosses the
 * repository and sync boundaries, where the value is only ever moved, merged and compared -- and
 * comparing decoded geometry for equality is both slower and less strict than comparing the bytes
 * that will actually be written. Decode at the edge that draws it. See `StrokeCodec`.
 */
data class SketchData(
    val id: String,
    val noteId: String,
    /** Index over the owning note's top-level blocks. See `NoteBlocks`. */
    val anchor: Int,
    /** Position among sketches sharing one anchor; ties break by [id] so both devices agree. */
    val order: Int,
    val strokes: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
)
