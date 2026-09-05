package my.cheysoff.desktop.store

import kotlinx.coroutines.flow.Flow
import my.cheysoff.core_domain.model.SketchData

/**
 * The desktop UI's one seam onto a note's sketches: read them live, and delete one.
 *
 * This is deliberately its own interface rather than a widening of `NotesRepository` (the interface
 * `NotesWorkspaceModel` is otherwise coded against): sketches are not a `NotesRepository` concern on
 * any platform -- the phone reads and writes them through the separate `SketchesRepository` -- and
 * `NotesWorkspaceModel` still has to run against `InMemoryNotesRepository` in the preview/screenshot
 * entry point, which carries no sketch storage of its own. Making this its own nullable dependency
 * (see [NotesWorkspaceModel]'s constructor) means the preview simply omits it and the sketch section
 * does not appear, rather than that build having to fake a whole sketch store for a feature it never
 * exercises.
 *
 * Plan 3's desktop task is render-and-delete, not draw (see task 6's brief) -- so unlike
 * `SketchesRepository`, there is no `saveSketch` here. Adding one with no caller would be an
 * untested, unreachable seam.
 *
 * [RecordNotesRepository] is the only real implementation.
 */
interface DesktopSketches {

    /** Sketches anchored under [noteId] that are not soft-deleted, live, unsorted. */
    fun getSketchesForNote(noteId: String): Flow<List<SketchData>>

    /** Soft-deletes one sketch. See [RecordNotesRepository.deleteSketch] for what that means here. */
    suspend fun deleteSketch(id: String)
}
