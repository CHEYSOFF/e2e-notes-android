package my.cheysoff.feature_notes

import kotlinx.coroutines.CompletableDeferred
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

    /**
     * When set, [saveSketch] suspends here before recording anything -- the hook a test uses to
     * pin down the ViewModel's actual concurrent interleaving (e.g. a `BackClicked` racing an
     * in-flight `SketchSaved`) instead of the two always happening to run sequentially, which is
     * all a plain suspend fun with no real suspension point can ever exercise on
     * `StandardTestDispatcher`. `null` by default, so every other test's timing is unaffected.
     */
    var saveGate: CompletableDeferred<Unit>? = null

    override fun getSketchesForNote(noteId: String): Flow<List<SketchData>> = sketchesByNote

    override suspend fun saveSketch(sketch: SketchData) {
        saveGate?.await()
        saved += sketch
    }

    override suspend fun deleteSketch(id: String) {
        deleted += id
    }
}
