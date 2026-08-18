package de.velospot.feature.backup.presentation

import de.velospot.data.backup.BackupSecretStore
import de.velospot.feature.backup.data.BackupScheduleDataStore
import de.velospot.feature.backup.domain.BackupInterval
import de.velospot.feature.backup.domain.BackupSchedule
import de.velospot.feature.backup.scheduler.BackupScheduler
import de.velospot.testsupport.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import java.util.Calendar

/**
 * JVM unit tests for [BackupScheduleViewModel].
 *
 * The three collaborators are Mockito mocks. Every field edit runs its persistence
 * on the real [kotlinx.coroutines.Dispatchers.Default], so completion is awaited via
 * `verifyBlocking(..., timeout(...))` rather than the test scheduler; the enable gate
 * and the initial `hasPassphrase` probe run on the controlled Main dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupScheduleViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val scheduleDataStore = mock<BackupScheduleDataStore>()
    private val scheduler = mock<BackupScheduler>()
    private val secretStore = mock<BackupSecretStore>()

    private val armed = BackupSchedule(
        enabled = true,
        interval = BackupInterval.DAILY,
        dayOfWeek = Calendar.MONDAY,
        dayOfMonth = 10,
        hour = 8,
        minute = 30
    )
    private val disarmed = armed.copy(enabled = false)

    // --- init -------------------------------------------------------------

    @Test
    fun `init reads the stored passphrase presence`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        whenever(scheduleDataStore.schedule).thenReturn(MutableStateFlow(disarmed))
        whenever(scheduleDataStore.destinationTreeUri).thenReturn(flowOf(null))
        whenever(secretStore.hasPassphrase()).thenReturn(true)

        val viewModel = BackupScheduleViewModel(scheduleDataStore, scheduler, secretStore)
        advanceUntilIdle()

        assertTrue(viewModel.hasPassphrase.value)
    }

    @Test
    fun `exposed state flows start from their initial values`() =
        runTest(mainDispatcherRule.dispatcher.scheduler) {
            whenever(scheduleDataStore.schedule).thenReturn(MutableStateFlow(disarmed))
            whenever(scheduleDataStore.destinationTreeUri).thenReturn(flowOf(null))
            whenever(secretStore.hasPassphrase()).thenReturn(false)

            val viewModel = BackupScheduleViewModel(scheduleDataStore, scheduler, secretStore)
            advanceUntilIdle()

            assertEquals(false, viewModel.hasPassphrase.value)
            assertEquals(null, viewModel.destinationTreeUri.value)
        }

    // --- setEnabled gate --------------------------------------------------

    @Test
    fun `setEnabled true is ignored when destination and passphrase are missing`() =
        runTest(mainDispatcherRule.dispatcher.scheduler) {
            whenever(scheduleDataStore.schedule).thenReturn(MutableStateFlow(disarmed))
            whenever(scheduleDataStore.destinationTreeUri).thenReturn(flowOf(null))
            whenever(secretStore.hasPassphrase()).thenReturn(false)

            val viewModel = BackupScheduleViewModel(scheduleDataStore, scheduler, secretStore)
            advanceUntilIdle()

            viewModel.setEnabled(true)

            // The guard returns synchronously without launching an update coroutine.
            verifyBlocking(scheduler, never()) { reschedule(any()) }
        }

    @Test
    fun `setEnabled false always goes through the update path`() =
        runTest(mainDispatcherRule.dispatcher.scheduler) {
            whenever(scheduleDataStore.schedule).thenReturn(MutableStateFlow(armed))
            whenever(scheduleDataStore.destinationTreeUri).thenReturn(flowOf(null))
            whenever(secretStore.hasPassphrase()).thenReturn(false)

            val viewModel = BackupScheduleViewModel(scheduleDataStore, scheduler, secretStore)
            advanceUntilIdle()

            viewModel.setEnabled(false)

            verifyBlocking(scheduleDataStore, timeout(2_000)) { setSchedule(disarmed) }
            verifyBlocking(scheduler, timeout(2_000)) { reschedule(any()) }
        }

    // --- field edits ------------------------------------------------------

    @Test
    fun `setInterval persists the changed schedule and reschedules`() =
        runTest(mainDispatcherRule.dispatcher.scheduler) {
            whenever(scheduleDataStore.schedule).thenReturn(MutableStateFlow(disarmed))
            whenever(scheduleDataStore.destinationTreeUri).thenReturn(flowOf(null))
            whenever(secretStore.hasPassphrase()).thenReturn(false)

            val viewModel = BackupScheduleViewModel(scheduleDataStore, scheduler, secretStore)
            advanceUntilIdle()

            viewModel.setInterval(BackupInterval.MONTHLY)

            verifyBlocking(scheduleDataStore, timeout(2_000)) {
                setSchedule(disarmed.copy(interval = BackupInterval.MONTHLY))
            }
            verifyBlocking(scheduler, timeout(2_000)) { reschedule(any()) }
        }

    @Test
    fun `day and time edits reschedule`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        whenever(scheduleDataStore.schedule).thenReturn(MutableStateFlow(disarmed))
        whenever(scheduleDataStore.destinationTreeUri).thenReturn(flowOf(null))
        whenever(secretStore.hasPassphrase()).thenReturn(false)

        val viewModel = BackupScheduleViewModel(scheduleDataStore, scheduler, secretStore)
        advanceUntilIdle()

        viewModel.setDayOfWeek(Calendar.WEDNESDAY)
        viewModel.setDayOfMonth(15)
        viewModel.setTime(6, 45)

        verifyBlocking(scheduler, timeout(2_000).atLeast(3)) { reschedule(any()) }
    }

    // --- destination ------------------------------------------------------

    @Test
    fun `setDestination stores the tree uri and reschedules`() =
        runTest(mainDispatcherRule.dispatcher.scheduler) {
            whenever(scheduleDataStore.schedule).thenReturn(MutableStateFlow(disarmed))
            whenever(scheduleDataStore.destinationTreeUri).thenReturn(flowOf(null))
            whenever(secretStore.hasPassphrase()).thenReturn(false)

            val viewModel = BackupScheduleViewModel(scheduleDataStore, scheduler, secretStore)
            advanceUntilIdle()

            viewModel.setDestination("content://tree/primary")

            verifyBlocking(scheduleDataStore, timeout(2_000)) {
                setDestinationTreeUri("content://tree/primary")
            }
            verifyBlocking(scheduler, timeout(2_000)) { reschedule(any()) }
        }

    @Test
    fun `clearing the destination disables an armed schedule`() =
        runTest(mainDispatcherRule.dispatcher.scheduler) {
            whenever(scheduleDataStore.schedule).thenReturn(MutableStateFlow(armed))
            whenever(scheduleDataStore.destinationTreeUri).thenReturn(flowOf("content://tree"))
            whenever(secretStore.hasPassphrase()).thenReturn(true)

            val viewModel = BackupScheduleViewModel(scheduleDataStore, scheduler, secretStore)
            advanceUntilIdle()

            viewModel.setDestination(null)

            verifyBlocking(scheduleDataStore, timeout(2_000)) { setDestinationTreeUri(null) }
            verifyBlocking(scheduleDataStore, timeout(2_000)) { setSchedule(disarmed) }
            verifyBlocking(scheduler, timeout(2_000)) { reschedule(any()) }
        }

    // --- passphrase -------------------------------------------------------

    @Test
    fun `setPassphrase stores a trimmed secret and flips hasPassphrase`() =
        runTest(mainDispatcherRule.dispatcher.scheduler) {
            whenever(scheduleDataStore.schedule).thenReturn(MutableStateFlow(disarmed))
            whenever(scheduleDataStore.destinationTreeUri).thenReturn(flowOf(null))
            whenever(secretStore.hasPassphrase()).thenReturn(false)

            val viewModel = BackupScheduleViewModel(scheduleDataStore, scheduler, secretStore)
            advanceUntilIdle()

            viewModel.setPassphrase("  hunter2  ")

            verifyBlocking(secretStore, timeout(2_000)) { setPassphrase("hunter2") }
            verifyBlocking(scheduler, timeout(2_000)) { reschedule(any()) }
            assertTrue(viewModel.hasPassphrase.value)
        }

    @Test
    fun `blank passphrase clears the secret and disables an armed schedule`() =
        runTest(mainDispatcherRule.dispatcher.scheduler) {
            whenever(scheduleDataStore.schedule).thenReturn(MutableStateFlow(armed))
            whenever(scheduleDataStore.destinationTreeUri).thenReturn(flowOf("content://tree"))
            whenever(secretStore.hasPassphrase()).thenReturn(true)

            val viewModel = BackupScheduleViewModel(scheduleDataStore, scheduler, secretStore)
            advanceUntilIdle()

            viewModel.setPassphrase("   ")

            verifyBlocking(secretStore, timeout(2_000)) { clear() }
            verifyBlocking(scheduleDataStore, timeout(2_000)) { setSchedule(disarmed) }
            verifyBlocking(scheduler, timeout(2_000)) { reschedule(any()) }
            assertFalse(viewModel.hasPassphrase.value)
        }
}


