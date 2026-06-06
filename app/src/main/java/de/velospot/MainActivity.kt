package de.velospot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import dagger.hilt.android.AndroidEntryPoint
import de.velospot.feature.map.presentation.MainMapScreen
import de.velospot.ui.theme.VeloSpotTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VeloSpotTheme {
                Surface(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                    MainMapScreen()
                }
            }
        }
    }
}
