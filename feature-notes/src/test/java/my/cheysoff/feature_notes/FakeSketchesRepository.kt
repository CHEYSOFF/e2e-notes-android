package my.cheysoff.feature_notes

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import my.cheysoff.core_domain.model.SketchData
import my.cheysoff.core_domain.repository.SketchesRepository

/**
 * Hand-written [SketchesRepository] test double, matching [FakeNotesRepository]'s own reasoning:
 * the flow is a [MutableStateFlow] the test drives directly, so an emission happens exactly when
 * the test says the DB holds it, and every call is recorded rather than asserted on indirectly
 * through a mock's verify().
 */
internal class FakeSketchesRepository : SketchesRepository {

    /** The row set [getSketchesForNote] hands out, for whatever noteId the test cares about. */
    val sketchesByNote = MutableStateFlow<List<SketchData>>(emptyList())

    /** Every [SketchData] handed to [saveSketch], in call order. */
    val saved = mutableListOf<SketchData>()

    /** Every id handed to [deleteSketch], in call order. */
    val deleted = mutableListOf<String>()

    override fun getSketchesForNote(noteId: String): Flow<List<SketchData>> = sketchesByNote

    override suspend fun saveSketch(sketch: SketchData) {
        saved += sketch
    }

    override suspend fun deleteSketch(id: String) {
        deleted += id
    }
}
