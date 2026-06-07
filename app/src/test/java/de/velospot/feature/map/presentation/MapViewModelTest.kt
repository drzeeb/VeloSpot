package de.velospot.feature.map.presentation

import de.velospot.domain.model.BikeParkingSpace
import de.velospot.domain.model.BikeRoute
import de.velospot.domain.model.BikeParkingType
import de.velospot.domain.model.RoutePoint
import de.velospot.domain.repository.BikeParkingRepository
import de.velospot.domain.repository.FavoritesRepository
import de.velospot.domain.repository.LocationRepository
import de.velospot.domain.repository.RoutingRepository
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

@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadParkingSpaces emits success when repository returns data`() = runTest {
        val expected = listOf(sampleSpace(id = "1"), sampleSpace(id = "2"))
        val viewModel = MapViewModel(
            bikeParkingRepository = FakeBikeParkingRepository(expected),
            favoritesRepository = FakeFavoritesRepository(),
            locationRepository = FakeLocationRepository(),
            routingRepository = FakeRoutingRepository()
        )

        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is MapUiState.Success)
        assertEquals(expected, (state as MapUiState.Success).spaces)
    }

    @Test
    fun `toggleFavorite adds then removes favorite`() = runTest {
        val favoritesRepository = FakeFavoritesRepository()
        val viewModel = MapViewModel(
            bikeParkingRepository = FakeBikeParkingRepository(emptyList()),
            favoritesRepository = favoritesRepository,
            locationRepository = FakeLocationRepository(),
            routingRepository = FakeRoutingRepository()
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
        val locationRepository = FakeLocationRepository(initialLocation = 49.75 to 6.64)
        val viewModel = MapViewModel(
            bikeParkingRepository = FakeBikeParkingRepository(emptyList()),
            favoritesRepository = FakeFavoritesRepository(),
            locationRepository = locationRepository,
            routingRepository = FakeRoutingRepository()
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
        val viewModel = MapViewModel(
            bikeParkingRepository = FakeBikeParkingRepository(emptyList()),
            favoritesRepository = FakeFavoritesRepository(),
            locationRepository = locationRepository,
            routingRepository = FakeRoutingRepository()
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
        val viewModel = MapViewModel(
            bikeParkingRepository = FakeBikeParkingRepository(listOf(destination)),
            favoritesRepository = FakeFavoritesRepository(),
            locationRepository = FakeLocationRepository(initialLocation = 49.75 to 6.64),
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
        val viewModel = MapViewModel(
            bikeParkingRepository = FakeBikeParkingRepository(emptyList()),
            favoritesRepository = FakeFavoritesRepository(),
            locationRepository = FakeLocationRepository(initialLocation = null),
            routingRepository = FakeRoutingRepository()
        )

        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.startInAppNavigation(sampleSpace(id = "target"))

        val state = viewModel.navigationUiState.value
        assertTrue(state is NavigationUiState.Error)
    }

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
    private val spaces: List<BikeParkingSpace>
) : BikeParkingRepository {
    override suspend fun getBikeParkingSpaces(): List<BikeParkingSpace> = spaces
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

    override suspend fun getFavoritesCount(): Int = favorites.value.size
}

private class FakeLocationRepository(
    initialLocation: Pair<Double, Double>? = null
) : LocationRepository {
    private val locationFlow = MutableStateFlow(initialLocation)
    var startUpdatesCallCount: Int = 0
        private set

    override fun getCurrentLocationFlow(): Flow<Pair<Double, Double>?> = locationFlow


    override fun startLocationUpdates() {
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
    )
) : RoutingRepository {
    override suspend fun getBikeRoute(
        startLatitude: Double,
        startLongitude: Double,
        endLatitude: Double,
        endLongitude: Double
    ): BikeRoute = route
}

