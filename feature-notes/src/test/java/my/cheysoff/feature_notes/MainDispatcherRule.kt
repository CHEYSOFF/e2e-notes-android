package my.cheysoff.feature_notes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Swaps [Dispatchers.Main] for a [TestDispatcher] around each test.
 *
 * Every ViewModel here launches on `viewModelScope`, whose context is `Dispatchers.Main.immediate`.
 * Outside an Android runtime there is no main looper to back it, so the very first `launch` throws
 * `IllegalStateException: Module with the Main dispatcher had failed to initialize` — which is why
 * none of these classes could be unit-tested before `kotlinx-coroutines-test` was available.
 *
 * The dispatcher is created here and exposed, so a test can hand the SAME instance to `runTest`.
 * That is what puts the ViewModel's coroutines and the test body on one [
 * kotlinx.coroutines.test.TestCoroutineScheduler]: `advanceTimeBy`/`advanceUntilIdle` in the test
 * then drive the ViewModel's `delay(300)` autosave debounce on virtual time, with no real waiting
 * and no flakiness.
 *
 * [StandardTestDispatcher] rather than `UnconfinedTestDispatcher` deliberately. Unconfined runs
 * each coroutine eagerly at the point it is launched, which would hide exactly the orderings these
 * tests exist to pin down — a queued write, an emission that arrives between two writes, a save
 * still sitting in its debounce window. With the standard dispatcher nothing runs until the test
 * asks it to, so each test states its own interleaving.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
