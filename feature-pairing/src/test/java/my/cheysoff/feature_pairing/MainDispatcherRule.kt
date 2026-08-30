package my.cheysoff.feature_pairing

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
 * A near-copy of `:feature-notes`' rule of the same name, duplicated rather than shared because
 * this project has no test-fixtures module and Gradle does not put one module's `src/test` on
 * another's classpath. Ten lines of duplication is cheaper than a shared test module that exists
 * for one class.
 *
 * [StandardTestDispatcher] rather than unconfined, for the same reason as over there: the
 * ViewModel's countdown is a `delay(1000)` loop, and the point of these tests is to say when it
 * runs rather than to let it run eagerly wherever it was launched.
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
