package de.velospot.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.velospot.feature.analysis.presentation.RideAnalysisScreen
import de.velospot.feature.analysis.presentation.RideAnalysisViewModel
import de.velospot.feature.map.presentation.MainMapScreen

/** Navigation routes for the app's screens. */
object Destinations {
    const val MAP = "map"
    const val RIDE_ANALYSIS = "ride_analysis"
    fun rideAnalysis(rideId: String) = "$RIDE_ANALYSIS/$rideId"
}

/**
 * Top-level navigation graph. The map is the start destination; opening a recorded
 * ride's detailed analysis pushes a full-screen [RideAnalysisScreen] on top (the
 * map back-stack entry — and its [de.velospot.feature.map.presentation.MapViewModel]
 * — survive, so returning restores the map state).
 */
@Composable
fun VeloSpotNavHost(
    isDarkTheme: Boolean,
    onDarkThemeToggle: () -> Unit
) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Destinations.MAP) {
        composable(Destinations.MAP) {
            MainMapScreen(
                isDarkTheme = isDarkTheme,
                onDarkThemeToggle = onDarkThemeToggle,
                onOpenRideAnalysis = { rideId ->
                    navController.navigate(Destinations.rideAnalysis(rideId))
                }
            )
        }
        composable(
            route = "${Destinations.RIDE_ANALYSIS}/{${RideAnalysisViewModel.ARG_RIDE_ID}}",
            arguments = listOf(
                navArgument(RideAnalysisViewModel.ARG_RIDE_ID) { type = NavType.StringType }
            )
        ) {
            RideAnalysisScreen(
                onBack = { navController.popBackStack() },
                isDarkTheme = isDarkTheme
            )
        }
    }
}
