package de.velospot.feature.map.presentation

import android.content.Context
import de.velospot.data.brouter.BRouterSegmentManager
import de.velospot.data.geocoding.NominatimGeocoder
import de.velospot.domain.model.BikeParkingSpace
import de.velospot.domain.model.BikeRoute
import de.velospot.domain.model.BikeParkingType
import de.velospot.domain.model.BoundingBox
import de.velospot.domain.model.EmptyRouteGeometryException
import de.velospot.domain.model.GeoCoordinate
import de.velospot.domain.model.MapError
import de.velospot.domain.model.NoRouteFoundException
import de.velospot.domain.model.ParkedBike
import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.RoutePoint
import de.velospot.domain.model.RoutingFailedException
import de.velospot.domain.model.SavedPlace
import de.velospot.domain.repository.BikeParkingRepository
import de.velospot.domain.repository.FavoritesRepository
import de.velospot.domain.repository.LocationRepository
import de.velospot.domain.repository.ParkedBikeRepository
import de.velospot.domain.repository.RecordedRidesRepository
import de.velospot.domain.repository.RoutingRepository
import de.velospot.domain.repository.SavedPlacesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var mockContext: Context
    private lateinit var mockSegmentManager: BRouterSegmentManager
    private lateinit var mockNominatimGeocoder: NominatimGeocoder

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockContext = mock()
        mockSegmentManager = mock()
        mockNominatimGeocoder = mock()

        // SharedPreferences stub so OfflineRoutingPreferences doesn't crash
        val sharedPrefs = mock<android.content.SharedPreferences>()
        val editor = mock<android.content.SharedPreferences.Editor>()
        whenever(mockContext.getSharedPreferences(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenReturn(sharedPrefs)
        whenever(sharedPrefs.getBoolean(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenReturn(false)
        whenever(sharedPrefs.getString(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenReturn(null)
        whenever(sharedPrefs.edit()).thenReturn(editor)
        whenever(editor.putBoolean(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenReturn(editor)
        whenever(editor.putString(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenReturn(editor)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun makeViewModel(
        bikeParkingRepository: BikeParkingRepository = FakeBikeParkingRepository(),
        favoritesRepository: FavoritesRepository = FakeFavoritesRepository(),
        locationRepository: LocationRepository = FakeLocationRepository(),
        routingRepository: RoutingRepository = FakeRoutingRepository()
    ): MapViewModel {
        val recordedRidesRepository = FakeRecordedRidesRepository()
        return MapViewModel(
            bikeParkingRepository = bikeParkingRepository,
            favoritesRepository   = favoritesRepository,
            locationRepository    = locationRepository,
            routingRepository     = routingRepository,
            segmentManager        = mockSegmentManager,
            nominatimGeocoder     = mockNominatimGeocoder,
            recordingManager      = de.velospot.core.tracking.RideRecordingManager(
                context = mockContext,
                locationRepository = locationRepository,
                recordedRidesRepository = recordedRidesRepository
            ),
            savedPlacesRepository = FakeSavedPlacesRepository(),
            parkedBikeRepository  = FakeParkedBikeRepository(),
            recordedRidesRepository = recordedRidesRepository,
            context               = mockContext
        )
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

        // Within the arrival radius: the bike is parked at the destination.
        viewModel.updateNavigationProgress(progress(remainingMeters = 12.0))
        testDispatcher.scheduler.advanceUntilIdle()

        val bike = viewModel.parkedBike.value
        assertTrue(bike != null)
        assertEquals(destination.latitude, bike!!.latitude, 0.0)
        assertEquals(destination.longitude, bike.longitude, 0.0)
        assertEquals(NavigationUiState.Idle, viewModel.navigationUiState.value)
    }

    @Test
    fun `arriving at a synthetic destination does not auto-park`() = runTest {
        // A saved place is wrapped in a synthetic BikeParkingSpace and must never auto-park.
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

        viewModel.updateNavigationProgress(progress(remainingMeters = 5.0))
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.parkedBike.value == null)
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

    /** Simulates a data-load error (e.g. DB corruption) — only on the primary query. */
    override suspend fun getSpacesInBoundingBox(bbox: BoundingBox): List<BikeParkingSpace> {
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

private class FakeRecordedRidesRepository : RecordedRidesRepository {
    private val rides = MutableStateFlow<List<RecordedRide>>(emptyList())

    override fun getRidesFlow(): Flow<List<RecordedRide>> = rides

    override suspend fun saveRide(ride: RecordedRide) {
        rides.value = rides.value.filterNot { it.id == ride.id } + ride
    }

    override suspend fun removeRide(id: String) {
        rides.value = rides.value.filterNot { it.id == id }
    }

    override suspend fun clearAll() { rides.value = emptyList() }
}

private class FakeLocationRepository(
    initialLocation: GeoCoordinate? = null
) : LocationRepository {
    private val locationFlow = MutableStateFlow(initialLocation)
    var startUpdatesCallCount: Int = 0
        private set

    override fun getCurrentLocationFlow(): Flow<GeoCoordinate?> = locationFlow


    override fun startLocationUpdates(highAccuracy: Boolean) {
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
    private val error: Throwable? = null
) : RoutingRepository {
    var lastFrom: GeoCoordinate? = null
        private set
    var lastTo: GeoCoordinate? = null
        private set

    override suspend fun getBikeRoute(from: GeoCoordinate, to: GeoCoordinate): BikeRoute {
        lastFrom = from
        lastTo = to
        error?.let { throw it }
        return route
    }
}

