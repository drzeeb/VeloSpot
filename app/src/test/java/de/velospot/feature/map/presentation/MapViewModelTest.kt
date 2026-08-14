package de.velospot.feature.map.presentation

import android.content.Context
import de.velospot.core.map.LayerVisibility
import de.velospot.core.map.MapLayerCategory
import de.velospot.core.map.RideViewOptions
import de.velospot.data.brouter.BRouterSegmentManager
import de.velospot.data.geocoding.PhotonGeocoder
import de.velospot.domain.model.AddressSearchResult
import de.velospot.domain.model.BikeParkingSpace
import de.velospot.domain.model.BikeRoute
import de.velospot.domain.model.BikeParkingType
import de.velospot.domain.model.PlannedRoute
import de.velospot.domain.model.BoundingBox
import de.velospot.domain.model.EmptyRouteGeometryException
import de.velospot.domain.model.GeoCoordinate
import de.velospot.domain.model.MapError
import de.velospot.domain.model.NoRouteFoundException
import de.velospot.domain.model.ParkedBike
import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.RecordedRideSummary
import de.velospot.domain.model.RoutePoint
import de.velospot.domain.model.RoutingFailedException
import de.velospot.domain.model.SavedPlace
import de.velospot.domain.repository.BikeParkingRepository
import de.velospot.domain.repository.FavoritesRepository
import de.velospot.domain.repository.LocationPowerProfile
import de.velospot.domain.repository.LocationRepository
import de.velospot.domain.repository.MapSettingsRepository
import de.velospot.domain.repository.ParkedBikeRepository
import de.velospot.domain.repository.RecordedRidesRepository
import de.velospot.domain.repository.RoutingRepository
import de.velospot.domain.repository.SavedPlacesRepository
import de.velospot.testsupport.MainDispatcherRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModelTest {

    /**
     * Installs a [StandardTestDispatcher] as `Dispatchers.Main` and — crucially —
     * only resets Main in its `finished` hook, which runs *after* [tearDown]. That
     * ordering lets [tearDown] cancel/join every coroutine and drain the scheduler
     * while the test dispatcher is still Main, so nothing can dispatch onto a
     * reset (missing on the JVM) Main afterwards. See [MainDispatcherRule].
     */
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val testDispatcher get() = mainDispatcherRule.dispatcher

    private lateinit var mockContext: Context
    private lateinit var mockSegmentManager: BRouterSegmentManager
    private lateinit var mockOfflineMapTilesManager: de.velospot.data.maptiles.OfflineMapTilesManager
    private lateinit var mockPhotonGeocoder: PhotonGeocoder

    /**
     * Every [MapViewModel] built by [makeViewModel] is tracked here and cleared in
     * [tearDown]. Without this the view-models' `viewModelScope` coroutines (e.g.
     * the infinite location/favorites flow collectors) outlive the test and keep
     * running on the shared main dispatcher; an exception thrown by such a leaked
     * coroutine then surfaces against the *next* test as `UncaughtExceptionsBeforeTest`,
     * making the suite flaky on CI. Clearing cancels each `viewModelScope`.
     */
    private val createdViewModels = mutableListOf<MapViewModel>()

    /**
     * Real, cancellable scopes handed to each `RideRecordingManager`. The manager
     * runs background work (a 1 s stats ticker + GPS collector) on its own scope
     * that `ViewModel.clear()` does NOT touch; navigation tests auto-start a
     * recording and never stop it, so on the default scope that work would keep
     * running on background threads across every later test and — firing after
     * `resetMain()` — bridge into the (now reset) main dispatcher, throwing and
     * surfacing as a flaky `UncaughtExceptionsBeforeTest`. We hand the manager a
     * **real** Default scope (never a test scheduler, whose `advanceUntilIdle()`
     * would spin forever on the ticker's endless `delay` loop) and cancel it here.
     */
    private val createdManagerScopes = mutableListOf<kotlinx.coroutines.CoroutineScope>()

    @Before
    fun setUp() {
        // Main is installed by `mainDispatcherRule` (before this runs) and reset by
        // it *after* tearDown, so the teardown drain below happens while the test
        // dispatcher is still Main.
        mockContext = mock()
        mockSegmentManager = mock()
        mockOfflineMapTilesManager = mock()
        mockPhotonGeocoder = mock()

        // SharedPreferences stub so OfflineRoutingPreferences doesn't crash
        val sharedPrefs = mock<android.content.SharedPreferences>()
        val editor = mock<android.content.SharedPreferences.Editor>()
        whenever(mockContext.getSharedPreferences(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenReturn(sharedPrefs)
        whenever(sharedPrefs.getBoolean(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenReturn(false)
        whenever(sharedPrefs.getString(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenReturn(null)
        whenever(sharedPrefs.edit()).thenReturn(editor)
        whenever(editor.putBoolean(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenReturn(editor)
        whenever(editor.putString(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenReturn(editor)

        // Synthetic navigation destinations (parked bike / round trip / custom pin /
        // saved place) derive their labels from string resources; a bare mock Context
        // returns null and the non-null `getString` contract would NPE. Return a
        // stable placeholder for both the plain and the formatted overloads.
        whenever(mockContext.getString(org.mockito.kotlin.any())).thenReturn("label")
        whenever(mockContext.getString(org.mockito.kotlin.any(), org.mockito.kotlin.anyVararg()))
            .thenReturn("label")
    }

    @After
    fun tearDown() {
        // The flaky CI failure ("Module with the Main dispatcher had failed to
        // initialize" → Looper unavailable in JVM, surfacing as an
        // UncaughtExceptionsBeforeTest against the *next* test) comes from a leaked
        // background coroutine that emits *after* `resetMain()`. Navigation tests
        // auto-start a ride recording whose RideRecordingManager runs a 1 s stats
        // ticker + GPS collector on a real Default scope; those never get stopped by
        // the test body. A late emission to the manager's `trackingState` then wakes
        // the eager `viewModelScope` collectors in RideTrackingController (which
        // combine the manager flows) and forces a dispatch onto the now-reset Main
        // dispatcher. We close the race deterministically, in strict order:

        // 1) Stop any still-running (navigation-auto-started) recording FIRST, while
        //    Main is still the test dispatcher. `discardRideTracking()` synchronously
        //    cancels the manager's ticker + GPS jobs (`stopTicker()` / `locationJob`)
        //    and flips the tracker out of the recording state, so the ticker's
        //    `while (isActive && tracker.isRecording)` loop can no longer emit. It's a
        //    no-op for view-models that never recorded.
        createdViewModels.forEach { vm -> runCatching { vm.discardRideTracking() } }

        // 2) Cancel each manager's background scope and BLOCK until every child job
        //    (ticker, GPS collector, persistence worker) has fully finished. Using
        //    cancelAndJoin guarantees no coroutine is still live on a background thread
        //    once we continue — so nothing can emit after this point. A real, cancellable
        //    Default scope is used (never the test scheduler, whose advanceUntilIdle()
        //    would spin forever on the ticker's endless delay loop).
        createdManagerScopes.forEach { scope ->
            runCatching {
                kotlinx.coroutines.runBlocking {
                    scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
                }
            }
        }
        createdManagerScopes.clear()

        // 3) Cancel each view-model's viewModelScope so its collector coroutines are
        //    torn down (still on the live test Main dispatcher, so their cancellation
        //    dispatches cleanly). ViewModel.clear() is not public, so reach it
        //    reflectively.
        createdViewModels.forEach { vm ->
            runCatching {
                androidx.lifecycle.ViewModel::class.java
                    .getDeclaredMethod("clear")
                    .apply { isAccessible = true }
                    .invoke(vm)
            }
        }
        createdViewModels.clear()

        // 4) Drain the test scheduler while the test dispatcher is STILL Main. Steps
        //    1–3 above cancel the view-model scopes and their `combine(...).stateIn`
        //    collectors; that cancellation (and any StateFlow write from step 1) posts
        //    cleanup continuations onto the (test) Main dispatcher. If we reset Main
        //    before those run, a later dispatch onto the now-reset Main would throw
        //    `IllegalStateException` (Looper unavailable) and surface against the next
        //    test — the flaky failure. Running the scheduler to idle drains them
        //    deterministically before Main is reset.
        testDispatcher.scheduler.advanceUntilIdle()

        // 5) Main is reset by `mainDispatcherRule.finished()`, which runs strictly
        //    after this method — at which point no live coroutine remains that could
        //    dispatch onto it.
    }

    private fun makeViewModel(
        bikeParkingRepository: BikeParkingRepository = FakeBikeParkingRepository(),
        favoritesRepository: FavoritesRepository = FakeFavoritesRepository(),
        locationRepository: LocationRepository = FakeLocationRepository(),
        routingRepository: RoutingRepository = FakeRoutingRepository(),
        // Defaulted so all existing call sites are unaffected. Tests that exercise
        // the opened-GPX cold-start hand-off pass ONE shared bus to two view-models.
        gpxOpenBus: de.velospot.core.gpx.GpxOpenBus = de.velospot.core.gpx.GpxOpenBus()
    ): MapViewModel {
        val recordedRidesRepository = FakeRecordedRidesRepository()
        // Hand the manager a REAL but cancellable Default scope (cancelled in tearDown)
        // so its background ticker / GPS collector can't leak across tests. Never a
        // test scheduler — its `advanceUntilIdle()` would spin forever on the ticker.
        val managerScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + Dispatchers.Default
        ).also { createdManagerScopes.add(it) }
        // Single GPS owner shared by the manager and the ViewModel (as in production).
        val locationController = de.velospot.core.location.LocationController(locationRepository)
        return MapViewModel(
            bikeParkingRepository = bikeParkingRepository,
            favoritesRepository   = favoritesRepository,
            locationController    = locationController,
            routingRepository     = routingRepository,
            segmentManager        = mockSegmentManager,
            offlineMapTilesManager = mockOfflineMapTilesManager,
            photonGeocoder        = mockPhotonGeocoder,
            recordingManager      = de.velospot.core.tracking.RideRecordingManager(
                context = mockContext,
                locationController = locationController,
                recordedRidesRepository = recordedRidesRepository,
                scope = managerScope
            ),
            gpxFileStore          = de.velospot.data.gpx.GpxFileStore(mockContext),
            gpxOpenBus            = gpxOpenBus,
            savedPlacesRepository = FakeSavedPlacesRepository(),
            parkedBikeRepository  = FakeParkedBikeRepository(),
            recordedRidesRepository = recordedRidesRepository,
            plannedRoutesRepository = FakePlannedRoutesRepository(),
            destinationHistoryRepository = FakeDestinationHistoryRepository(),
            mapSettings           = FakeMapSettingsRepository(),
            sensorRepository      = FakeSensorRepository(),
            weatherRepository     = FakeWeatherRepository(),
            backupManager         = org.mockito.kotlin.mock(),
            context               = mockContext
        ).also { createdViewModels.add(it) }
    }

    @Test
    fun `loadParkingSpaces emits success when repository returns data`() = runTest {
        val expected = listOf(sampleSpace(id = "1"), sampleSpace(id = "2"))
        val viewModel = makeViewModel(
            bikeParkingRepository = FakeBikeParkingRepository(expected)
        )

        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is MapUiState.Success)
        assertEquals(expected, (state as MapUiState.Success).spaces)
    }

    @Test
    fun `toggleFavorite adds then removes favorite`() = runTest {
        val favoritesRepository = FakeFavoritesRepository()
        val viewModel = makeViewModel(
            bikeParkingRepository = FakeBikeParkingRepository(emptyList()),
            favoritesRepository = favoritesRepository
        )

        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.toggleFavorite("space-1")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("space-1"), viewModel.favorites.value)

        viewModel.toggleFavorite("space-1")
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.favorites.value.isEmpty())
    }

    @Test
    fun `centerMapOnUserLocation emits camera target when location is available`() = runTest {
        val locationRepository = FakeLocationRepository(
            initialLocation = GeoCoordinate(latitude = 49.75, longitude = 6.64)
        )
        val viewModel = makeViewModel(
            bikeParkingRepository = FakeBikeParkingRepository(emptyList()),
            locationRepository = locationRepository
        )

        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.centerMapOnUserLocation()

        assertEquals(
            MapCameraTarget(latitude = 49.75, longitude = 6.64, zoom = 16.0),
            viewModel.mapCameraTarget.value
        )

        viewModel.onMapCameraTargetHandled()
        assertEquals(null, viewModel.mapCameraTarget.value)
    }

    @Test
    fun `onLocationPermissionGranted starts location updates`() = runTest {
        val locationRepository = FakeLocationRepository()
        val viewModel = makeViewModel(
            bikeParkingRepository = FakeBikeParkingRepository(emptyList()),
            locationRepository = locationRepository
        )

        testDispatcher.scheduler.advanceUntilIdle()
        val callsBefore = locationRepository.startUpdatesCallCount

        viewModel.onLocationPermissionGranted()

        assertEquals(callsBefore + 1, locationRepository.startUpdatesCallCount)
    }

    @Test
    fun `startInAppNavigation emits active route when location is available`() = runTest {
        val destination = sampleSpace(id = "target")
        val expectedRoute = BikeRoute(
            points = listOf(
                RoutePoint(latitude = 49.75, longitude = 6.64),
                RoutePoint(latitude = 49.76, longitude = 6.65)
            ),
            distanceMeters = 1200.0,
            durationSeconds = 420.0
        )
        val viewModel = makeViewModel(
            bikeParkingRepository = FakeBikeParkingRepository(listOf(destination)),
            locationRepository = FakeLocationRepository(
                initialLocation = GeoCoordinate(latitude = 49.75, longitude = 6.64)
            ),
            routingRepository = FakeRoutingRepository(route = expectedRoute)
        )

        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.startInAppNavigation(destination)

        assertTrue(viewModel.navigationUiState.value is NavigationUiState.Loading)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.navigationUiState.value
        assertTrue(state is NavigationUiState.Active)
        assertEquals(destination, (state as NavigationUiState.Active).destination)
        assertEquals(expectedRoute, state.route)
        assertEquals(null, viewModel.selectedSpace.value)
    }

    @Test
    fun `startInAppNavigation emits error when location is missing`() = runTest {
        val viewModel = makeViewModel(
            bikeParkingRepository = FakeBikeParkingRepository(emptyList()),
            locationRepository = FakeLocationRepository(initialLocation = null)
        )

        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.startInAppNavigation(sampleSpace(id = "target"))

        val state = viewModel.navigationUiState.value
        assertTrue(state is NavigationUiState.Error)
        assertEquals(MapError.LocationUnavailable, (state as NavigationUiState.Error).error)
    }

    @Test
    fun `findNearestParkingAndNavigate picks the shortest bike route not the crow-flies nearest`() = runTest {
        // Fix at (49.75, 6.64). Spot A is crow-flies-closer, but its real bike route
        // is much longer (e.g. it's across railway tracks). Spot B is slightly farther
        // in a straight line but a much shorter route, so navigation must go to B.
        val fix = GeoCoordinate(latitude = 49.75, longitude = 6.64)
        val spotA = sampleSpace(id = "A").copy(latitude = 49.7505, longitude = 6.6405)
        val spotB = sampleSpace(id = "B").copy(latitude = 49.7515, longitude = 6.6415)
        val viewModel = makeViewModel(
            bikeParkingRepository = FakeBikeParkingRepository(listOf(spotA, spotB)),
            locationRepository = FakeLocationRepository(initialLocation = fix),
            routingRepository = FakeRoutingRepository(
                distanceByDestination = mapOf(
                    GeoCoordinate(spotA.latitude, spotA.longitude) to 3_000.0,
                    GeoCoordinate(spotB.latitude, spotB.longitude) to 800.0
                )
            )
        )

        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.findNearestParkingAndNavigate()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.navigationUiState.value
        assertTrue(state is NavigationUiState.Active)
        assertEquals(spotB, (state as NavigationUiState.Active).destination)
    }

    @Test
    fun `findNearestParkingAndNavigate falls back to crow-flies nearest when routing fails`() = runTest {
        // Routing fails for every candidate ⇒ fall back to the crow-flies nearest (A).
        // With no routes to rank, navigation is (re-)attempted to A: assert it is the
        // last destination the routing repository was asked to route to.
        val fix = GeoCoordinate(latitude = 49.75, longitude = 6.64)
        val near = sampleSpace(id = "A").copy(latitude = 49.7502, longitude = 6.6402)
        val far = sampleSpace(id = "B").copy(latitude = 49.7515, longitude = 6.6415)
        val routing = FakeRoutingRepository(error = RuntimeException("no route"))
        val viewModel = makeViewModel(
            bikeParkingRepository = FakeBikeParkingRepository(listOf(far, near)),
            locationRepository = FakeLocationRepository(initialLocation = fix),
            routingRepository = routing
        )

        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.findNearestParkingAndNavigate()
        testDispatcher.scheduler.advanceUntilIdle()

        // The final routing attempt is the fallback navigation to the crow-flies nearest.
        assertEquals(GeoCoordinate(near.latitude, near.longitude), routing.lastTo)
    }

    @Test
    fun `findNearestParkingAndNavigate with no spaces sets message and does not navigate`() = runTest {
        val viewModel = makeViewModel(
            bikeParkingRepository = FakeBikeParkingRepository(emptyList()),
            locationRepository = FakeLocationRepository(
                initialLocation = GeoCoordinate(latitude = 49.75, longitude = 6.64)
            )
        )

        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.findNearestParkingAndNavigate()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(de.velospot.R.string.park_find_none, viewModel.userMessageRes.value)
        assertTrue(viewModel.navigationUiState.value is NavigationUiState.Idle)
    }

    @Test
    fun `findNearestParkingAndNavigate without a fix surfaces location unavailable`() = runTest {
        val repository = FakeBikeParkingRepository(listOf(sampleSpace(id = "s")))
        val viewModel = makeViewModel(
            bikeParkingRepository = repository,
            locationRepository = FakeLocationRepository(initialLocation = null)
        )

        testDispatcher.scheduler.advanceUntilIdle()
        val queriesBefore = repository.boundingBoxQueryCount
        viewModel.findNearestParkingAndNavigate()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.navigationUiState.value
        assertTrue(state is NavigationUiState.Error)
        assertEquals(MapError.LocationUnavailable, (state as NavigationUiState.Error).error)
        // No fix ⇒ no nearest search was attempted.
        assertEquals(queriesBefore, repository.boundingBoxQueryCount)
    }

    @Test
    fun `nearestSpace picks the closest by distance`() {
        val fixLat = 49.75
        val fixLon = 6.64
        val far = sampleSpace(id = "far").copy(latitude = 49.80, longitude = 6.70)
        val near = sampleSpace(id = "near").copy(latitude = 49.7502, longitude = 6.6402)
        val picked = nearestSpace(fixLat, fixLon, listOf(far, near))
        assertEquals(near, picked)
        assertEquals(null, nearestSpace(fixLat, fixLon, emptyList()))
    }

    @Test
    fun `nearestSpaces returns the k nearest sorted nearest-first`() {
        val fixLat = 49.75
        val fixLon = 6.64
        val a = sampleSpace(id = "a").copy(latitude = 49.7501, longitude = 6.6401)
        val b = sampleSpace(id = "b").copy(latitude = 49.7510, longitude = 6.6410)
        val c = sampleSpace(id = "c").copy(latitude = 49.8000, longitude = 6.7000)
        val picked = nearestSpaces(fixLat, fixLon, listOf(c, b, a), k = 2)
        assertEquals(listOf(a, b), picked)
    }

    @Test
    fun `shortestRoutedSpace picks the smallest non-null distance`() {
        val a = sampleSpace(id = "a")
        val b = sampleSpace(id = "b")
        val c = sampleSpace(id = "c")
        val picked = shortestRoutedSpace(
            listOf(a to 3_000.0, b to 800.0, c to null)
        )
        assertEquals(b, picked)
    }

    @Test
    fun `shortestRoutedSpace returns null when all distances are null`() {
        val a = sampleSpace(id = "a")
        val b = sampleSpace(id = "b")
        assertEquals(null, shortestRoutedSpace(listOf(a to null, b to null)))
        assertEquals(null, shortestRoutedSpace(emptyList()))
    }

    @Test
    fun `boundingBoxAround contains the point and grows with radius`() {
        val lat = 49.75
        val lon = 6.64
        val small = boundingBoxAround(lat, lon, 1.0)
        val large = boundingBoxAround(lat, lon, 8.0)

        // The centre point lies inside both boxes.
        assertTrue(lat in small.minLat..small.maxLat)
        assertTrue(lon in small.minLon..small.maxLon)

        // A larger radius yields a strictly larger box.
        assertTrue(large.maxLat > small.maxLat)
        assertTrue(large.minLat < small.minLat)
        assertTrue(large.maxLon > small.maxLon)
        assertTrue(large.minLon < small.minLon)
    }

    @Test
    fun `loadParkingSpaces maps database failures to Unknown error`() = runTest {
        val viewModel = makeViewModel(
            bikeParkingRepository = FakeBikeParkingRepository(error = RuntimeException("DB read failed"))
        )

        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is MapUiState.Error)
        assertTrue((state as MapUiState.Error).error is MapError.Unknown)
    }

    @Test
    fun `startInAppNavigation maps RoutingFailedException to RoutingFailed error`() = runTest {
        val destination = sampleSpace(id = "target")
        val viewModel = makeViewModel(
            bikeParkingRepository = FakeBikeParkingRepository(listOf(destination)),
            locationRepository = FakeLocationRepository(
                initialLocation = GeoCoordinate(latitude = 49.75, longitude = 6.64)
            ),
            routingRepository = FakeRoutingRepository(error = RoutingFailedException("NoRoute"))
        )

        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.startInAppNavigation(destination)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.navigationUiState.value
        assertTrue(state is NavigationUiState.Error)
        assertEquals(MapError.RoutingFailed("NoRoute"), (state as NavigationUiState.Error).error)
    }

    @Test
    fun `startInAppNavigation maps NoRouteFoundException to NoRouteFound error`() = runTest {
        val destination = sampleSpace(id = "target")
        val viewModel = makeViewModel(
            bikeParkingRepository = FakeBikeParkingRepository(listOf(destination)),
            locationRepository = FakeLocationRepository(
                initialLocation = GeoCoordinate(latitude = 49.75, longitude = 6.64)
            ),
            routingRepository = FakeRoutingRepository(error = NoRouteFoundException())
        )

        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.startInAppNavigation(destination)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.navigationUiState.value
        assertTrue(state is NavigationUiState.Error)
        assertEquals(MapError.NoRouteFound, (state as NavigationUiState.Error).error)
    }

    @Test
    fun `startInAppNavigation maps EmptyRouteGeometryException to EmptyRouteGeometry error`() = runTest {
        val destination = sampleSpace(id = "target")
        val viewModel = makeViewModel(
            bikeParkingRepository = FakeBikeParkingRepository(listOf(destination)),
            locationRepository = FakeLocationRepository(
                initialLocation = GeoCoordinate(latitude = 49.75, longitude = 6.64)
            ),
            routingRepository = FakeRoutingRepository(error = EmptyRouteGeometryException())
        )

        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.startInAppNavigation(destination)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.navigationUiState.value
        assertTrue(state is NavigationUiState.Error)
        assertEquals(MapError.EmptyRouteGeometry, (state as NavigationUiState.Error).error)
    }

    // ── Persisted map/ride settings ──────────────────────────────────────────

    @Test
    fun `navigation and display setters persist through map settings`() = runTest {
        val vm = makeViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setNavigation3DEnabled(false)
        vm.setVoiceGuidanceEnabled(true)
        vm.setKeepScreenOnEnabled(false)
        vm.setPortraitLockEnabled(true)
        vm.setRoundedBuildingsEnabled(true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, vm.is3DNavigation.value)
        assertEquals(true, vm.voiceGuidanceEnabled.value)
        assertEquals(false, vm.keepScreenOnEnabled.value)
        assertEquals(true, vm.portraitLockEnabled.value)
        assertEquals(true, vm.roundedBuildingsEnabled.value)
    }

    @Test
    fun `setLayerVisible flips the layer visibility flow`() = runTest {
        val vm = makeViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        val category = MapLayerCategory.entries.first()

        vm.setLayerVisible(category, false)
        testDispatcher.scheduler.advanceUntilIdle()
        val off = vm.layerVisibility.value
        vm.setLayerVisible(category, true)
        testDispatcher.scheduler.advanceUntilIdle()
        val on = vm.layerVisibility.value

        assertTrue(off != on)
    }

    @Test
    fun `onboarding can be replayed and completed`() = runTest {
        val vm = makeViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.replayOnboarding()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(false, vm.onboardingCompleted.value)

        vm.completeOnboarding()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(true, vm.onboardingCompleted.value)
    }

    @Test
    fun `ride view option setters are applied without error`() = runTest {
        val vm = makeViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setMaxSpeedBubbleEnabled(true)
        vm.setColorTrackBySpeedEnabled(true)
        testDispatcher.scheduler.advanceUntilIdle()
        // Reaching here means the launched settings writes completed cleanly.
        assertTrue(vm.uiState.value is MapUiState)
    }

    @Test
    fun `startInAppNavigation forwards correct from and to coordinates to routing repository`() = runTest {
        val destination = sampleSpace(id = "target")
        val routingRepository = FakeRoutingRepository()
        val viewModel = makeViewModel(
            bikeParkingRepository = FakeBikeParkingRepository(listOf(destination)),
            locationRepository = FakeLocationRepository(
                initialLocation = GeoCoordinate(latitude = 49.75, longitude = 6.64)
            ),
            routingRepository = routingRepository
        )

        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.startInAppNavigation(destination)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(GeoCoordinate(latitude = 49.75, longitude = 6.64), routingRepository.lastFrom)
        assertEquals(
            GeoCoordinate(latitude = destination.latitude, longitude = destination.longitude),
            routingRepository.lastTo
        )
    }

    @Test
    fun `stopInAppNavigation resets state to idle from active`() = runTest {
        val destination = sampleSpace(id = "target")
        val viewModel = makeViewModel(
            bikeParkingRepository = FakeBikeParkingRepository(listOf(destination)),
            locationRepository = FakeLocationRepository(
                initialLocation = GeoCoordinate(latitude = 49.75, longitude = 6.64)
            )
        )

        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.startInAppNavigation(destination)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.navigationUiState.value is NavigationUiState.Active)

        viewModel.stopInAppNavigation()
        assertEquals(NavigationUiState.Idle, viewModel.navigationUiState.value)
    }

    @Test
    fun `clearNavigationError clears error state only`() = runTest {
        val viewModel = makeViewModel(
            bikeParkingRepository = FakeBikeParkingRepository(emptyList()),
            locationRepository = FakeLocationRepository(initialLocation = null)
        )

        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.startInAppNavigation(sampleSpace(id = "target"))
        assertTrue(viewModel.navigationUiState.value is NavigationUiState.Error)

        viewModel.clearNavigationError()
        assertEquals(NavigationUiState.Idle, viewModel.navigationUiState.value)

        viewModel.clearNavigationError()
        assertEquals(NavigationUiState.Idle, viewModel.navigationUiState.value)
    }

    @Test
    fun `parkBikeAtCurrentLocation stores a parked bike at the current fix`() = runTest {
        val viewModel = makeViewModel(
            locationRepository = FakeLocationRepository(
                initialLocation = GeoCoordinate(latitude = 49.75, longitude = 6.64)
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.parkBikeAtCurrentLocation()
        testDispatcher.scheduler.advanceUntilIdle()

        val bike = viewModel.parkedBike.value
        assertTrue(bike != null)
        assertEquals(49.75, bike!!.latitude, 0.0)
        assertEquals(6.64, bike.longitude, 0.0)
    }

    @Test
    fun `parkBikeAtCurrentLocation without a fix reports location unavailable`() = runTest {
        val viewModel = makeViewModel(
            locationRepository = FakeLocationRepository(initialLocation = null)
        )
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.parkBikeAtCurrentLocation()

        assertTrue(viewModel.parkedBike.value == null)
        assertEquals(de.velospot.R.string.error_location_unavailable, viewModel.userMessageRes.value)
    }

    @Test
    fun `arriving at a bike parking spot auto-parks the bike and ends navigation`() = runTest {
        val destination = sampleSpace(id = "rack-1")
        val viewModel = makeViewModel(
            bikeParkingRepository = FakeBikeParkingRepository(listOf(destination)),
            locationRepository = FakeLocationRepository(
                initialLocation = GeoCoordinate(latitude = 49.75, longitude = 6.64)
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.startInAppNavigation(destination)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.navigationUiState.value is NavigationUiState.Active)

        // Still far away: no auto-park yet.
        viewModel.updateNavigationProgress(progress(remainingMeters = 120.0))
        assertTrue(viewModel.parkedBike.value == null)

        // Within the arrival radius: two consecutive fixes (debounced) park the
        // bike at the destination and end navigation.
        viewModel.updateNavigationProgress(progress(remainingMeters = 12.0))
        viewModel.updateNavigationProgress(progress(remainingMeters = 12.0))
        testDispatcher.scheduler.advanceUntilIdle()

        val bike = viewModel.parkedBike.value
        assertTrue(bike != null)
        assertEquals(destination.latitude, bike!!.latitude, 0.0)
        assertEquals(destination.longitude, bike.longitude, 0.0)
        assertEquals(NavigationUiState.Idle, viewModel.navigationUiState.value)
    }

    @Test
    fun `arriving at a synthetic destination ends navigation without parking`() = runTest {
        // A saved place is wrapped in a synthetic BikeParkingSpace: navigation must
        // still end on arrival, but the bike must never be auto-parked.
        val viewModel = makeViewModel(
            locationRepository = FakeLocationRepository(
                initialLocation = GeoCoordinate(latitude = 49.75, longitude = 6.64)
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.navigateToSavedPlace(
            SavedPlace(
                id = "p1",
                name = "Home",
                latitude = 49.76,
                longitude = 6.65,
                address = null,
                addedAt = 0L
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.navigationUiState.value is NavigationUiState.Active)

        // Two consecutive arrival fixes end navigation but leave the bike unparked.
        viewModel.updateNavigationProgress(progress(remainingMeters = 5.0))
        viewModel.updateNavigationProgress(progress(remainingMeters = 5.0))
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.parkedBike.value == null)
        assertEquals(NavigationUiState.Idle, viewModel.navigationUiState.value)
    }


    @Test
    fun `a single arrival fix does not yet end navigation`() = runTest {
        val destination = sampleSpace(id = "rack-1")
        val viewModel = makeViewModel(
            bikeParkingRepository = FakeBikeParkingRepository(listOf(destination)),
            locationRepository = FakeLocationRepository(
                initialLocation = GeoCoordinate(latitude = 49.75, longitude = 6.64)
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.startInAppNavigation(destination)
        testDispatcher.scheduler.advanceUntilIdle()

        // A single noisy fix inside the radius is debounced — navigation continues.
        viewModel.updateNavigationProgress(progress(remainingMeters = 12.0))
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.parkedBike.value == null)
        assertTrue(viewModel.navigationUiState.value is NavigationUiState.Active)
    }

    @Test
    fun `pickUpBike clears the previously parked bike`() = runTest {
        val viewModel = makeViewModel(
            locationRepository = FakeLocationRepository(
                initialLocation = GeoCoordinate(latitude = 49.75, longitude = 6.64)
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.parkBikeAtCurrentLocation()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.parkedBike.value != null)

        viewModel.pickUpBike()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.parkedBike.value == null)
    }

    @Test
    fun `saving a ride as a route seeds the leaderboard with the ride time`() = runTest {
        val viewModel = makeViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val ride = RecordedRide(
            id = "ride-1",
            startedAt = 0L,
            endedAt = 600_000L,
            distanceMeters = 2000.0,
            elapsedSeconds = 600L,
            movingSeconds = 560L,
            avgSpeedMps = 3.5,
            maxSpeedMps = 8.0,
            elevationGainMeters = 40.0,
            elevationLossMeters = 35.0,
            points = listOf(
                de.velospot.domain.model.TrackPoint(49.75, 6.64, 0L),
                de.velospot.domain.model.TrackPoint(49.752, 6.64, 60_000L),
                de.velospot.domain.model.TrackPoint(49.75, 6.64, 120_000L)
            ),
            name = "Evening loop"
        )

        viewModel.saveRideAsRoute(ride)
        testDispatcher.scheduler.advanceUntilIdle()

        // A route was created and its leaderboard opened, seeded with the ride's time.
        assertEquals(1, viewModel.plannedRoutes.value.size)
        assertEquals("Evening loop", viewModel.plannedRoutes.value.first().name)
        assertTrue(viewModel.leaderboardRoute.value != null)
        val attempts = viewModel.routeAttempts.value
        assertEquals(1, attempts.size)
        assertEquals(600L, attempts.first().elapsedSeconds)
        assertEquals(false, attempts.first().reversed)
    }

    // ── Opened-GPX cold-start hand-off (GpxOpenBus) ──────────────────────────
    // Guards the regression where a cold-start "open .gpx from another app" opened a
    // *new* MainActivity + MapViewModel while a previous, being-destroyed one was
    // still collecting the bus. The old code consumed the bus inside the collector,
    // so the stale ViewModel cleared it before the newly-shown one started collecting;
    // the shown VM then observed `null` and never raised the chooser (the "only works
    // on the 2nd/3rd try" bug). The bus is now consumed only on a user action.
    //
    // A mock Uri is posted so no Android framework is needed in this pure JVM test.
    // GpxFileStore.cacheIncomingGpx(uri) fails gracefully (mock Context has no real
    // contentResolver/cacheDir) and returns null, so MapViewModel falls back to the
    // original posted uri — hence the chooser equals the posted uri.

    /**
     * Awaits the opened-GPX chooser value. `observeGpxOpenIntents()` first hops onto
     * `Dispatchers.IO` (in `GpxFileStore.cacheIncomingGpx`) before setting
     * `_gpxOpenChooser`, and that IO hop is a REAL background dispatcher the test
     * scheduler cannot fast-forward. So we alternate: advance the virtual scheduler
     * to run the collector up to (and past) the IO hop, then briefly yield real time
     * for the IO continuation, until the chooser is set (or a timeout guards it).
     */
    private fun awaitGpxChooser(viewModel: MapViewModel): android.net.Uri? {
        repeat(200) {
            testDispatcher.scheduler.advanceUntilIdle()
            viewModel.gpxOpenChooser.value?.let { return it }
            Thread.sleep(10)
        }
        testDispatcher.scheduler.advanceUntilIdle()
        return viewModel.gpxOpenChooser.value
    }

    @Test
    fun `a newly-shown ViewModel still receives the opened GPX not consumed by the previous one`() = runTest {
        val bus = de.velospot.core.gpx.GpxOpenBus()
        val uri = mock<android.net.Uri>()
        bus.post(uri)

        // VM1 collects the bus but — being torn down on the cold start — must NOT
        // consume it.
        val vm1 = makeViewModel(gpxOpenBus = bus)
        assertEquals(uri, awaitGpxChooser(vm1))

        // VM2 (the ViewModel the UI actually shows) starts collecting the SAME bus
        // afterwards and must still pick up the retained uri.
        val vm2 = makeViewModel(gpxOpenBus = bus)
        assertEquals(uri, awaitGpxChooser(vm2))

        // The value survived both collectors: nobody consumed it.
        assertEquals(uri, bus.pending.value)
    }

    @Test
    fun `dismissGpxOpenChooser consumes the bus`() = runTest {
        val bus = de.velospot.core.gpx.GpxOpenBus()
        val uri = mock<android.net.Uri>()
        bus.post(uri)

        val viewModel = makeViewModel(gpxOpenBus = bus)
        assertEquals(uri, awaitGpxChooser(viewModel))

        viewModel.dismissGpxOpenChooser()

        assertEquals(null, viewModel.gpxOpenChooser.value)
        assertEquals(null, bus.pending.value)
    }

    @Test
    fun `importOpenedGpx consumes the bus`() = runTest {
        val bus = de.velospot.core.gpx.GpxOpenBus()
        val uri = mock<android.net.Uri>()
        bus.post(uri)

        val viewModel = makeViewModel(gpxOpenBus = bus)
        assertEquals(uri, awaitGpxChooser(viewModel))

        // The consume happens synchronously before the async GPX read.
        viewModel.importOpenedGpx()

        assertEquals(null, viewModel.gpxOpenChooser.value)
        assertEquals(null, bus.pending.value)
    }

    @Test
    fun `previewOpenedGpx consumes the bus`() = runTest {
        val bus = de.velospot.core.gpx.GpxOpenBus()
        val uri = mock<android.net.Uri>()
        bus.post(uri)

        val viewModel = makeViewModel(gpxOpenBus = bus)
        assertEquals(uri, awaitGpxChooser(viewModel))

        // The consume happens synchronously before the async GPX read.
        viewModel.previewOpenedGpx()

        assertEquals(null, viewModel.gpxOpenChooser.value)
        assertEquals(null, bus.pending.value)
    }

    @Test
    fun `a uri posted before any ViewModel exists is still delivered`() = runTest {
        // Cold-start ordering: MainActivity posts to the retained StateFlow before the
        // MapViewModel is even created; the VM must pick it up once it starts collecting.
        val bus = de.velospot.core.gpx.GpxOpenBus()
        val uri = mock<android.net.Uri>()
        bus.post(uri)

        val viewModel = makeViewModel(gpxOpenBus = bus)

        assertEquals(uri, awaitGpxChooser(viewModel))
    }

    // ── Custom map pin ────────────────────────────────────────────────────────

    @Test
    fun `onMapTapped drops a custom pin and moves the camera`() = runTest {
        val vm = makeViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.onMapTapped(49.7, 6.6)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(GeoCoordinate(49.7, 6.6), vm.customMapPin.value)
        val target = vm.mapCameraTarget.value
        assertTrue(target != null)
        assertEquals(49.7, target!!.latitude, 0.0)
        assertEquals(6.6, target.longitude, 0.0)
    }

    @Test
    fun `onMapTapped is ignored during an active follow session`() = runTest {
        val destination = sampleSpace(id = "target")
        val vm = makeViewModel(
            bikeParkingRepository = FakeBikeParkingRepository(listOf(destination)),
            locationRepository = FakeLocationRepository(
                initialLocation = GeoCoordinate(latitude = 49.75, longitude = 6.64)
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()
        vm.startInAppNavigation(destination)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.navigationUiState.value is NavigationUiState.Active)

        vm.onMapTapped(1.0, 2.0)

        // A drop mid-trip would trigger reverse-geocoding + a camera jump — suppressed.
        assertEquals(null, vm.customMapPin.value)
    }

    @Test
    fun `dismissCustomMapPin clears the pin and its address`() = runTest {
        val vm = makeViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.onMapTapped(49.7, 6.6)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.customMapPin.value != null)

        vm.dismissCustomMapPin()
        assertEquals(null, vm.customMapPin.value)
        assertEquals(null, vm.customMapPinAddress.value)
    }

    @Test
    fun `saveCustomPinAsFavorite persists a named saved place and dismisses the pin`() = runTest {
        val vm = makeViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.onMapTapped(49.7, 6.6)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.saveCustomPinAsFavorite("Home")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, vm.savedPlaces.value.size)
        assertEquals("Home", vm.savedPlaces.value.first().name)
        assertEquals(null, vm.customMapPin.value)
    }

    // ── Address search pins ───────────────────────────────────────────────────

    @Test
    fun `onSearchResultSelected drops a search pin and dismissSearchPin clears it`() = runTest {
        val vm = makeViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        val result = AddressSearchResult(displayName = "Main St 1, Trier", latitude = 49.75, longitude = 6.64)

        vm.onSearchResultSelected(result)
        assertEquals(result, vm.selectedSearchPin.value)
        val target = vm.mapCameraTarget.value
        assertTrue(target != null)
        assertEquals(49.75, target!!.latitude, 0.0)

        vm.dismissSearchPin()
        assertEquals(null, vm.selectedSearchPin.value)
    }

    @Test
    fun `saveSearchPinAsFavorite persists a saved place and dismisses the search pin`() = runTest {
        val vm = makeViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        val result = AddressSearchResult(displayName = "Market Square, Trier", latitude = 49.75, longitude = 6.64)
        vm.onSearchResultSelected(result)

        vm.saveSearchPinAsFavorite("Work")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, vm.savedPlaces.value.size)
        assertEquals("Work", vm.savedPlaces.value.first().name)
        assertEquals(null, vm.selectedSearchPin.value)
    }

    @Test
    fun `startNavigationToAddress routes to a synthetic address destination`() = runTest {
        val vm = makeViewModel(
            locationRepository = FakeLocationRepository(
                initialLocation = GeoCoordinate(latitude = 49.75, longitude = 6.64)
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()
        val result = AddressSearchResult(displayName = "Porta Nigra, Trier", latitude = 49.76, longitude = 6.64)

        vm.startNavigationToAddress(result)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.navigationUiState.value
        assertTrue(state is NavigationUiState.Active)
        assertEquals(MapViewModel.ID_ADDRESS_SEARCH_PIN, (state as NavigationUiState.Active).destination.id)
        assertEquals(null, vm.selectedSearchPin.value)
    }

    @Test
    fun `onSearchCleared resets the query and search pin`() = runTest {
        val vm = makeViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        // A short query returns early without hitting the geocoder.
        vm.onSearchQueryChanged("ab")
        assertEquals("ab", vm.searchQuery.value)
        assertTrue(vm.searchResults.value.isEmpty())

        vm.onSearchResultSelected(
            AddressSearchResult(displayName = "X", latitude = 1.0, longitude = 2.0)
        )
        vm.onSearchCleared()

        assertEquals("", vm.searchQuery.value)
        assertEquals(null, vm.selectedSearchPin.value)
    }

    // ── Space selection ───────────────────────────────────────────────────────

    @Test
    fun `selectSpace selects a space and centres the camera`() = runTest {
        val vm = makeViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        val space = sampleSpace(id = "s1")

        vm.selectSpace(space)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(space, vm.selectedSpace.value)
        val target = vm.mapCameraTarget.value
        assertTrue(target != null)
        assertEquals(space.latitude, target!!.latitude, 0.0)

        vm.selectSpace(null)
        assertEquals(null, vm.selectedSpace.value)
    }

    // ── Saved places ──────────────────────────────────────────────────────────

    @Test
    fun `selectSavedPlace opens the detail sheet and dismiss clears it`() = runTest {
        val vm = makeViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        val place = SavedPlace(
            id = "p1", name = "Home", latitude = 49.76, longitude = 6.65,
            address = null, addedAt = 0L
        )

        vm.selectSavedPlace(place)
        assertEquals(place, vm.selectedSavedPlace.value)

        vm.dismissSelectedSavedPlace()
        assertEquals(null, vm.selectedSavedPlace.value)
    }

    // ── Parked bike sheet + navigation ────────────────────────────────────────


    @Test
    fun `navigateToParkedBike routes to the parked-bike destination`() = runTest {
        val vm = makeViewModel(
            locationRepository = FakeLocationRepository(
                initialLocation = GeoCoordinate(latitude = 49.75, longitude = 6.64)
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()
        vm.parkBikeAtCurrentLocation()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.navigateToParkedBike()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.navigationUiState.value
        assertTrue(state is NavigationUiState.Active)
        assertEquals(MapViewModel.ID_PARKED_BIKE, (state as NavigationUiState.Active).destination.id)
    }

    // ── Follow-lock / re-centre ───────────────────────────────────────────────

    @Test
    fun `panning and re-centring do not lock the camera on the idle map`() = runTest {
        val vm = makeViewModel(
            locationRepository = FakeLocationRepository(
                initialLocation = GeoCoordinate(latitude = 49.75, longitude = 6.64)
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(!vm.isFollowSessionActive)
        vm.onMapPannedByUser()          // no session → no-op
        assertTrue(!vm.isFollowingLocation.value)

        vm.recenterOnUserLocation()     // centres but does not lock without a session
        assertTrue(vm.mapCameraTarget.value != null)
        assertTrue(!vm.isFollowingLocation.value)
    }

    // ── Persisted toggle flows ────────────────────────────────────────────────

    @Test
    fun `hud, amoled, sun-alert and weather toggles persist through map settings`() = runTest {
        val vm = makeViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setHudEnabled(true)
        vm.setHudExpanded(true)
        vm.setAmoledEnabled(true)
        vm.setSunAlertEnabled(false)
        vm.setWeatherEnabled(true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(true, vm.hudEnabled.value)
        assertEquals(true, vm.hudExpanded.value)
        assertEquals(true, vm.amoledEnabled.value)
        assertEquals(false, vm.sunAlertEnabled.value)
        assertEquals(true, vm.weatherEnabled.value)
    }

    @Test
    fun `consumeUserMessage clears the one-shot message`() = runTest {
        val vm = makeViewModel(
            locationRepository = FakeLocationRepository(initialLocation = null)
        )
        testDispatcher.scheduler.advanceUntilIdle()

        // No fix → parking sets a one-shot "location unavailable" message.
        vm.parkBikeAtCurrentLocation()
        assertEquals(de.velospot.R.string.error_location_unavailable, vm.userMessageRes.value)

        vm.consumeUserMessage()
        assertEquals(null, vm.userMessageRes.value)
    }

    @Test
    fun `clearViewportLoadError resets the transient viewport error`() = runTest {
        val vm = makeViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.clearViewportLoadError()
        assertEquals(null, vm.viewportLoadError.value)
    }

    // ── Recent destinations & round trips ─────────────────────────────────────

    @Test
    fun `navigateToRecentDestination routes to a synthetic recent destination`() = runTest {
        val vm = makeViewModel(
            locationRepository = FakeLocationRepository(
                initialLocation = GeoCoordinate(latitude = 49.75, longitude = 6.64)
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()
        val recent = de.velospot.domain.model.RecentDestination(
            id = "r1", name = "Cinema", latitude = 49.77, longitude = 6.63,
            address = "Filmstr 1", lastUsedAt = 0L,
            kind = de.velospot.domain.model.DestinationKind.RECENT
        )

        vm.navigateToRecentDestination(recent)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.navigationUiState.value
        assertTrue(state is NavigationUiState.Active)
        assertEquals(MapViewModel.ID_ADDRESS_SEARCH_PIN, (state as NavigationUiState.Active).destination.id)
    }

    @Test
    fun `startRoundTrip routes to a synthetic loop when a fix is available`() = runTest {
        val vm = makeViewModel(
            locationRepository = FakeLocationRepository(
                initialLocation = GeoCoordinate(latitude = 49.75, longitude = 6.64)
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        vm.startRoundTrip(distanceMeters = 5_000.0)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.navigationUiState.value
        assertTrue(state is NavigationUiState.Active)
        assertEquals(MapViewModel.ID_ROUND_TRIP, (state as NavigationUiState.Active).destination.id)
    }

    @Test
    fun `startRoundTrip is a no-op without a fix`() = runTest {
        val vm = makeViewModel(
            locationRepository = FakeLocationRepository(initialLocation = null)
        )
        testDispatcher.scheduler.advanceUntilIdle()

        vm.startRoundTrip(distanceMeters = 5_000.0)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(NavigationUiState.Idle, vm.navigationUiState.value)
    }

    // ── Route planning ────────────────────────────────────────────────────────

    @Test
    fun `route planning drops waypoints, previews and saves a named route`() = runTest {
        val vm = makeViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.startRoutePlanning()
        assertTrue(vm.isPlanningRoute.value)

        vm.onMapTapped(49.70, 6.60)
        vm.onMapTapped(49.71, 6.61)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, vm.planningWaypoints.value.size)
        // Two stops → a preview polyline was computed off the fake routing repo.
        assertTrue(vm.planningPreviewRoute.value != null)

        val saved = vm.savePlannedRoute("City loop")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(saved)
        assertEquals(1, vm.plannedRoutes.value.size)
        assertEquals("City loop", vm.plannedRoutes.value.first().name)
        assertTrue(!vm.isPlanningRoute.value)
    }

    @Test
    fun `undo and cancel discard planning waypoints`() = runTest {
        val vm = makeViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.startRoutePlanning()
        vm.onMapTapped(49.70, 6.60)
        vm.onMapTapped(49.71, 6.61)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, vm.planningWaypoints.value.size)

        vm.undoLastWaypoint()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, vm.planningWaypoints.value.size)

        vm.cancelRoutePlanning()
        assertTrue(!vm.isPlanningRoute.value)
        assertTrue(vm.planningWaypoints.value.isEmpty())
    }

    @Test
    fun `savePlannedRoute refuses a session with fewer than two waypoints`() = runTest {
        val vm = makeViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.startRoutePlanning()
        vm.onMapTapped(49.70, 6.60)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(!vm.savePlannedRoute("Too short"))
        assertTrue(vm.plannedRoutes.value.isEmpty())
        // Still planning: the caller can keep dropping stops.
        assertTrue(vm.isPlanningRoute.value)
    }

    @Test
    fun `route leaderboard and preview open and close`() = runTest {
        val vm = makeViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        val route = samplePlannedRoute()

        vm.openRouteLeaderboard(route)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(route, vm.leaderboardRoute.value)
        vm.closeRouteLeaderboard()
        assertEquals(null, vm.leaderboardRoute.value)

        vm.showRouteOnMap(route)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(route, vm.previewedRoute.value)
        vm.closeRoutePreview()
        assertEquals(null, vm.previewedRoute.value)
    }

    @Test
    fun `ridePlannedRoute navigates through the route waypoints`() = runTest {
        val vm = makeViewModel(
            locationRepository = FakeLocationRepository(
                initialLocation = GeoCoordinate(latitude = 49.75, longitude = 6.64)
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        vm.ridePlannedRoute(samplePlannedRoute(), reversed = false)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.navigationUiState.value
        assertTrue(state is NavigationUiState.Active)
        assertEquals(MapViewModel.ID_PLANNED_ROUTE, (state as NavigationUiState.Active).destination.id)
    }

    // ── Offline sheets / region picking ───────────────────────────────────────

    @Test
    fun `offline profile sheet and region picking toggle their flags`() = runTest {
        val vm = makeViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.openProfileSheet()
        assertTrue(vm.showProfileSheet.value)
        vm.dismissProfileSheet()
        assertTrue(!vm.showProfileSheet.value)

        vm.startPickingOfflineRegion()
        assertTrue(vm.isPickingOfflineRegion.value)
        vm.cancelPickingOfflineRegion()
        assertTrue(!vm.isPickingOfflineRegion.value)
    }

    // ── Ride-name prompt (manual stop) ────────────────────────────────────────

    @Test
    fun `requestStopRideTracking is ignored when nothing is recording`() = runTest {
        val vm = makeViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.requestStopRideTracking()
        // No recording → no naming prompt is raised.
        assertEquals(null, vm.rideNamePrompt.value)
    }

    @Test
    fun `cancelRideNamePrompt clears any open naming prompt`() = runTest {
        val vm = makeViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.cancelRideNamePrompt()
        assertEquals(null, vm.rideNamePrompt.value)
    }

    // ── Recording-only debug mock coupling ────────────────────────────────────
    // The GPS "Mock" that drives the cyclist to the Porta Nigra during a
    // recording-only session (no navigation) must be paused / resumed / stopped by
    // the visible recording controls, so it never feels un-pausable / un-stoppable.

    @Test
    fun `recording-only mock is paused, resumed and stopped by the ride controls`() = runTest {
        val vm = makeViewModel(
            locationRepository = FakeLocationRepository(
                initialLocation = GeoCoordinate(latitude = 49.75, longitude = 6.64)
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        // Record, then drive the debug mock towards the Porta Nigra (no navigation).
        vm.startRideTracking()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.toggleRouteSimulation()
        // Advance just enough to compute the line and start the ticker — NOT to the
        // end of the route (that would finish the mock on its own).
        testDispatcher.scheduler.advanceTimeBy(50)
        assertTrue(vm.isSimulatingRoute.value)
        assertTrue(vm.navigationUiState.value is NavigationUiState.Idle)

        // Pausing the recording pauses the mock (the avatar stops driving).
        vm.togglePauseRideTracking()
        assertEquals(false, vm.isSimulatingRoute.value)

        // Resuming the recording resumes the mock.
        vm.togglePauseRideTracking()
        assertTrue(vm.isSimulatingRoute.value)

        // Requesting stop (opens the naming prompt) immediately halts the mock.
        vm.requestStopRideTracking()
        assertEquals(false, vm.isSimulatingRoute.value)
    }

    @Test
    fun `discardRideTracking stops an engaged recording-only mock`() = runTest {
        val vm = makeViewModel(
            locationRepository = FakeLocationRepository(
                initialLocation = GeoCoordinate(latitude = 49.75, longitude = 6.64)
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        vm.startRideTracking()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.toggleRouteSimulation()
        testDispatcher.scheduler.advanceTimeBy(50)
        assertTrue(vm.isSimulatingRoute.value)

        vm.discardRideTracking()
        assertEquals(false, vm.isSimulatingRoute.value)
    }

    @Test
    fun `a normal recording without a mock never touches the simulator`() = runTest {
        val vm = makeViewModel(
            locationRepository = FakeLocationRepository(
                initialLocation = GeoCoordinate(latitude = 49.75, longitude = 6.64)
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        // A plain recording, no debug mock engaged.
        vm.startRideTracking()
        testDispatcher.scheduler.advanceUntilIdle()
        val locationBefore = vm.userLocation.value
        assertEquals(false, vm.isSimulatingRoute.value)

        // Pausing / requesting stop must not engage or brake a (non-existent) mock —
        // in particular no synthetic braking fix is injected into the location flow.
        vm.togglePauseRideTracking()
        assertEquals(false, vm.isSimulatingRoute.value)
        assertEquals(locationBefore, vm.userLocation.value)

        vm.togglePauseRideTracking()
        vm.requestStopRideTracking()
        assertEquals(false, vm.isSimulatingRoute.value)
        assertEquals(locationBefore, vm.userLocation.value)
    }

    // ── External sensors ──────────────────────────────────────────────────────

    @Test
    fun `sensor management calls complete without error`() = runTest {
        val vm = makeViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.rememberSensor("AA:BB:CC:DD:EE:FF")
        vm.forgetSensor("AA:BB:CC:DD:EE:FF")
        vm.setWheelCircumferenceMeters(2.105)
        testDispatcher.scheduler.advanceUntilIdle()

        // The fake sensor repo is a no-op; reaching here means every launch completed.
        assertTrue(vm.rememberedSensorAddresses.value.isEmpty())
    }

    private fun samplePlannedRoute() = PlannedRoute(
        id = "route-1",
        name = "Sample route",
        waypoints = listOf(
            de.velospot.domain.model.RouteWaypoint(latitude = 49.75, longitude = 6.64),
            de.velospot.domain.model.RouteWaypoint(latitude = 49.76, longitude = 6.65)
        ),
        geometry = listOf(
            RoutePoint(latitude = 49.75, longitude = 6.64),
            RoutePoint(latitude = 49.76, longitude = 6.65)
        ),
        distanceMeters = 1500.0,
        elevationGainMeters = 10.0,
        elevationLossMeters = 8.0,
        energyJoules = null,
        createdAt = 0L
    )

    private fun progress(remainingMeters: Double) = de.velospot.core.navigation.NavigationProgress(
        remainingMeters = remainingMeters,
        remainingSeconds = remainingMeters / 4.5,
        distanceFromRouteMeters = 2.0,
        isOffRoute = false
    )

    private fun sampleSpace(id: String) = BikeParkingSpace(
        id = id,
        latitude = 49.75,
        longitude = 6.64,
        type = BikeParkingType.BIKE_RACK,
        capacity = 8,
        name = "Sample $id",
        address = "Test Street 1",
        isCovered = true,
        imageUrl = null,
        operator = null,
        sourceLayer = "fahrradabstellanlagen"
    )
}

private class FakeBikeParkingRepository(
    private val spaces: List<BikeParkingSpace> = emptyList(),
    private val error: Throwable? = null
) : BikeParkingRepository {

    /** Number of bounding-box queries made — lets tests assert the search ran (or not). */
    var boundingBoxQueryCount: Int = 0
        private set

    /** Simulates a data-load error (e.g. DB corruption) — only on the primary query. */
    override suspend fun getSpacesInBoundingBox(bbox: BoundingBox): List<BikeParkingSpace> {
        boundingBoxQueryCount++
        error?.let { throw it }
        return spaces
    }

    /** Always succeeds in tests — used for resolving favorites by ID. */
    override suspend fun getSpacesByIds(ids: List<String>): List<BikeParkingSpace> =
        spaces.filter { it.id in ids }

    /** Returns the space unchanged — address resolution is a no-op in tests. */
    override suspend fun resolveAddress(space: BikeParkingSpace): BikeParkingSpace = space
}

private class FakeFavoritesRepository : FavoritesRepository {
    private val favorites = MutableStateFlow<List<String>>(emptyList())

    override fun getFavoritesFlow(): Flow<List<String>> = favorites

    override suspend fun isFavorite(parkingSpaceId: String): Boolean {
        return favorites.value.contains(parkingSpaceId)
    }

    override suspend fun addFavorite(parkingSpaceId: String) {
        favorites.value = (favorites.value + parkingSpaceId).distinct()
    }

    override suspend fun removeFavorite(parkingSpaceId: String) {
        favorites.value = favorites.value - parkingSpaceId
    }

    override suspend fun toggleFavorite(parkingSpaceId: String) {
        if (favorites.value.contains(parkingSpaceId)) removeFavorite(parkingSpaceId)
        else addFavorite(parkingSpaceId)
    }
}

private class FakeSavedPlacesRepository : SavedPlacesRepository {
    private val savedPlaces = MutableStateFlow<List<SavedPlace>>(emptyList())

    override fun getSavedPlacesFlow(): Flow<List<SavedPlace>> = savedPlaces

    override suspend fun savePlace(place: SavedPlace) {
        savedPlaces.value = savedPlaces.value.filter { it.id != place.id } + place
    }

    override suspend fun removePlace(id: String) {
        savedPlaces.value = savedPlaces.value.filterNot { it.id == id }
    }
}

private class FakeParkedBikeRepository : ParkedBikeRepository {
    private val parkedBike = MutableStateFlow<ParkedBike?>(null)

    override fun getParkedBikeFlow(): Flow<ParkedBike?> = parkedBike

    override suspend fun park(bike: ParkedBike) { parkedBike.value = bike }

    override suspend fun clear() { parkedBike.value = null }
}

private class FakePlannedRoutesRepository : de.velospot.domain.repository.PlannedRoutesRepository {
    private val routes = MutableStateFlow<List<de.velospot.domain.model.PlannedRoute>>(emptyList())
    private val attempts = MutableStateFlow<List<de.velospot.domain.model.RouteAttempt>>(emptyList())

    override fun getRoutesFlow(): Flow<List<de.velospot.domain.model.PlannedRoute>> = routes

    override fun getAttemptsFlow(routeId: String): Flow<List<de.velospot.domain.model.RouteAttempt>> = attempts

    override suspend fun saveRoute(route: de.velospot.domain.model.PlannedRoute) {
        routes.value = routes.value.filterNot { it.id == route.id } + route
    }

    override suspend fun renameRoute(id: String, name: String) {
        routes.value = routes.value.map { if (it.id == id) it.copy(name = name) else it }
    }

    override suspend fun deleteRoute(id: String) {
        routes.value = routes.value.filterNot { it.id == id }
        attempts.value = attempts.value.filterNot { it.routeId == id }
    }

    override suspend fun addAttempt(attempt: de.velospot.domain.model.RouteAttempt) {
        attempts.value = attempts.value + attempt
    }

    override suspend fun deleteAttempt(id: String) {
        attempts.value = attempts.value.filterNot { it.id == id }
    }
}

private class FakeRecordedRidesRepository : RecordedRidesRepository {
    private val rides = MutableStateFlow<List<RecordedRide>>(emptyList())

    override fun getRideSummariesFlow(): Flow<List<RecordedRideSummary>> =
        rides.map { list -> list.map { it.toSummary() } }

    override fun getRidesWithTracksFlow(): Flow<List<RecordedRide>> = rides

    override suspend fun getRide(id: String): RecordedRide? =
        rides.value.firstOrNull { it.id == id }

    override suspend fun getRides(ids: List<String>): List<RecordedRide> =
        ids.mapNotNull { id -> rides.value.firstOrNull { it.id == id } }

    override suspend fun saveRide(ride: RecordedRide) {
        rides.value = rides.value.filterNot { it.id == ride.id } + ride
    }

    override suspend fun removeRide(id: String) {
        rides.value = rides.value.filterNot { it.id == id }
    }

    override suspend fun updateRideName(id: String, name: String?) {
        rides.value = rides.value.map { if (it.id == id) it.copy(name = name) else it }
    }

    override suspend fun setRideArchived(id: String, archived: Boolean) {
        rides.value = rides.value.map {
            if (it.id == id) it.copy(archivedAt = if (archived) 1L else null) else it
        }
    }

    override suspend fun clearAll() { rides.value = emptyList() }

    private fun RecordedRide.toSummary() = RecordedRideSummary(
        id = id,
        startedAt = startedAt,
        endedAt = endedAt,
        distanceMeters = distanceMeters,
        elapsedSeconds = elapsedSeconds,
        movingSeconds = movingSeconds,
        avgSpeedMps = avgSpeedMps,
        maxSpeedMps = maxSpeedMps,
        elevationGainMeters = elevationGainMeters,
        elevationLossMeters = elevationLossMeters,
        name = name,
        isMock = isMock,
        archivedAt = archivedAt
    )
}

private class FakeMapSettingsRepository : MapSettingsRepository {
    private val _layerVisibility = MutableStateFlow(LayerVisibility())
    override val layerVisibility: Flow<LayerVisibility> = _layerVisibility
    private val _is3DNavigation = MutableStateFlow(true)
    override val is3DNavigation: Flow<Boolean> = _is3DNavigation
    private val _voiceGuidance = MutableStateFlow(false)
    override val voiceGuidanceEnabled: Flow<Boolean> = _voiceGuidance
    private val _keepScreenOn = MutableStateFlow(true)
    override val keepScreenOnEnabled: Flow<Boolean> = _keepScreenOn
    private val _hudEnabled = MutableStateFlow(false)
    override val hudEnabled: Flow<Boolean> = _hudEnabled
    private val _hudExpanded = MutableStateFlow(false)
    override val hudExpanded: Flow<Boolean> = _hudExpanded
    private val _portraitLock = MutableStateFlow(false)
    override val portraitLockEnabled: Flow<Boolean> = _portraitLock
    private val _roundedBuildings = MutableStateFlow(false)
    override val roundedBuildingsEnabled: Flow<Boolean> = _roundedBuildings
    private val _amoled = MutableStateFlow(false)
    override val amoledEnabled: Flow<Boolean> = _amoled
    private val _sunAlert = MutableStateFlow(true)
    override val sunAlertEnabled: Flow<Boolean> = _sunAlert
    private val _weatherEnabled = MutableStateFlow(false)
    override val weatherEnabled: Flow<Boolean> = _weatherEnabled
    private val _rideViewOptions = MutableStateFlow(RideViewOptions())
    override val rideViewOptions: Flow<RideViewOptions> = _rideViewOptions
    private val _onboardingCompleted = MutableStateFlow(true)
    override val onboardingCompleted: Flow<Boolean> = _onboardingCompleted

    override suspend fun setLayerVisible(category: MapLayerCategory, visible: Boolean) {
        _layerVisibility.value = _layerVisibility.value.withVisibility(category, visible)
    }

    override suspend fun setRideTracksMode(mode: de.velospot.core.map.RideTracksMode) {
        _layerVisibility.value = _layerVisibility.value.copy(rideTracksMode = mode)
    }

    override suspend fun set3DNavigation(enabled: Boolean) { _is3DNavigation.value = enabled }
    override suspend fun setVoiceGuidance(enabled: Boolean) { _voiceGuidance.value = enabled }
    override suspend fun setKeepScreenOn(enabled: Boolean) { _keepScreenOn.value = enabled }
    override suspend fun setHudEnabled(enabled: Boolean) { _hudEnabled.value = enabled }
    override suspend fun setHudExpanded(expanded: Boolean) { _hudExpanded.value = expanded }
    override suspend fun setPortraitLock(enabled: Boolean) { _portraitLock.value = enabled }
    override suspend fun setRoundedBuildings(enabled: Boolean) { _roundedBuildings.value = enabled }
    override suspend fun setAmoled(enabled: Boolean) { _amoled.value = enabled }
    override suspend fun setSunAlertEnabled(enabled: Boolean) { _sunAlert.value = enabled }
    override suspend fun setWeatherEnabled(enabled: Boolean) { _weatherEnabled.value = enabled }
    override suspend fun setShowMaxSpeedBubble(enabled: Boolean) {
        _rideViewOptions.value = _rideViewOptions.value.copy(showMaxSpeedBubble = enabled)
    }
    override suspend fun setColorTrackBySpeed(enabled: Boolean) {
        _rideViewOptions.value = _rideViewOptions.value.copy(colorTrackBySpeed = enabled)
    }
    override suspend fun setOnboardingCompleted(completed: Boolean) {
        _onboardingCompleted.value = completed
    }
}

private class FakeDestinationHistoryRepository : de.velospot.domain.repository.DestinationHistoryRepository {
    private val store = MutableStateFlow<List<de.velospot.domain.model.RecentDestination>>(emptyList())

    override fun recentDestinations(limit: Int): Flow<List<de.velospot.domain.model.RecentDestination>> =
        kotlinx.coroutines.flow.MutableStateFlow(
            store.value.filter { it.kind == de.velospot.domain.model.DestinationKind.RECENT }.take(limit)
        )

    override fun pinnedDestinations(): Flow<List<de.velospot.domain.model.RecentDestination>> =
        kotlinx.coroutines.flow.MutableStateFlow(
            store.value.filter { it.kind != de.velospot.domain.model.DestinationKind.RECENT }
        )

    override suspend fun record(name: String, latitude: Double, longitude: Double, address: String?) {
        val id = "%.4f,%.4f".format(latitude, longitude)
        store.value = store.value.filterNot { it.id == id } + de.velospot.domain.model.RecentDestination(
            id = id, name = name, latitude = latitude, longitude = longitude,
            address = address, lastUsedAt = 0L, kind = de.velospot.domain.model.DestinationKind.RECENT
        )
    }

    override suspend fun pin(id: String, kind: de.velospot.domain.model.DestinationKind) {
        store.value = store.value.map { if (it.id == id) it.copy(kind = kind) else it }
    }

    override suspend fun remove(id: String) {
        store.value = store.value.filterNot { it.id == id }
    }
}

private class FakeLocationRepository(
    initialLocation: GeoCoordinate? = null
) : LocationRepository {    private val locationFlow = MutableStateFlow(initialLocation)
    var startUpdatesCallCount: Int = 0
        private set

    override fun getCurrentLocationFlow(): Flow<GeoCoordinate?> = locationFlow


    override fun startLocationUpdates(profile: LocationPowerProfile) {
        startUpdatesCallCount += 1
    }

    override fun stopLocationUpdates() = Unit
}

private class FakeRoutingRepository(
    private val route: BikeRoute = BikeRoute(
        points = listOf(
            RoutePoint(latitude = 49.75, longitude = 6.64),
            RoutePoint(latitude = 49.76, longitude = 6.65)
        ),
        distanceMeters = 1000.0,
        durationSeconds = 360.0
    ),
    private val error: Throwable? = null,
    /**
     * Optional per-destination route distance (metres), keyed by the destination
     * coordinate. Lets tests give each candidate its own real bike-route distance
     * so the "shortest route" ranking can be exercised. Falls back to [route] when
     * a destination isn't listed.
     */
    private val distanceByDestination: Map<GeoCoordinate, Double> = emptyMap()
) : RoutingRepository {
    var lastFrom: GeoCoordinate? = null
        private set
    var lastTo: GeoCoordinate? = null
        private set

    override suspend fun getBikeRoute(from: GeoCoordinate, to: GeoCoordinate): BikeRoute {
        lastFrom = from
        lastTo = to
        error?.let { throw it }
        val distance = distanceByDestination[to]
        return if (distance != null) route.copy(distanceMeters = distance) else route
    }

    override suspend fun getBikeRouteVia(waypoints: List<GeoCoordinate>): BikeRoute {
        lastFrom = waypoints.first()
        lastTo = waypoints.last()
        error?.let { throw it }
        return route
    }

    override suspend fun getRoundTrip(from: GeoCoordinate, targetDistanceMeters: Double): BikeRoute {
        lastFrom = from
        error?.let { throw it }
        return route
    }
}

private class FakeSensorRepository : de.velospot.domain.repository.SensorRepository {
    override val snapshot =
        MutableStateFlow(de.velospot.core.sensors.SensorSnapshot())
    override val rememberedAddresses: Flow<Set<String>> = MutableStateFlow(emptySet())
    override val wheelCircumferenceMeters: Flow<Double> =
        MutableStateFlow(de.velospot.core.sensors.SensorParsers.DEFAULT_WHEEL_CIRCUMFERENCE_METERS)

    override fun scan(): Flow<List<de.velospot.core.sensors.DiscoveredSensor>> =
        MutableStateFlow(emptyList())

    override suspend fun remember(address: String) = Unit
    override suspend fun forget(address: String) = Unit
    override fun connectRemembered() = Unit
    override fun disconnectAll() = Unit
    override suspend fun setWheelCircumferenceMeters(meters: Double) = Unit
}

private class FakeWeatherRepository : de.velospot.domain.repository.WeatherRepository {
    override suspend fun currentWeather(lat: Double, lon: Double): de.velospot.domain.model.WeatherSnapshot? = null
}

