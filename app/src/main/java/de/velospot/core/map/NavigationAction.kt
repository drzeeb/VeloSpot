package de.velospot.core.map

import de.velospot.domain.model.BikeParkingSpace

/**
 * Handles in-app navigation actions for a selected parking space.
 */
typealias NavigationHandler = (space: BikeParkingSpace) -> Unit


