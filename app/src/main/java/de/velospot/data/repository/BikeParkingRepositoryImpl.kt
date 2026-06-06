package de.velospot.data.repository

import android.util.Log
import de.velospot.data.local.BikeParkingCacheDataSource
import de.velospot.data.remote.api.TrierGeoportalApi
import de.velospot.data.remote.parser.BikeParkingGmlParser
import de.velospot.domain.model.BikeParkingSpace
import de.velospot.domain.model.BikeParkingType
import de.velospot.domain.repository.BikeParkingRepository
import java.util.concurrent.TimeUnit
import javax.inject.Inject

private const val TAG = "BikeParkingRepo"

class BikeParkingRepositoryImpl @Inject constructor(
    private val geoportalApi: TrierGeoportalApi,
    private val cache: BikeParkingCacheDataSource,
    private val gmlParser: BikeParkingGmlParser
) : BikeParkingRepository {

    private val syncIntervalMs = TimeUnit.DAYS.toMillis(14)

    private val layers = listOf(
        LayerConfig(mapFile = "fahrradgaragen", typeName = "ms:fahrradgaragen", type = BikeParkingType.GARAGE),
        LayerConfig(mapFile = "fahrradabstellanlagen", typeName = "ms:fahrradabstellanlagen", type = BikeParkingType.BIKE_RACK)
    )

    override suspend fun getBikeParkingSpaces(): List<BikeParkingSpace> {
        val cached = cache.readSpaces()
        val cacheAgeMs = System.currentTimeMillis() - cache.lastSyncEpochMs()
        val isCacheFresh = cached.isNotEmpty() && cacheAgeMs in 1 until syncIntervalMs

        if (isCacheFresh) {
            Log.d(TAG, "Nutze lokalen Cache (${cached.size} Einträge, Alter ${cacheAgeMs / 1000}s)")
            return cached
        }

        val remoteResult = runCatching { fetchRemoteSpaces() }
        return remoteResult
            .onSuccess { spaces ->
                cache.writeSpaces(spaces)
                Log.d(TAG, "Remote-Sync erfolgreich (${spaces.size} Einträge) und lokal gespeichert")
            }
            .getOrElse { error ->
                if (cached.isNotEmpty()) {
                    Log.w(TAG, "Remote-Sync fehlgeschlagen, nutze Cache-Fallback: ${error.message}")
                    cached
                } else {
                    throw error
                }
            }
    }

    private suspend fun fetchRemoteSpaces(): List<BikeParkingSpace> {
        return layers
            .flatMap { layer -> fetchLayer(layer) }
            .distinctBy { it.id }
    }

    private suspend fun fetchLayer(layer: LayerConfig): List<BikeParkingSpace> {
        val response = geoportalApi.getBikeParkingLayerGml(
            mapFile = layer.mapFile,
            typeNames = layer.typeName
        )

        if (!response.isSuccessful) {
            val errorSnippet = response.errorBody()
                ?.string()
                ?.take(300)
                ?.trimIndent()
                ?: "(kein Body)"
            throw IllegalStateException("HTTP ${response.code()} für '${layer.mapFile}': $errorSnippet")
        }

        val body = response.body()
            ?.string()
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Leere Antwort für '${layer.mapFile}'")

        return gmlParser.parse(
            xml = body,
            sourceLayer = layer.mapFile,
            type = layer.type
        )
    }

    private data class LayerConfig(
        val mapFile: String,
        val typeName: String,
        val type: BikeParkingType
    )
}
