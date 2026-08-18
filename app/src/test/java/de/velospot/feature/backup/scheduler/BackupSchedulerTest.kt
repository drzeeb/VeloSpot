package de.velospot.feature.backup.scheduler
import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import de.velospot.feature.backup.data.BackupScheduleDataStore
import de.velospot.feature.backup.domain.BackupInterval
import de.velospot.feature.backup.domain.BackupSchedule
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Calendar
/**
 * JVM unit tests for the WorkManager glue of [BackupScheduler].
 *
 * A mock [WorkManager] is supplied through the [BackupScheduler.workManagerProvider]
 * testability seam (avoiding the un-unit-testable static WorkManager.getInstance),
 * so the enqueue/cancel branches are verified without an Android runtime. The pure
 * next-fire maths is covered separately in [BackupSchedulerDelayTest].
 */
class BackupSchedulerTest {
    private val context = mock<Context>()
    private val scheduleDataStore = mock<BackupScheduleDataStore>()
    private val workManager = mock<WorkManager>()
    private fun scheduler(schedule: BackupSchedule): BackupScheduler {
        whenever(scheduleDataStore.schedule).thenReturn(flowOf(schedule))
        return BackupScheduler(context, scheduleDataStore).apply {
            workManagerProvider = { workManager }
        }
    }
    /** 2024-06-12 18:00 local time - before an evening daily fire. */
    private val nowBeforeFire: Long = Calendar.getInstance().apply {
        firstDayOfWeek = Calendar.MONDAY
        clear()
        set(2024, Calendar.JUNE, 12, 18, 0, 0)
    }.timeInMillis
    @Test
    fun `an enabled schedule enqueues unique replace work`() = runTest {
        val scheduler = scheduler(
            BackupSchedule(enabled = true, interval = BackupInterval.DAILY, hour = 20)
        )
        scheduler.reschedule(now = nowBeforeFire)
        verify(workManager).enqueueUniqueWork(
            eq(BackupScheduler.UNIQUE_WORK_NAME),
            eq(ExistingWorkPolicy.REPLACE),
            any<OneTimeWorkRequest>()
        )
    }
    @Test
    fun `a disabled schedule cancels the unique work instead of enqueuing`() = runTest {
        val scheduler = scheduler(
            BackupSchedule(enabled = false, interval = BackupInterval.DAILY)
        )
        scheduler.reschedule(now = nowBeforeFire)
        verify(workManager).cancelUniqueWork(BackupScheduler.UNIQUE_WORK_NAME)
        verify(workManager, never()).enqueueUniqueWork(
            any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>()
        )
    }
    @Test
    fun `schedule alias delegates to reschedule`() = runTest {
        val scheduler = scheduler(
            BackupSchedule(enabled = true, interval = BackupInterval.DAILY, hour = 20)
        )
        scheduler.schedule(now = nowBeforeFire)
        verify(workManager).enqueueUniqueWork(
            eq(BackupScheduler.UNIQUE_WORK_NAME),
            eq(ExistingWorkPolicy.REPLACE),
            any<OneTimeWorkRequest>()
        )
    }
    @Test
    fun `cancel cancels the unique work`() {
        val scheduler = scheduler(
            BackupSchedule(enabled = true, interval = BackupInterval.DAILY)
        )
        scheduler.cancel()
        verify(workManager).cancelUniqueWork(BackupScheduler.UNIQUE_WORK_NAME)
    }
}
