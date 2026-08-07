package de.velospot.testsupport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Installs a controlled [TestDispatcher] as `Dispatchers.Main` for the whole
 * lifetime of a test and reliably resets it afterwards.
 *
 * Using a JUnit rule (rather than hand-written `@Before`/`@After` calls) matters
 * for the *ordering* that closes the classic flaky-Main race on the JVM: a rule's
 * [finished] runs **strictly after** the test's own `@After`. That lets a test do
 * all of its coroutine teardown â-- cancelling/joining background scopes and draining
 * the [dispatcher]'s scheduler â-- while the test dispatcher is *still* installed as
 * Main, and only then does the rule call [Dispatchers.resetMain]. Once Main is reset
 * back to the (missing on the JVM) real Android main dispatcher, any late
 * continuation that dispatches onto it throws
 * `IllegalStateException: Module with the Main dispatcher had failed to initialize`
 * (Looper unavailable), which surfaces against the *next* test â-- the exact
 * intermittent CI failure this rule prevents.
 *
 * The same [dispatcher] the rule installs is exposed so tests can advance its
 * scheduler (`dispatcher.scheduler.advanceUntilIdle()`), guaranteeing they drive
 * the very dispatcher their view-model collectors run on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher()
) : TestWatcher() {

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}


