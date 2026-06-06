package de.velospot.feature.map.presentation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.text.TextPaint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velospot.core.map.externalNavigationHandler
import de.velospot.domain.model.BikeParkingSpace
import kotlin.math.roundToInt
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

private const val TRIER_LAT = 49.7596
private const val TRIER_LON = 6.6441
private const val DEFAULT_ZOOM = 14.0

@Composable
fun MainMapScreen(
    viewModel: MapViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedSpace by viewModel.selectedSpace.collectAsStateWithLifecycle()

    val mapView = rememberMapViewWithLifecycle()
    var zoomBucket by remember { mutableIntStateOf(DEFAULT_ZOOM.roundToInt()) }

    DisposableEffect(mapView) {
        val listener = object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean = false

            override fun onZoom(event: ZoomEvent?): Boolean {
                val nextZoomBucket = (event?.zoomLevel ?: mapView.zoomLevelDouble).roundToInt()
                if (nextZoomBucket != zoomBucket) {
                    zoomBucket = nextZoomBucket
                }
                return false
            }
        }
        mapView.addMapListener(listener)
        onDispose {
            mapView.removeMapListener(listener)
        }
    }

    val markerIcon = remember(context, zoomBucket) {
        createBikeEmojiMarkerIcon(context, zoomBucket)
    }

     // Center the map once on Trier
     remember(mapView) {
         mapView.controller.apply {
             setZoom(DEFAULT_ZOOM)
             setCenter(GeoPoint(TRIER_LAT, TRIER_LON))
         }
     }

     // ── Navigation handler (currently external, easily replaceable with internal route) ──
     // For in-app navigation, just swap this line:
     //   val navigationHandler: NavigationHandler = { space -> navController.navigate(...) }
     val navigationHandler = remember(context) { externalNavigationHandler(context) }

     Box(modifier = Modifier.fillMaxSize()) {

         // ── Map view ──────────────────────────────────────────────────────
         AndroidView(
             factory = { mapView },
             modifier = Modifier.fillMaxSize(),
             update = { map ->
                 if (uiState is MapUiState.Success) {
                     val spaces = (uiState as MapUiState.Success).spaces
                     updateMarkers(
                         map = map,
                         spaces = spaces,
                         markerIcon = markerIcon,
                         onMarkerClick = viewModel::selectSpace
                     )
                 }
             }
         )

         // ── Loading indicator ─────────────────────────────────────────────────────
         if (uiState is MapUiState.Loading) {
             CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
         }

         // ── Error banner ──────────────────────────────────────────────────────
         if (uiState is MapUiState.Error) {
             val message = (uiState as MapUiState.Error).message
             Card(
                 modifier = Modifier
                     .align(Alignment.TopCenter)
                     .padding(16.dp)
                     .fillMaxWidth(),
                 colors = CardDefaults.cardColors(
                     containerColor = MaterialTheme.colorScheme.errorContainer
                 )
             ) {
                 Text(
                     text = message,
                     modifier = Modifier.padding(12.dp),
                     color = MaterialTheme.colorScheme.onErrorContainer
                 )
             }
         }
     }

     // ── Detail sheet for selected parking space ───────────────────────────────
     // Outside the Box so the sheet correctly overlays the entire UI.
     selectedSpace?.let { space ->
         SelectedSpaceSheet(
             space = space,
             onDismiss = { viewModel.selectSpace(null) },
             onNavigate = navigationHandler
         )
     }
 }

 // ── Marker management (UI-only, no domain write access) ────────────

 private fun updateMarkers(
    map: MapView,
    spaces: List<BikeParkingSpace>,
    markerIcon: Drawable,
    onMarkerClick: (BikeParkingSpace) -> Unit
) {
    map.overlays.clear()
    spaces.forEach { space ->
        val marker = Marker(map).apply {
            position = GeoPoint(space.latitude, space.longitude)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = markerIcon.constantState?.newDrawable()?.mutate() ?: markerIcon
            title = space.name ?: space.type.label()
            snippet = buildSnippet(space)
            setOnMarkerClickListener { _, _ ->
                onMarkerClick(space)
                true
            }
        }
        map.overlays.add(marker)
    }
    map.invalidate()
}

private fun createBikeEmojiMarkerIcon(context: Context, zoomBucket: Int): Drawable {
    val scale = when {
        zoomBucket <= 12 -> 0.42f
        zoomBucket == 13 -> 0.52f
        zoomBucket == 14 -> 0.62f
        zoomBucket == 15 -> 0.76f
        zoomBucket == 16 -> 0.90f
        zoomBucket == 17 -> 1.00f
        else -> 1.12f
    }

    val width = (120 * scale).roundToInt()
    val height = (148 * scale).roundToInt()
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val darkBlue = Color.parseColor("#0A2A66")

    val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = darkBlue
        style = Paint.Style.FILL
    }

     // Upper circular marker head
     val circleCenterX = width / 2f
     val circleCenterY = 52f * scale
     val circleRadius = 44f * scale
     canvas.drawCircle(circleCenterX, circleCenterY, circleRadius, pinPaint)

     // Lower point (dart pin)
     val tipPath = Path().apply {
         moveTo(width / 2f, 140f * scale)
         lineTo(30f * scale, 78f * scale)
         lineTo(90f * scale, 78f * scale)
         close()
     }
     canvas.drawPath(tipPath, pinPaint)

     // Bike emoji instead of standard hand symbol
     val emoji = "🚲"
     val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 46f * scale
        textAlign = Paint.Align.CENTER
    }
    val baselineY = circleCenterY - ((textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2f) - (2f * scale)
    canvas.drawText(emoji, circleCenterX, baselineY, textPaint)

    return BitmapDrawable(context.resources, bitmap)
}

private fun buildSnippet(space: BikeParkingSpace): String = buildString {
    space.address?.let { append(it) }
    space.capacity?.let { cap ->
        if (isNotEmpty()) append(" · ")
        append("$cap Stellplätze")
    }
    if (space.isCovered == true) {
        if (isNotEmpty()) append(" · ")
        append("überdacht")
    }
}
