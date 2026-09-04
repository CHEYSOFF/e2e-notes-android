package my.cheysoff.core_domain.repository

import kotlinx.coroutines.flow.Flow
import my.cheysoff.core_domain.model.SketchData

/**
 * The seam a UI (or a test standing in for one) uses to put a drawing somewhere real, instead of
 * writing through a DAO directly. A test that seeds sketches through `SketchDao` while claiming to
 * exercise "the real path" would hide exactly the kind of dead seam that survived four reviews
 * earlier in this project — this interface is what makes that claim checkable.
 */
interface SketchesRepository {

    /** Sketches anchored under [noteId], visible ones only, in drawing order. */
    fun getSketchesForNote(noteId: String): Flow<List<SketchData>>

    /**
     * Creates or updates a sketch. One method for both, matching `NotesRepository.saveNote`: the
     * caller does not have to know whether [sketch]'s id already exists, and the implementation
     * decides — per field — what actually changed so an unrelated device's concurrent edit to the
     * other field is not clobbered. See `FieldClocks.SKETCH_FIELDS`.
     */
    suspend fun saveSketch(sketch: SketchData)

    /**
     * Soft-deletes one sketch by id: its own tombstone, its own fresh clock, `dirty` set so it is
     * pushed. Mirrors `NotesRepository.deleteNote`.
     *
     * Before this existed, the only way to trash a single sketch was `saveSketch(copy(isDeleted =
     * true))` — which hands `createdAt`/`updatedAt` stamping to the caller (`SketchData`'s are
     * caller-owned, unlike `Note`'s) and leaves it to hand-roll `deletedAt` correctly too. This is
     * the seam that does both, so nobody has to.
     */
    suspend fun deleteSketch(id: String)
}
