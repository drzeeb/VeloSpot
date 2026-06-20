package de.velospot.feature.map.presentation.search

import de.velospot.data.geocoding.NominatimGeocoder
import de.velospot.domain.model.AddressSearchResult
import de.velospot.domain.model.GeoCoordinate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the debounced address-search flow: query text, live results and the
 * in-flight indicator. Extracted from `MapViewModel` so the search concern is
 * isolated, independently testable and free of any map/selection state.
 *
 * Pure presentation logic — holds no Android dependency beyond the injected
 * [NominatimGeocoder]; the current GPS position is supplied lazily via
 * [currentLocation] so results can be biased towards the user without coupling
 * this controller to the location pipeline.
 */
class AddressSearchController(
    private val scope: CoroutineScope,
    private val geocoder: NominatimGeocoder,
    private val currentLocation: () -> GeoCoordinate?,
) {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<AddressSearchResult>>(emptyList())
    val results: StateFlow<List<AddressSearchResult>> = _results.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private var searchJob: Job? = null

    /**
     * Records the latest [query] and, once it is long enough, debounces a geocoder
     * lookup. Each keystroke cancels the previous in-flight request so only the
     * final query actually hits the network.
     */
    fun onQueryChanged(query: String) {
        _query.value = query
        searchJob?.cancel()
        if (query.length < SEARCH_MIN_CHARS) {
            _results.value = emptyList()
            return
        }
        searchJob = scope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            _isSearching.value = true
            val near = currentLocation()
            _results.value = geocoder.searchAddress(
                query = query,
                nearLatitude = near?.latitude,
                nearLongitude = near?.longitude
            )
            _isSearching.value = false
        }
    }

    /** Cancels any pending lookup and resets the query, results and indicator. */
    fun clear() {
        searchJob?.cancel()
        _query.value = ""
        _results.value = emptyList()
        _isSearching.value = false
    }

    /**
     * Collapses the results dropdown (e.g. after the user picked a result) while
     * keeping the query text intact. Cancels any in-flight lookup so a late
     * response cannot re-expand the just-dismissed dropdown.
     */
    fun collapseResults() {
        searchJob?.cancel()
        _results.value = emptyList()
        _isSearching.value = false
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 400L
        private const val SEARCH_MIN_CHARS = 3
    }
}


