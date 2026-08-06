package de.velospot.core.tracking

import android.content.Context
import android.os.Build
import de.velospot.core.location.LocationController
import de.velospot.domain.model.RecordedRide
import de.velospot.domain.repository.LocationPowerProfile
import de.velospot.domain.repository.LocationRepository
import de.velospot.domain.repository.RecordedRidesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Regression coverage for the ride-recording **process-boundary desync** fix in
 * [RideRecordingManager] (widget/tile/in-app "Recording ↔ Idle" going out of sync):
 *
 *  1. **Crash-recovery re-sync** — after a killed process's orphaned ride is
 *     recovered/saved and state reset to Idle, `init` now re-paints the out-of-app
 *     controls so a stale "Stop" widget self-corrects.
 *  2. **FGS-start-failure rollback** — when the foreground service is refused with
 *     `ForegroundServiceStartNotAllowedException` (API ≥ 31), `start()` rolls the
 *     in-memory recording back to Idle so no surface can claim "recording" without a
 *     live foreground service. The failure detection is deliberately **narrowed** to
 *     exactly that platform exception so every other (ambiguous) outcome — including
 *     the stubbed `Context` used by the JVM unit tests — keeps the recording.
 *
 * ── Test-environment note (why some assertions are indirect) ──────────────────────
 * These are plain-JVM unit tests: there is no Robolectric on the classpath (it does
 * not resolve for this toolchain) and the bare `android.jar` stub throws
 * "Method … not mocked" for real framework calls. Concretely this means:
 *   • [RideRecordingManager.refreshExternalControls] is *inert* here — its
 *     `Intent.setAction(...)` / `ComponentName(...)` / `TileService.requestListeningState`
 *     calls all throw "not mocked" and are swallowed by the manager's `runCatching`,
 *     so the widget-refresh broadcast is never actually emitted on the JVM. Its effect
 *     therefore can't be asserted via a captured broadcast; we assert the recovery +
 *     Idle reset it is there to re-paint instead. (The broadcast itself needs an
 *     instrumented/Robolectric runtime.)
 *   • For the same reason [RideRecordingManager.start] can never reach
 *     `ContextCompat.startForegroundService` on the JVM (it throws earlier at
 *     `Intent.setAction(...)`), so the real FGS-refusal can't be produced through
 *     `start()`. We therefore drive the two halves of fix #2 directly and
 *     deterministically: the narrowing predicate truth-table
 *     (isForegroundServiceStartNotAllowed) and the rollback cleanup contract
 *     (rollBackFailedStart) — the exact private members the fix added — plus an
 *     end-to-end assertion that a normal `start()` is NOT rolled back.
 */
class RideRecordingDesyncTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Real, cancellable scopes handed to each manager; cancelled + joined in tearDown
     *  so the init IO worker / GPS collector never outlive the test (mirrors the
     *  leaked-coroutine handling documented in MapViewModelTest). */
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach { scope ->
            runCatching {
                val job = scope.coroutineContext[Job]
                scope.cancel()
                runBlocking { job?.join() }
            }
        }
    }

    private fun newScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }

    private fun context(): Context = mock {
        whenever(it.filesDir).doReturn(tmp.root)
        whenever(it.packageName).doReturn("de.velospot")
    }

    private fun locationController(repo: LocationRepository): LocationController {
        whenever(repo.getCurrentLocationFlow()).doReturn(emptyFlow())
        return LocationController(repo)
    }

    private fun awaitTrue(timeoutMs: Long = 2_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    // ── (a) Crash-recovery re-sync ───────────────────────────────────────────────

    /**
     * A recreated process that finds an orphaned session must recover + save the
     * partial ride, clear the session and come up **Idle** — the clean state the
     * newly-added `refreshExternalControls()` call then re-paints onto the widget/tile
     * (that broadcast is inert under the JVM stub, see class note). Before the fix the
     * recovery already ran, but the surfaces were never re-synced; this asserts the
     * recovery pipeline the re-sync is anchored to.
     */
    @Test
    fun `crash recovery saves the orphaned ride and comes up idle`() {
        val ctx = context()

        // Seed an orphaned session on disk (killed mid-ride): two fixes + meta whose
        // distance clears the "too short to keep" recovery gate.
        val seed = RideRecordingPersistence(ctx)
        seed.begin(1_000L)
        seed.appendPoint(de.velospot.domain.model.TrackPoint(50.0000, 8.0000, 1_000L, 5f, 100.0, 4f))
        seed.appendPoint(de.velospot.domain.model.TrackPoint(50.0010, 8.0010, 4_000L, 6f, 102.0, 4f))
        seed.writeMeta(1_000L, distanceMeters = 140.0, movingSeconds = 3, maxSpeedMps = 6.0,
            elevationGain = 2.0, elevationLoss = 0.0)
        assertTrue("precondition: an active session exists", seed.hasActiveSession())

        val saved = CountDownLatch(1)
        var recovered: RecordedRide? = null
        val repo = mock<RecordedRidesRepository>()
        val capturing = object : RecordedRidesRepository by repo {
            override suspend fun saveRide(ride: RecordedRide) { recovered = ride; saved.countDown() }
        }
        val locationRepo = mock<LocationRepository>()

        val manager = RideRecordingManager(
            context = ctx,
            locationController = locationController(locationRepo),
            recordedRidesRepository = capturing,
            scope = newScope(),
        )

        assertTrue("recovered ride should be saved on init", saved.await(2, TimeUnit.SECONDS))
        assertNotNull("recovered ride", recovered)
        val ride = recovered!!
        assertEquals("both persisted fixes recovered", 2, ride.points.size)
        assertEquals("recovered distance from meta", 140.0, ride.distanceMeters, 1e-6)

        // The recreated process is Idle (not falsely "recording"), and the orphaned
        // session is cleared so it isn't replayed again on the next launch.
        assertEquals(RideTrackingUiState.Idle, manager.trackingState.value)
        assertFalse("manager must not think it is recording", manager.isRecording)
        assertTrue(
            "orphaned session cleared after recovery",
            awaitTrue { !RideRecordingPersistence(ctx).hasActiveSession() }
        )
    }

    // ── (c) Narrowing guard: a normal start must NOT roll back ───────────────────

    /**
     * Guards the narrowing that keeps the existing tests green: under the stubbed
     * `Context`, `start()` cannot actually bring the foreground service up (the
     * framework call throws "not mocked"), yet because that failure is **not** the
     * specific `ForegroundServiceStartNotAllowedException`, the recording must be
     * kept — exactly as before the fix. If the failure detection were widened to
     * "any error rolls back", this recording would be torn down and the assertions
     * below would fail.
     */
    @Test
    fun `a normal start is kept and not rolled back`() {
        val ctx = context()
        val locationRepo = mock<LocationRepository>()
        val manager = RideRecordingManager(
            context = ctx,
            locationController = locationController(locationRepo),
            recordedRidesRepository = mock(),
            scope = newScope(),
        )

        manager.start()

        assertTrue("recording is kept despite the stubbed FGS start", manager.isRecording)
        assertFalse(manager.isPaused)
        assertTrue(manager.trackingState.value is RideTrackingUiState.Recording)
        // start() declared its location need and never rolled it back.
        verify(locationRepo).startLocationUpdates(LocationPowerProfile.NAVIGATION_OR_MOVING)
        verify(locationRepo, never()).stopLocationUpdates()
    }

    // ── (b) FGS-refusal rollback ─────────────────────────────────────────────────

    /**
     * The FGS-refusal decision (fix #2's core): `isForegroundServiceStartNotAllowed`
     * must be **true only** for `ForegroundServiceStartNotAllowedException` on API ≥ 31,
     * and **false** for anything else (including the same exception below API 31, via
     * the `Build.VERSION` guard). This is the predicate whose narrowing keeps the
     * stubbed-context tests recording rather than rolling back.
     *
     * `SDK_INT` is a runtime value in the JVM `android.jar` stub, so it is temporarily
     * flipped via `Unsafe`; the API-31 `ForegroundServiceStartNotAllowedException` is
     * allocated without its stubbed constructor for the same reason.
     */
    @Test
    fun `FGS-refusal is classified only for the platform exception on API 31 plus`() {
        val manager = RideRecordingManager(
            context = context(),
            locationController = locationController(mock()),
            recordedRidesRepository = mock(),
            scope = newScope(),
        )
        val predicate = RideRecordingManager::class.java
            .getDeclaredMethod("isForegroundServiceStartNotAllowed", Throwable::class.java)
            .apply { isAccessible = true }
        fun classify(t: Throwable): Boolean = predicate.invoke(manager, t) as Boolean

        val fgs = allocateFgsException()
        val other = RuntimeException("some other failure")

        val original = Build.VERSION.SDK_INT
        try {
            setSdkInt(30)
            assertFalse("below API 31 the platform guard must reject it", classify(fgs))
            assertFalse(classify(other))

            setSdkInt(31)
            assertTrue("API 31+ platform exception is an FGS refusal", classify(fgs))
            assertFalse("a non-FGS error is never a refusal", classify(other))
        } finally {
            setSdkInt(original)
        }
    }

    /**
     * The rollback cleanup contract (fix #2's effect): invoking the exact private
     * `rollBackFailedStart()` the fix added on an in-flight recording must return the
     * manager to a clean **Idle** state, drop the crash-recovery session and release
     * the location need — so no surface can outlive the missing foreground service.
     * (`start()` itself can't reach the real FGS refusal on the JVM stub — see the
     * class note — so the rollback path is driven directly here.)
     */
    @Test
    fun `rollback of a failed start returns to idle and releases location`() {
        val ctx = context()
        val locationRepo = mock<LocationRepository>()
        val manager = RideRecordingManager(
            context = ctx,
            locationController = locationController(locationRepo),
            recordedRidesRepository = mock(),
            scope = newScope(),
        )

        manager.start()
        assertTrue("precondition: started", manager.isRecording)

        RideRecordingManager::class.java
            .getDeclaredMethod("rollBackFailedStart")
            .apply { isAccessible = true }
            .invoke(manager)

        assertFalse("no in-memory recording claim survives the failed start", manager.isRecording)
        assertFalse(manager.isPaused)
        assertEquals(RideTrackingUiState.Idle, manager.trackingState.value)
        // Location need released (setRecording(false) → stop, since map isn't visible).
        verify(locationRepo).stopLocationUpdates()
        // The crash-recovery session is dropped so nothing is replayed next launch.
        assertTrue(
            "crash-recovery session cleared by the rollback",
            awaitTrue { !RideRecordingPersistence(ctx).hasActiveSession() }
        )
    }

    // ── Unsafe helpers (JVM-stub workarounds; see class note) ────────────────────

    private fun unsafe(): sun.misc.Unsafe {
        val f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        f.isAccessible = true
        return f.get(null) as sun.misc.Unsafe
    }

    /** Overwrites the runtime `Build.VERSION.SDK_INT` stub value for the test. */
    private fun setSdkInt(value: Int) {
        val u = unsafe()
        val field = Build.VERSION::class.java.getField("SDK_INT")
        u.putInt(u.staticFieldBase(field), u.staticFieldOffset(field), value)
    }

    /** Allocates the (API-31, `final`) platform exception without its stubbed ctor. */
    private fun allocateFgsException(): android.app.ForegroundServiceStartNotAllowedException =
        unsafe().allocateInstance(android.app.ForegroundServiceStartNotAllowedException::class.java)
            as android.app.ForegroundServiceStartNotAllowedException
}




