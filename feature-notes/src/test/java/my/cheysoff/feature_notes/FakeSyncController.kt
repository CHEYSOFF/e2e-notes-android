package my.cheysoff.feature_notes

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import my.cheysoff.core_domain.sync.SyncController
import my.cheysoff.core_domain.sync.SyncPassState
import my.cheysoff.core_domain.sync.SyncTrigger

/**
 * A [SyncController] that records what it was asked to do and answers with whatever the test set.
 *
 * It never touches a network or a database, which is the point: the notes list's job is to ask for
 * a pass and render what came back, and a fake that actually synced would make every assertion here
 * a test of the engine instead.
 */
internal class FakeSyncController(
    private var next: SyncPassState = SyncPassState.Completed(
        my.cheysoff.core_domain.sync.SyncPassSummary(),
    ),
) : SyncController {

    private val _state = MutableStateFlow<SyncPassState>(SyncPassState.Idle)
    override val state: StateFlow<SyncPassState> = _state.asStateFlow()

    /** Every trigger this controller was handed, in order. */
    val triggers = mutableListOf<SyncTrigger>()

    /** What the next [syncNow] will answer with. */
    fun answerWith(result: SyncPassState) {
        next = result
    }

    override fun requestSync(trigger: SyncTrigger) {
        triggers += trigger
    }

    override suspend fun syncNow(trigger: SyncTrigger): SyncPassState {
        triggers += trigger
        _state.value = next
        return next
    }
}
